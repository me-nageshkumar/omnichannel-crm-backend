package me.nageshk.omnichannel.crm.activity;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.dto.ActivityView;
import me.nageshk.omnichannel.crm.activity.dto.LogRequest;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.company.CrmCompanyService;
import me.nageshk.omnichannel.crm.contact.CrmContact;
import me.nageshk.omnichannel.crm.contact.CrmContactService;
import me.nageshk.omnichannel.crm.deal.DealService;
import me.nageshk.omnichannel.crm.deal.dto.DealSearchRequest;
import me.nageshk.omnichannel.crm.deal.dto.DealView;
import me.nageshk.omnichannel.crm.platform.ContactNoteDirectory;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformNote;
import me.nageshk.omnichannel.crm.platform.PlatformPerson;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Reads a record's timeline and logs calls, meetings and emails by hand (spec 6.3). */
@Service
public class TimelineService {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;
    private static final int DEAL_PAGE = 200;

    private final MongoTemplate mongo;
    private final CrmContactService contacts;
    private final CrmCompanyService companies;
    private final DealService deals;
    private final ContactNoteDirectory notes;
    private final PeopleDirectory people;
    private final ActivityLog log;
    private final Clock clock;

    TimelineService(MongoTemplate mongo, CrmContactService contacts, CrmCompanyService companies, DealService deals,
            ContactNoteDirectory notes, PeopleDirectory people, ActivityLog log, Clock clock) {
        this.mongo = mongo;
        this.contacts = contacts;
        this.companies = companies;
        this.deals = deals;
        this.notes = notes;
        this.people = people;
        this.log = log;
        this.clock = clock;
    }

    /**
     * Newest first. A CONTACT's timeline holds its own entries, entries that list it as related and those of its
     * accessible deals; a COMPANY's its own and its accessible deals'; a DEAL's its own. Restricted callers lose the
     * entries of pipelines outside their access. The contact's legacy inbox notes ({@code LEGACY_NOTE}) are merged
     * by time on page 0 only, all of them; {@code total} counts the stored activities only, never the notes.
     *
     * @throws NotFoundException when the target is not visible to the caller
     */
    public CrmPage<ActivityView> timeline(CrmContext ctx, TargetType type, String id, int page, int size) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (page < 0) errors.put("page", "must not be negative");
        if (size < 1) errors.put("size", "must be at least 1");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        size = Math.min(size, MAX_SIZE);

