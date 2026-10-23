package me.nageshk.omnichannel.crm.contact;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.access.PipelineIds;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.contact.dto.CaptureRequest;
import me.nageshk.omnichannel.crm.contact.dto.CaptureResult;
import me.nageshk.omnichannel.crm.contact.dto.CreateRequest;
import me.nageshk.omnichannel.crm.platform.ChatDirectory;
import me.nageshk.omnichannel.crm.platform.ContactDirectory;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformChat;
import me.nageshk.omnichannel.crm.platform.PlatformContact;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Turns a chat or a typed phone into a CRM contact on a channel number (spec 4.5, 6.1). */
@Service
public class CaptureService {

    private final MongoTemplate mongo;
    private final ChatDirectory chats;
    private final ContactDirectory contactDirectory;
    private final PeopleDirectory people;
    private final PipelineIds pipelineIds;
    private final CrmContactService contactService;
    private final ActivityLog activities;
    private final Clock clock;

    CaptureService(MongoTemplate mongo, ChatDirectory chats, ContactDirectory contactDirectory,
            PeopleDirectory people, PipelineIds pipelineIds, CrmContactService contactService,
            ActivityLog activities, Clock clock) {
        this.mongo = mongo;
        this.chats = chats;
        this.contactDirectory = contactDirectory;
        this.people = people;
        this.pipelineIds = pipelineIds;
        this.contactService = contactService;
        this.activities = activities;
        this.clock = clock;
    }

    public CaptureResult capture(CrmContext ctx, CaptureRequest req) {
        ctx.require("crm:edit");
        requireAnyPipeline(ctx);
        if (req.chatId() == null || req.chatId().isBlank()) throw new ValidationException("chatId", "is required");
        PlatformChat chat = chats.find(ctx.workspaceId(), req.chatId())
                .orElseThrow(() -> new NotFoundException("Chat not found"));
        if (!ctx.viewAll() && !assignedToMe(ctx, chat)) {
            throw CrmErrors.forbidden("CHAT_NOT_ASSIGNED", "The chat is not assigned to you or your team");
        }
        String pipelineId = resolvePipeline(ctx, req.pipelineId());
        PlatformContact contact = contactDirectory.find(ctx.workspaceId(), chat.contactId())
                .orElseThrow(() -> new NotFoundException("Contact not found"));
        PlatformNumber channel = numberOf(ctx, chat.phoneNumberId());
        Origin origin = new Origin(OriginKind.INBOUND_CHAT, channel.id(), chat.id(), chat.lastInboundAt(), ctx.userId());
        return findOrCreate(ctx, contact, channel, origin, pipelineId, req.dealTitle());
    }

    public CaptureResult create(CrmContext ctx, CreateRequest req) {
        ctx.require("crm:edit");
        requireAnyPipeline(ctx);
        if (req.phoneNumberId() == null || !ctx.chattable().contains(req.phoneNumberId())) {
            throw new NotFoundException("Number not found");
        }
        PlatformNumber channel = numberOf(ctx, req.phoneNumberId());
        String pipelineId = resolvePipeline(ctx, req.pipelineId());
        if (req.phone() == null || req.phone().isBlank()) throw new ValidationException("phone", "is required");
        String first = clean(req.firstName(), "firstName");
        String last = clean(req.lastName(), "lastName");
        String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        PlatformContact contact = contactDirectory.ensure(ctx.workspaceId(), ctx.customerId(), req.phone(),
                name.isEmpty() ? null : name);
        Origin origin = new Origin(OriginKind.MANUAL, channel.id(), null, null, ctx.userId());
        return findOrCreate(ctx, contact, channel, origin, pipelineId, req.dealTitle(), first, last);
    }

    public CaptureResult findOrCreate(CrmContext ctx, PlatformContact c, PlatformNumber channel, Origin origin,
            String pipelineId, String dealTitle) {
        return findOrCreate(ctx, c, channel, origin, pipelineId, dealTitle, null, null);
    }

