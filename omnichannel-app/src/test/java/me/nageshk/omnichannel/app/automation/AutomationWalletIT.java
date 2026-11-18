package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.automation.ActionContext;
import me.nageshk.omnichannel.service.automation.ActionResult;
import me.nageshk.omnichannel.service.automation.SendTemplateHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A rule's send fails, without another try, when the wallet cannot pay for it. */
class AutomationWalletIT extends AutomationTestBase {

    private static final String ASHA = "919811100001@c.us";

    @Autowired SendTemplateHandler handler;
    @Autowired TestBilling billing;

    @BeforeEach
    void noPlan() {
        billing.reset();
    }

    @AfterEach
    void leaveNoPlan() {
        billing.reset();
    }

    private ActionResult run(WaWebChat chat, String templateId) {
        Contact contact = mongo.findById(chat.getContactId(), Contact.class);
        EventData.ChatData c = new EventData.ChatData(chat.getId(), false, chat.getStatus(), null, null, false);
        EventData.ContactData k = new EventData.ContactData(contact.getId(), contact.getName(), contact.getPhone(), false, false);
        ActionContext ctx = new ActionContext(brand.getId(), acme.getId(), AutomationEventKind.MESSAGE_RECEIVED, "m-1",
                new EventData(c, null, null, k, null, null, null, null), "r1", "Welcome rule", false);
        return handler.run(ctx, new AutomationAction(AutomationActionType.SEND_TEMPLATE, templateId, null, null, null, null,
                null, null, null, null, null, null));
    }

    @Test
    void theSendActionFailsWithTheReasonAndNothingReachesThePhone() throws Exception {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        String t = template("Welcome", "Hi {{contact.name}}");
        billing.assign(acme.getId(), Set.of("automations"), Map.of());
        billing.messageRate(acme.getId(), "0.25");
        billing.wallet(acme.getId(), "0.2500");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            assertThat(run(chat, t).outcome()).isEqualTo(StepOutcome.DONE);

            ActionResult r = run(chat, t);
            assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
            assertThat(r.reason()).isEqualTo("The wallet balance is too low");
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }
}
