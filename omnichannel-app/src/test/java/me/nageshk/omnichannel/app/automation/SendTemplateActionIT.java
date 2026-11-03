package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.inbox.ChatClosedException;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.DoNotContact;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.service.automation.ActionContext;
import me.nageshk.omnichannel.service.automation.ActionResult;
import me.nageshk.omnichannel.service.automation.SendTemplateHandler;
import me.nageshk.omnichannel.service.inbox.InboxSendService;
import me.nageshk.omnichannel.service.inbox.SendRequest;
import me.nageshk.omnichannel.service.inbox.UserActor;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** The send-template action against a fake device: who it sends as, what it skips, what it retries. */
class SendTemplateActionIT extends AutomationTestBase {

    private static final String ASHA = "919811100001@c.us";
    private static final String LID = "7788991122@lid";

    @Autowired SendTemplateHandler handler;
    @Autowired InboxSendService sender;
    @Autowired AccessService access;

    private AutomationAction action(String templateId, Map<String, String> values) {
        return new AutomationAction(AutomationActionType.SEND_TEMPLATE, templateId, values, null, null, null, null,
                null, null, null);
    }

    private ActionContext ctx(WaWebChat chat, Contact contact) {
        EventData.ChatData c = chat == null ? null
                : new EventData.ChatData(chat.getId(), false, chat.getStatus(), null, null);
        EventData.ContactData k = contact == null ? null : new EventData.ContactData(contact.getId(),
                contact.getName(), contact.getPhone(), false, false);
        return new ActionContext(brand.getId(), acme.getId(), AutomationEventKind.MESSAGE_RECEIVED, "m-1",
                new EventData(c, null, null, k, null, null, null, null), "r1", "Welcome rule", false);
    }

    private Contact contactOf(WaWebChat chat) {
        return mongo.findById(chat.getContactId(), Contact.class);
    }

    private ActionResult run(WaWebChat chat, String templateId, Map<String, String> values) {
        return handler.run(ctx(chat, contactOf(chat)), action(templateId, values));
    }

    private List<WaWebMessage> stored(WaWebChat chat) {
        return mongo.find(new Query(Criteria.where("chatId").is(chat.getId()).and("direction").is("OUT")),
                WaWebMessage.class);
    }

    private String content(AutomationDevice d) {
        return d.requests("sendMessage").get(0).params().get("content").asString();
    }

