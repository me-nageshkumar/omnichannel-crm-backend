package me.nageshk.omnichannel.inbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.api.NoDeviceException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactService;
import me.nageshk.omnichannel.rpc.RpcDispatcher;
import me.nageshk.omnichannel.template.RenderRequest;
import me.nageshk.omnichannel.template.RenderedTemplate;
import me.nageshk.omnichannel.template.TemplateContent;
import me.nageshk.omnichannel.template.TemplateMediaService;
import me.nageshk.omnichannel.template.TemplateRenderer;
import me.nageshk.omnichannel.template.TemplateService;
import me.nageshk.omnichannel.template.TemplateStatus;
import me.nageshk.omnichannel.template.TemplateType;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import me.nageshk.omnichannel.ws.DeviceSession;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends a reply typed in the console, or a campaign's message, through the number's device (spec section 6) and
 * stores it with the {@link SendActor}'s source, sender and campaign. Checks run in this order, and nothing reaches
 * the device unless all pass: chat open, number usable and device ready, request and template valid.
 */
@Service
@RequiredArgsConstructor
public class InboxSendService {

    public static final int MAX_TEXT = 4096;

    private static final Logger log = LoggerFactory.getLogger(InboxSendService.class);

    /** What goes to the device and what is stored for it; {@code body} may be null. */
    private record Outgoing(String content, ObjectNode options, String type, String body,
            WaWebMessage.Media media, byte[] mediaBytes, WaWebMessage.Location location, String templateId) {
    }

    /** The stored message and the chat after it; {@code inserted} is false when the ingest listener got there first. */
    private record Recorded(WaWebChat chat, WaWebMessage message, boolean inserted) {
    }

    private final InboxDevices devices;
    private final RpcDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final ChatMediaService chatMedia;
    private final TemplateRenderer renderer;
    private final TemplateMediaService templateMedia;
    private final TemplateService templates;
    private final ChatAssignmentService assignment;
    private final ContactService contacts;
    private final WaWebChatRepository chats;
    private final WaWebMessageRepository messages;
    private final UserRepository users;
    private final InboxViews views;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /** Sends to a chat the console did not address by a typed phone. */
    public CompletableFuture<MessageView> send(AuthenticatedUser me, String workspaceId, WaWebChat chat,
            SendRequest req) {
        return send(me, workspaceId, chat, req, null);
    }

    /** Sends as the console user {@code me}; see {@link #send(SendActor, String, WaWebChat, SendRequest, String)}. */
    public CompletableFuture<MessageView> send(AuthenticatedUser me, String workspaceId, WaWebChat chat,
            SendRequest req, String typedPhone) {
        UserActor actor = new UserActor(me, users.findById(me.userId()).map(User::getName).orElse(me.getUsername()));
        return send(actor, workspaceId, chat, req, typedPhone);
    }

    /**
     * Callers check {@code inbox:send} and that the chat is visible to the user; a campaign's runner did its own
     * checks. A {@link UserActor} may claim the chat afterwards (the number's {@code claimOnReply}), a
     * {@link CampaignActor} never does. The chat may be unsaved (no id):
     * it is stored with the first message once the device accepted it, or merged into the row that exists for the
     * same remote id by then. The future fails with the {@code RpcException} of a device error or timeout, in which
     * case nothing is stored. {@code typedPhone} is the phone the console addressed an unsaved chat by (null for
     * saved chats): it is what the contact of a chat stored under a {@code @lid} is linked to.
     *
     * @param typedPhone normalised digits typed (or imported) for an unsaved chat, else null
     * @throws ChatClosedException if the chat is closed
     * @throws NoDeviceException if the number is disabled or its device is missing or not CONNECTED
     * @throws ValidationException for a bad request, a missing template value or a contact card template
     */
    public CompletableFuture<MessageView> send(SendActor actor, String workspaceId, WaWebChat chat,
            SendRequest req, String typedPhone) {
        if (chat.getStatus() != ChatStatus.OPEN) throw new ChatClosedException();
        DeviceSession device = devices.requireReady(workspaceId, chat);
        Outgoing out = outgoing(actor, workspaceId, chat, req);

        ObjectNode params = mapper.createObjectNode();
        params.put("chatId", chat.getRemoteId());
        params.put("content", out.content());
        params.set("options", out.options());
        params.put("sendSeen", true);
        return dispatcher.call(device, "sendMessage", params)
                .thenApply(result -> afterSend(actor, chat, out, result, typedPhone));
    }

