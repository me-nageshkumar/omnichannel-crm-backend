package me.nageshk.omnichannel.service.page;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import com.mongodb.client.gridfs.model.GridFSFile;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessages;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.db.page.MessagePage;
import me.nageshk.omnichannel.db.page.MessagePage.Action;
import me.nageshk.omnichannel.db.page.MessagePage.ActionKind;
import me.nageshk.omnichannel.db.page.MessagePageRepository;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import me.nageshk.omnichannel.service.campaign.CampaignMessageListener;
import me.nageshk.omnichannel.service.cloud.CloudMessageValidator;
import me.nageshk.omnichannel.service.inbox.InboxDevices;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;

/**
 * Message pages (spec §5.3, §5.5): created before a Cloud interactive message is sent, with the preview image drawn
 * before the dispatch; served by token; tracking opens, replies, link clicks and shared locations.
 */
@Service
public class MessagePageService {

    private static final Logger log = LoggerFactory.getLogger(MessagePageService.class);
    static final int MAX_ACTIONS = 50;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MessagePageRepository pages;
    private final GridFsTemplate previews;
    private final CloudMediaService media;
    private final MessagePageRenderer renderer;
    private final PreviewCardRenderer cards;
    private final CloudMessages cloud;
    private final InboxDevices devices;
    private final WaWebMessageRepository messages;
    private final WaWebChatRepository chats;
    private final OmnichannelProperties.Pages props;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final MessagePageObserver observer;

    public MessagePageService(MessagePageRepository pages,
            @Qualifier("messagePagePreviewsGridFs") GridFsTemplate previews, CloudMediaService media,
            MessagePageRenderer renderer, PreviewCardRenderer cards, CloudMessages cloud, InboxDevices devices,
            WaWebMessageRepository messages, WaWebChatRepository chats, OmnichannelProperties props,
            ApplicationEventPublisher publisher, Clock clock, MessagePageObserver observer) {
        this.pages = pages;
        this.previews = previews;
        this.media = media;
        this.renderer = renderer;
        this.cards = cards;
        this.cloud = cloud;
        this.devices = devices;
        this.messages = messages;
        this.chats = chats;
        this.props = props.pages();
        this.publisher = publisher;
        this.clock = clock;
        this.observer = observer;
    }

    // ---- creating ----

    /** Stores the page for a message about to be sent to {@code chat}; the preview is drawn by {@link #drawPreview}. */
    public MessagePage create(WaWebChat chat, CloudMessage message) {
        Instant now = clock.instant();
        MessagePage p = new MessagePage();
        p.setWorkspaceId(chat.getWorkspaceId());
        p.setCustomerId(chat.getCustomerId());
        p.setToken(newToken());
        p.setChatId(chat.getId());
        p.setContactId(chat.getContactId());
        p.setPhoneNumberId(chat.getPhoneNumberId());
        p.setSenderDigits(devices.digitsOf(chat.getPhoneNumberId()));
        p.setMessage(cloud.toMap(message));
        p.setCreatedAt(now);
        p.setExpiresAt(now.plus(Duration.ofDays(props.ttlDays())));
        return pages.save(p);
    }

    /**
     * Draws and stores the og.png; the page is only linkable after this.
     *
     * @throws IllegalStateException when it cannot be drawn: the page row is deleted and the send must not happen
     */
    public MessagePage drawPreview(MessagePage page) {
        try {
            CloudMessage m = cloud.fromMap(page.getMessage());
            Optional<byte[]> headerImage = Optional.empty();
            Interactive.Header h = m.interactive() == null ? null : m.interactive().header();
            if (h != null && "image".equals(h.type()) && h.image() != null && h.image().id() != null) {
                CloudMediaService.MediaFile f = media.open(page.getWorkspaceId(), h.image().id());
                try (var in = f.resource().getInputStream()) {
                    headerImage = Optional.of(in.readAllBytes());
                }
            }
            byte[] png = cards.draw(m, headerImage);
            Document meta = new Document("pageId", page.getId()).append("createdAt", Date.from(clock.instant()));
            ObjectId fileId = previews.store(new java.io.ByteArrayInputStream(png), "og.png", "image/png", meta);
            page.setPreviewReady(true);
            page.setPreviewFileId(fileId.toHexString());
            return pages.save(page);
        } catch (Exception e) {
            pages.deleteById(page.getId());
            throw new IllegalStateException("preview image could not be generated", e);
        }
    }

