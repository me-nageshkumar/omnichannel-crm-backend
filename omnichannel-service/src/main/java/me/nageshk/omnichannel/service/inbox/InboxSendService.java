package me.nageshk.omnichannel.service.inbox;

import me.nageshk.omnichannel.common.channel.whatsapp.message.TemplateSnapshot;
import me.nageshk.omnichannel.meta.api.CloudTemplateInfo;
import me.nageshk.omnichannel.meta.api.CloudTemplates;
import me.nageshk.omnichannel.service.cloud.CloudTemplateFills;
import me.nageshk.omnichannel.meta.api.CloudAccountInfo;
import me.nageshk.omnichannel.meta.api.CloudApi;
import me.nageshk.omnichannel.meta.api.CloudMedia;
import me.nageshk.omnichannel.meta.api.CloudSendException;
import me.nageshk.omnichannel.service.channel.cloud.CloudStoredStatuses;
import me.nageshk.omnichannel.service.billing.WorkGate;
import me.nageshk.omnichannel.service.billing.wallet.UsageCharger;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageEcho;
import me.nageshk.omnichannel.common.inbox.ChatClosedException;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.db.template.TemplateContent;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.template.TemplateType;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.channel.whatsapp.WaWebWebhookConverter;
import me.nageshk.omnichannel.service.contact.ContactService;
import me.nageshk.omnichannel.service.rpc.RpcDispatcher;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.LocationObject;
import me.nageshk.omnichannel.common.channel.whatsapp.message.MediaObject;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessages;
import me.nageshk.omnichannel.common.channel.whatsapp.message.MessagingProduct;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Text;
import me.nageshk.omnichannel.db.page.MessagePage;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import me.nageshk.omnichannel.service.cloud.CloudMessageValidator;
import me.nageshk.omnichannel.service.page.MessagePageService;
import me.nageshk.omnichannel.service.template.RenderRequest;
import me.nageshk.omnichannel.service.template.RenderedTemplate;
import me.nageshk.omnichannel.service.template.TemplateMediaService;
import me.nageshk.omnichannel.service.template.TemplateRenderer;
import me.nageshk.omnichannel.service.template.TemplateService;
import me.nageshk.omnichannel.service.ws.DeviceSession;
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
            WaWebMessage.Media media, byte[] mediaBytes, WaWebMessage.Location location, String templateId,
            List<String> pollOptions, String messagePageId, Map<String, Object> request, CloudMessage cloudMessage,
            String cloudTemplateId, TemplateSnapshot template) {

        Outgoing(String content, ObjectNode options, String type, String body, WaWebMessage.Media media,
                byte[] mediaBytes, WaWebMessage.Location location, String templateId, List<String> pollOptions,
                String messagePageId, Map<String, Object> request, CloudMessage cloudMessage) {
            this(content, options, type, body, media, mediaBytes, location, templateId, pollOptions, messagePageId,
                    request, cloudMessage, null, null);
        }

        /** Without a Cloud message: what goes to a WhatsApp Web device (and to Meta, mapped). */
        Outgoing(String content, ObjectNode options, String type, String body, WaWebMessage.Media media,
                byte[] mediaBytes, WaWebMessage.Location location, String templateId, List<String> pollOptions,
                String messagePageId, Map<String, Object> request) {
            this(content, options, type, body, media, mediaBytes, location, templateId, pollOptions, messagePageId,
                    request, null);
        }

        Outgoing(String content, ObjectNode options, String type, String body, WaWebMessage.Media media,
                byte[] mediaBytes, WaWebMessage.Location location, String templateId) {
            this(content, options, type, body, media, mediaBytes, location, templateId, null);
        }

        Outgoing(String content, ObjectNode options, String type, String body, WaWebMessage.Media media,
                byte[] mediaBytes, WaWebMessage.Location location, String templateId, List<String> pollOptions) {
            this(content, options, type, body, media, mediaBytes, location, templateId, pollOptions, null, null);
        }
    }

    /** The stored message and the chat after it; {@code inserted} is false when the ingest listener got there first. */
    private record Recorded(WaWebChat chat, WaWebMessage message, boolean inserted) {
    }

    private final InboxDevices devices;
    private final WorkGate workGate;
    private final WaWebWebhookConverter converter;
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
    private final MessagePageService pages;
    private final CloudMessageValidator validator;
    private final CloudMediaService cloudMedia;
    private final CloudMessages cloud;
    private final UsageCharger usage;
    private final CloudApi cloudApi;
    private final CloudMedia metaMedia;
    private final CloudStoredStatuses cloudStatuses;
    private final CloudTemplates cloudTemplates;
    private final CloudTemplateFills templateFills;

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
     * {@link CampaignActor} or {@link AutomationActor} never does; only an {@link AutomationActor} may send to a closed chat. The chat may be unsaved (no id):
     * it is stored with the first message once the device accepted it, or merged into the row that exists for the
     * same remote id by then. The future fails with the {@code RpcException} of a device error or timeout, in which
     * case nothing is stored. {@code typedPhone} is the phone the console addressed an unsaved chat by (null for
     * saved chats): it is what the contact of a chat stored under a {@code @lid} is linked to.
     *
     * @param typedPhone normalised digits typed (or imported) for an unsaved chat, else null
     * @throws ChatClosedException if the chat is closed
     * @throws NoDeviceException if the number is disabled or its device is missing or not CONNECTED
     * @throws me.nageshk.omnichannel.common.api.SubscriptionInactiveException if the customer's plan does not cover
     *     the send or its subscription has ended
     * @throws ValidationException for a bad request, a missing template value or a contact card template
     */
    public CompletableFuture<MessageView> send(SendActor actor, String workspaceId, WaWebChat chat,
            SendRequest req, String typedPhone) {
        // a rule's message may go to a closed chat, and leaves it closed; anyone may write into a chatbot's chat
        if (!(actor instanceof AutomationActor || actor instanceof ChatbotActor) && chat.getStatus() == ChatStatus.CLOSED) {
            throw new ChatClosedException();
        }
        SendChannel channel = devices.requireChannel(workspaceId, chat);
        workGate.requireSend(workspaceId, actor);
        Outgoing out = outgoing(actor, workspaceId, chat, req);
        if (channel instanceof SendChannel.CloudChannel) refuseOnCloud(out);
        return charged(actor, workspaceId, () -> dispatch(actor, takenOver(actor, chat), channel, out, typedPhone));
    }

    /**
     * Sends with the message charged to the customer's wallet first. An empty wallet stops the send before anything
     * reaches the phone ({@code WalletEmptyException}, 402). The charge is given back when the send could not be
     * started or the phone refused it, and kept when the outcome is unknown.
     */
    private CompletableFuture<MessageView> charged(SendActor actor, String workspaceId,
            Supplier<CompletableFuture<MessageView>> send) {
        UsageCharger.Charge charge = usage.charge(workspaceId, actor);
        if (charge == null) return send.get();
        CompletableFuture<MessageView> sent;
        try {
            sent = send.get();
        } catch (RuntimeException e) {
            usage.giveBack(charge, "not sent");
            throw e;
        }
        return sent.whenComplete((view, error) -> {
            if (error == null) usage.stamp(view == null ? null : view.id(), charge);
            else if (UsageCharger.definitelyNotSent(error)) usage.giveBack(charge, "the phone refused it");
        });
    }

    /** Sends a Cloud API message as the console user {@code me}; see {@link #sendCloud(SendActor, String, WaWebChat, CloudMessage, String, boolean)}. */
    public CompletableFuture<MessageView> sendCloud(AuthenticatedUser me, String workspaceId, WaWebChat chat,
            CloudMessage message, String typedPhone, boolean toIsPhone) {
        UserActor actor = new UserActor(me, users.findById(me.userId()).map(User::getName).orElse(me.getUsername()));
        return sendCloud(actor, workspaceId, chat, message, typedPhone, toIsPhone);
    }

    /**
     * Sends a Cloud API message (spec §5.7). A {@code text} goes out as typed text does. An {@code interactive}
     * message goes out as a link to its message page: the page row and its preview image exist before the device is
     * asked, so WhatsApp's preview fetch finds them. The same checks and order as {@link #send} apply.
     *
     * @param toIsPhone whether {@code to} must be a phone (the Cloud-shaped API) or any id (a chat-keyed send)
     * @throws ValidationException for anything Meta's limits or ours refuse, keyed by JSON path
     * @throws IllegalStateException when the preview image cannot be drawn: nothing is sent
     */
    public CompletableFuture<MessageView> sendCloud(SendActor actor, String workspaceId, WaWebChat chat,
            CloudMessage message, String typedPhone, boolean toIsPhone) {
        if (!(actor instanceof AutomationActor || actor instanceof ChatbotActor) && chat.getStatus() == ChatStatus.CLOSED) {
            throw new ChatClosedException();
        }
        SendChannel channel = devices.requireChannel(workspaceId, chat);
        workGate.requireSend(workspaceId, actor);
        CloudMessage m = validator.validate(workspaceId, message, cloudMedia::mimeOf, toIsPhone);
        // charged first: a message the wallet cannot pay for prepares no page and no preview
        return charged(actor, workspaceId, () -> {
            WaWebChat target = takenOver(actor, chat);
            Outgoing out = channel instanceof SendChannel.CloudChannel ? cloudNative(target, m) : cloudOutgoing(target, m);
            return dispatch(actor, target, channel, out, typedPhone);
        });
    }

    /**
     * A person whose send is about to go out to a chat a chatbot holds takes the chat over (spec 2026-11-18 §5.3);
     * done only once every check passed, so a refused send leaves the chat with the chatbot.
     */
    private WaWebChat takenOver(SendActor actor, WaWebChat chat) {
        return actor instanceof UserActor user && chat.getStatus() == ChatStatus.BOT ? assignment.takeOver(user.me(), chat)
                : chat;
    }

    private CompletableFuture<MessageView> dispatch(SendActor actor, WaWebChat chat, SendChannel channel,
            Outgoing out, String typedPhone) {
        if (channel instanceof SendChannel.CloudChannel c) return cloudDispatch(actor, chat, c.account(), out, typedPhone);
        DeviceSession device = ((SendChannel.DeviceChannel) channel).device();
        ObjectNode params = mapper.createObjectNode();
        params.put("chatId", chat.getRemoteId());
        params.put("content", out.content());
        params.set("options", out.options());
        params.put("sendSeen", true);
        return dispatcher.call(device, "sendMessage", params)
                .thenApply(result -> afterSend(actor, chat, out, result, typedPhone));
    }

    // ---- Cloud API numbers (spec 2026-11-20 §5.4) ----

    /** A saved message Meta has no equivalent for is refused before anything is charged. */
    private static void refuseOnCloud(Outgoing out) {
        if ("poll_creation".equals(out.type())) {
            throw new ValidationException("templateId", "Polls are not available on Cloud API numbers");
        }
    }

    /** A validated Cloud message goes to Meta as it is: buttons and lists are native, no message page. */
    private Outgoing cloudNative(WaWebChat chat, CloudMessage m) {
        Map<String, Object> request = cloud.toMap(m);
        if (CloudMessage.TEXT.equals(m.type())) {
            String body = m.text().body();
            return new Outgoing(body, null, null, body, null, null, null, null, null, null, request, m);
        }
        if (CloudMessage.LOCATION.equals(m.type())) {
            LocationObject l = m.location();
            return new Outgoing("", null, "location", null, null, null,
                    new WaWebMessage.Location(l.latitude(), l.longitude(), joined(l.name(), l.address())), null, null,
                    null, request, m);
        }
        if (CloudMessage.MEDIA_TYPES.contains(m.type())) {
            MediaObject o = m.media();
            CloudMediaService.MediaFile file = cloudMedia.open(chat.getWorkspaceId(), o.id());
            byte[] bytes;
            try (InputStream in = file.resource().getInputStream()) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String filename = o.filename() != null ? o.filename() : file.resource().getFilename();
            String caption = o.caption() == null ? "" : o.caption();
            WaWebMessage.Media stored = new WaWebMessage.Media(file.mimeType(), filename, file.size(), null, null);
            return new Outgoing(caption, null, m.type(), caption.isEmpty() ? null : caption, stored, bytes, null, null,
                    null, null, request, m);
        }
        if (CloudMessage.TEMPLATE.equals(m.type())) {
            // a template sent through the Cloud-shaped API as Meta's object; the inbox sends imported ones with a preview
            String body = "Template " + m.template().name();
            return new Outgoing(body, null, CloudMessage.TEMPLATE, body, null, null, null, null, null, null, request, m);
        }
        String body = m.interactive() != null && m.interactive().body() != null ? m.interactive().body().text() : null;
        return new Outgoing(body, null, CloudMessage.INTERACTIVE, body, null, null, null, null, null, null, request, m);
    }

    /**
     * Sends through Meta. A refusal fails the future (so the charge is given back when nothing went out); a refused
     * token stops the number until it is replaced; a rate limit is a "try later", as a missing device is.
     */
    private CompletableFuture<MessageView> cloudDispatch(SendActor actor, WaWebChat chat, CloudAccountInfo account,
            Outgoing out, String typedPhone) {
        String wamid;
        try {
            wamid = cloudApi.send(chat.getPhoneNumberId(), toCloud(chat, out), userIdOf(chat.getRemoteId()));
        } catch (CloudSendException e) {
            return CompletableFuture.failedFuture(switch (e.kind()) {
                case TOKEN_REFUSED -> new NoDeviceException(InboxDevices.CLOUD_NOT_READY,
                        "Meta refused this number's access token; replace it on Phone numbers.");
                case RATE_LIMITED -> new NoDeviceException(CLOUD_RATE_LIMITED, "Meta asks to slow down: " + e.getMessage());
                default -> e;
            });
        } catch (CloudMedia.CloudMediaException e) {
            return CompletableFuture.failedFuture(new CloudSendException(CloudSendException.Kind.REFUSED, null,
                    "Meta did not take the file", e.getMessage(), null));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.completedFuture(afterCloudSend(actor, chat, account, out, wamid, typedPhone));
    }

    /** Sends an imported Meta template as the console user {@code me}; see the {@link SendActor} form. */
    public CompletableFuture<MessageView> sendCloudTemplate(AuthenticatedUser me, String workspaceId, WaWebChat chat,
            CloudTemplateRequest req, String typedPhone) {
        UserActor actor = new UserActor(me, users.findById(me.userId()).map(User::getName).orElse(me.getUsername()));
        return sendCloudTemplate(actor, workspaceId, chat, req, typedPhone);
    }

    /**
     * Sends an imported, approved Meta template on a Cloud API number (spec 2026-11-20 §5.6), any time: templates are
     * what Meta allows outside the 24-hour window. Blanks take the given values, else their fills; every blank must
     * have a value and a media header needs its file, all checked before anything is charged.
     *
     * @throws ValidationException {@code cloudTemplateId} (unknown, not sendable, another WABA's, or a WhatsApp Web
     *     chat), {@code values.<key>} (empty), {@code headerMediaId} (missing)
     */
    public CompletableFuture<MessageView> sendCloudTemplate(SendActor actor, String workspaceId, WaWebChat chat,
            CloudTemplateRequest req, String typedPhone) {
        if (!(actor instanceof AutomationActor || actor instanceof ChatbotActor) && chat.getStatus() == ChatStatus.CLOSED) {
            throw new ChatClosedException();
        }
        if (req == null || isBlank(req.cloudTemplateId())) throw new ValidationException("cloudTemplateId", "required");
        SendChannel channel = devices.requireChannel(workspaceId, chat);
        if (!(channel instanceof SendChannel.CloudChannel cc)) {
            throw new ValidationException("cloudTemplateId", "channel not available");
        }
        workGate.requireSend(workspaceId, actor);
        CloudTemplateInfo t = cloudTemplates.get(workspaceId, req.cloudTemplateId().strip());
        if (!t.sendable()) {
            throw new ValidationException("cloudTemplateId", "This WhatsApp template is not approved, or not supported yet");
        }
        if (!t.wabaId().equals(cc.account().wabaId())) {
            throw new ValidationException("cloudTemplateId", "This template belongs to another WhatsApp account");
        }
        Map<String, String> values = templateFills.resolve(t, chat, req.values());
        Map<String, String> errors = new LinkedHashMap<>();
        cloudTemplates.missing(t, values).forEach(k -> errors.put("values." + k, "required"));
        CloudMediaService.MediaFile headerFile = null;
        if (t.header().media()) {
            if (isBlank(req.headerMediaId())) {
                errors.put("headerMediaId", "a file for the template's " + t.header().format().toLowerCase() + " header");
            } else {
                headerFile = cloudMedia.open(workspaceId, req.headerMediaId());
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        CloudMediaService.MediaFile file = headerFile;
        return charged(actor, workspaceId, () -> {
            WaWebChat target = takenOver(actor, chat);
            String metaMediaId = null;
            if (file != null) {
                try (InputStream in = file.resource().getInputStream()) {
                    metaMediaId = metaMedia.upload(chat.getPhoneNumberId(), in.readAllBytes(), file.mimeType(),
                            file.resource().getFilename());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (CloudMedia.CloudMediaException e) {
                    return CompletableFuture.failedFuture(new CloudSendException(CloudSendException.Kind.REFUSED, null,
                            "Meta did not take the header file", e.getMessage(), null));
                }
            }
            CloudTemplates.Built built = cloudTemplates.build(t, values, metaMediaId);
            TemplateSnapshot shown = file == null ? built.snapshot() : new TemplateSnapshot(built.snapshot().name(),
                    built.snapshot().language(), new TemplateSnapshot.Header(built.snapshot().header().format(), null,
                            req.headerMediaId()), built.snapshot().body(), built.snapshot().footer(),
                    built.snapshot().buttons());
            CloudMessage m = CloudMessage.template(phoneOf(target.getRemoteId()), built.template());
            Outgoing out = new Outgoing(built.renderedBody(), null, CloudMessage.TEMPLATE, built.renderedBody(), null,
                    null, null, null, null, null, cloud.toMap(m), m, t.id(), shown);
            return dispatch(actor, target, channel, out, typedPhone).thenApply(view -> {
                try {
                    cloudTemplates.markUsed(t.id());
                } catch (RuntimeException e) {
                    log.warn("could not mark WhatsApp template {} used: {}", t.id(), e.toString());
                }
                return view;
            });
        });
    }

    /** Meta asks the number to slow down; the chatbot and rules try again later. */
    public static final String CLOUD_RATE_LIMITED = "CLOUD_RATE_LIMITED";

    /** The Cloud message for what is going out: the caller's own, or the typed text or saved message mapped. */
    private CloudMessage toCloud(WaWebChat chat, Outgoing out) {
        String to = phoneOf(chat.getRemoteId());
        CloudMessage m = out.cloudMessage();
        if (m == null) {
            String type = out.type() == null ? CloudMessage.TEXT : out.type();
            m = switch (type) {
                case CloudMessage.TEXT -> new CloudMessage(MessagingProduct.WHATSAPP, "individual", to, CloudMessage.TEXT,
                        new Text(true, out.body()), null, null);
                case CloudMessage.LOCATION -> new CloudMessage(MessagingProduct.WHATSAPP, "individual", to,
                        CloudMessage.LOCATION, null, null, null, null, null, null, new LocationObject(
                                out.location().latitude(), out.location().longitude(), out.location().description(),
                                null), null);
                case CloudMessage.IMAGE, CloudMessage.VIDEO, CloudMessage.AUDIO, CloudMessage.DOCUMENT -> media(
                        chat.getPhoneNumberId(), type, to, null, out);
                default -> throw new ValidationException("templateId", "A " + type
                        + " message is not available on Cloud API numbers");
            };
        } else if (CloudMessage.MEDIA_TYPES.contains(m.type())) {
            m = media(chat.getPhoneNumberId(), m.type(), to, m, out);
        }
        return m.withTo(to);
    }

    /** A media message by Meta's id: the bytes are uploaded first (spec §5.4: every send, no reuse cache). */
    private CloudMessage media(String phoneNumberId, String type, String to, CloudMessage given, Outgoing out) {
        String metaId = metaMedia.upload(phoneNumberId, out.mediaBytes(), out.media().mimetype(),
                out.media().filename());
        MediaObject o = new MediaObject(metaId, null, out.body(),
                CloudMessage.DOCUMENT.equals(type) ? out.media().filename() : null);
        return new CloudMessage(MessagingProduct.WHATSAPP, "individual", to, type, null, null,
                CloudMessage.IMAGE.equals(type) ? o : null, CloudMessage.VIDEO.equals(type) ? o : null,
                CloudMessage.AUDIO.equals(type) ? o : null, CloudMessage.DOCUMENT.equals(type) ? o : null, null,
                given == null ? null : given.context());
    }

    /** Meta's user id of a {@code @user} chat (a person whose phone Meta does not share), else null. */
    private static String userIdOf(String remoteId) {
        return remoteId != null && remoteId.endsWith(MessageFields.USER)
                ? remoteId.substring(0, remoteId.length() - MessageFields.USER.length()) : null;
    }

    private MessageView afterCloudSend(SendActor actor, WaWebChat sent, CloudAccountInfo account, Outgoing out,
            String wamid, String typedPhone) {
        devices.requireStillIn(sent.getWorkspaceId(), sent.getPhoneNumberId());
        Instant now = clock.instant();
        String type = out.type() == null ? "text" : out.type();
        Recorded recorded = InboxIngestService.retrying(
                () -> record(actor, sent, out, type, wamid, now, 0, now, typedPhone));
        ChannelContext ctx = new ChannelContext(ChannelKind.WHATSAPP_CLOUD, sent.getCustomerId(), sent.getWorkspaceId(),
                sent.getPhoneNumberId(), devices.digitsOf(sent.getPhoneNumberId()));
        try {
            cloudStatuses.apply(ctx, wamid);
        } catch (RuntimeException e) {
            log.warn("could not apply early statuses of {}: {}", wamid, e.toString());
        }
        markReadAtMeta(recorded.chat());
        return finish(actor, recorded, out, now);
    }

    /** The customer's last message is marked read at Meta, as the bridge's {@code sendSeen} does; best effort. */
    private void markReadAtMeta(WaWebChat chat) {
        messages.findFirstByChatIdAndDirectionOrderByTimestampDesc(chat.getId(), MessageDirection.IN)
                .map(WaWebMessage::getWaMessageId)
                .ifPresent(id -> CompletableFuture.runAsync(() -> cloudApi.markRead(chat.getPhoneNumberId(), id)));
    }

    /** What goes to the device for a validated Cloud message. */
    private Outgoing cloudOutgoing(WaWebChat chat, CloudMessage m) {
        ObjectNode options = mapper.createObjectNode().put("linkPreview", true);
        Map<String, Object> request = cloud.toMap(m);
        if (CloudMessage.TEXT.equals(m.type())) {
            String body = m.text().body();
            return new Outgoing(body, options, null, body, null, null, null, null, null, null, request);
        }
        if (CloudMessage.LOCATION.equals(m.type())) {
            LocationObject l = m.location();
            String description = joined(l.name(), l.address());
            ObjectNode location = mapper.createObjectNode().putObject("location");
            location.put("latitude", l.latitude());
            location.put("longitude", l.longitude());
            if (description != null) location.put("description", description);
            ObjectNode opts = mapper.createObjectNode();
            opts.set("location", location);
            return new Outgoing("", opts, null, null, null, null,
                    new WaWebMessage.Location(l.latitude(), l.longitude(), description), null, null, null, request);
        }
        if (CloudMessage.MEDIA_TYPES.contains(m.type())) {
            MediaObject o = m.media();
            CloudMediaService.MediaFile file = cloudMedia.open(chat.getWorkspaceId(), o.id());
            byte[] bytes;
            try (InputStream in = file.resource().getInputStream()) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String filename = o.filename() != null ? o.filename() : file.resource().getFilename();
            String caption = o.caption() == null ? "" : o.caption();
            ObjectNode opts = mapper.createObjectNode();
            ObjectNode media = opts.putObject("media");
            media.put("mimetype", file.mimeType());
            media.put("data", Base64.getEncoder().encodeToString(bytes));
            media.put("filename", filename);
            if (!caption.isEmpty()) opts.put("caption", caption);
            if (CloudMessage.DOCUMENT.equals(m.type())) opts.put("sendMediaAsDocument", true);
            WaWebMessage.Media stored = new WaWebMessage.Media(file.mimeType(), filename, file.size(), null, null);
            return new Outgoing(caption, opts, null, caption.isEmpty() ? null : caption, stored, bytes, null, null,
                    null, null, request);
        }
        MessagePage page = pages.drawPreview(pages.create(chat, m));
        String link = pages.link(page);
        // the type is the echo's ("chat"), as for typed text; the stored body is the link WhatsApp shows
        return new Outgoing(link, options, null, link, null, null, null, null, null, page.getId(), request);
    }

    /** The Cloud request a typed text is recorded as; {@code to} is the chat's digits when it has any. */
    private Map<String, Object> textRequest(WaWebChat chat, String text) {
        String remote = chat.getRemoteId() == null ? "" : chat.getRemoteId();
        String to = remote.endsWith("@c.us") ? remote.substring(0, remote.indexOf('@')) : null;
        return cloud.toMap(new CloudMessage(MessagingProduct.WHATSAPP_WEB, "individual", to, CloudMessage.TEXT,
                new Text(true, text), null, null));
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
            // whatsapp-web.js's Client.sendMessage turns on link previews unless asked not to; the page script takes
            // our options as its internal options as they are, so a text with a link needs the flag from here.
            ObjectNode options = mapper.createObjectNode().put("linkPreview", true);
            return new Outgoing(text, options, null, text, null, null, null, null, null, null,
                    textRequest(chat, text));
        }
        String templateId = req.templateId().strip();
        if (templates.require(workspaceId, templateId).getStatus() == TemplateStatus.ARCHIVED) {
            throw new ValidationException("templateId", "archived");
        }
        RenderedTemplate rendered = switch (actor) {
            case UserActor u -> renderer.render(u.me(), workspaceId, templateId,
                    new RenderRequest(chat.getContactId(), req.values()));
            case CampaignActor c -> renderer.renderForCampaign(workspaceId, templateId, req.values());
            case AutomationActor a -> renderer.renderForAutomation(workspaceId, templateId, chat.getContactId(),
                    req.values());
            case ChatbotActor c -> renderer.renderForAutomation(workspaceId, templateId, chat.getContactId(),
                    req.values());
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
                return new Outgoing(c.getBody(), options, "text", c.getBody(), null, null, null, templateId);
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
                    default -> "audio";
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
                // the extension hands this to WhatsApp as it is, so it has the shape of the whatsapp-web.js Poll class
                for (int i = 0; i < c.getOptions().size(); i++) {
                    choices.addObject().put("name", c.getOptions().get(i).strip()).put("localId", i);
                }
                poll.putObject("options").put("allowMultipleAnswers", Boolean.TRUE.equals(c.getAllowMultiple()));
                return new Outgoing("", options, "poll_creation", c.getQuestion(), null, null, null, templateId,
                        c.getOptions());
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
        devices.requireStillIn(sent.getWorkspaceId(), sent.getPhoneNumberId());
        Instant now = clock.instant();
        MessageEcho echo = echoOf(sent, result, now, typedPhone);
        String waMessageId = echo == null ? "" : echo.id();
        if (waMessageId.isEmpty()) {
            waMessageId = "console:" + new ObjectId();
            echo = null;
            log.warn("sendMessage on {} returned no message model; stored {} and it will not merge with the later "
                    + "message_create event", sent.getPhoneNumberId(), waMessageId);
        }
        String id = waMessageId;
        Instant at = echo == null ? now : MessageFields.timestampOf(echo.timestamp(), now);
        int ack = echo == null || echo.waWeb() == null || echo.waWeb().ack() == null ? 0 : echo.waWeb().ack();
        String type = echo != null && out.type() == null ? echo.type() : out.type();
        // a chat started from the console was addressed to phone@c.us; WhatsApp mostly answers under the person's
        // @lid, and that is the chat the message (and its message_create event) belong to
        String lidPhone = typedPhone;
        String reported = echo == null || echo.waWeb() == null || echo.waWeb().chatId() == null ? ""
                : echo.waWeb().chatId();
        if (sent.getId() == null && !reported.isEmpty() && !reported.equals(sent.getRemoteId())) {
            if (lidPhone == null) lidPhone = phoneOf(sent.getRemoteId());
            sent.setRemoteId(reported);
        }
        String phone = lidPhone;

        Recorded recorded = InboxIngestService.retrying(
                () -> record(actor, sent, out, type == null ? "text" : type, id, at, ack, now, phone));
        return finish(actor, recorded, out, now);
    }

    /** After the row is stored: the sender claims the chat, the page and template note it, the consoles hear. */
    private MessageView finish(SendActor actor, Recorded recorded, Outgoing out, Instant now) {
        WaWebChat chat = recorded.chat();
        if (actor instanceof UserActor user) {
            try {
                chat = assignment.claimOnReply(user.me(), chat);
            } catch (RuntimeException e) {
                log.warn("could not claim chat {} for {} after a send: {}", chat.getId(), user.userId(),
                        e.toString());
            }
        }
        if (out.messagePageId() != null) {
            try {
                pages.attachMessage(out.messagePageId(), recorded.message().getId());
            } catch (RuntimeException e) {
                log.warn("could not attach message {} to page {}: {}", recorded.message().getId(),
                        out.messagePageId(), e.toString());
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
     * The model {@code sendMessage} returned, as the echo of our message; null when it returned none (or one with no
     * id), or one the converter could not read: the message was sent, so it is still stored.
     */
    private MessageEcho echoOf(WaWebChat sent, JsonNode result, Instant now, String typedPhone) {
        if (result == null || !result.isObject()) return null;
        ChannelContext ctx = new ChannelContext(ChannelKind.WHATSAPP_WEB, sent.getCustomerId(), sent.getWorkspaceId(),
                sent.getPhoneNumberId(), devices.digitsOf(sent.getPhoneNumberId()));
        try {
            return converter.echo(ctx, result, now, typedPhone).orElse(null);
        } catch (RuntimeException e) {
            log.warn("could not read the sendMessage result on {} (fields {}): {}", sent.getPhoneNumberId(),
                    result.propertyNames(), e.toString());
            return null;
        }
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
        // the person the console wrote to becomes (or already is) a contact, carrying the phone that was typed; done
        // before the chat is read, as linking the lid can merge two contacts and so re-point the chat row
        Contact contact = null;
        if (sent.getId() == null) {
            contact = lidPhone != null && sent.getRemoteId().endsWith("@lid")
                    ? contacts.linkLidToPhone(sent.getWorkspaceId(), sent.getCustomerId(), sent.getRemoteId(),
                            lidPhone, now)
                    : contacts.upsertFromChat(sent.getWorkspaceId(), sent.getCustomerId(), sent.getRemoteId(), null,
                            null, now);
        }
        // a chat without an id is stored now, the device having accepted its first message; a row another writer
        // created for the same remote id wins, and a lost race is retried by the caller
        WaWebChat chat = sent.getId() == null
                ? chats.findByPhoneNumberIdAndRemoteId(sent.getPhoneNumberId(), sent.getRemoteId()).orElse(sent)
                : chats.findById(sent.getId()).orElse(sent);
        if (contact != null) chat.setContactId(contact.getId());
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
        if (message.getPollOptions() == null) message.setPollOptions(out.pollOptions());
        message.setSource(actor.source());
        message.setSentByUserId(actor.userId());
        message.setSentByName(actor.displayName());
        message.setCampaignId(actor.campaignId());
        message.setTemplateId(out.templateId());
        if (out.messagePageId() != null) message.setMessagePageId(out.messagePageId());
        if (out.request() != null) message.setRequest(out.request());
        if (out.cloudTemplateId() != null) message.setCloudTemplateId(out.cloudTemplateId());
        if (out.template() != null) message.setTemplate(out.template());
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
