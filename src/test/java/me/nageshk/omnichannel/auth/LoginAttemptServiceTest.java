package me.nageshk.omnichannel.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    /** A clock the test can advance. */
    static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Test
    void blocksAfterFiveFailedLogins() {
        var svc = new LoginAttemptService(new MutableClock());
        for (int i = 0; i < 4; i++) svc.recordFailedLogin("alice");
        assertThat(svc.isLoginBlocked("alice")).isFalse();

        svc.recordFailedLogin("alice");
        assertThat(svc.isLoginBlocked("alice")).isTrue();
        assertThat(svc.loginBlockRemainingSeconds("alice")).isEqualTo(1800);
    }

    @Test
    void loginBlockExpiresAfterThirtyMinutes() {
        var clock = new MutableClock();
        var svc = new LoginAttemptService(clock);
        for (int i = 0; i < 5; i++) svc.recordFailedLogin("alice");
        assertThat(svc.isLoginBlocked("alice")).isTrue();

        clock.advance(Duration.ofMinutes(31));
        assertThat(svc.isLoginBlocked("alice")).isFalse();
        assertThat(svc.loginBlockRemainingSeconds("alice")).isZero();

        svc.recordFailedLogin("alice");
        assertThat(svc.isLoginBlocked("alice")).isFalse();
    }

    @Test
    void clearLoginResetsCounter() {
        var svc = new LoginAttemptService(new MutableClock());
        for (int i = 0; i < 4; i++) svc.recordFailedLogin("alice");
        svc.clearLogin("alice");
        svc.recordFailedLogin("alice");
        assertThat(svc.isLoginBlocked("alice")).isFalse();
    }

    @Test
    void blocksAfterThreeFailedOtps() {
        var svc = new LoginAttemptService(new MutableClock());
        svc.recordFailedOtp("alice");
        svc.recordFailedOtp("alice");
        assertThat(svc.isOtpBlocked("alice")).isFalse();

        svc.recordFailedOtp("alice");
        assertThat(svc.isOtpBlocked("alice")).isTrue();
        assertThat(svc.otpBlockRemainingSeconds("alice")).isEqualTo(600);
        assertThat(svc.isLoginBlocked("alice")).isFalse();
    }

    @Test
    void keysAreCaseInsensitive() {
        var svc = new LoginAttemptService(new MutableClock());
        for (int i = 0; i < 5; i++) svc.recordFailedLogin("Alice");
        assertThat(svc.isLoginBlocked("alice")).isTrue();
    }
}
