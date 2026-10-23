package me.nageshk.omnichannel.crm.activity;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Appends to the CRM activity log and keeps the records' {@code lastActivityAt} current. Depends on Mongo only. */
@Service
public class ActivityLog {

    private final MongoTemplate mongo;
    private final Clock clock;

    ActivityLog(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    /**
     * Appends an entry acted by the caller. {@code happenedAt} is now unless {@code payload.happenedAt} (an ISO
     * instant, from the log endpoint; 400 when unparseable) is given; then raises {@code lastActivityAt} of the target and every related
     * contact, company and deal to it (never lowers it, never bumps {@code version}).
     */
    public CrmActivity record(CrmContext ctx, ActivityType type, CrmTarget target, List<CrmTarget> related,
            String pipelineId, String phoneNumberId, String summary, Map<String, Object> payload) {
        Map<String, Object> body = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        Instant at = happenedAt(body.get("happenedAt"));
        CrmActivity a = new CrmActivity();
        a.setWorkspaceId(ctx.workspaceId());
        a.setCustomerId(ctx.customerId());
        a.setType(type);
        a.setTarget(target);
        a.setRelatedTargets(related == null ? new ArrayList<>() : new ArrayList<>(related));
        a.setPipelineId(pipelineId);
        a.setPhoneNumberId(phoneNumberId);
        a.setActorId(ctx.userId());
        a.setActorType(ActorType.USER);
        a.setHappenedAt(at);
        a.setSummary(summary);
        a.setPayload(body);
        a.setCreatedBy(ctx.userId());
        a.setLastModifiedBy(ctx.userId());
        CrmActivity saved = mongo.insert(a);
        touch(ctx.workspaceId(), target, at);
        a.getRelatedTargets().forEach(t -> touch(ctx.workspaceId(), t, at));
        return saved;
    }

    private void touch(String workspaceId, CrmTarget t, Instant at) {
        String collection = collectionOf(t.type());
        if (collection == null) return;
        mongo.updateFirst(new Query(Criteria.where("_id").is(t.id()).and("workspaceId").is(workspaceId)),
                new Update().max("lastActivityAt", at), collection);
    }

    private static String collectionOf(TargetType type) {
        return switch (type) {
            case CONTACT -> "crmContacts";
            case COMPANY -> "crmCompanies";
            case DEAL -> "crmDeals";
            case PIPELINE, SETTINGS -> null;
        };
    }

    private Instant happenedAt(Object given) {
        if (given instanceof Instant i) return i;
        if (given instanceof Date d) return d.toInstant();
        if (given == null || given instanceof String s && s.isBlank()) return Instant.now(clock);
        if (given instanceof String s) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e) {
                throw new ValidationException("happenedAt", "must be an ISO-8601 instant");
            }
        }
        throw new ValidationException("happenedAt", "must be an ISO-8601 instant");
    }

    /** The keys of {@code after} whose value differs from {@code before} (absent = null); numbers compare by value. */
    public static List<FieldDiff> diff(Map<String, Object> before, Map<String, Object> after) {
        List<FieldDiff> out = new ArrayList<>();
        after.forEach((field, to) -> {
            Object from = before.get(field);
            if (!same(from, to)) out.add(new FieldDiff(field, from, to));
        });
        return out;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return Objects.equals(a, b);
    }
}
