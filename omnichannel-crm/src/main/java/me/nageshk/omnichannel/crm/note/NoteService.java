package me.nageshk.omnichannel.crm.note;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.note.dto.NoteRequest;
import me.nageshk.omnichannel.crm.note.dto.NoteView;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformPerson;
import me.nageshk.omnichannel.crm.task.TaskAccess;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Markdown notes on contacts, companies and deals (spec §6.6). The author edits; {@code crm:delete} holders too. */
@Service
public class NoteService {

    public static final int MAX_BODY = 20_000;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final MongoTemplate mongo;
    private final TaskAccess access;
    private final NoteFiles files;
    private final PeopleDirectory people;
    private final ActivityLog activities;
    private final Clock clock;

    NoteService(MongoTemplate mongo, TaskAccess access, NoteFiles files, PeopleDirectory people, ActivityLog activities,
            Clock clock) {
        this.mongo = mongo;
        this.access = access;
        this.files = files;
        this.people = people;
        this.activities = activities;
        this.clock = clock;
    }

    public NoteView create(CrmContext ctx, NoteRequest req) {
        ctx.require("crm:edit");
        TaskAccess.TargetInfo info = access.assertVisible(ctx, req.target());
        String body = body(req.body());
        CrmNote n = new CrmNote();
        n.setWorkspaceId(ctx.workspaceId());
        n.setCustomerId(ctx.customerId());
        n.setTarget(new CrmTarget(req.target().type(), req.target().id()));
        n.setPipelineId(info.pipelineId());
        n.setPhoneNumberId(info.phoneNumberId());
        n.setBody(body);
        n.setPinned(Boolean.TRUE.equals(req.pinned()));
        n.setCreatedBy(ctx.userId());
        n.setLastModifiedBy(ctx.userId());
        CrmNote saved = mongo.insert(n);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("noteId", saved.getId());
        payload.put("attachmentCount", 0);
        activities.record(ctx, ActivityType.NOTE_ADDED, saved.getTarget(), List.of(), saved.getPipelineId(),
                saved.getPhoneNumberId(), "Note added", payload);
        return views(List.of(saved)).get(0);
    }

    public NoteView update(CrmContext ctx, String id, NoteRequest req) {
        CrmNote current = editable(ctx, id);
        if (req.version() == null) throw new ValidationException("version", "is required");
        Update u = new Update().set("body", body(req.body()));
        if (req.pinned() != null) u.set("pinned", req.pinned());
        return views(List.of(guarded(ctx, current.getId(), req.version(), u))).get(0);
    }

    /** Pinning is not an edit of the text: anyone who may edit the target's records may pin. */
    public NoteView pin(CrmContext ctx, String id, boolean pinned) {
        ctx.require("crm:edit");
        CrmNote current = load(ctx, id);
        return views(List.of(guarded(ctx, id, current.getVersion(), new Update().set("pinned", pinned)))).get(0);
    }

    public void delete(CrmContext ctx, String id) {
        CrmNote current = editable(ctx, id);
        mongo.remove(byId(ctx, current.getId()), CrmNote.class);
        files.deleteForNote(ctx.workspaceId(), current.getId());
    }

    public CrmPage<NoteView> list(CrmContext ctx, CrmTarget target, int page, int size) {
        access.assertVisible(ctx, target);
        int p = Math.max(0, page);
        int s = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Query q = new Query(Criteria.where("workspaceId").is(ctx.workspaceId()).and("target.type").is(target.type())
                .and("target.id").is(target.id()));
        long total = mongo.count(q, CrmNote.class);
        q.with(Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("_id"))).skip((long) p * s).limit(s);
        return new CrmPage<>(views(mongo.find(q, CrmNote.class)), total, p);
    }

    // --- used by the attachment service ----------------------------------------------------------------

    /** The note, if the caller may see its target; 404 otherwise. */
    CrmNote load(CrmContext ctx, String id) {
        CrmNote n = mongo.findOne(byId(ctx, id), CrmNote.class);
        if (n == null) throw new NotFoundException("Note not found");
        access.assertVisible(ctx, n.getTarget());
        return n;
    }

    /** The note, if the caller may change it: its author, or a holder of {@code crm:delete}; 403 otherwise. */
    CrmNote editable(CrmContext ctx, String id) {
        ctx.require("crm:edit");
        CrmNote n = load(ctx, id);
        if (!ctx.userId().equals(n.getCreatedBy()) && !ctx.can("crm:delete")) {
            throw new ForbiddenException("only the author or someone who may delete records can change this note");
        }
        return n;
    }

    CrmNote guarded(CrmContext ctx, String id, Long version, Update u) {
        u.set("lastModifiedBy", ctx.userId()).set("lastUpdateTime", Instant.now(clock)).inc("version", 1);
        CrmNote saved = mongo.findAndModify(new Query(Criteria.where("_id").is(id).and("workspaceId")
                .is(ctx.workspaceId()).and("version").is(version)), u, FindAndModifyOptions.options().returnNew(true),
                CrmNote.class);
        if (saved != null) return saved;
        if (mongo.exists(byId(ctx, id), CrmNote.class)) {
            throw CrmErrors.conflict("VERSION_CONFLICT", "The note was changed by someone else; reload and retry",
                    Map.of());
        }
        throw new NotFoundException("Note not found");
    }

    List<NoteView> views(List<CrmNote> notes) {
        Set<String> authors = new LinkedHashSet<>();
        notes.forEach(n -> {
            if (n.getCreatedBy() != null) authors.add(n.getCreatedBy());
        });
        Map<String, PlatformPerson> who = people.people(authors);
        List<NoteView> out = new ArrayList<>();
        for (CrmNote n : notes) {
            PlatformPerson p = n.getCreatedBy() == null ? null : who.get(n.getCreatedBy());
            out.add(new NoteView(n.getId(), n.getTarget(), n.getBody(), n.isPinned(),
                    n.getAttachments() == null ? List.of() : n.getAttachments().stream()
                            .map(a -> new NoteView.Attachment(a.id(), a.fileName(), a.contentType(), a.size())).toList(),
                    n.getCreatedBy() == null ? null : new Ref(n.getCreatedBy(), p == null ? null : p.name()),
                    n.getCreationTime(), n.getLastUpdateTime(), n.getVersion()));
        }
        return out;
    }

    private static Query byId(CrmContext ctx, String id) {
        return new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId()));
    }

    private static String body(String raw) {
        String b = raw == null ? "" : raw.strip();
        if (b.isEmpty()) throw new ValidationException("body", "is required");
        if (b.length() > MAX_BODY) throw new ValidationException("body", "must be at most " + MAX_BODY + " characters");
        return b;
    }
}