    @Test
    void sendsFilledTemplateAsAutomation() throws Exception {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        String t = template("Welcome", "Hi {{contact.name}}, welcome to Acme!");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ActionResult r = run(chat, t, null);

            assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
            assertThat(r.reason()).isEqualTo("Sent “Welcome”");
            assertThat(d.requests("sendMessage")).hasSize(1);
            assertThat(content(d)).isEqualTo("Hi Asha, welcome to Acme!");
            List<WaWebMessage> out = stored(chat);
            assertThat(out).hasSize(1);
            assertThat(out.get(0).getSource()).isEqualTo(MessageSource.AUTOMATION);
            assertThat(out.get(0).getSentByName()).isEqualTo("Automation: Welcome rule");
            assertThat(out.get(0).getSentByUserId()).isNull();
            assertThat(out.get(0).getTemplateId()).isEqualTo(t);
        }
    }

    @Test
    void nameFallsBackWhenUnknown() throws Exception {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", null);
        String t = template("Welcome", "Hi {{contact.name}}, welcome to Acme!");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            assertThat(run(chat, t, null).outcome()).isEqualTo(StepOutcome.DONE);
            assertThat(content(d)).isEqualTo("Hi there, welcome to Acme!");
        }
    }

    @Test
    void agentPlaceholderTakesRuleValue() throws Exception {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        String t = template("Order", "Order {{order_id}} is ready");
        mongo.updateFirst(new Query(Criteria.where("_id").is(t)),
                new org.springframework.data.mongodb.core.query.Update().set("placeholders.0.required", true),
                MessageTemplate.class);
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ActionResult without = run(chat, t, Map.of());
            assertThat(without.outcome()).isEqualTo(StepOutcome.FAILED);
            assertThat(without.reason()).isEqualTo("Placeholder “order_id” has no value");
            assertThat(d.requests("sendMessage")).isEmpty();

            assertThat(run(chat, t, Map.of("order_id", "A-17")).outcome()).isEqualTo(StepOutcome.DONE);
            assertThat(content(d)).isEqualTo("Order A-17 is ready");
        }
    }

    @Test
    void doNotContactIsSkipped() throws Exception {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        Contact c = contactOf(chat);
        c.setDoNotContact(new DoNotContact(clock.instant(), DoNotContact.Reason.MANUAL, null));
        mongo.save(c);
        String t = template("Welcome", "Hi {{contact.name}}");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ActionResult r = run(chat, t, null);
            assertThat(r.outcome()).isEqualTo(StepOutcome.SKIPPED);
            assertThat(r.reason()).isEqualTo("Contact is marked do-not-contact");
            assertThat(d.requests("sendMessage")).isEmpty();
        }
    }

    @Test
    void doNotContactByPhoneIsSkipped() throws Exception {
        WaWebChat chat = incoming(n1, LID, "chat", "Hi", "Asha");
        Contact lidContact = contactOf(chat);
        assertThat(lidContact.getDoNotContact()).isNull();
        Contact marked = new Contact();
        marked.setWorkspaceId(brand.getId());
        marked.setCustomerId(acme.getId());
        marked.setPhone("919811100001");
        marked.setName("Asha");
        marked.setDoNotContact(new DoNotContact(clock.instant(), DoNotContact.Reason.STOP_REPLY, null));
        mongo.save(marked);
        String t = template("Welcome", "Hi {{contact.name}}");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            Contact withPhone = new Contact();
            withPhone.setId(lidContact.getId());
            withPhone.setName("Asha");
            withPhone.setPhone("919811100001");
            ActionResult r = handler.run(ctx(chat, withPhone), action(t, null));
            assertThat(r.outcome()).isEqualTo(StepOutcome.SKIPPED);
            assertThat(r.reason()).isEqualTo("Contact is marked do-not-contact");
            assertThat(d.requests("sendMessage")).isEmpty();
        }
    }

    @Test
    void sendsOutsideCampaignHours() throws Exception {
        clock.set(Instant.parse("2026-11-08T17:30:00Z"));
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        String t = template("Welcome", "Hi {{contact.name}}");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            assertThat(run(chat, t, null).outcome()).isEqualTo(StepOutcome.DONE);
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }

    @Test
    void doesNotClaimOrReopen() throws Exception {
        WaWebChat open = incoming(n1, ASHA, "chat", "Hi", "Asha");
        assertThat(open.getAssignedUserId()).isNull();
        WaWebChat closed = incoming(n1, "919811100002@c.us", "chat", "Hi", "Ravi");
        closed.setStatus(ChatStatus.CLOSED);
        chats.save(closed);
        String t = template("Welcome", "Hi {{contact.name}}");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            assertThat(run(open, t, null).outcome()).isEqualTo(StepOutcome.DONE);
            WaWebChat after = chats.findById(open.getId()).orElseThrow();
            assertThat(after.getAssignedUserId()).isNull();
            assertThat(after.getLastAgentUserId()).isNull();

            assertThat(run(closed, t, null).outcome()).isEqualTo(StepOutcome.DONE);
            assertThat(stored(closed)).hasSize(1);
            WaWebChat still = chats.findById(closed.getId()).orElseThrow();
            assertThat(still.getStatus()).isEqualTo(ChatStatus.CLOSED);
            assertThat(still.getAssignedUserId()).isNull();
            assertThat(mongo.find(new Query(Criteria.where("chatId").is(closed.getId())
                    .and("type").is(ActionType.REOPENED)), WaWebChatAction.class)).isEmpty();
        }
    }

    @Test
    void noChatFails() {
        ActionResult r = handler.run(ctx(null, null), action("t", null));
        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("No chat with this contact");
    }

    @Test
    void archivedTemplateFails() throws Exception {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        String t = template("Welcome", "Hi {{contact.name}}");
        mongo.updateFirst(new Query(Criteria.where("_id").is(t)),
                new org.springframework.data.mongodb.core.query.Update().set("status", TemplateStatus.ARCHIVED),
                MessageTemplate.class);
        ActionResult r = run(chat, t, null);
        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("The template is archived");
    }

    @Test
    void missingTemplateFails() {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        ActionResult r = run(chat, "nope", null);
        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("The template no longer exists");
    }

    @Test
    void deviceOfflineRetriesLater() {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        String t = template("Welcome", "Hi {{contact.name}}");
        ActionResult r = run(chat, t, null);
        assertThat(r.outcome()).isEqualTo(StepOutcome.RETRYING);
        assertThat(r.reason()).isEqualTo("The number's extension is offline or the number is disabled");
    }

    @Test
    void extensionNotAnsweringIsInterrupted() throws Exception {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        String t = template("Welcome", "Hi {{contact.name}}");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            d.sendAnswer = req -> null;
            ActionResult r = run(chat, t, null);
            assertThat(r.outcome()).isEqualTo(StepOutcome.INTERRUPTED);
            assertThat(r.reason()).isEqualTo("The extension did not answer in time; the message may have gone");
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }

    @Test
    void disconnectedMidCallIsInterrupted() throws Exception {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        String t = template("Welcome", "Hi {{contact.name}}");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            d.sendAnswer = req -> {
                try {
                    d.drop();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return null;
            };
            ActionResult r = run(chat, t, null);
            assertThat(r.outcome()).isEqualTo(StepOutcome.INTERRUPTED);
            assertThat(r.reason()).isEqualTo("The extension lost the connection; the message may have gone");
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }

    @Test
    void whatsappRefusalFails() throws Exception {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        String t = template("Welcome", "Hi {{contact.name}}");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            d.sendAnswer = (Envelope req) -> Envelope.errorResponse(req.id(), "INVALID_CHAT", "no such chat");
            ActionResult r = run(chat, t, null);
            assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
            assertThat(r.reason()).isEqualTo("WhatsApp did not send it (INVALID_CHAT)");
        }
    }

    @Test
    void whatsappNotReadyFromTheExtensionRetriesLater() throws Exception {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        String t = template("Welcome", "Hi {{contact.name}}");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            d.sendAnswer = (Envelope req) -> Envelope.errorResponse(req.id(), "WHATSAPP_NOT_READY", "not ready");
            ActionResult r = run(chat, t, null);
            assertThat(r.outcome()).isEqualTo(StepOutcome.RETRYING);
            assertThat(r.reason()).isEqualTo("WhatsApp Web is not ready on the number");
        }
    }

    @Test
    void consoleSendStillRefusesClosedChat() throws Exception {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        chat.setStatus(ChatStatus.CLOSED);
        chats.save(chat);
        String t = template("Welcome", "Hi {{contact.name}}");
        UserActor user = new UserActor(access.load(manager), "Manager");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            assertThatThrownBy(() -> sender.send(user, brand.getId(),
                    chats.findById(chat.getId()).orElseThrow(), new SendRequest(null, t, null), null))
                    .isInstanceOf(ChatClosedException.class);
            assertThat(d.requests("sendMessage")).isEmpty();
        }
    }
}
