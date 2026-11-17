package me.nageshk.omnichannel.common.billing;

import static me.nageshk.omnichannel.common.billing.BillingPeriod.MONTHLY;
import static me.nageshk.omnichannel.common.billing.BillingPeriod.QUARTERLY;
import static me.nageshk.omnichannel.common.billing.BillingPeriod.YEARLY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BillingPeriodTest {

    @Test
    void aTermEndsOnTheAnchorDayClampedToTheMonth() {
        assertThat(BillingPeriod.endOf(LocalDate.of(2027, 1, 31), MONTHLY, 31)).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(BillingPeriod.endOf(LocalDate.of(2027, 2, 28), MONTHLY, 31)).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(BillingPeriod.endOf(LocalDate.of(2028, 1, 31), MONTHLY, 31)).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(BillingPeriod.endOf(LocalDate.of(2026, 11, 17), YEARLY, 17)).isEqualTo(LocalDate.of(2027, 11, 17));
        assertThat(BillingPeriod.endOf(LocalDate.of(2026, 11, 30), QUARTERLY, 30)).isEqualTo(LocalDate.of(2027, 2, 28));
    }

    @Test
    void everyLimitKeyHasAScopeAndALabel() {
        for (LimitKey key : LimitKey.values()) {
            assertThat(key.scope()).as(key.name()).isNotNull();
            assertThat(key.label()).as(key.name()).isNotBlank();
        }
        assertThat(LimitKey.USERS.scope()).isEqualTo(LimitScope.CUSTOMER);
        assertThat(LimitKey.CHATBOT_AI_NODES.scope()).isEqualTo(LimitScope.CHATBOT);
        assertThat(LimitKey.CRM_PIPELINES.scope()).isEqualTo(LimitScope.BRAND);
    }
}
