package me.nageshk.omnichannel.meta.connection;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.common.api.DuplicateException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.secret.SecretBox;
import me.nageshk.omnichannel.meta.api.ConnectionNumber;
import me.nageshk.omnichannel.meta.api.ConnectionNumbers;
import me.nageshk.omnichannel.meta.api.ConnectionStatus;
import me.nageshk.omnichannel.meta.api.PublicOrigin;
import me.nageshk.omnichannel.meta.connection.ConnectionView.Account;
import me.nageshk.omnichannel.meta.connection.ConnectionView.Check;
import me.nageshk.omnichannel.meta.connection.ConnectionView.MetaNumber;
import me.nageshk.omnichannel.meta.connection.ConnectionView.Save;
import me.nageshk.omnichannel.meta.graph.GraphClient;
import me.nageshk.omnichannel.meta.graph.GraphException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** A brand's Meta connections (spec 2026-11-20 §5.2, §5.7): sealed secrets, the token check, the webhook address. */
@Service
public class MetaConnectionService {

    private static final Logger log = LoggerFactory.getLogger(MetaConnectionService.class);
    static final String WEBHOOK_PATH = "/hooks/meta/";
    static final List<String> SCOPES = List.of("whatsapp_business_management", "whatsapp_business_messaging");
    private static final int MAX_NAME = 80;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Meta's current phone-number fields (v27; {@code platform_type} and {@code messaging_limit_tier} are gone). */
    public static final String NUMBER_FIELDS = "id,display_phone_number,verified_name,name_status,quality_rating,"
            + "code_verification_status,status,host_platform,account_mode";

    /** The opened secrets of a connection, for Graph calls inside this module; never leaves the server. */
    public record Secrets(String appSecret, String accessToken) {
        public String appToken(String appId) {
            return appId + "|" + appSecret;
        }
    }

    private final MetaConnectionRepository repository;
    private final SecretBox secrets;
    private final GraphClient graph;
    private final PublicOrigin origin;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final Optional<ConnectionNumbers> numbersOf;
    private final me.nageshk.omnichannel.meta.account.CloudAccountRepository accounts;
    private final WebhookSetupService webhooks;
    private final org.springframework.context.ApplicationEventPublisher events;

    /** Meta accepted a connection's token just now (a save or a replaced secret): its templates are imported. */
    public record ConnectionChecked(String connectionId) {}

    public MetaConnectionService(MetaConnectionRepository repository, SecretBox secrets, GraphClient graph,
            PublicOrigin origin, MongoTemplate mongo, Clock clock, Optional<ConnectionNumbers> numbersOf,
            WebhookSetupService webhooks, me.nageshk.omnichannel.meta.account.CloudAccountRepository accounts,
            org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.accounts = accounts;
        this.webhooks = webhooks;
        this.repository = repository;
        this.secrets = secrets;
        this.graph = graph;
        this.origin = origin;
        this.mongo = mongo;
        this.clock = clock;
        this.numbersOf = numbersOf;
    }

    public List<ConnectionView> list(String workspaceId) {
        return repository.findByWorkspaceIdOrderByName(workspaceId).stream().map(this::view).toList();
    }

    public ConnectionView get(String workspaceId, String id) {
        return view(find(workspaceId, id));
    }

    public ConnectionView create(String workspaceId, String customerId, String userId, Save in) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = name(in, errors);
        String appId = in == null || in.appId() == null ? "" : in.appId().strip();
        if (!appId.matches("\\d{1,25}")) errors.put("appId", "the app ID from the Meta app dashboard (digits)");
        if (in == null || blank(in.appSecret())) errors.put("appSecret", "is required");
        if (in == null || blank(in.accessToken())) errors.put("accessToken", "is required");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        if (repository.findByAppId(appId).isPresent()) throw appTaken();

