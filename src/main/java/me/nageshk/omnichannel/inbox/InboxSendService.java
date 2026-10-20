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
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.api.NoDeviceException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberService;
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
import me.nageshk.omnichannel.ws.DeviceRegistry;
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
 * Sends a reply typed in the console through the number's device (spec section 6) and stores it as a
 * {@link MessageSource#CONSOLE} message. Checks run in this order, and nothing reaches the device unless all pass:
 * chat open, number usable and device ready, request and template valid.
 */
@Service
@RequiredArgsConstructor
public class InboxSendService {

    public static final int MAX_TEXT = 4096;

    private static final Logger log = LoggerFactory.getLogger(InboxSendService.class);

    /** What goes to the device and what is stored for it; {@code body} may be null. */
    private record Outgoing(String content, ObjectNode options, String type, String body,
            WaWebMessage.Media media, WaWebMessage.Location location, String templateId) {
    }

    /** The stored message and the chat after it; {@code inserted} is false when the ingest listener got there first. */
    private record Recorded(WaWebChat chat, WaWebMessage message, boolean inserted) {
    }

    private final DeviceRegistry registry;
    private final RpcDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final AccessService access;
    private final PhoneNumberService phoneNumbers;
    private final TemplateRenderer renderer;
    private final TemplateMediaService templateMedia;
    private final TemplateService templates;
    private final ChatAssignmentService assignment;
    private final WaWebChatRepository chats;
    private final WaWebMessageRepository messages;
    private final UserRepository users;
    private final InboxViews views;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /**
     * Callers check {@code inbox:send} and that the chat is visible to {@code me}. The future fails with the
     * {@code RpcException} of a device error or timeout, in which case nothing is stored.
     *
     * @throws ChatClosedException if the chat is closed
     * @throws NoDeviceException if the number is disabled or its device is missing or not CONNECTED
     * @throws ValidationException for a bad request, a missing template value or a contact card template
     */
    public CompletableFuture<MessageView> send(AuthenticatedUser me, String workspaceId, WaWebChat chat,
            SendRequest req) {
        if (chat.getStatus() != ChatStatus.OPEN) throw new ChatClosedException();
        DeviceSession device = requireReadyDevice(workspaceId, chat);
        Outgoing out = outgoing(me, workspaceId, chat, req);

        ObjectNode params = mapper.createObjectNode();
        params.put("chatId", chat.getRemoteId());
        params.put("content", out.content());
        params.set("options", out.options());
        params.put("sendSeen", true);
        return dispatcher.call(device, "sendMessage", params).thenApply(result -> afterSend(me, chat, out, result));
    }

    private DeviceSession requireReadyDevice(String workspaceId, WaWebChat chat) {
        PhoneNumber number = phoneNumbers.requireInWorkspace(workspaceId, chat.getPhoneNumberId());
        if (number.getDisabled() != null || !access.isWorkspaceUsable(workspaceId)) {
            throw new NoDeviceException(NoDeviceException.NO_DEVICE, "the number is disabled");
        }
        DeviceSession device = registry.find(number.getId()).orElseThrow(() -> new NoDeviceException(
                NoDeviceException.NO_DEVICE, "no extension connected for number " + number.getId()));
        if (!device.confirmed() || !DeviceSession.STATE_CONNECTED.equals(device.waState())) {
            throw new NoDeviceException(NoDeviceException.WHATSAPP_NOT_READY, "WhatsApp state is " + device.waState());
        }
        return device;
    }

    // ---- request to outgoing message ----

    /** Exactly one of text and template; anything else is {@code fields.text = "required"}. */
    private Outgoing outgoing(AuthenticatedUser me, String workspaceId, WaWebChat chat, SendRequest req) {
        String text = req == null || req.text() == null ? "" : req.text().strip();
        boolean hasText = !text.isEmpty();
        boolean hasTemplate = req != null && !isBlank(req.templateId());
        if (hasText == hasTemplate) throw new ValidationException("text", "required");
        if (hasText) {
            if (text.length() > MAX_TEXT) {
                throw new ValidationException("text", "must be at most " + MAX_TEXT + " characters");
            }
            return new Outgoing(text, mapper.createObjectNode(), null, text, null, null, null);
        }
        String templateId = req.templateId().strip();
        if (templates.require(workspaceId, templateId).getStatus() == TemplateStatus.ARCHIVED) {
            throw new ValidationException("templateId", "archived");
        }
        RenderedTemplate rendered = renderer.render(me, workspaceId, templateId,
                new RenderRequest(chat.getContactId(), req.values()));
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
                return new Outgoing(c.getBody(), options, "chat", c.getBody(), null, null, templateId);
            }
            case IMAGE, VIDEO, DOCUMENT, AUDIO -> {
                String caption = c.getCaption() == null ? "" : c.getCaption();
                TemplateMediaService.MediaFile file = templateMedia.open(workspaceId, r.media().mediaId());
                ObjectNode media = options.putObject("media");
                media.put("mimetype", file.mimeType());
                media.put("data", Base64.getEncoder().encodeToString(read(file)));
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
                // media copy in Task 8: the bytes are not stored with the message yet, so mediaId stays null
                WaWebMessage.Media stored = new WaWebMessage.Media(file.mimeType(), file.fileName(), file.size(),
                        null, null);
                return new Outgoing(caption, options, type, caption.isEmpty() ? null : caption, stored, null,
                        templateId);
            }
            case LOCATION -> {
                String description = joined(c.getName(), c.getAddress());
                ObjectNode location = options.putObject("location");
                location.put("latitude", c.getLatitude());
                location.put("longitude", c.getLongitude());
                if (description != null) location.put("description", description);
                return new Outgoing("", options, "location", null, null,
                        new WaWebMessage.Location(c.getLatitude(), c.getLongitude(), description), templateId);
            }
            case POLL -> {
                ObjectNode poll = options.putObject("poll");
                poll.put("pollName", c.getQuestion());
                ArrayNode choices = poll.putArray("pollOptions");
                c.getOptions().forEach(choices::add);
                poll.putObject("options").put("allowMultipleAnswers", Boolean.TRUE.equals(c.getAllowMultiple()));
                return new Outgoing("", options, "poll_creation", c.getQuestion(), null, null, templateId);
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

    private MessageView afterSend(AuthenticatedUser me, WaWebChat sent, Outgoing out, JsonNode result) {
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
        String sender = users.findById(me.userId()).map(User::getName).orElse(me.getUsername());

        Recorded recorded = InboxIngestService.retrying(
                () -> record(me, sender, sent, out, type == null ? "chat" : type, id, at, ack, now));
        WaWebChat chat = recorded.chat();
        try {
            chat = assignment.claimOnReply(me, chat);
        } catch (RuntimeException e) {
            log.warn("could not claim chat {} for {} after a send: {}", chat.getId(), me.userId(), e.toString());
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
     * Upserts the message by {@code (phoneNumberId, waMessageId)} and folds it into the chat. A row the ingest
     * listener stored first (its {@code message_create} can beat the RPC result) is taken over: the console is its
     * source and sender, its status only moves up.
     */
    private Recorded record(AuthenticatedUser me, String senderName, WaWebChat sent, Outgoing out, String type,
            String waMessageId, Instant at, int ack, Instant now) {
        WaWebMessage message = messages.findByPhoneNumberIdAndWaMessageId(sent.getPhoneNumberId(), waMessageId)
                .orElse(null);
        boolean inserted = message == null;
        if (inserted) {
            message = new WaWebMessage();
            message.setCustomerId(sent.getCustomerId());
            message.setWorkspaceId(sent.getWorkspaceId());
            message.setPhoneNumberId(sent.getPhoneNumberId());
            message.setChatId(sent.getId());
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
        message.setSource(MessageSource.CONSOLE);
        message.setSentByUserId(me.userId());
        message.setSentByName(senderName);
        message.setTemplateId(out.templateId());
        InboxIngestService.applyAck(message, ack, now);
        message = messages.save(message);

        WaWebChat chat = chats.findById(sent.getId()).orElse(sent);
        InboxIngestService.foldOutbound(chat, message, message.getTimestamp());
        return new Recorded(chats.save(chat), message, inserted);
    }
}
