package me.nageshk.omnichannel.app.page;

import java.util.Optional;
import me.nageshk.omnichannel.db.page.MessagePage;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import me.nageshk.omnichannel.service.page.MessagePageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * The public side of a message page (spec §5.5): the page, its preview image and header media, the beacon the page's
 * script posts, and the call-to-action redirect. Nothing here needs a login, nothing here answers with the JSON
 * advice, and an unknown token looks the same as any other 404.
 */
@Controller
public class MessagePageController {

    private static final Logger log = LoggerFactory.getLogger(MessagePageController.class);
    private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);

    /** What the page's script posts: {@code k} is open, reply or location. */
    public record Beacon(String k, String id, Double lat, Double lng) {}

    private final MessagePageService pages;

    public MessagePageController(MessagePageService pages) {
        this.pages = pages;
    }

    @GetMapping("/t/{token}")
    public ResponseEntity<String> page(@PathVariable String token) {
        Optional<MessagePage> page = pages.byToken(token);
        if (page.isEmpty()) return html(HttpStatus.NOT_FOUND, pages.notFoundHtml());
        if (pages.expired(page.get())) return html(HttpStatus.GONE, pages.expiredHtml());
        return html(HttpStatus.OK, pages.html(page.get()));
    }

    @GetMapping("/t/{token}/og.png")
    public ResponseEntity<Resource> preview(@PathVariable String token) {
        Optional<Resource> png = pages.byToken(token).flatMap(pages::preview).map(r -> (Resource) r);
        if (png.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .body(png.get());
    }

    @GetMapping("/t/{token}/m")
    public ResponseEntity<Resource> media(@PathVariable String token) {
        Optional<MessagePage> page = pages.byToken(token).filter(p -> !pages.expired(p));
        Optional<CloudMediaService.MediaFile> file = page.flatMap(pages::headerMedia);
        if (file.isEmpty()) return ResponseEntity.notFound().build();
        CloudMediaService.MediaFile f = file.get();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, f.mimeType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(f.size())
                .body(f.resource());
    }

    @PostMapping(value = "/t/{token}/e", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> event(@PathVariable String token, @RequestBody(required = false) Beacon beacon,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        try {
            Optional<MessagePage> page = pages.byToken(token);
            if (page.isPresent() && beacon != null && beacon.k() != null) {
                switch (beacon.k()) {
                    case "open" -> pages.open(page.get(), userAgent);
                    case "reply" -> pages.reply(page.get(), beacon.id(), userAgent);
                    case "location" -> pages.location(page.get(), beacon.lat(), beacon.lng(), userAgent);
                    default -> { }
                }
            }
        } catch (RuntimeException e) {
            log.warn("could not record a page beacon", e);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/t/{token}/a")
    public ResponseEntity<Void> cta(@PathVariable String token,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        Optional<MessagePage> page = pages.byToken(token);
        if (page.isEmpty()) return ResponseEntity.notFound().build();
        if (pages.expired(page.get())) return ResponseEntity.status(HttpStatus.GONE).build();
        Optional<String> url = pages.cta(page.get(), userAgent);
        if (url.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url.get())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    /** Whatever goes wrong under /t, the answer is a plain 404 page, never the API's JSON. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> failed(Exception e) {
        log.warn("message page request failed", e);
        return html(HttpStatus.NOT_FOUND, pages.notFoundHtml());
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Robots-Tag", "noindex")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
