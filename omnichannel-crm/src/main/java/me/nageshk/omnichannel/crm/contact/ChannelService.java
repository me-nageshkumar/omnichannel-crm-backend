package me.nageshk.omnichannel.crm.contact;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.contact.dto.CommunicationView;
import me.nageshk.omnichannel.crm.contact.dto.CrmContactView;
import me.nageshk.omnichannel.crm.contact.dto.MoveChannelRequest;
import me.nageshk.omnichannel.crm.platform.ChatDirectory;
import me.nageshk.omnichannel.crm.platform.ContactDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformContact;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Where a CRM contact is reached and moving it to another of the brand's numbers (spec 4.4). */
@Service
public class ChannelService {

    private final MongoTemplate mongo;
    private final ChatDirectory chats;
    private final ContactDirectory contactDirectory;
    private final CrmContactService contactService;
    private final ContactCounters counters;
    private final ActivityLog activities;
    private final Clock clock;

    ChannelService(MongoTemplate mongo, ChatDirectory chats, ContactDirectory contactDirectory,
            CrmContactService contactService, ContactCounters counters, ActivityLog activities, Clock clock) {
        this.mongo = mongo;
        this.counters = counters;
        this.chats = chats;
        this.contactDirectory = contactDirectory;
        this.contactService = contactService;
        this.activities = activities;
        this.clock = clock;
    }

    public CommunicationView communication(CrmContext ctx, String id) {
        return communication(ctx, id, null);
    }

    /** @param phoneNumberId the number the caller believes the contact is on; null skips the check */
    public CommunicationView communication(CrmContext ctx, String id, String phoneNumberId) {
        CrmContact c = contactService.load(ctx, id);
        if (phoneNumberId != null && !phoneNumberId.isBlank()) assertChannel(c, phoneNumberId);
        String channelId = c.getChannel().phoneNumberId();
        String chatId = chats.findFor(channelId, c.getContactId()).map(x -> x.id()).orElse(null);
        PlatformContact live = contactDirectory.find(ctx.workspaceId(), c.getContactId()).orElse(null);
        String phone = live != null && live.phone() != null ? live.phone() : c.getPhone();
        return new CommunicationView(channelId, c.getChannel().number(), phone, chatId,
                ctx.chattable().contains(channelId));
    }

    /** @throws me.nageshk.omnichannel.common.api.CodedException 409 CHANNEL_MISMATCH */
    public void assertChannel(CrmContact c, String phoneNumberId) {
        if (!c.getChannel().phoneNumberId().equals(phoneNumberId)) {
            throw CrmErrors.conflict("CHANNEL_MISMATCH", "The contact is on another number",
                    Map.of("phoneNumberId", c.getChannel().phoneNumberId()));
        }
    }

    public CrmContactView move(CrmContext ctx, String id, MoveChannelRequest req) {
        ctx.require("crm:settings");
        if (!ctx.settings().isAllowChannelMove()) throw new ForbiddenException("Moving channels is turned off");
        CrmContact current = contactService.load(ctx, id);
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.version() == null) errors.put("version", "is required");
        if (req.toPhoneNumberId() == null || req.toPhoneNumberId().isBlank()) errors.put("toPhoneNumberId", "is required");
        if (req.reason() != null && req.reason().trim().length() > 500) errors.put("reason", "must be at most 500 characters");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        PlatformNumber target = ctx.numbers().stream().filter(n -> n.id().equals(req.toPhoneNumberId())).findFirst()
                .orElseThrow(() -> new NotFoundException("Number not found"));
        String from = current.getChannel().phoneNumberId();
        if (from.equals(target.id())) throw new ValidationException("toPhoneNumberId", "is already the contact's number");
        if (mongo.exists(new Query(Criteria.where("workspaceId").is(ctx.workspaceId())
                .and("contactId").is(current.getContactId()).and("channel.phoneNumberId").is(target.id())),
                CrmContact.class)) throw taken();

        Instant now = Instant.now(clock);
        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        Update u = new Update().set("channel", new Channel(target.id(), target.number()))
                .push("channelHistory", new ChannelChange(from, target.id(), ctx.userId(), now, reason))
                .set("lastModifiedBy", ctx.userId()).set("lastUpdateTime", now).inc("version", 1);
        CrmContact saved;
        try {
            saved = mongo.findAndModify(new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())
                    .and("version").is(req.version())), u, FindAndModifyOptions.options().returnNew(true),
                    CrmContact.class);
        } catch (DuplicateKeyException race) {
            throw taken();
        }
        if (saved == null) throw CrmContactService.versionConflict();
        Set<String> unlinked = keepDealsOnOneChannel(ctx.workspaceId(), id, target);
        unlinked.add(id);
        unlinked.forEach(c -> counters.recompute(ctx.workspaceId(), c));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("to", target.id());
        payload.put("reason", reason);
        activities.record(ctx, ActivityType.CHANNEL_CHANGED, new CrmTarget(TargetType.CONTACT, id), List.of(), null,
                target.id(), "Moved to " + target.number(), payload);
        return contactService.view(ctx, saved);
    }

    /**
     * Linked contacts share the primary's channel (spec 4.4, 6.2). The contact's own deals follow it to the new
     * number and drop linked contacts that stay behind; deals of others where it is only linked drop it. No
     * activity is recorded for the unlinking. Returns the contacts that lost a deal.
     */
    private Set<String> keepDealsOnOneChannel(String ws, String id, PlatformNumber target) {
        Set<String> unlinked = new HashSet<>();
        mongo.updateMulti(new Query(Criteria.where("workspaceId").is(ws).and("crmContactIds").is(id)
                        .and("primaryCrmContactId").ne(id)),
                new Update().pull("crmContactIds", id), ContactCounters.DEALS);
        Query own = new Query(Criteria.where("workspaceId").is(ws).and("primaryCrmContactId").is(id));
        own.fields().include("crmContactIds");
        List<Document> deals = mongo.find(own, Document.class, ContactCounters.DEALS);
        Set<String> others = new HashSet<>();
        deals.forEach(d -> linkedOf(d).stream().filter(x -> !x.equals(id)).forEach(others::add));
        Set<String> onTarget = new HashSet<>();
        if (!others.isEmpty()) {
            Query q = new Query(Criteria.where("workspaceId").is(ws).and("_id").in(others)
                    .and("channel.phoneNumberId").is(target.id()));
            q.fields().include("_id");
            mongo.find(q, CrmContact.class).forEach(c -> onTarget.add(c.getId()));
        }
        Document channel = new Document("phoneNumberId", target.id()).append("number", target.number());
        for (Document d : deals) {
            List<String> keep = new ArrayList<>();
            keep.add(id);
            for (String x : linkedOf(d)) {
                if (x.equals(id)) continue;
                if (onTarget.contains(x)) keep.add(x);
                else unlinked.add(x);
            }
            mongo.updateFirst(new Query(Criteria.where("_id").is(d.get("_id")).and("workspaceId").is(ws)),
                    new Update().set("channel", channel).set("crmContactIds", keep), ContactCounters.DEALS);
        }
        return unlinked;
    }

    private static List<String> linkedOf(Document deal) {
        Object ids = deal.get("crmContactIds");
        List<String> out = new ArrayList<>();
        if (ids instanceof List<?> l) l.stream().filter(java.util.Objects::nonNull).forEach(x -> out.add(x.toString()));
        return out;
    }

    private static RuntimeException taken() {
        return CrmErrors.conflict("CHANNEL_TAKEN", "The person already has a CRM contact on that number", Map.of());
    }
}
