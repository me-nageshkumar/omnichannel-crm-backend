package me.nageshk.omnichannel.security.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory brute-force counters, per lowercase username. The first failure arms a block window;
 * once the count reaches the limit the user is blocked until that window ends. A restart clears
 * everything, which is acceptable for a single instance.
 */
@Service
public class LoginAttemptService {

    public static final int MAX_LOGIN_ATTEMPTS = 5;
    public static final Duration LOGIN_BLOCK = Duration.ofMinutes(30);
    public static final int MAX_OTP_ATTEMPTS = 3;
    public static final Duration OTP_BLOCK = Duration.ofMinutes(10);

    private record Attempt(int count, Instant expiresAt) {}

    private final Clock clock;
    private final Map<String, Attempt> login = new ConcurrentHashMap<>();
    private final Map<String, Attempt> otp = new ConcurrentHashMap<>();

    public LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    public boolean isLoginBlocked(String username) {
        return isBlocked(login, username, MAX_LOGIN_ATTEMPTS);
    }

    public void recordFailedLogin(String username) {
        record(login, username, LOGIN_BLOCK);
    }

    public void clearLogin(String username) {
        login.remove(key(username));
    }

    public long loginBlockRemainingSeconds(String username) {
        return remaining(login, username);
    }

    public boolean isOtpBlocked(String username) {
        return isBlocked(otp, username, MAX_OTP_ATTEMPTS);
    }

    public void recordFailedOtp(String username) {
        record(otp, username, OTP_BLOCK);
    }

    public void clearOtp(String username) {
        otp.remove(key(username));
    }

    public long otpBlockRemainingSeconds(String username) {
        return remaining(otp, username);
    }

    private boolean isBlocked(Map<String, Attempt> map, String username, int max) {
        Attempt a = live(map, username);
        return a != null && a.count() >= max;
    }

    private void record(Map<String, Attempt> map, String username, Duration block) {
        map.compute(key(username), (k, a) -> {
            Instant now = clock.instant();
            if (a == null || !a.expiresAt().isAfter(now)) return new Attempt(1, now.plus(block));
            return new Attempt(a.count() + 1, a.expiresAt());
        });
    }

    private long remaining(Map<String, Attempt> map, String username) {
        Attempt a = live(map, username);
        if (a == null) return 0;
        long seconds = Duration.between(clock.instant(), a.expiresAt()).getSeconds();
        return Math.max(seconds, 0);
    }

    /** The entry if it exists and has not expired; expired entries are dropped. */
    private Attempt live(Map<String, Attempt> map, String username) {
        String k = key(username);
        Attempt a = map.get(k);
        if (a == null) return null;
        if (!a.expiresAt().isAfter(clock.instant())) {
            map.remove(k, a);
            return null;
        }
        return a;
    }

    private static String key(String username) {
        return username == null ? "" : username.toLowerCase();
    }
}
