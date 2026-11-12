package me.nageshk.omnichannel.service.page;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.samskivert.mustache.Escapers;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive;
import me.nageshk.omnichannel.service.cloud.CloudMessageValidator;
import org.springframework.stereotype.Component;

/**
 * Renders a Cloud interactive message as a self-contained HTML page (spec §5.4): CSS and JS inline, every value
 * escaped, Open Graph tags for the link preview, and each action wired the agreed way (reply and row to wa.me, CTA to
 * the tracked redirect, location to the browser's geolocation with a wa.me fallback).
 */
@Component
public class MessagePageRenderer {

    /** The page and the OG texts it carries (unescaped). */
    public record Rendered(String html, String ogTitle, String ogDescription) {}

    static final int OG_TITLE = 60;
    static final int OG_DESCRIPTION = 200;
    private static final String EXPIRED_TEXT = "This message has expired";
    private static final String NOT_FOUND_TEXT = "This link is not valid";

    private final Template page;
    private final Template notice;
    private final String css;
    private final String js;

    public MessagePageRenderer() {
        Mustache.Compiler compiler = Mustache.compiler().withEscaper(Escapers.simple(new String[][] {
                {"&", "&amp;"}, {"<", "&lt;"}, {">", "&gt;"}, {"\"", "&quot;"}, {"'", "&#39;"}}))
                .nullValue("").defaultValue("");
        page = compiler.compile(resource("pages/page.mustache"));
        notice = compiler.compile(resource("pages/notice.mustache"));
        css = resource("pages/page.css");
        js = resource("pages/page.js");
    }

    public Rendered render(PageModel m) {
        Interactive i = m.message().interactive();
        String body = i.body() == null ? "" : i.body().text();
        String headerText = i.header() != null && "text".equals(i.header().type()) ? i.header().text() : null;
        String ogTitle = cut(headerText != null ? headerText : WhatsAppMarkup.plain(body), OG_TITLE);
        String ogDescription = cut(WhatsAppMarkup.plain(body), OG_DESCRIPTION);
        String base = m.baseUrl() + "/t/" + m.token();

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("css", css);
        ctx.put("js", js);
        ctx.put("ogTitle", ogTitle);
        ctx.put("ogDescription", ogDescription);
        ctx.put("ogImage", base + "/og.png");
        ctx.put("ogUrl", base);
        ctx.put("tracking", !m.preview());
        ctx.put("beacon", m.preview() ? "" : base + "/e");
        ctx.put("senderDigits", m.senderDigits() == null ? "" : m.senderDigits());
        ctx.put("bodyHtml", WhatsAppMarkup.toHtml(body));
        ctx.put("footer", i.footer() == null ? null : i.footer().text());
        String headerKind = i.header() == null || i.header().type() == null ? "none" : i.header().type();
        ctx.put("headerText", headerText);
        ctx.put("headerImage", "image".equals(headerKind));
        ctx.put("headerVideo", "video".equals(headerKind));
        ctx.put("headerDocument", "document".equals(headerKind));
        ctx.put("mediaUrl", m.headerDataUrl() != null ? m.headerDataUrl() : base + "/m");

        String type = i.type() == null ? "" : i.type();
        ctx.put("isButton", Interactive.BUTTON.equals(type));
        ctx.put("isList", Interactive.LIST.equals(type));
        ctx.put("isCta", Interactive.CTA_URL.equals(type));
        ctx.put("isLocation", Interactive.LOCATION_REQUEST.equals(type));

        List<Map<String, Object>> buttons = new ArrayList<>();
        if (Interactive.BUTTON.equals(type)) for (Interactive.Reply r : CloudMessageValidator.replies(i)) {
            buttons.add(Map.of("id", nz(r.id()), "title", nz(r.title()), "href", replyHref(m, r.title())));
        }
        ctx.put("buttons", buttons);

        List<Map<String, Object>> sections = new ArrayList<>();
        if (Interactive.LIST.equals(type) && i.action() != null && i.action().sections() != null) {
            for (Interactive.Section s : i.action().sections()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                if (s.rows() != null) {
                    for (Interactive.Row r : s.rows()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", nz(r.id()));
                        row.put("title", nz(r.title()));
                        row.put("description", r.description());
                        row.put("href", replyHref(m, r.title()));
                        rows.add(row);
                    }
                }
                sections.add(Map.of("title", nz(s.title()), "rows", rows));
            }
        }
        ctx.put("sections", sections);
        ctx.put("listButton", i.action() == null ? null : i.action().button());
        ctx.put("ctaText", i.action() != null && i.action().parameters() != null ? i.action().parameters().displayText() : null);
        ctx.put("ctaHref", m.preview() ? "#" : base + "/a");
        ctx.put("locationHref", m.preview() ? "#" : waMe(m.senderDigits(), "Here is my location:"));

        return new Rendered(page.execute(ctx), ogTitle, ogDescription);
    }

    public String expired() {
        return notice.execute(Map.of("css", css, "title", EXPIRED_TEXT,
                "text", "The sender's message is no longer available here."));
    }

    public String notFound() {
        return notice.execute(Map.of("css", css, "title", NOT_FOUND_TEXT,
                "text", "Check the link you were sent."));
    }

    private static String replyHref(PageModel m, String title) {
        return m.preview() ? "#" : waMe(m.senderDigits(), nz(title));
    }

    static String waMe(String digits, String text) {
        return "https://wa.me/" + (digits == null ? "" : digits) + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String resource(String path) {
        try (InputStream in = MessagePageRenderer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
