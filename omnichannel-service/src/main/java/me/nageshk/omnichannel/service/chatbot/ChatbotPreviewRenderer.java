package me.nageshk.omnichannel.service.chatbot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.LocationObject;
import me.nageshk.omnichannel.common.channel.whatsapp.message.MediaObject;
import me.nageshk.omnichannel.common.channel.whatsapp.message.MessagingProduct;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import me.nageshk.omnichannel.service.cloud.CloudMessageValidator;
import me.nageshk.omnichannel.service.page.MessagePageService;
import me.nageshk.omnichannel.service.page.WhatsAppMarkup;
import org.springframework.stereotype.Component;

/**
 * The editor's inert preview of a Send message node (spec §8.3): interactive messages through the message page
 * renderer, text, media and location as a plain WhatsApp-styled bubble with the page's own stylesheet.
 */
@Component
public class ChatbotPreviewRenderer {

    private static final String SENDER = "0000000000";

    private final MessagePageService pages;
    private final CloudMessageValidator validator;
    private final CloudMediaService media;
    private final String css;

    public ChatbotPreviewRenderer(MessagePageService pages, CloudMessageValidator validator, CloudMediaService media) {
        this.pages = pages;
        this.validator = validator;
        this.media = media;
        this.css = resource("pages/page.css");
    }

    /** Validates like a chat-keyed send (the node's message has no {@code to}) and renders. */
    public String html(String workspaceId, CloudMessage raw) {
        CloudMessage m = validator.validate(workspaceId, new CloudMessage(MessagingProduct.WHATSAPP_WEB, "individual", "preview",
                raw.type(), raw.text(), raw.interactive(), raw.image(), raw.video(), raw.audio(), raw.document(), raw.location(), null),
                media::mimeOf, false);
        if (CloudMessage.INTERACTIVE.equals(m.type())) return pages.previewHtml(workspaceId, m, SENDER);
        StringBuilder bubble = new StringBuilder();
        if (CloudMessage.TEXT.equals(m.type())) {
            bubble.append("<div class=\"body\">").append(WhatsAppMarkup.toHtml(m.text().body())).append("</div>");
        } else if (CloudMessage.LOCATION.equals(m.type())) {
            LocationObject l = m.location();
            bubble.append("<div class=\"doc\">📍 ").append(esc(l.name() == null ? "" : l.name()));
            if (l.address() != null) bubble.append("<br><small>").append(esc(l.address())).append("</small>");
            bubble.append("<br><small>").append(l.latitude()).append(", ").append(l.longitude()).append("</small></div>");
        } else {
            MediaObject o = m.media();
            String type = m.type();
            if (CloudMessage.IMAGE.equals(type)) {
                bubble.append("<img class=\"media\" alt=\"\" src=\"").append(dataUrl(workspaceId, o.id())).append("\">");
            } else {
                String label = CloudMessage.VIDEO.equals(type) ? "🎬 Video" : CloudMessage.AUDIO.equals(type) ? "🎧 Audio"
                        : "📄 " + esc(o.filename() == null ? "Document" : o.filename());
                bubble.append("<div class=\"doc\">").append(label).append("</div>");
            }
            if (o.caption() != null && !o.caption().isBlank()) {
                bubble.append("<div class=\"body\">").append(WhatsAppMarkup.toHtml(o.caption())).append("</div>");
            }
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\"><title>Preview</title><style>" + css + "</style></head><body>"
                + "<header class=\"bar\"><span class=\"avatar\"></span><span>+" + SENDER + "</span></header>"
                + "<main class=\"canvas\"><article class=\"bubble\">" + bubble + "</article></main></body></html>";
    }

    private String dataUrl(String workspaceId, String mediaId) {
        try {
            CloudMediaService.MediaFile f = media.open(workspaceId, mediaId);
            try (InputStream in = f.resource().getInputStream()) {
                return "data:" + f.mimeType() + ";base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String resource(String path) {
        try (InputStream in = ChatbotPreviewRenderer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