    // ---- request to outgoing message ----

    /** Exactly one of text and template; anything else is {@code fields.text = "required"}. */
    private Outgoing outgoing(SendActor actor, String workspaceId, WaWebChat chat, SendRequest req) {
        String text = req == null || req.text() == null ? "" : req.text().strip();
        boolean hasText = !text.isEmpty();
        boolean hasTemplate = req != null && !isBlank(req.templateId());
        if (hasText == hasTemplate) throw new ValidationException("text", "required");
        if (hasText) {
            if (text.length() > MAX_TEXT) {
                throw new ValidationException("text", "must be at most " + MAX_TEXT + " characters");
            }
            return new Outgoing(text, mapper.createObjectNode(), null, text, null, null, null, null);
        }
        String templateId = req.templateId().strip();
        if (templates.require(workspaceId, templateId).getStatus() == TemplateStatus.ARCHIVED) {
            throw new ValidationException("templateId", "archived");
        }
        RenderedTemplate rendered = switch (actor) {
            case UserActor u -> renderer.render(u.me(), workspaceId, templateId,
                    new RenderRequest(chat.getContactId(), req.values()));
            case CampaignActor c -> renderer.renderForCampaign(workspaceId, templateId, req.values());
        };
        if (rendered.type() == TemplateType.CONTACT_CARD) {
            throw new ValidationException("templateId", "contact cards cannot be sent from the inbox yet");
        }
        if (!rendered.missing().isEmpty()) {
            Map<String, String> fields = new LinkedHashMap<>();
            rendered.missing().forEach(name -> fields.put(name, "required"));
            throw new ValidationException(fields);
        }
        return fromTemplate(workspaceId, rendered, templateId);
    }

    private Outgoing fromTemplate(String workspaceId, RenderedTemplate r, String templateId) {
        TemplateContent c = r.content();
        ObjectNode options = mapper.createObjectNode();
        switch (r.type()) {
            case TEXT -> {
                if (c.getLinkPreview() != null) options.put("linkPreview", c.getLinkPreview());
                return new Outgoing(c.getBody(), options, "chat", c.getBody(), null, null, null, templateId);
            }
            case IMAGE, VIDEO, DOCUMENT, AUDIO -> {
                String caption = c.getCaption() == null ? "" : c.getCaption();
                TemplateMediaService.MediaFile file = templateMedia.open(workspaceId, r.media().mediaId());
                ObjectNode media = options.putObject("media");
                media.put("mimetype", file.mimeType());
                byte[] bytes = read(file);
                media.put("data", Base64.getEncoder().encodeToString(bytes));
                media.put("filename", file.fileName());
                if (!caption.isEmpty()) options.put("caption", caption);
                boolean voice = r.type() == TemplateType.AUDIO
                        && Boolean.TRUE.equals(c.getVoiceNote());
                if (voice) options.put("sendAudioAsVoice", true);
                if (r.type() == TemplateType.DOCUMENT) {
                    options.put("sendMediaAsDocument", true);
                }
                String type = switch (r.type()) {
                    case IMAGE -> "image";
                    case VIDEO -> "video";
                    case DOCUMENT -> "document";
                    default -> voice ? "ptt" : "audio";
                };
                WaWebMessage.Media stored = new WaWebMessage.Media(file.mimeType(), file.fileName(), file.size(),
                        null, null);
                return new Outgoing(caption, options, type, caption.isEmpty() ? null : caption, stored, bytes, null,
                        templateId);
            }
            case LOCATION -> {
                String description = joined(c.getName(), c.getAddress());
                ObjectNode location = options.putObject("location");
                location.put("latitude", c.getLatitude());
                location.put("longitude", c.getLongitude());
                if (description != null) location.put("description", description);
                return new Outgoing("", options, "location", null, null, null,
                        new WaWebMessage.Location(c.getLatitude(), c.getLongitude(), description), templateId);
            }
            case POLL -> {
                ObjectNode poll = options.putObject("poll");
                poll.put("pollName", c.getQuestion());
                ArrayNode choices = poll.putArray("pollOptions");
                c.getOptions().forEach(choices::add);
                poll.putObject("options").put("allowMultipleAnswers", Boolean.TRUE.equals(c.getAllowMultiple()));
                return new Outgoing("", options, "poll_creation", c.getQuestion(), null, null, null, templateId);
            }
            default -> throw new IllegalStateException("cannot send a " + r.type() + " template");
        }
    }