    private CaptureResult findOrCreate(CrmContext ctx, PlatformContact c, PlatformNumber channel, Origin origin,
            String pipelineId, String dealTitle, String firstName, String lastName) {
        String ws = ctx.workspaceId();
        Query existing = new Query(Criteria.where("workspaceId").is(ws).and("contactId").is(c.id())
                .and("channel.phoneNumberId").is(channel.id()));
        CrmContact found = mongo.findOne(existing, CrmContact.class);
        boolean created = found == null;
        if (created) {
            CrmContact n = new CrmContact();
            n.setWorkspaceId(ws);
            n.setCustomerId(ctx.customerId());
            n.setContactId(c.id());
            n.setChannel(new Channel(channel.id(), channel.number()));
            n.setPhone(c.phone());
            n.setContactSnapshot(new ContactSnapshot(c.name()));
            n.setOrigin(origin);
            n.setFirstName(firstName);
            n.setLastName(lastName);
            n.setOwnerId(ctx.userId());
            n.setLifecycleStatus(defaultKey(ctx.settings().getLifecycleStatuses()));
            n.setCreatedBy(ctx.userId());
            n.setLastModifiedBy(ctx.userId());
            try {
                found = mongo.insert(n);
                activities.record(ctx, ActivityType.RECORD_CREATED, new CrmTarget(TargetType.CONTACT, found.getId()),
                        List.of(), null, channel.id(), "Contact added from " + origin.kind(),
                        new LinkedHashMap<>(Map.of("origin", origin.kind().name())));
            } catch (DuplicateKeyException race) {
                found = mongo.findOne(existing, CrmContact.class);
                created = false;
            }
        }
        createDealForCapture(ctx, found, pipelineId, dealTitle);
        return new CaptureResult(contactService.view(ctx, found), created);
    }

    /** Task 10: DealService.createForCapture(ctx, contact, pipelineId, dealTitle) goes here. */
    private void createDealForCapture(CrmContext ctx, CrmContact contact, String pipelineId, String dealTitle) {}

    private static void requireAnyPipeline(CrmContext ctx) {
        if (!ctx.viewAll() && ctx.pipelines().pipelineIds().isEmpty()) {
            throw CrmErrors.forbidden("NO_PIPELINE_ACCESS", "No pipeline is assigned to you");
        }
    }

    private boolean assignedToMe(CrmContext ctx, PlatformChat chat) {
        if (ctx.userId().equals(chat.assignedUserId())) return true;
        return chat.assignedTeamId() != null
                && people.teamIdsOf(ctx.workspaceId(), ctx.userId()).contains(chat.assignedTeamId());
    }

    /** Required for restricted users; view-all users default to the brand's default pipeline. */
    private String resolvePipeline(CrmContext ctx, String requested) {
        String id = requested == null || requested.isBlank() ? null : requested.trim();
        if (id == null) {
            if (!ctx.viewAll()) throw new ValidationException("pipelineId", "is required");
            id = ctx.settings().getDefaultPipelineId();
            if (id == null) throw new ValidationException("pipelineId", "the brand has no default pipeline");
        }
        if (!ctx.pipelines().allows(id) || !pipelineIds.existing(ctx.workspaceId()).contains(id)) {
            throw new NotFoundException("Pipeline not found");
        }
        if (!pipelineIds.active(ctx.workspaceId()).contains(id)) {
            throw CrmErrors.badRequest("PIPELINE_INACTIVE", "The pipeline is not active");
        }
        return id;
    }

    private static PlatformNumber numberOf(CrmContext ctx, String phoneNumberId) {
        return ctx.numbers().stream().filter(n -> n.id().equals(phoneNumberId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Number not found"));
    }

    private static String defaultKey(List<PicklistItem> items) {
        if (items == null) return null;
        return items.stream().filter(i -> i.active() && i.isDefault()).map(PicklistItem::key).findFirst().orElse(null);
    }

    private static String clean(String s, String field) {
        if (s == null || s.isBlank()) return null;
        if (s.trim().length() > 200) throw new ValidationException(field, "must be at most 200 characters");
        return s.trim();
    }
}
