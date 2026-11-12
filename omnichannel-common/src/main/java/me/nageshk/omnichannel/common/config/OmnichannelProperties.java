package me.nageshk.omnichannel.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "omnichannel")
public record OmnichannelProperties(
        Jwt jwt, SuperAdmin superAdmin, TwoFactor twoFactor, Ws ws, Rpc rpc, History history,
        Automation automation, @DefaultValue Contacts contacts,
        @DefaultValue Webhooks webhooks, @DefaultValue Pages pages, @DefaultValue Chatbot chatbot) {

    public record Jwt(String secret, @DefaultValue("12h") Duration ttl) {}

    /**
     * Seeded once, with two-factor off, when no SUPER_ADMIN exists. {@code password} is a bare
     * bcrypt hash.
     */
    public record SuperAdmin(
            @DefaultValue("Nagesh Kumar") String name,
            @DefaultValue("nageshk") String username,
            String password,
            String email,
            String mobileNumber) {}

    public record TwoFactor(@DefaultValue("Omnichannel CRM") String issuer) {}

    public record Ws(@DefaultValue("chrome-extension://*") String allowedOrigins) {}

    public record Rpc(
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("5s") Duration authTimeout) {}

    /**
     * Chat history import: the runner's tick and how hard it pulls from the phone. {@code receipts}: after a page, our
     * messages the fetch found delivered or later get their receipt times from the page, {@code receiptsBatch} ids per
     * call (the page takes at most 100, and reads each id in turn, so a call must end well inside the RPC timeout).
     */
    public record History(
            @DefaultValue("true") boolean autoTick,
            @DefaultValue("5s") Duration tick,
            @DefaultValue("100") int pageSize,
            @DefaultValue("20") int maxLoads,
            @DefaultValue("400ms") Duration pageDelay,
            @DefaultValue("1s") Duration chatDelay,
            @DefaultValue("5000") int maxMessagesPerChat,
            @DefaultValue("3") int maxEmptyPages,
            @DefaultValue("25") int maxIdlePages,
            @DefaultValue("60s") Duration timeBudget,
            @DefaultValue("3") int maxTimeoutsPerChat,
            @DefaultValue("7d") Duration waitingWindow,
            @DefaultValue("true") boolean receipts,
            @DefaultValue("25") int receiptsBatch) {}

    /** Automations: the worker's tick and lease, retries, and how long a live test may sit idle. */
    public record Automation(
            @DefaultValue("true") boolean autoTick,
            @DefaultValue("2s") Duration tick,
            @DefaultValue("5m") Duration lease,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("20") int batch,
            @DefaultValue("30m") Duration testIdle,
            @DefaultValue("60s") Duration sendTimeout,
            @DefaultValue("2m") Duration retryDelay) {}

    /**
     * Resolving a contact's other WhatsApp id through its number's device: at most one attempt per contact per
     * {@code resolveRetry}, at most one device call per {@code resolvePace} per device, and at most
     * {@code resolveBatch} contacts queued by one "Resolve hidden numbers" request.
     */
    /** Chatbot runs (spec §5): the pool, leases, caps and the HTTP node's limits. */
    public record Chatbot(
            @DefaultValue("true") boolean autoTick,
            @DefaultValue("2s") Duration tick,
            @DefaultValue("2m") Duration lease,
            @DefaultValue("4") int poolSize,
            @DefaultValue("200") int maxSteps,
            @DefaultValue("5") int maxJumps,
            @DefaultValue("10s") Duration httpTimeout,
            @DefaultValue("1048576") int httpMaxBody,
            @DefaultValue("60s") Duration sendTimeout,
            @DefaultValue("3") int sendAttempts,
            @DefaultValue("30s") Duration sendRetryDelay,
            @DefaultValue("50") int versionsKept,
            @DefaultValue("false") boolean allowPrivateHttp) {}

    public record Contacts(
            @DefaultValue("1h") Duration resolveRetry,
            @DefaultValue("300ms") Duration resolvePace,
            @DefaultValue("500") int resolveBatch) {}

    /** Converted webhook events: how long they are kept, and whether (and up to what size) the raw model is kept. */
    public record Webhooks(
            @DefaultValue("30d") Duration retention,
            @DefaultValue("true") boolean keepRaw,
            @DefaultValue("65536") int rawMaxBytes) {}

    /**
     * Message pages (Cloud interactive messages sent over the bridge as a link): the public origin the links and the
     * preview image are served from, how long a page is served, and how long a typed reply may follow its page.
     */
    public record Pages(
            @DefaultValue("http://localhost:8080") String baseUrl,
            @DefaultValue("90") int ttlDays,
            @DefaultValue("48h") Duration replyWindow) {}
}