    private static String joined(String name, String address) {
        String n = name == null || name.isBlank() ? null : name.strip();
        String a = address == null || address.isBlank() ? null : address.strip();
        if (n != null && a != null) return n + ", " + a;
        return n != null ? n : a;
    }

    private static byte[] read(TemplateMediaService.MediaFile file) {
        try (InputStream in = file.resource().getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ---- after the device answered ----

    private MessageView afterSend(SendActor actor, WaWebChat sent, Outgoing out, JsonNode result,
            String typedPhone) {
        Instant now = clock.instant();
        boolean model = result != null && result.isObject();
        String waMessageId = model ? MessagePayloads.waMessageId(result) : "";
        if (waMessageId.isEmpty()) {
            waMessageId = "console:" + new ObjectId();
            model = false;
            log.warn("sendMessage on {} returned no message model; stored {} and it will not merge with the later "
                    + "message_create event", sent.getPhoneNumberId(), waMessageId);
        }
        String id = waMessageId;
        Instant at = model ? MessagePayloads.timestamp(result, now) : now;
        int ack = model ? MessagePayloads.ack(result) : 0;
        String type = model && out.type() == null ? MessagePayloads.type(result) : out.type();
        // a chat started from the console was addressed to phone@c.us; WhatsApp mostly answers under the person's
        // @lid, and that is the chat the message (and its message_create event) belong to
        String lidPhone = typedPhone;
        String reported = model ? MessagePayloads.remote(result) : "";
        if (sent.getId() == null && !reported.isEmpty() && !reported.equals(sent.getRemoteId())) {
            if (lidPhone == null) lidPhone = phoneOf(sent.getRemoteId());
            sent.setRemoteId(reported);
        }
        String phone = lidPhone;

        Recorded recorded = InboxIngestService.retrying(
                () -> record(actor, sent, out, type == null ? "chat" : type, id, at, ack, now, phone));
        WaWebChat chat = recorded.chat();
        if (actor instanceof UserActor user) {
            try {
                chat = assignment.claimOnReply(user.me(), chat);
            } catch (RuntimeException e) {
                log.warn("could not claim chat {} for {} after a send: {}", chat.getId(), user.userId(),
                        e.toString());
            }
        }
        if (out.templateId() != null) {
            try {
                templates.markUsed(out.templateId(), now);
            } catch (RuntimeException e) {
                log.warn("could not mark template {} as used: {}", out.templateId(), e.toString());
            }
        }
        publisher.publishEvent(new InboxEvents.MessageUpserted(chat, recorded.message(), recorded.inserted()));
        publisher.publishEvent(new InboxEvents.ChatChanged(chat));
        return views.message(recorded.message());
    }

    /**
     * Keeps the bytes of a template's media with the stored message, so opening it never asks the phone. A failure
     * is logged: the message was sent, and the console can still fetch the media from the phone.
     */
    private WaWebMessage cacheMedia(WaWebMessage message, Outgoing out) {
        WaWebMessage.Media media = message.getMedia();
        if (out.mediaBytes() == null || media == null || media.mediaId() != null) return message;
        // an ingest-created row may lack what the template knows
        String mimetype = media.mimetype() != null ? media.mimetype() : out.media().mimetype();
        String filename = media.filename() != null ? media.filename() : out.media().filename();
        Long size = media.size() != null ? media.size() : out.media().size();
        try {
            String mediaId = chatMedia.store(message.getWorkspaceId(), message.getPhoneNumberId(), message.getId(),
                    mimetype, filename, out.mediaBytes());
            message.setMedia(new WaWebMessage.Media(mimetype, filename, size, mediaId, clock.instant()));
            return messages.save(message);
        } catch (RuntimeException e) {
            log.warn("could not keep the media of {}: {}", message.getWaMessageId(), e.toString());
            return message;
        }
    }

    /** The digits of a {@code phone@c.us} id, else null. */
    private static String phoneOf(String remoteId) {
        return remoteId != null && remoteId.endsWith("@c.us") ? remoteId.substring(0, remoteId.indexOf('@')) : null;
    }

    /** A row that nobody has written to, assigned or routed: only an outbound message of ours can have created it. */
    private static boolean isOurOwnOutbound(WaWebChat chat) {
        return chat.getAssignedUserId() == null && chat.getAssignedTeamId() == null && chat.getLastInboundAt() == null;
    }

    /**
     * Upserts the message by {@code (phoneNumberId, waMessageId)} and folds it into the chat. A row the ingest
     * listener stored first (its {@code message_create} can beat the RPC result) is taken over: the actor is its
     * source and sender, its status only moves up.
     */
    private Recorded record(SendActor actor, WaWebChat sent, Outgoing out, String type, String waMessageId,
            Instant at, int ack, Instant now, String lidPhone) {
        // a chat without an id is stored now, the device having accepted its first message; a row another writer
        // created for the same remote id wins, and a lost race is retried by the caller
        WaWebChat chat = sent.getId() == null
                ? chats.findByPhoneNumberIdAndRemoteId(sent.getPhoneNumberId(), sent.getRemoteId()).orElse(sent)
                : chats.findById(sent.getId()).orElse(sent);
        if (sent.getId() == null) {
            // the person the console wrote to becomes (or already is) a contact, carrying the phone that was typed
            Contact contact = lidPhone != null && chat.getRemoteId().endsWith("@lid")
                    ? contacts.linkLidToPhone(sent.getWorkspaceId(), sent.getCustomerId(), chat.getRemoteId(),
                            lidPhone, now)
                    : contacts.upsertFromChat(sent.getWorkspaceId(), sent.getCustomerId(), chat.getRemoteId(), null,
                            null, now);
            if (contact != null) chat.setContactId(contact.getId());
        }
        if (chat.getId() == null) {
            chat = chats.save(chat);
        } else if (sent.getId() == null && isOurOwnOutbound(chat)) {
            // the device's message_create beat this result and created the row from our own first message, so the
            // creator the console meant it for (or the campaign that started it) was lost with the unsaved chat
            chat.setAssignedUserId(sent.getAssignedUserId());
            chat.setAssignedAt(sent.getAssignedAt());
            chat.setCampaignId(sent.getCampaignId());
        } else if (sent.getId() != null && actor.campaignId() != null
                && actor.campaignId().equals(sent.getCampaignId())) {
            // a saved chat the campaign marked for routing on reply; the row was re-read above, so mark it again
            chat.setCampaignId(sent.getCampaignId());
        }

        WaWebMessage message = messages.findByPhoneNumberIdAndWaMessageId(sent.getPhoneNumberId(), waMessageId)
                .orElse(null);
        boolean inserted = message == null;
        if (inserted) {
            message = new WaWebMessage();
            message.setCustomerId(sent.getCustomerId());
            message.setWorkspaceId(sent.getWorkspaceId());
            message.setPhoneNumberId(sent.getPhoneNumberId());
            message.setChatId(chat.getId());
            message.setWaMessageId(waMessageId);
            message.setDirection(MessageDirection.OUT);
            message.setType(type);
            message.setBody(out.body());
            message.setTimestamp(at);
            message.setStatus(MessageStatus.PENDING);
            message.setStatusTimes(new WaWebMessage.StatusTimes());
        }
        if (message.getMedia() == null) message.setMedia(out.media());
        if (message.getLocation() == null) message.setLocation(out.location());
        message.setSource(actor.source());
        message.setSentByUserId(actor.userId());
        message.setSentByName(actor.displayName());
        message.setCampaignId(actor.campaignId());
        message.setTemplateId(out.templateId());
        InboxIngestService.applyAck(message, ack, now);
        message = messages.save(message);
        message = cacheMedia(message, out);

        InboxIngestService.foldOutbound(chat, message, message.getTimestamp());
        if (actor.userId() != null) {
            // a person handled this chat: a returning customer may stick to them (design §3.3)
            chat.setLastAgentUserId(actor.userId());
            chat.setLastAgentAt(now);
        }
        return new Recorded(chats.save(chat), message, inserted);
    }
}
