package me.nageshk.omnichannel.app.chatbot;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.db.chatbot.ChatbotForm;
import me.nageshk.omnichannel.service.chatbot.form.FormPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** The public side of a form page (spec §8.4): the form, its submission, and our own HTML for closed or unknown. */
@Controller
public class FormPageController {

    private static final Logger log = LoggerFactory.getLogger(FormPageController.class);
    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);

    private final FormPageService forms;

    public FormPageController(FormPageService forms) {
        this.forms = forms;
    }

    @GetMapping("/f/{token}")
    public ResponseEntity<String> page(@PathVariable String token) {
        Optional<ChatbotForm> form = forms.byToken(token);
        if (form.isEmpty()) return html(HttpStatus.NOT_FOUND, forms.notFoundHtml());
        if (!forms.open(form.get())) return html(HttpStatus.GONE, forms.closedHtml());
        return html(HttpStatus.OK, forms.html(form.get()));
    }

    /** The link card's image; served for any known form, open or closed, so an old chat keeps its card. */
    @GetMapping("/f/{token}/og.png")
    public ResponseEntity<byte[]> preview(@PathVariable String token) {
        Optional<ChatbotForm> form = forms.byToken(token);
        if (form.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .body(forms.previewImage(form.get()));
    }

    @PostMapping(value = "/f/{token}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> submit(@PathVariable String token, @RequestBody MultiValueMap<String, String> body) {
        Optional<ChatbotForm> form = forms.byToken(token);
        if (form.isEmpty()) return html(HttpStatus.NOT_FOUND, forms.notFoundHtml());
        Map<String, String> values = new LinkedHashMap<>();
        body.forEach((k, v) -> values.put(k, v == null || v.isEmpty() ? "" : v.get(0)));
        FormPageService.SubmitResult r = forms.submit(form.get(), values);
        return switch (r.state()) {
            case ANSWERED -> html(HttpStatus.OK, r.html());
            case INVALID -> html(HttpStatus.BAD_REQUEST, r.html());
            case CLOSED -> html(HttpStatus.GONE, r.html());
        };
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> failed(RuntimeException e) {
        log.warn("form page request failed", e);
        return html(HttpStatus.NOT_FOUND, forms.notFoundHtml());
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Robots-Tag", "noindex")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }
}