        List<Criteria> scope = new ArrayList<>();
        CrmContact contact = null;
        List<String> dealIds = List.of();
        switch (type) {
            case CONTACT -> {
                contact = contacts.load(ctx, id);
                dealIds = dealIds(ctx, new DealSearchRequest(null, null, null, null, null, id, null, null, null, 0, 0));
                scope.add(own(type, id));
                scope.add(Criteria.where("relatedTargets")
                        .elemMatch(Criteria.where("type").is(TargetType.CONTACT).and("id").is(id)));
            }
            case COMPANY -> {
                companies.load(ctx, id);
                dealIds = dealIds(ctx, new DealSearchRequest(null, null, null, null, id, null, null, null, null, 0, 0));
                scope.add(own(type, id));
            }
            case DEAL -> {
                deals.get(ctx, id);
                scope.add(own(type, id));
            }
            default -> throw new ValidationException("targetType", "must be CONTACT, COMPANY or DEAL");
        }
        if (!dealIds.isEmpty()) {
            scope.add(Criteria.where("target.type").is(TargetType.DEAL).and("target.id").in(dealIds));
        }
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("workspaceId").is(ctx.workspaceId()));
        all.add(new Criteria().orOperator(scope));
        if (!ctx.pipelines().all()) {
            all.add(new Criteria().orOperator(Criteria.where("pipelineId").is(null),
                    ctx.pipelines().criteria("pipelineId")));
        }
        Query filter = new Query(new Criteria().andOperator(all));
        long total = mongo.count(filter, CrmActivity.class);
        List<CrmActivity> found = mongo.find(Query.of(filter).with(Sort.by(Sort.Direction.DESC, "happenedAt")
                .and(Sort.by(Sort.Direction.DESC, "_id"))).skip((long) page * size).limit(size), CrmActivity.class);

        List<PlatformNote> legacy = page == 0 && contact != null
                ? notes.notes(ctx.workspaceId(), contact.getContactId()) : List.of();
        Set<String> actorIds = new LinkedHashSet<>();
        found.forEach(a -> {
            if (a.getActorType() == ActorType.USER && a.getActorId() != null) actorIds.add(a.getActorId());
        });
        Map<String, PlatformPerson> persons = actorIds.isEmpty() ? Map.of() : people.people(actorIds);

        List<ActivityView> items = new ArrayList<>();
        found.forEach(a -> items.add(view(a, persons)));
        for (PlatformNote n : legacy) {
            items.add(new ActivityView(n.id(), "LEGACY_NOTE", new CrmTarget(TargetType.CONTACT, id), n.text(),
                    Map.of(), new Ref(null, n.createdByName()), ActorType.USER.name(), n.at()));
        }
        if (!legacy.isEmpty()) {
            // stable: on equal times the stored activities stay ahead of the notes
            items.sort(Comparator.comparing(ActivityView::happenedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        }
        return new CrmPage<>(items, total, page);
    }

    /**
     * Records a call, meeting or email the user did outside the CRM.
     *
     * @throws NotFoundException when the target is not visible
     */
    public ActivityView logActivity(CrmContext ctx, LogRequest req) {
        ctx.require("crm:edit");
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.target() == null) errors.put("target", "is required");
        TargetType targetType = null;
        if (req.target() != null) {
            targetType = enumOf(req.target().type());
            if (targetType != TargetType.CONTACT && targetType != TargetType.COMPANY && targetType != TargetType.DEAL) {
                errors.put("target.type", "must be CONTACT, COMPANY or DEAL");
            }
            if (req.target().id() == null || req.target().id().isBlank()) errors.put("target.id", "is required");
        }
        ActivityType type = switch (req.type() == null ? "" : req.type().trim().toUpperCase(java.util.Locale.ROOT)) {
            case "CALL" -> ActivityType.CALL_LOGGED;
            case "MEETING" -> ActivityType.MEETING_LOGGED;
            case "EMAIL" -> ActivityType.EMAIL_LOGGED;
            default -> null;
        };
        if (type == null) errors.put("type", "must be CALL, MEETING or EMAIL");
        String summary = req.summary() == null ? "" : req.summary().trim();
        if (summary.isEmpty() || summary.length() > 2000) errors.put("summary", "must be 1 to 2000 characters");
        Instant at = null;
        if (req.happenedAt() != null && !req.happenedAt().isBlank()) {
            try {
                at = Instant.parse(req.happenedAt().trim());
                if (at.isAfter(Instant.now(clock).plusSeconds(60))) errors.put("happenedAt", "must not be in the future");
            } catch (DateTimeParseException e) {
                errors.put("happenedAt", "must be an ISO-8601 instant");
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        String targetId = req.target().id().trim();
        String pipelineId = null;
        String phoneNumberId = null;
        switch (targetType) {
            case CONTACT -> {
                CrmContact c = contacts.load(ctx, targetId);
                phoneNumberId = c.getChannel() == null ? null : c.getChannel().phoneNumberId();
            }
            case COMPANY -> companies.load(ctx, targetId);
            default -> {
                DealView d = deals.get(ctx, targetId);
                pipelineId = d.pipelineId();
                phoneNumberId = d.channel() == null ? null : d.channel().phoneNumberId();
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (at != null) payload.put("happenedAt", at);
        CrmActivity saved = log.record(ctx, type, new CrmTarget(targetType, targetId), List.of(), pipelineId,
                phoneNumberId, summary, payload);
        Map<String, PlatformPerson> me = people.people(List.of(ctx.userId()));
        return view(saved, me);
    }

    private static Criteria own(TargetType type, String id) {
        return Criteria.where("target.type").is(type).and("target.id").is(id);
    }

    private List<String> dealIds(CrmContext ctx, DealSearchRequest base) {
        List<String> ids = new ArrayList<>();
        for (int p = 0;; p++) {
            DealSearchRequest r = new DealSearchRequest(null, null, null, null, base.companyId(), base.crmContactId(),
                    null, null, null, p, DEAL_PAGE);
            List<DealView> items = deals.search(ctx, r).items();
            items.forEach(d -> ids.add(d.id()));
            if (items.size() < DEAL_PAGE) return ids;
        }
    }

    private static TargetType enumOf(String s) {
        if (s == null) return null;
        try {
            return TargetType.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ActivityView view(CrmActivity a, Map<String, PlatformPerson> persons) {
        Ref actor = null;
        if (a.getActorType() == ActorType.USER && a.getActorId() != null) {
            PlatformPerson p = persons.get(a.getActorId());
            actor = new Ref(a.getActorId(), p == null ? null : p.name());
        }
        return new ActivityView(a.getId(), a.getType().name(), a.getTarget(), a.getSummary(), a.getPayload(), actor,
                a.getActorType().name(), a.getHappenedAt());
    }
}
