package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Rules fire, and people send, only inside the customer's plan; incoming messages are always kept. */
class AutomationPlanIT extends AutomationTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired TestBilling billing;

    @BeforeEach
    void noPlan() {
        billing.reset();
    }

    @AfterEach
    void leaveNoPlan() {
        billing.reset();
    }

    private String messages(WaWebChat chat) {
        return "/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId() + "/messages";
    }

    @Test
    void eventsAreQueuedOnlyWhenThePlanHasAutomations() {
        rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null);

        billing.assign(acme.getId(), Set.of("campaigns"), Map.of());
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        assertThat(queued()).isEmpty();
        // the message itself is kept
        assertThat(mongo.findAll(WaWebMessage.class)).extracting(WaWebMessage::getChatId).containsExactly(chat.getId());

        billing.assign(acme.getId(), Set.of("automations"), Map.of());
        incoming(n1, VEENU, "chat", "hello again", "Veenu");
        assertThat(queued()).hasSize(1);
    }

    @Test
    void withoutAPlanRulesFireAsBefore() {
        rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null);
        incoming(n1, VEENU, "chat", "hello", "Veenu");
        assertThat(queued()).hasSize(1);
    }

    @Test
    void anEndedSubscriptionReceivesButNeitherSendsNorFiresRules() throws Exception {
        rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null);
        billing.assign(acme.getId(), Set.of("automations"), Map.of());
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        assertThat(queued()).hasSize(1);

        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            assertThat(call(HttpMethod.POST, messages(chat), agent, Map.of("text", "Hi")).getStatusCode())
                    .isEqualTo(HttpStatus.OK);

            // thirty days of term, seven of grace, then read-only
            clock.advance(Duration.ofDays(45));
            ResponseEntity<JsonNode> refused = call(HttpMethod.POST, messages(chat), agent, Map.of("text", "Hi again"));
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            // read-only mode answers before the send is tried
            assertThat(refused.getBody().get("code").asString()).isEqualTo("SUBSCRIPTION_EXPIRED");
            assertThat(d.requests("sendMessage")).hasSize(1);

            incoming(n1, VEENU, "chat", "are you there?", "Veenu");
            assertThat(queued()).hasSize(1);
            assertThat(mongo.findAll(WaWebMessage.class)).extracting(WaWebMessage::getBody).contains("are you there?");
        }
    }
}
