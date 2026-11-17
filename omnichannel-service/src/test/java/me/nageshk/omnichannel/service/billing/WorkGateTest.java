package me.nageshk.omnichannel.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.SubscriptionInactiveException;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.security.entitlement.Entitlement;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.inbox.AutomationActor;
import me.nageshk.omnichannel.service.inbox.CampaignActor;
import me.nageshk.omnichannel.service.inbox.ChatbotActor;
import me.nageshk.omnichannel.service.inbox.SendActor;
import me.nageshk.omnichannel.service.inbox.UserActor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WorkGateTest {

    private static final SendActor CAMPAIGN = new CampaignActor("c1", "Diwali");
    private static final SendActor RULE = new AutomationActor("r1", "Welcome");
    private static final SendActor BOT = new ChatbotActor("b1", "Shirts", "run1");
    private static final SendActor USER = new UserActor(null, "Asha");

    Entitlements entitlements = mock(Entitlements.class);
    WorkGate gate = new WorkGate(entitlements);

    @Test
    void aCustomerWithoutAPlanSendsAsBefore() {
        when(entitlements.ofWorkspace("w1")).thenReturn(Entitlement.UNRESTRICTED);
        for (SendActor a : new SendActor[] {CAMPAIGN, RULE, BOT, USER}) {
            assertThatCode(() -> gate.requireSend("w1", a)).doesNotThrowAnyException();
        }
        assertThat(gate.allows("w1", "campaign:manage")).isTrue();
    }

    @Test
    void aFeatureOutsideThePlanDoesNotSendButPeopleStillDo() {
        when(entitlements.ofWorkspace("w1")).thenReturn(plan(false, "automation:manage", "inbox:send"));
        assertThatThrownBy(() -> gate.requireSend("w1", CAMPAIGN))
                .isInstanceOfSatisfying(SubscriptionInactiveException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                    assertThat(e.code()).isEqualTo("SUBSCRIPTION_INACTIVE");
                });
        assertThatThrownBy(() -> gate.requireSend("w1", BOT)).isInstanceOf(SubscriptionInactiveException.class);
        assertThatCode(() -> gate.requireSend("w1", RULE)).doesNotThrowAnyException();
        assertThatCode(() -> gate.requireSend("w1", USER)).doesNotThrowAnyException();
        assertThat(gate.allows("w1", "campaign:manage")).isFalse();
        assertThat(gate.allows("w1", "automation:manage")).isTrue();
    }

    @Test
    void aReadOnlyCustomerSendsNothing() {
        when(entitlements.ofWorkspace("w1")).thenReturn(plan(true, "automation:manage", "campaign:manage",
                "chatbot:manage"));
        for (SendActor a : new SendActor[] {CAMPAIGN, RULE, BOT, USER}) {
            assertThatThrownBy(() -> gate.requireSend("w1", a)).isInstanceOf(SubscriptionInactiveException.class);
        }
        assertThat(gate.allows("w1", "campaign:manage")).isFalse();
    }

    private static Entitlement plan(boolean readOnly, String... authorities) {
        return new Entitlement(readOnly ? SubscriptionState.EXPIRED : SubscriptionState.ACTIVE, Set.of(authorities),
                Map.of(), readOnly, "Gold", null, null, null, "UTC");
    }
}