    public String link(MessagePage page) {
        return props.baseUrl() + "/t/" + page.getToken();
    }

    public void attachMessage(String pageId, String messageId) {
        pages.findById(pageId).ifPresent(p -> {
            p.setMessageId(messageId);
            pages.save(p);
        });
    }

    // ---- serving ----

    public Optional<MessagePage> byToken(String token) {
        return token == null || token.isBlank() ? Optional.empty() : pages.findByToken(token);
    }

    public boolean expired(MessagePage p) {
        return p.getExpiresAt() != null && !clock.instant().isBefore(p.getExpiresAt());
    }

    public String html(MessagePage p) {
        return renderer.render(new PageModel(cloud.fromMap(p.getMessage()), p.getToken(), props.baseUrl(),
                p.getSenderDigits(), false)).html();
    }

    /** The composer's preview: same renderer, no beacon, inert actions. */
    public String previewHtml(CloudMessage m, String senderDigits) {
        return renderer.render(new PageModel(m, "preview", props.baseUrl(), senderDigits, true)).html();
    }

    public String notFoundHtml() {
        return renderer.notFound();
    }

    public String expiredHtml() {
        return renderer.expired();
    }

    public Optional<GridFsResource> preview(MessagePage p) {
        if (!p.isPreviewReady() || p.getPreviewFileId() == null || !ObjectId.isValid(p.getPreviewFileId())) {
            return Optional.empty();
        }
        GridFSFile f = previews.findOne(new Query(Criteria.where("_id").is(new ObjectId(p.getPreviewFileId()))));
        return Optional.ofNullable(f).map(previews::getResource);
    }

