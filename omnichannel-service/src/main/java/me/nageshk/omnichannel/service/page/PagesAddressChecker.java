package me.nageshk.omnichannel.service.page;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.nageshk.omnichannel.db.page.PagesAddress;
import me.nageshk.omnichannel.db.page.PagesAddressRepository;
import me.nageshk.omnichannel.db.page.PagesAddressStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Proves a link address reaches this server (spec 2026-11-18 link addresses §5.4): a fresh nonce is stored, fetched
 * through the address, and must come back as {@code omnichannel-pages <nonce>}. Results land only on the address
 * the nonce was written for, and never touch the document's version.
 */
@Service
public class PagesAddressChecker {

    private static final Logger log = LoggerFactory.getLogger(PagesAddressChecker.class);
    static final String ANSWER_PREFIX = "omnichannel-pages ";
    private static final int FAILURES_BEFORE_FAILING = 3;
    private static final Duration PLATFORM_NONCE_LIFE = Duration.ofMinutes(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private enum Mode { ONCE, DAILY }

    private final PagesAddressRepository addresses;
    private final MongoTemplate mongo;
    private final PagesProbe probe;
    private final Clock clock;
    private final Map<String, Instant> platformNonces = new ConcurrentHashMap<>();

    public PagesAddressChecker(PagesAddressRepository addresses, MongoTemplate mongo, PagesProbe probe, Clock clock) {
        this.addresses = addresses;
        this.mongo = mongo;
        this.probe = probe;
        this.clock = clock;
    }

    /** One check whose result decides: WORKING, or FAILING with the reason. Returns the address as stored after it. */
    public PagesAddress check(String workspaceId) {
        return run(workspaceId, Mode.ONCE);
    }

    /** The daily pass: three failures in a row (or one on an unchecked address) make an address FAILING. */
    public int recheckAll() {
        int n = 0;
        for (PagesAddress a : addresses.findByBaseUrlNotNull()) {
            try {
                run(a.getWorkspaceId(), Mode.DAILY);
                n++;
            } catch (RuntimeException e) {
                log.warn("could not check the link address of workspace {}", a.getWorkspaceId(), e);
            }
        }
        return n;
    }

    /** Checks the platform's address once; the failure text, or null when it reaches us. */
    public String checkPlatform(String baseUrl) {
        String nonce = nonce();
        Instant now = clock.instant();
        platformNonces.entrySet().removeIf(e -> e.getValue().isBefore(now));
        platformNonces.put(nonce, now.plus(PLATFORM_NONCE_LIFE));
        try {
            return failureText(probe.get(url(baseUrl, nonce)), nonce);
        } finally {
            platformNonces.remove(nonce);
        }
    }

    /** Whether the check endpoint should answer this nonce. */
    public boolean knows(String nonce) {
        if (nonce == null || nonce.isBlank()) return false;
        Instant until = platformNonces.get(nonce);
        if (until != null && until.isAfter(clock.instant())) return true;
        return mongo.exists(new Query(Criteria.where("checkNonce").is(nonce)), PagesAddress.class);
    }

    private PagesAddress run(String workspaceId, Mode mode) {
        PagesAddress a = addresses.findByWorkspaceId(workspaceId).orElse(null);
        if (a == null || a.getBaseUrl() == null) return a;
        String nonce = nonce();
        long marked = mongo.updateFirst(new Query(Criteria.where("workspaceId").is(workspaceId).and("baseUrl").is(a.getBaseUrl())),
                new Update().set("checkNonce", nonce), PagesAddress.class).getMatchedCount();
        if (marked == 0) return addresses.findByWorkspaceId(workspaceId).orElse(a);

        String failure = failureText(probe.get(url(a.getBaseUrl(), nonce)), nonce);
        Update result = new Update().set("checkedAt", clock.instant());
        if (failure == null) {
            result.set("status", PagesAddressStatus.WORKING).set("failures", 0).unset("lastError");
        } else {
            int failures = a.getFailures() + 1;
            boolean failing = mode == Mode.ONCE || failures >= FAILURES_BEFORE_FAILING
                    || a.getStatus() != PagesAddressStatus.WORKING;
            result.set("failures", failures).set("lastError", failure);
            if (failing) result.set("status", PagesAddressStatus.FAILING);
        }
        // only the address this nonce was written for: a newer save or check wins
        mongo.updateFirst(new Query(Criteria.where("workspaceId").is(workspaceId).and("checkNonce").is(nonce)), result,
                PagesAddress.class);
        return addresses.findByWorkspaceId(workspaceId).orElse(a);
    }

    private static String failureText(PagesProbe.Result r, String nonce) {
        if (r.failure() != null) {
            return switch (r.failure()) {
                case PagesProbe.BAD_CERTIFICATE -> "The certificate is not valid";
                case PagesProbe.NO_SUCH_NAME -> "The name does not exist";
                case PagesProbe.REFUSED_ADDRESS -> "The address points to a private network";
                default -> "The address did not answer";
            };
        }
        if (r.status() == 200 && (ANSWER_PREFIX + nonce).equals(r.body() == null ? null : r.body().strip())) return null;
        return "The address answered, but not from this server";
    }

    private static String url(String baseUrl, String nonce) {
        return baseUrl + "/t/-/check/" + nonce;
    }

    private static String nonce() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
