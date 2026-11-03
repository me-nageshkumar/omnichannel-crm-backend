package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.AutomationEvent;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.EventStatus;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.service.automation.AutomationGate;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import me.nageshk.omnichannel.service.inbox.InboxIngestService;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportContext;
import me.nageshk.omnichannel.service.inbox.InboxSettingService;
import me.nageshk.omnichannel.service.inbox.RoutingSetting;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentMatchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A live incoming real message becomes one queued event, for brands that want it. */
class MessageEventIT extends AutomationTestBase {

    private static final String ASHA = "919811100001@c.us";
    private static final String RAVI = "919811100002@c.us";

    @Autowired InboxIngestService ingest;
    @Autowired InboxSettingService settings;
    @MockitoSpyBean AutomationGate gate;

    @BeforeEach
    void publishedRule() {
        rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null);
    }

    private AutomationEvent only() {
        assertThat(queued()).hasSize(1);
        return queued().get(0);
    }

    @Test
    void firstRealMessageQueuesNewChatEvent() {
        settings.putOverride(brand.getId(), support.getId(),
                new RoutingSetting.Request(true, false, null, null, null, null));

        WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");

        AutomationEvent e = only();
        assertThat(e.getId()).startsWith("m-");
        assertThat(e.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(e.getKind()).isEqualTo(AutomationEventKind.MESSAGE_RECEIVED);
        assertThat(e.getWorkspaceId()).isEqualTo(brand.getId());
        assertThat(e.getCustomerId()).isEqualTo(acme.getId());
        assertThat(e.getCreatedAt()).isEqualTo(NOW);
        assertThat(e.getContactId()).isEqualTo(chat.getContactId());
        assertThat(e.getContactPhone()).isEqualTo("919811100001");
        assertThat(e.getData().chat().id()).isEqualTo(chat.getId());
        assertThat(e.getData().chat().isNew()).isTrue();
        assertThat(e.getData().chat().assignedTeam().id()).isEqualTo(support.getId());
        assertThat(e.getData().chat().assignedTeam().name()).isEqualTo("support");
        assertThat(e.getData().message().text()).isEqualTo("Hello");
        assertThat(e.getData().message().type()).isEqualTo("TEXT");
        assertThat(e.getData().number().id()).isEqualTo(n1.getId());
        assertThat(e.getData().number().number()).isEqualTo(n1.getNumber());
        assertThat(e.getData().contact().id()).isEqualTo(chat.getContactId());
        assertThat(e.getData().contact().name()).isEqualTo("Asha");
        assertThat(e.getData().contact().phone()).isEqualTo("919811100001");
        assertThat(e.getData().contact().inCrm()).isFalse();
        assertThat(e.getData().contact().doNotContact()).isFalse();
    }

    @Test
    void secondRealMessageIsNotNew() {
        incoming(n1, ASHA, "chat", "Hello", "Asha");
        incoming(n1, ASHA, "chat", "Anyone?", "Asha");

        List<AutomationEvent> events = queued();
        assertThat(events).hasSize(2);
        assertThat(events).filteredOn(e -> "Hello".equals(e.getData().message().text()))
                .singleElement().satisfies(e -> assertThat(e.getData().chat().isNew()).isTrue());
        assertThat(events).filteredOn(e -> "Anyone?".equals(e.getData().message().text()))
                .singleElement().satisfies(e -> assertThat(e.getData().chat().isNew()).isFalse());
    }

    @Test
    void callOrNoticeFirstKeepsTheChatNew() {
        incoming(n1, ASHA, "call_log", null, "Asha");
        incoming(n1, ASHA, "chat", "Hello", "Asha");

        AutomationEvent e = only();
        assertThat(e.getData().message().text()).isEqualTo("Hello");
        assertThat(e.getData().chat().isNew()).isTrue();
    }

    @Test
    void fetchedChatIsNotNew() {
        ingest.importMessage(new ImportContext(acme.getId(), brand.getId(), n1.getId()),
                payload(ASHA, false, "chat", "Old one", "Asha"));
        assertThat(queued()).isEmpty();

        incoming(n1, ASHA, "chat", "Hello again", "Asha");

        AutomationEvent e = only();
        assertThat(e.getData().message().text()).isEqualTo("Hello again");
        assertThat(e.getData().chat().isNew()).isFalse();
    }

    @Test
    void nonRealTypesQueueNothing() {
        incoming(n1, ASHA, "call_log", null, "Asha");
        incoming(n1, ASHA, "revoked", null, "Asha");
        incoming(n1, ASHA, "e2e_notification", null, "Asha");

        assertThat(queued()).isEmpty();
    }

    @Test
    void outgoingQueuesNothing() {
        publish(n1, payload(ASHA, true, "chat", "We reply", null));

        assertThat(queued()).isEmpty();
    }

    @Test
    void noEventWithoutPublishedRuleOrWaitingTest() {
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(),
                me.nageshk.omnichannel.db.automation.AutomationRule.class);
        rule("Draft", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, 1, null);

        incoming(n1, ASHA, "chat", "Hello", "Asha");
        assertThat(queued()).isEmpty();

        waitingTest(AutomationEventKind.MESSAGE_RECEIVED, null, "919811100009");
        incoming(n1, "919811100009@c.us", "chat", "Hi", "Nine");
        assertThat(queued()).singleElement()
                .satisfies(e -> assertThat(e.getContactPhone()).isEqualTo("919811100009"));
    }

    @Test
    void testOnlyGateTakesTheTestContactOnly() {
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(),
                me.nageshk.omnichannel.db.automation.AutomationRule.class);
        rule("Draft", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, 1, null);
        WaWebChat asha = incoming(n1, ASHA, "chat", "Hello", "Asha");
        assertThat(queued()).isEmpty();
        waitingTest(AutomationEventKind.MESSAGE_RECEIVED, asha.getContactId(), null);

        incoming(n1, ASHA, "chat", "Again", "Asha");
        incoming(n1, RAVI, "chat", "Hi", "Ravi");

        assertThat(queued()).singleElement()
                .satisfies(e -> assertThat(e.getContactId()).isEqualTo(asha.getContactId()));
    }

    @Test
    void otherEventKindDoesNotOpenTheGate() {
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(),
                me.nageshk.omnichannel.db.automation.AutomationRule.class);
        rule("Lane", AutomationEventKind.DEAL_LANE_CHANGED, RuleStatus.PUBLISHED, 1, null);

        incoming(n1, ASHA, "chat", "Hello", "Asha");

        assertThat(queued()).isEmpty();
    }

    @Test
    void replayedMessageQueuesOneEvent() {
        var p = payload(ASHA, false, "chat", "Hello", "Asha");

        publish(n1, p);
        publish(n1, p);

        assertThat(only().getId()).isEqualTo("m-" + mongo.findAll(WaWebMessage.class).get(0).getId());
    }

    @Test
    void ingestionSurvivesEventWriteFailure() {
        settings.putOverride(brand.getId(), support.getId(),
                new RoutingSetting.Request(true, false, null, null, null, null));
        doThrow(new DataAccessResourceFailureException("down")).when(gate).wants(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

        var p = payload(ASHA, false, "chat", "Hello", "Asha");
        assertThatCode(() -> publish(n1, p)).doesNotThrowAnyException();

        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(n1.getId(), ASHA).orElseThrow();
        assertThat(mongo.findAll(WaWebMessage.class)).hasSize(1);
        assertThat(chat.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(queued()).isEmpty();
    }

    @Test
    void nullTypeNeverBreaksIngestion() {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), AutomationEvent.class);
        WaWebMessage m = new WaWebMessage();
        m.setId("null-type");
        m.setChatId(chat.getId());
        m.setDirection(MessageDirection.IN);
        m.setSource(MessageSource.CONTACT);
        m.setType(null);

        assertThatCode(() -> publisher.publishEvent(new InboxEvents.MessageUpserted(chat, m, true)))
                .doesNotThrowAnyException();
        assertThat(queued()).isEmpty();
    }

    @Test
    void inCrmReadsTheCrm() {
        enableCrm();
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");
        assertThat(call(HttpMethod.POST, "/api/workspaces/" + brand.getId() + "/crm/contacts/capture", manager,
                Map.of("chatId", chat.getId())).getStatusCode().is2xxSuccessful()).isTrue();

        incoming(n1, ASHA, "chat", "Back", "Asha");

        assertThat(queued()).filteredOn(e -> "Back".equals(e.getData().message().text())).singleElement()
                .satisfies(e -> assertThat(e.getData().contact().inCrm()).isTrue());
        assertThat(queued()).filteredOn(e -> "Hello".equals(e.getData().message().text())).singleElement()
                .satisfies(e -> assertThat(e.getData().contact().inCrm()).isFalse());
    }
}