    public Optional<CloudMediaService.MediaFile> headerMedia(MessagePage p) {
        CloudMessage m = cloud.fromMap(p.getMessage());
        Interactive.Header h = m.interactive() == null ? null : m.interactive().header();
        if (h == null) return Optional.empty();
        Interactive.MediaRef ref = h.image() != null ? h.image() : h.video() != null ? h.video() : h.document();
        if (ref == null || ref.id() == null) return Optional.empty();
        try {
            return Optional.of(media.open(p.getWorkspaceId(), ref.id()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ---- tracking ----

    public void open(MessagePage p, String userAgent) {
        if (expired(p)) return;
        boolean first = p.getOpenedAt() == null;
        Instant now = clock.instant();
        if (first) p.setOpenedAt(now);
        p.setOpenCount(p.getOpenCount() + 1);
        record(p, new Action(ActionKind.OPEN, null, null, null, null, now, uaHash(userAgent), null), first);
    }

    /** A tap on a reply button or list row; ignored unless {@code id} is one of the message's replies. */
    public void reply(MessagePage p, String id, String userAgent) {
        if (expired(p) || id == null) return;
        Interactive i = cloud.fromMap(p.getMessage()).interactive();
        Interactive.Reply reply = CloudMessageValidator.replies(i).stream().filter(r -> id.equals(r.id())).findFirst()
                .orElse(null);
        if (reply == null) return;
        ActionKind kind = Interactive.LIST.equals(i.type()) ? ActionKind.LIST_REPLY : ActionKind.BUTTON_REPLY;
        boolean first = p.getActions().stream().noneMatch(a -> a.kind() == kind && id.equals(a.id()));
        record(p, new Action(kind, id, reply.title(), null, null, clock.instant(), uaHash(userAgent), null), first);
    }

    /** A shared location; only a location-request page takes one, and only with coordinates on Earth. */
    public void location(MessagePage p, Double lat, Double lng, String userAgent) {
        if (expired(p) || lat == null || lng == null) return;
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return;
        Interactive i = cloud.fromMap(p.getMessage()).interactive();
        if (i == null || !Interactive.LOCATION_REQUEST.equals(i.type())) return;
        boolean first = p.getActions().stream().noneMatch(a -> a.kind() == ActionKind.LOCATION);
        record(p, new Action(ActionKind.LOCATION, null, null, lat, lng, clock.instant(), uaHash(userAgent), null), first);
    }

    /** Records the click and answers the link's url; empty when the page is not a call-to-action link. */
    public Optional<String> cta(MessagePage p, String userAgent) {
        Interactive i = cloud.fromMap(p.getMessage()).interactive();
        if (i == null || !Interactive.CTA_URL.equals(i.type()) || i.action() == null || i.action().parameters() == null) {
            return Optional.empty();
        }
        String url = i.action().parameters().url();
        if (expired(p)) return Optional.of(url);
        boolean first = p.getActions().stream().noneMatch(a -> a.kind() == ActionKind.CTA_URL);
        record(p, new Action(ActionKind.CTA_URL, null, i.action().parameters().displayText(), null, null,
                clock.instant(), uaHash(userAgent), null), first);
        return Optional.of(url);
    }

    /** Appends the action (within the cap), saves, and tells the listeners and the inbox stream. */
    void record(MessagePage p, Action action, boolean first) {
        if (p.getActions().size() >= MAX_ACTIONS) {
            if (action.kind() != ActionKind.OPEN) return;
        } else {
            p.getActions().add(action);
        }
        p.setLastActionAt(action.at());
        MessagePage saved = pages.save(p);
        publisher.publishEvent(new MessagePageEvents.Activity(saved, action, first));
        if (first) observe(saved, action);
        changed(saved);
    }

    /** One CRM activity per page open and per distinct action; a failure there never fails the beacon. */
    private void observe(MessagePage p, Action a) {
        try {
            MessagePageObserver.PageActivity e = new MessagePageObserver.PageActivity(p.getWorkspaceId(),
                    p.getContactId(), p.getPhoneNumberId(), p.getMessageId(), a.kind().name(), a.id(), a.title(),
                    a.latitude(), a.longitude(), a.at());
            if (a.kind() == ActionKind.OPEN) observer.opened(e);
            else observer.acted(e);
        } catch (RuntimeException e) {
            log.warn("message page observer failed for page {}", p.getId(), e);
        }
    }

    /** Pushes the message (whose view carries the page's counts) to anyone watching the chat. */
    void changed(MessagePage p) {
        if (p.getMessageId() == null) return;
        try {
            WaWebMessage m = messages.findById(p.getMessageId()).orElse(null);
            WaWebChat chat = m == null ? null : chats.findById(m.getChatId()).orElse(null);
            if (m != null && chat != null) publisher.publishEvent(new InboxEvents.MessagePageChanged(chat, m));
        } catch (RuntimeException e) {
            log.warn("could not announce the change of message page {}", p.getId(), e);
        }
    }

    // ---- matching the customer's WhatsApp reply ----

    /**
     * Matches an inbound message to the newest page of its chat within the reply window: a text equal (bare) to a
     * button or row title, or a location after a location request. A tapped action without a reply is completed;
     * otherwise the action is created, since the customer may have read the titles off the preview card and typed
     * one. Returns the page when something matched.
     */
    public Optional<MessagePage> matchReply(WaWebChat chat, WaWebMessage inbound) {
        if (chat == null || chat.getId() == null || inbound == null || inbound.getTimestamp() == null) {
            return Optional.empty();
        }
        Instant since = clock.instant().minus(props.replyWindow());
        List<MessagePage> candidates = pages.findByChatIdAndCreatedAtAfterOrderByCreatedAtDesc(chat.getId(), since);
        boolean isLocation = "location".equals(inbound.getType()) && inbound.getLocation() != null;
        String bareBody = isLocation || inbound.getBody() == null ? null : CampaignMessageListener.bare(inbound.getBody());
        if (!isLocation && (bareBody == null || bareBody.isEmpty())) return Optional.empty();
        for (MessagePage p : candidates) {
            if (p.getCreatedAt() != null && p.getCreatedAt().isAfter(inbound.getTimestamp())) continue;
            Interactive i = cloud.fromMap(p.getMessage()).interactive();
            if (i == null) continue;
            if (isLocation) {
                if (!Interactive.LOCATION_REQUEST.equals(i.type())) continue;
                WaWebMessage.Location loc = inbound.getLocation();
                complete(p, ActionKind.LOCATION, null, null, loc.latitude(), loc.longitude(), inbound);
                return Optional.of(p);
            }
            ActionKind kind = Interactive.LIST.equals(i.type()) ? ActionKind.LIST_REPLY : ActionKind.BUTTON_REPLY;
            for (Interactive.Reply r : CloudMessageValidator.replies(i)) {
                if (r.title() != null && bareBody.equals(CampaignMessageListener.bare(r.title()))) {
                    complete(p, kind, r.id(), r.title(), null, null, inbound);
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }

    private void complete(MessagePage p, ActionKind kind, String id, String title, Double lat, Double lng,
            WaWebMessage inbound) {
        List<Action> actions = p.getActions();
        for (int n = 0; n < actions.size(); n++) {
            Action a = actions.get(n);
            boolean same = a.kind() == kind && (id == null ? a.id() == null : id.equals(a.id()));
            if (same && a.repliedMessageId() == null) {
                actions.set(n, a.withReplied(inbound.getId()));
                p.setLastActionAt(inbound.getTimestamp());
                MessagePage saved = pages.save(p);
                publisher.publishEvent(new MessagePageEvents.Activity(saved, actions.get(n), false));
                changed(saved);
                return;
            }
        }
        boolean first = actions.stream().noneMatch(a -> a.kind() == kind && (id == null ? a.id() == null : id.equals(a.id())));
        record(p, new Action(kind, id, title, lat, lng, inbound.getTimestamp(), null, inbound.getId()), first);
    }

    // ---- views ----

    public MessagePageView view(MessagePage p) {
        Interactive i = cloud.fromMap(p.getMessage()).interactive();
        List<MessagePageView.Count> buttons = new ArrayList<>();
        List<MessagePageView.Count> rows = new ArrayList<>();
        if (i != null) {
            boolean list = Interactive.LIST.equals(i.type());
            ActionKind kind = list ? ActionKind.LIST_REPLY : ActionKind.BUTTON_REPLY;
            for (Interactive.Reply r : CloudMessageValidator.replies(i)) {
                int n = 0;
                boolean replied = false;
                for (Action a : p.getActions()) {
                    if (a.kind() == kind && r.id() != null && r.id().equals(a.id())) {
                        n++;
                        replied |= a.repliedMessageId() != null;
                    }
                }
                (list ? rows : buttons).add(new MessagePageView.Count(r.id(), r.title(), n, replied));
            }
        }
        MessagePageView.Count cta = null;
        if (i != null && Interactive.CTA_URL.equals(i.type()) && i.action() != null && i.action().parameters() != null) {
            int n = (int) p.getActions().stream().filter(a -> a.kind() == ActionKind.CTA_URL).count();
            cta = new MessagePageView.Count("cta", i.action().parameters().displayText(), n, false);
        }
        MessagePageView.Location location = p.getActions().stream().filter(a -> a.kind() == ActionKind.LOCATION)
                .reduce((a, b) -> b).map(a -> new MessagePageView.Location(a.latitude(), a.longitude(), a.at()))
                .orElse(null);
        return new MessagePageView(link(p), p.getExpiresAt(), p.getOpenedAt(), p.getOpenCount(), buttons, rows, cta,
                location);
    }

    // ---- helpers ----

    static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String uaHash(String userAgent) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((userAgent == null ? "" : userAgent).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
