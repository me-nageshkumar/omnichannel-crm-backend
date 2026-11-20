package me.nageshk.omnichannel.meta.webhook;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import me.nageshk.omnichannel.meta.account.CloudAccount;
import me.nageshk.omnichannel.meta.account.CloudAccountService;
import me.nageshk.omnichannel.meta.api.CloudChange;
import me.nageshk.omnichannel.meta.api.CloudWebhookSink;
import me.nageshk.omnichannel.meta.config.MetaProperties;
import me.nageshk.omnichannel.meta.connection.MetaConnection;
import me.nageshk.omnichannel.meta.connection.MetaConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Meta's webhook for one connection (spec 2026-11-20 §5.3): the verify handshake, then signed deliveries. The
 * signature is checked over the raw bytes before anything is parsed; each change must name a number of this
 * connection; message changes are stored before Meta gets its answer and processed after.
 */
@RestController
class MetaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaWebhookController.class);
    static final String SIGNATURE = "X-Hub-Signature-256";
    static final String MESSAGES = "messages";

    private final MetaConnectionService connections;
    private final CloudAccountService accounts;
    private final me.nageshk.omnichannel.meta.template.CloudTemplateService templates;
    static final String TEMPLATE_STATUS = "message_template_status_update";
    private final CloudWebhookSink sink;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final Clock clock;
    private final long maxBytes;
    private final Map<String, Instant> ignoredLogged = new ConcurrentHashMap<>();

    MetaWebhookController(MetaConnectionService connections, CloudAccountService accounts,
            me.nageshk.omnichannel.meta.template.CloudTemplateService templates, CloudWebhookSink sink,
            ObjectMapper mapper, @Qualifier("metaWebhookExecutor") Executor executor, Clock clock,
            MetaProperties props) {
        this.connections = connections;
        this.accounts = accounts;
        this.templates = templates;
        this.sink = sink;
        this.mapper = mapper;
        this.executor = executor;
        this.clock = clock;
        this.maxBytes = props.webhookMaxBytes().toBytes();
    }

    @GetMapping(value = "/hooks/meta/{key}", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> verify(@PathVariable String key,
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        Optional<MetaConnection> c = connections.findByWebhookKey(key);
        if (c.isEmpty()) return ResponseEntity.notFound().build();
        if (!"subscribe".equals(mode) || token == null || !token.equals(c.get().getVerifyToken()) || challenge == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
    }

    @PostMapping("/hooks/meta/{key}")
    ResponseEntity<Void> deliver(@PathVariable String key, HttpServletRequest request) throws IOException {
        Optional<MetaConnection> found = connections.findByWebhookKey(key);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        MetaConnection c = found.get();
        byte[] body = read(request);
        if (body == null) return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        String secret;
        try {
            secret = connections.open(c).appSecret();
        } catch (RuntimeException e) {
            log.warn("Meta webhook for app {}: the stored app secret cannot be opened", c.getAppId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!WebhookSignature.valid(body, request.getHeader(SIGNATURE), secret)) {
            log.info("Meta webhook for app {} refused: bad or missing signature ({} bytes)", c.getAppId(), body.length);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
        Instant now = clock.instant();
        connections.noteWebhook(c.getId(), now);

        List<CloudChange> messages = new ArrayList<>();
        List<Runnable> numberChanges = new ArrayList<>();
        for (JsonNode entry : payload.path("entry")) {
            String wabaId = entry.path("id").asString(null);
            for (JsonNode change : entry.path("changes")) {
                String field = change.path("field").asString("");
                JsonNode value = change.path("value");
                if (MESSAGES.equals(field)) {
                    route(c, wabaId, value, now).ifPresent(messages::add);
                } else if (TEMPLATE_STATUS.equals(field)) {
                    numberChanges.add(() -> templates.applyChange(c, value));
                } else if (CloudAccountService.NUMBER_FIELDS_OF_CHANGES.contains(field)) {
                    numberChanges.add(() -> accounts.applyChange(c, field, value));
                } else {
                    log.debug("Meta webhook field {} of app {} is not used", field, c.getAppId());
                }
            }
        }
        CloudWebhookSink.Delivery delivery = messages.isEmpty() ? new CloudWebhookSink.Delivery(false, () -> {})
                : sink.accept(messages);
        try {
            executor.execute(() -> {
                run(delivery.afterReply());
                numberChanges.forEach(MetaWebhookController::run);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // stored: the replay sweep processes the messages; number and template changes come again with the next
            log.warn("Meta webhook for app {}: the processing queue is full or stopping; left to the replay", c.getAppId());
        }
        return delivery.failed() ? ResponseEntity.internalServerError().build() : ResponseEntity.ok().build();
    }

    /** The change's number must be one of this connection's; any other is counted and dropped. */
    private Optional<CloudChange> route(MetaConnection c, String wabaId, JsonNode value, Instant now) {
        String metaId = value.path("metadata").path("phone_number_id").asString("");
        Optional<CloudAccount> account = accounts.account(metaId).filter(a -> c.getId().equals(a.getConnectionId()));
        if (account.isEmpty()) {
            connections.noteIgnored(c.getId(), metaId);
            Instant last = ignoredLogged.get(c.getId() + ":" + metaId);
            if (last == null || last.isBefore(now.minusSeconds(3600))) {
                ignoredLogged.put(c.getId() + ":" + metaId, now);
                log.info("Meta webhook for app {} names number {}, which is not one of its numbers here; dropped",
                        c.getAppId(), metaId);
            }
            return Optional.empty();
        }
        CloudAccount a = account.get();
        String digits = a.getDisplayPhoneNumber() == null ? null : a.getDisplayPhoneNumber().replaceAll("\\D", "");
        return Optional.of(new CloudChange(c.getId(), a.getCustomerId(), a.getWorkspaceId(), a.getPhoneNumberId(),
                digits, wabaId != null ? wabaId : a.getWabaId(), MESSAGES, value, now));
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("processing a Meta webhook change failed: {}", e.toString());
        }
    }

    /** The body, or null when it is larger than allowed. */
    private byte[] read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBytes) return null;
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            return bytes.length > maxBytes ? null : bytes;
        }
    }
}
