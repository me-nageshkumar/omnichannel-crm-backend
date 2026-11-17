package me.nageshk.omnichannel.security.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class LimitsTest {

    Entitlements entitlements = mock(Entitlements.class);
    long users = 0;
    Limits limits = new Limits(entitlements, List.of(new LimitCounter() {
        @Override public LimitKey key() { return LimitKey.USERS; }
        @Override public long count(String customerId) { return users; }
    }));

    @Test
    void withoutALimitNothingIsRefused() {
        when(entitlements.of("c1")).thenReturn(Entitlement.UNRESTRICTED);
        users = 1000;
        assertThatCode(() -> limits.require("c1", LimitKey.USERS)).doesNotThrowAnyException();
        assertThat(limits.effective("c1", LimitKey.USERS)).isEmpty();
        assertThat(limits.used("c1", LimitKey.USERS)).hasValue(1000);
        assertThat(limits.used("c1", LimitKey.CHATBOT_NODES)).isEmpty();
    }

    @Test
    void theLimitIsTheMostThatMayExist() {
        when(entitlements.of("c1")).thenReturn(with(Map.of(LimitKey.USERS, 5)));
        users = 4;
        assertThatCode(() -> limits.require("c1", LimitKey.USERS)).doesNotThrowAnyException();
        users = 5;
        assertThatThrownBy(() -> limits.require("c1", LimitKey.USERS))
                .isInstanceOfSatisfying(CodedException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("LIMIT_REACHED");
                    assertThat(e.extra()).containsEntry("key", "USERS").containsEntry("limit", "5")
                            .containsEntry("used", "5");
                    assertThat(e.getMessage()).isEqualTo("Your plan allows 5 users");
                });
    }

    @Test
    void theCallerMayCountItself() {
        when(entitlements.of("c1")).thenReturn(with(Map.of(LimitKey.CRM_PIPELINES, 2)));
        assertThatCode(() -> limits.require("c1", LimitKey.CRM_PIPELINES, 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limits.require("c1", LimitKey.CRM_PIPELINES, 2)).isInstanceOf(CodedException.class);
    }

    @Test
    void aPlatformUserIsNeverCounted() {
        assertThatCode(() -> limits.require(null, LimitKey.USERS)).doesNotThrowAnyException();
    }

    private static Entitlement with(Map<LimitKey, Integer> limits) {
        return new Entitlement(SubscriptionState.ACTIVE, Set.of(), limits, false, "Gold", null, null, null, "UTC");
    }
}