        MetaConnection c = new MetaConnection();
        c.setWorkspaceId(workspaceId);
        c.setCustomerId(customerId);
        c.setName(name);
        c.setAppId(appId);
        c.setWebhookKey(random(24));
        c.setVerifyToken(random(18));
        c.setCreatedAt(clock.instant());
        c.setUpdatedBy(userId);
        String secret = in.appSecret().strip();
        String token = in.accessToken().strip();
        setSecret(c, secret);
        setToken(c, token);
        Secrets opened = new Secrets(secret, token);
        runCheck(c, opened);
        setUpWebhook(c, opened);
        return view(announced(persist(c)));
    }

    public ConnectionView replace(String workspaceId, String id, String userId, Save in) {
        MetaConnection c = find(workspaceId, id);
        Map<String, String> errors = new LinkedHashMap<>();
        String name = name(in, errors);
        if (in.appId() != null && !in.appId().isBlank() && !in.appId().strip().equals(c.getAppId())) {
            errors.put("appId", "cannot change; add a new connection for another app");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        c.setName(name);
        c.setUpdatedBy(userId);
        boolean changed = false;
        if (!blank(in.appSecret())) {
            setSecret(c, in.appSecret().strip());
            changed = true;
        }
        if (!blank(in.accessToken())) {
            setToken(c, in.accessToken().strip());
            changed = true;
        }
        if (changed) {
            Secrets opened = open(c);
            runCheck(c, opened);
            setUpWebhook(c, opened);
            return view(announced(persist(c)));
        }
        return view(persist(c));
    }

    /** Checks the token again and lists the WABAs and numbers it may use. */
    public Check check(String workspaceId, String id, String userId) {
        return check(workspaceId, id, userId, null);
    }

    /**
     * As {@link #check(String, String, String)}; a typed {@code wabaId} is looked up too, since Meta leaves a token's
     * WABA ids out when the permission covers all of the business's WABAs. A WABA the token can read is remembered.
     */
    public Check check(String workspaceId, String id, String userId, String wabaId) {
        MetaConnection c = find(workspaceId, id);
        c.setUpdatedBy(userId);
        Secrets s = open(c);
        runCheck(c, s);
        String typed = wabaId == null ? "" : wabaId.strip();
        if (c.getStatus() == ConnectionStatus.OK && typed.matches("\\d{1,25}") && !c.getWabaIds().contains(typed)) {
            try {
                graph.get(s.accessToken(), typed, Map.of("fields", "id,name"));
                List<String> ids = new ArrayList<>(c.getWabaIds());
                ids.add(typed);
                c.setWabaIds(ids);
            } catch (GraphException e) {
                throw new ValidationException("wabaId", "Meta does not let this token read WABA " + typed + ": "
                        + e.getMessage());
            }
        }
        c = persist(c);
        List<Account> accounts = new ArrayList<>();
        if (c.getStatus() == ConnectionStatus.OK) {
            for (String waba : c.getWabaIds()) accounts.add(account(s.accessToken(), waba));
        }
        return new Check(view(c), accounts);
    }

    /** Tries the webhook setup again (after a failure, a new tunnel, or the app's callback changing). */
    public ConnectionView retryWebhook(String workspaceId, String id, String userId) {
        MetaConnection c = find(workspaceId, id);
        c.setUpdatedBy(userId);
        Secrets opened = open(c);
        if (c.getStatus() != ConnectionStatus.OK) runCheck(c, opened);
        setUpWebhook(c, opened);
        return view(persist(c));
    }

    public void delete(String workspaceId, String id) {
        MetaConnection c = find(workspaceId, id);
        List<ConnectionNumber> using = numbers(c.getId());
        if (!using.isEmpty()) {
            List<String> names = using.stream().map(n -> n.label() != null ? n.label() : n.number()).sorted().toList();
            throw new me.nageshk.omnichannel.common.api.CodedException(org.springframework.http.HttpStatus.CONFLICT,
                    "meta_connection_in_use", "numbers", "Used by: " + String.join(", ", names));
        }
        repository.delete(c);
    }

    // ---- for the rest of the module ----

    public MetaConnection require(String workspaceId, String id) {
        return find(workspaceId, id);
    }

    public Optional<MetaConnection> byId(String id) {
        return repository.findById(id);
    }

    public Optional<MetaConnection> findByWebhookKey(String key) {
        return key == null || key.isBlank() ? Optional.empty() : repository.findByWebhookKey(key);
    }

    /** The opened secrets; throws if the stored ones cannot be opened (a lost sealing key). */
    public Secrets open(MetaConnection c) {
        return new Secrets(secrets.open(c.getAppSecret()), secrets.open(c.getAccessToken()));
    }

    /** The webhook address Meta should call for this connection ({@code /hooks/meta/<key>} on the platform origin). */
    public String webhookUrl(MetaConnection c) {
        String o = origin.platformOrigin();
        String base = o == null ? "" : o.replaceAll("/+$", "");
        return base + WEBHOOK_PATH + c.getWebhookKey();
    }

    /** Marks the token refused by Meta (a 401 or code 190 on any call), so every number of it stops at once. */
    public void markInvalid(String connectionId, String error) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(connectionId)),
                new Update().set("status", ConnectionStatus.INVALID).set("lastError", error)
                        .set("checkedAt", clock.instant()), MetaConnection.class);
    }

    public void noteWebhook(String connectionId, Instant at) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(connectionId)), new Update().set("lastWebhookAt", at),
                MetaConnection.class);
    }

    public void noteIgnored(String connectionId, String metaPhoneNumberId) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(connectionId)),
                new Update().inc("ignoredChanges", 1).set("lastIgnoredNumber", metaPhoneNumberId),
                MetaConnection.class);
    }

    public MetaConnection save(MetaConnection c) {
        return persist(c);
    }

    public ConnectionView view(MetaConnection c) {
        return new ConnectionView(c.getId(), c.getName(), c.getAppId(), c.getAppSecretHint(), c.getAppSecretSetAt(),
                c.getTokenHint(), c.getTokenSetAt(), c.getStatus(), c.getCheckedAt(), c.getLastError(),
                new ConnectionView.Webhook(webhookUrl(c), c.getVerifyToken(), c.getWebhookSetup(), c.getWebhookError(),
                        c.getLastWebhookAt()),
                numbers(c.getId()), c.getIgnoredChanges(), c.getLastIgnoredNumber(), c.getVersion());
    }

    // ---- internals ----

    /**
     * Asks Meta about the token with the app token ({@code appId|appSecret}): valid, issued for this app, with both
     * WhatsApp scopes. The WABA ids come from the scopes' {@code target_ids}. A transport failure leaves the status
     * as it was and records the error.
     */
    void runCheck(MetaConnection c, Secrets s) {
        c.setCheckedAt(clock.instant());
        try {
            JsonNode data = graph.get(s.appToken(c.getAppId()), "debug_token", Map.of("input_token", s.accessToken()))
                    .path("data");
            String problem = problem(c, data);
            if (problem != null) {
                c.setStatus(ConnectionStatus.INVALID);
                c.setLastError(problem);
                return;
            }
            c.setWabaIds(wabas(data));
            c.setStatus(ConnectionStatus.OK);
            c.setLastError(null);
        } catch (GraphException e) {
            if (e.kind() == GraphException.Kind.UNAVAILABLE || e.kind() == GraphException.Kind.TIMEOUT) {
                c.setLastError(e.getMessage());
            } else {
                c.setStatus(ConnectionStatus.INVALID);
                c.setLastError(e.getMessage());
            }
        }
    }

    private static String problem(MetaConnection c, JsonNode data) {
        if (!data.path("is_valid").asBoolean(false)) {
            String why = data.path("error").path("message").asString("");
            return why.isBlank() ? "Meta says the access token is not valid" : "Meta says the access token is not valid: " + why;
        }
        if (!c.getAppId().equals(data.path("app_id").asString(""))) {
            return "The access token belongs to another Meta app; generate it for app " + c.getAppId();
        }
        Set<String> scopes = new LinkedHashSet<>();
        data.path("scopes").forEach(n -> scopes.add(n.asString()));
        List<String> missing = SCOPES.stream().filter(sc -> !scopes.contains(sc)).toList();
        if (!missing.isEmpty()) return "The access token lacks the permissions " + String.join(", ", missing);
        return null;
    }

    private static List<String> wabas(JsonNode data) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode g : data.path("granular_scopes")) {
            if (!SCOPES.contains(g.path("scope").asString(""))) continue;
            g.path("target_ids").forEach(t -> ids.add(t.asString()));
        }
        return new ArrayList<>(ids);
    }

    private Account account(String token, String wabaId) {
        String name = null;
        List<MetaNumber> numbers = new ArrayList<>();
        try {
            name = graph.get(token, wabaId, Map.of("fields", "id,name")).path("name").asString(null);
            JsonNode list = graph.get(token, wabaId + "/phone_numbers", Map.of("fields", NUMBER_FIELDS));
            for (JsonNode n : list.path("data")) {
                String metaId = n.path("id").asString();
                numbers.add(new MetaNumber(metaId, n.path("display_phone_number").asString(null),
                        n.path("verified_name").asString(null), n.path("name_status").asString(null),
                        n.path("quality_rating").asString(null), platformOf(n),
                        accounts.findByMetaPhoneNumberId(metaId).map(a -> a.getPhoneNumberId()).orElse(null)));
            }
        } catch (GraphException e) {
            log.info("could not list the numbers of WABA {}: {}", wabaId, e.getMessage());
        }
        return new Account(wabaId, name, numbers);
    }

    /** Only a token Meta accepted can subscribe WABAs; otherwise the admin is told to fix the token first. */
    private void setUpWebhook(MetaConnection c, Secrets s) {
        if (c.getStatus() != ConnectionStatus.OK) {
            c.setWebhookSetup(me.nageshk.omnichannel.meta.api.WebhookSetup.MANUAL);
            c.setWebhookError("Fix the access token first; the webhook is set up once Meta accepts it");
            return;
        }
        webhooks.setUp(c, s, webhookUrl(c));
    }

    /** Meta's hosting platform of a number ({@code host_platform}, the legacy {@code platform_type} as fallback). */
    public static String platformOf(JsonNode n) {
        String host = n.path("host_platform").asString(null);
        return host != null ? host : n.path("platform_type").asString(null);
    }

    /** Whether the number can send: registered on the Cloud API (status CONNECTED, or hosted on CLOUD_API). */
    public static boolean registeredOf(JsonNode n) {
        return "CONNECTED".equals(n.path("status").asString("")) || "CLOUD_API".equals(platformOf(n));
    }

    private List<ConnectionNumber> numbers(String connectionId) {
        return connectionId == null ? List.of() : numbersOf.map(f -> f.numbers(connectionId)).orElse(List.of());
    }

    private void setSecret(MetaConnection c, String plain) {
        c.setAppSecret(secrets.seal(plain));
        c.setAppSecretHint(SecretBox.hint(plain));
        c.setAppSecretSetAt(clock.instant());
    }

    private void setToken(MetaConnection c, String plain) {
        c.setAccessToken(secrets.seal(plain));
        c.setTokenHint(SecretBox.hint(plain));
        c.setTokenSetAt(clock.instant());
    }

    private MetaConnection announced(MetaConnection c) {
        if (c.getStatus() == ConnectionStatus.OK) events.publishEvent(new ConnectionChecked(c.getId()));
        return c;
    }

    private MetaConnection persist(MetaConnection c) {
        try {
            return repository.save(c);
        } catch (DuplicateKeyException e) {
            throw appTaken();
        }
    }

    private MetaConnection find(String workspaceId, String id) {
        return repository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("Meta connection not found"));
    }

    private static String name(Save in, Map<String, String> errors) {
        String name = in == null || in.name() == null ? "" : in.name().strip();
        if (name.isEmpty()) errors.put("name", "is required");
        else if (name.length() > MAX_NAME) errors.put("name", "at most " + MAX_NAME + " characters");
        return name;
    }

    private static DuplicateException appTaken() {
        return new DuplicateException("appId", "This Meta app is connected to another brand");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
