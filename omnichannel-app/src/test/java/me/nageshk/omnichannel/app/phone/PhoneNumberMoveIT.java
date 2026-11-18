package me.nageshk.omnichannel.app.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import me.nageshk.omnichannel.app.support.FakeDevice;
import me.nageshk.omnichannel.app.support.MutableClock;
import me.nageshk.omnichannel.app.support.TestDevices;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.crm.contact.Channel;
import me.nageshk.omnichannel.crm.contact.CrmContact;
import me.nageshk.omnichannel.crm.contact.Origin;
import me.nageshk.omnichannel.crm.contact.OriginKind;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRuleRepository;
import me.nageshk.omnichannel.db.automation.ConditionNode;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSender;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.security.phone.DeviceKeys;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import me.nageshk.omnichannel.service.inbox.ChatMediaService;
import me.nageshk.omnichannel.service.phone.move.MovePreview;
import me.nageshk.omnichannel.service.phone.move.NumberRecords;
import me.nageshk.omnichannel.service.phone.move.PhoneNumberMove;
import me.nageshk.omnichannel.service.phone.move.PhoneNumberMoveRepository;
import me.nageshk.omnichannel.service.phone.move.PhoneNumberMoveService;
import me.nageshk.omnichannel.service.ws.DeviceRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.socket.CloseStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Moving a number to another brand of the same customer (spec §7.2): the old brand keeps its chats on an archive, the
 * number starts afresh in the new brand, moving back merges the archive, and a move stopped half way finishes on Retry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhoneNumberMoveIT {

    static final Instant NOW = Instant.parse("2026-11-18T06:00:00Z");
    static final String MAIN = "919900000001";
    static final String VEENU = "919811111111";
    static final String RAVI = "919822222222";

    @TestConfiguration
    static class Fixtures {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @LocalServerPort int port;
    @Autowired PhoneNumberMoveService service;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired PhoneNumberRepository numbers;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebMessageRepository messages;
    @Autowired ContactRepository contacts;
    @Autowired TeamRepository teams;
    @Autowired AutomationRuleRepository rules;
    @Autowired ChatMediaService media;
    @Autowired MongoTemplate mongo;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ObjectMapper mapper;
    @Autowired DeviceRegistry registry;
    @Autowired MutableClock clock;
    @MockitoSpyBean NumberRecords records;
    @MockitoSpyBean PhoneNumberMoveRepository moves;

    Customer acme;
    Workspace brandA;
    Workspace brandB;
    Team sales;
    PhoneNumber main;
    AuthenticatedUser me;
    WaWebChat veenuChat;
    WaWebChat raviChat;
    ChatbotRun run;
    long messageCount;

    private static final List<String> COLLECTIONS = List.of("waWebChats", "waWebMessages", "waWebChatActions",
            "waWebHistoryImports", "waWebHistoryImportChats", "chatbotRuns", "messagePages", "campaignSenders",
            "campaigns", "campaignRecipients", "channelWebhookEvents", "chatMedia.files", "chatMedia.chunks",
            "crmContacts", "crmActivities", "crmNotes", "crmTasks", "crmDeals", "phoneNumberMoves", "phoneNumbers",
            "contacts", "teams", "automationRules", "automationEvents");

    @BeforeEach
    void setUp() {
        clean();
        clock.set(NOW);
        acme = testUsers.customer("Acme");
        brandA = testUsers.workspace(acme.getId(), "Brand A");
        brandB = testUsers.workspace(acme.getId(), "Brand B");
        me = new AuthenticatedUser(testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null),
                Set.of(BuiltInRoles.SUPER_ADMIN));
        sales = new Team();
        sales.setCustomerId(acme.getId());
        sales.setWorkspaceId(brandA.getId());
        sales.setName("Sales");
        sales.setMemberIds(new ArrayList<>());
        sales = teams.save(sales);

        main = testDevices.number(brandA, MAIN);
        main.setLabel("Main line");
        main.setTeamId(sales.getId());
        main = numbers.save(main);

        veenuChat = chat(brandA, contact(brandA, "Veenu", VEENU), VEENU);
        raviChat = chat(brandA, contact(brandA, "Ravi", RAVI), RAVI);
        WaWebMessage first = message(veenuChat, "hello");
        message(veenuChat, "price?");
        message(raviChat, "hi");
        message(raviChat, "thanks");
        messageCount = 4;

        run = waitingRun(veenuChat);
        crmRecords(veenuChat);
        campaignRecords();
        media.store(brandA.getId(), main.getId(), first.getId(), "text/plain", "note.txt",
                "x".getBytes(StandardCharsets.UTF_8));
        webhookEvent();
        historyImport();
        pendingAutomationEvent(veenuChat);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (String c : COLLECTIONS) mongo.remove(new Query(), c);
    }

    // --- the move ----------------------------------------------------------------------------------------

    @Test
    void moveKeepsOldChatsInAnArchiveOfTheSourceBrand() {
        PhoneNumberMove move = service.move(main.getId(), brandB.getId(), me);

        assertThat(move.getStep()).isEqualTo(PhoneNumberMove.Step.DONE);
        assertThat(move.getFinishedAt()).isEqualTo(NOW);
        assertThat(move.getError()).isNull();
        assertThat(move.getFromWorkspaceId()).isEqualTo(brandA.getId());
        assertThat(move.getToWorkspaceId()).isEqualTo(brandB.getId());
        assertThat(move.getCounts()).isEqualTo(new PhoneNumberMove.Counts(2, messageCount, 1));

        PhoneNumber archive = onlyArchive(brandA);
        assertThat(move.getSourceArchiveId()).isEqualTo(archive.getId());
        assertThat(archive.getLabel()).isEqualTo("Main line (until 18 Nov)");
        assertThat(archive.getArchiveOf()).isEqualTo(main.getId());
        assertThat(archive.getArchivedNumber()).isEqualTo(MAIN);
        assertThat(archive.getNumber()).isNull();
        assertThat(archive.getTeamId()).isEqualTo(sales.getId());
        assertThat(archive.getCustomerId()).isEqualTo(acme.getId());
        assertThat(archive.getArchivedAt()).isEqualTo(NOW);
        assertThat(archive.getDisabled()).isNotNull();
        assertThat(archive.getDeviceKeyHash()).isNull();
        assertThat(archive.getDeviceKeyId()).isNull();
        assertThat(archive.getLastConnectedAt()).isNull();

        // the old brand's records sit on the archive
        assertThat(chats.findAll()).extracting(WaWebChat::getPhoneNumberId).containsOnly(archive.getId());
        assertThat(chats.findAll()).extracting(WaWebChat::getWorkspaceId).containsOnly(brandA.getId());
        assertThat(messages.findAll()).hasSize((int) messageCount).extracting(WaWebMessage::getPhoneNumberId)
                .containsOnly(archive.getId());
        assertThat(field("crmContacts", "channel.phoneNumberId")).containsOnly(archive.getId());
        assertThat(field("crmContacts", "origin.phoneNumberId")).containsOnly(archive.getId());
        assertThat(field("crmContacts", "channelHistory.toPhoneNumberId")).containsOnly(archive.getId());
        for (String c : List.of("crmActivities", "crmNotes", "crmTasks", "chatbotRuns", "messagePages",
                "campaignSenders", "waWebHistoryImports", "waWebHistoryImportChats")) {
            assertThat(field(c, "phoneNumberId")).as(c).containsOnly(archive.getId());
        }
        assertThat(field("crmDeals", "channel.phoneNumberId")).containsOnly(archive.getId());
        assertThat(field("campaigns", "senderIds")).containsOnly(archive.getId());
        assertThat(field("campaignRecipients", "senderId")).containsOnly(archive.getId());
        assertThat(field("campaignRecipients", "preferredSenderId")).containsOnly(archive.getId());
        assertThat(field("chatMedia.files", "metadata.phoneNumberId")).containsOnly(archive.getId());
        assertThat(field("channelWebhookEvents", "event.entry.changes.value.metadata.phone_number_id"))
                .containsOnly(archive.getId());
        assertThat(field("automationEvents", "data.number.id")).containsExactly(archive.getId());

        // the run ended, its chat is no longer the chatbot's, the import stopped
        ChatbotRun ended = mongo.findById(run.getId(), ChatbotRun.class);
        assertThat(ended.getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
        assertThat(ended.getEndReason()).isEqualTo("number moved");
        WaWebChat released = chats.findById(veenuChat.getId()).orElseThrow();
        assertThat(released.getStatus()).isNotEqualTo(ChatStatus.BOT);
        assertThat(released.getChatbot()).isNull();
        assertThat(mongo.findAll(WaWebHistoryImport.class)).extracting(WaWebHistoryImport::isActive).containsOnly(false);

        // the number itself is in B, without a team
        PhoneNumber moved = numbers.findById(main.getId()).orElseThrow();
        assertThat(moved.getWorkspaceId()).isEqualTo(brandB.getId());
        assertThat(moved.getTeamId()).isNull();
        assertThat(moved.getMovedInAt()).isEqualTo(NOW);
        assertThat(moved.getMovingSince()).isNull();
        assertThat(moved.getNumber()).isEqualTo(MAIN);
        assertThat(service.last(main.getId())).map(PhoneNumberMove::getId).contains(move.getId());
    }

    @Test
    void botChatWithoutARunIsOpenedUnassigned() {
        mongo.updateFirst(new Query(Criteria.where("_id").is(raviChat.getId())), new org.springframework.data.mongodb
                .core.query.Update().set("status", ChatStatus.BOT).set("assignedTeamId", sales.getId())
                .set("chatbot", new WaWebChat.ActiveChatbot("gone", "cb", "Old bot", "Ask")), WaWebChat.class);

        service.move(main.getId(), brandB.getId(), me);

        WaWebChat ravi = chats.findById(raviChat.getId()).orElseThrow();
        assertThat(ravi.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(ravi.getAssignedTeamId()).isNull();
        assertThat(ravi.getAssignedUserId()).isNull();
        assertThat(ravi.getChatbot()).isNull();
    }

    @Test
    void newMessageAfterMoveOpensAChatInTheTargetBrand() {
        service.move(main.getId(), brandB.getId(), me);
        PhoneNumber archive = onlyArchive(brandA);

        incoming(brandB, VEENU, "back again");

        List<WaWebChat> onNumber = chats.findAll().stream().filter(c -> c.getPhoneNumberId().equals(main.getId()))
                .toList();
        assertThat(onNumber).hasSize(1);
        WaWebChat fresh = onNumber.getFirst();
        assertThat(fresh.getWorkspaceId()).isEqualTo(brandB.getId());
        Contact inB = contacts.findById(fresh.getContactId()).orElseThrow();
        assertThat(inB.getWorkspaceId()).isEqualTo(brandB.getId());
        assertThat(inB.getId()).isNotEqualTo(veenuChat.getContactId());

        WaWebChat old = chats.findById(veenuChat.getId()).orElseThrow();
        assertThat(old.getPhoneNumberId()).isEqualTo(archive.getId());
        assertThat(old.getWorkspaceId()).isEqualTo(brandA.getId());
        assertThat(messages.findAll().stream().filter(m -> m.getChatId().equals(old.getId()))).hasSize(2);
    }

    @Test
    void movingBackMergesTheArchive() {
        service.move(main.getId(), brandB.getId(), me);
        incoming(brandB, VEENU, "hello B");
        WaWebChat chatInB = chats.findAll().stream().filter(c -> c.getPhoneNumberId().equals(main.getId()))
                .findFirst().orElseThrow();
        clock.set(Instant.parse("2026-11-25T06:00:00Z"));

        PhoneNumberMove back = service.move(main.getId(), brandA.getId(), me);

        assertThat(back.getStep()).isEqualTo(PhoneNumberMove.Step.DONE);
        assertThat(back.getMergedArchiveId()).isNotNull();
        assertThat(numbers.findById(back.getMergedArchiveId())).isEmpty();
        assertThat(archivesIn(brandA)).isEmpty();
        assertThat(chats.findById(veenuChat.getId()).orElseThrow().getPhoneNumberId()).isEqualTo(main.getId());
        assertThat(chats.findById(raviChat.getId()).orElseThrow().getPhoneNumberId()).isEqualTo(main.getId());
        assertThat(messages.findAll().stream().filter(m -> m.getWorkspaceId().equals(brandA.getId())))
                .extracting(WaWebMessage::getPhoneNumberId).hasSize((int) messageCount).containsOnly(main.getId());
        assertThat(field("crmContacts", "channel.phoneNumberId")).containsOnly(main.getId());
        assertThat(field("campaignSenders", "phoneNumberId")).containsOnly(main.getId());
        assertThat(field("automationEvents", "data.number.id")).containsExactly(main.getId());

        PhoneNumber archiveInB = onlyArchive(brandB);
        assertThat(archiveInB.getLabel()).isEqualTo("Main line (until 25 Nov)");
        assertThat(chats.findById(chatInB.getId()).orElseThrow().getPhoneNumberId()).isEqualTo(archiveInB.getId());
        assertThat(numbers.findById(main.getId()).orElseThrow().getWorkspaceId()).isEqualTo(brandA.getId());
    }

    @Test
    void refusedAcrossCustomersAndSameBrandAndArchives() {
        Customer other = testUsers.customer("Other Co");
        Workspace foreign = testUsers.workspace(other.getId(), "Foreign");

        assertCode(() -> service.move(main.getId(), foreign.getId(), me), "number_move_other_customer", 400);
        assertCode(() -> service.preview(main.getId(), foreign.getId()), "number_move_other_customer", 400);
        assertCode(() -> service.move(main.getId(), brandA.getId(), me), "number_move_same_brand", 400);

        service.move(main.getId(), brandB.getId(), me);
        PhoneNumber archive = onlyArchive(brandA);
        assertCode(() -> service.move(archive.getId(), brandB.getId(), me), "number_archived", 409);

        assertThat(mongo.count(new Query(), PhoneNumberMove.class)).isEqualTo(1);
    }

    @Test
    void secondMoveWhileUnfinished409() {
        doAnswer(inv -> {
            throw new IllegalStateException("database went away");
        }).when(records).repoint(eq(main.getId()), any());

        PhoneNumberMove stopped = service.move(main.getId(), brandB.getId(), me);

        assertThat(stopped.getStep()).isEqualTo(PhoneNumberMove.Step.RUNS_ENDED);
        assertThat(stopped.getError()).contains("database went away");
        assertThat(stopped.getFinishedAt()).isNull();
        assertThat(numbers.findById(main.getId()).orElseThrow().getMovingSince()).isEqualTo(NOW);
        assertCode(() -> service.move(main.getId(), brandB.getId(), me), "number_move_unfinished", 409);
        assertThat(service.last(main.getId())).map(PhoneNumberMove::getId).contains(stopped.getId());
    }

    @ParameterizedTest
    @EnumSource(value = PhoneNumberMove.Step.class, names = {"OFFLINE", "RUNS_ENDED", "ARCHIVED", "MERGED"})
    void retryAfterStopAtEachStepFinishesTheMove(PhoneNumberMove.Step stopAfter) {
        AtomicBoolean thrown = new AtomicBoolean();
        doAnswer(inv -> {
            // the repository is a proxy with no real method to call: the template saves as it does
            PhoneNumberMove m = inv.getArgument(0);
            PhoneNumberMove saved = mongo.save(m);
            if (m.getStep() == stopAfter && thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("stopped after " + stopAfter);
            }
            return saved;
        }).when(moves).save(any(PhoneNumberMove.class));

        PhoneNumberMove stopped = service.move(main.getId(), brandB.getId(), me);

        assertThat(stopped.getStep()).isEqualTo(stopAfter);
        assertThat(stopped.getError()).isEqualTo("stopped after " + stopAfter);
        assertThat(numbers.findById(main.getId()).orElseThrow().getMovingSince()).isNotNull();

        PhoneNumberMove done = service.retry(main.getId(), me);

        assertThat(done.getId()).isEqualTo(stopped.getId());
        assertThat(done.getStep()).isEqualTo(PhoneNumberMove.Step.DONE);
        assertThat(done.getError()).isNull();
        assertMovedOnce(done);
    }

    @Test
    void retryAfterARepointStoppedHalfWayFinishesTheMove() {
        // the records reached the archive, then the step stopped before it was recorded
        AtomicBoolean thrown = new AtomicBoolean();
        doAnswer(inv -> {
            inv.callRealMethod();
            if (thrown.compareAndSet(false, true)) throw new IllegalStateException("stopped in the repoint");
            return null;
        }).when(records).repoint(eq(main.getId()), any());

        PhoneNumberMove stopped = service.move(main.getId(), brandB.getId(), me);
        assertThat(stopped.getStep()).isEqualTo(PhoneNumberMove.Step.RUNS_ENDED);
        assertThat(service.retry(main.getId(), me).getStep()).isEqualTo(PhoneNumberMove.Step.DONE);
        assertMovedOnce(service.last(main.getId()).orElseThrow());

        // moving back: the merge into the real id stops after its records moved
        incoming(brandB, VEENU, "hello B");
        AtomicBoolean thrownBack = new AtomicBoolean();
        String archiveA = onlyArchive(brandA).getId();
        doAnswer(inv -> {
            inv.callRealMethod();
            if (thrownBack.compareAndSet(false, true)) throw new IllegalStateException("stopped in the merge");
            return null;
        }).when(records).repoint(eq(archiveA), eq(main.getId()));

        PhoneNumberMove back = service.move(main.getId(), brandA.getId(), me);
        assertThat(back.getStep()).isEqualTo(PhoneNumberMove.Step.ARCHIVED);
        assertThat(service.retry(main.getId(), me).getStep()).isEqualTo(PhoneNumberMove.Step.DONE);

        assertThat(archivesIn(brandA)).isEmpty();
        assertThat(archivesIn(brandB)).hasSize(1);
        assertThat(chats.findAll()).hasSize(3);
        assertThat(chats.findById(veenuChat.getId()).orElseThrow().getPhoneNumberId()).isEqualTo(main.getId());
    }

    @Test
    void retryWithoutAnUnfinishedMoveIsRefused() {
        service.move(main.getId(), brandB.getId(), me);
        assertCode(() -> service.retry(main.getId(), me), "number_move_nothing_to_retry", 409);
    }

    @Test
    void deviceIsClosedWithMovedAndReconnectsIntoTheTargetBrand() throws Exception {
        DeviceKeys.Connected key = testDevices.redeem(main);
        try (FakeDevice device = FakeDevice.connectAndAuth(port, key.deviceKey())) {
            testDevices.awaitConnected(main);

            service.move(main.getId(), brandB.getId(), me);

            CloseStatus status = device.awaitClose();
            assertThat(status.getCode()).isEqualTo(4503);
        }
        try (FakeDevice again = FakeDevice.connectAndAuth(port, key.deviceKey())) {
            long end = System.nanoTime() + 5_000_000_000L;
            while (registry.find(main.getId()).map(s -> s.principal().workspaceId()).filter(brandB.getId()::equals)
                    .isEmpty()) {
                if (System.nanoTime() > end) throw new AssertionError("device did not reconnect into brand B");
                Thread.sleep(20);
            }
            assertThat(registry.find(main.getId()).orElseThrow().principal().workspaceId()).isEqualTo(brandB.getId());
            assertThat(again.isOpen()).isTrue();
        }
    }

    @Test
    void previewCounts() {
        AutomationRule naming = rule("Sales follow-up", new ConditionNode("and", false, List.of(
                new ConditionNode(null, null, null, "number", "is_one_of", List.of(main.getId()))), null, null,
                null));
        rule("Everyone", new ConditionNode("and", false, List.of(
                new ConditionNode(null, null, null, "message.type", "is", "TEXT")), null, null, null));

        MovePreview p = service.preview(main.getId(), brandB.getId());

        assertThat(p.fromWorkspaceName()).isEqualTo("Brand A");
        assertThat(p.toWorkspaceName()).isEqualTo("Brand B");
        assertThat(p.chats()).isEqualTo(2);
        assertThat(p.activeRuns()).isEqualTo(1);
        assertThat(p.teamName()).isEqualTo("Sales");
        assertThat(p.archiveLabel()).isEqualTo("Main line (until 18 Nov)");
        assertThat(p.movingBack()).isFalse();
        assertThat(p.returningChats()).isZero();
        assertThat(p.rulesNamingNumber()).containsExactly(naming.getName());
    }

    @Test
    void previewOfMovingBackCountsTheReturningChats() {
        service.move(main.getId(), brandB.getId(), me);

        MovePreview p = service.preview(main.getId(), brandA.getId());

        assertThat(p.movingBack()).isTrue();
        assertThat(p.returningChats()).isEqualTo(2);
        assertThat(p.chats()).isZero();
        assertThat(p.teamName()).isNull();
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private void assertMovedOnce(PhoneNumberMove done) {
        PhoneNumber archive = onlyArchive(brandA);
        assertThat(done.getSourceArchiveId()).isEqualTo(archive.getId());
        assertThat(chats.findAll()).extracting(WaWebChat::getId).containsExactlyInAnyOrder(veenuChat.getId(),
                raviChat.getId());
        assertThat(chats.findAll()).extracting(WaWebChat::getPhoneNumberId).containsOnly(archive.getId());
        assertThat(messages.findAll()).hasSize((int) messageCount).extracting(WaWebMessage::getPhoneNumberId)
                .containsOnly(archive.getId());
        assertThat(done.getCounts()).isEqualTo(new PhoneNumberMove.Counts(2, messageCount, 1));
        PhoneNumber moved = numbers.findById(main.getId()).orElseThrow();
        assertThat(moved.getWorkspaceId()).isEqualTo(brandB.getId());
        assertThat(moved.getMovingSince()).isNull();
        assertThat(mongo.findById(run.getId(), ChatbotRun.class).getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
    }

    private static void assertCode(Runnable call, String code, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CodedException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.status().value()).isEqualTo(status);
        });
    }

    private List<PhoneNumber> archivesIn(Workspace w) {
        return numbers.findByArchiveOf(main.getId()).stream().filter(a -> a.getWorkspaceId().equals(w.getId()))
                .toList();
    }

    private PhoneNumber onlyArchive(Workspace w) {
        List<PhoneNumber> archives = archivesIn(w);
        assertThat(archives).hasSize(1);
        return archives.getFirst();
    }

    /** Every value at the dotted path in the collection, arrays flattened. */
    private List<Object> field(String collection, String path) {
        List<Object> out = new ArrayList<>();
        for (Document d : mongo.findAll(Document.class, collection)) collect(d, path.split("\\."), 0, out);
        return out;
    }

    private static void collect(Object node, String[] path, int i, List<Object> out) {
        if (node instanceof List<?> list) {
            list.forEach(e -> collect(e, path, i, out));
        } else if (i == path.length) {
            out.add(node);
        } else if (node instanceof Document d && d.containsKey(path[i])) {
            collect(d.get(path[i]), path, i + 1, out);
        }
    }

    private void incoming(Workspace w, String phone, String body) {
        ObjectNode p = mapper.createObjectNode();
        ObjectNode id = p.putObject("id");
        id.put("fromMe", false);
        id.put("remote", phone + "@c.us");
        id.put("id", "IN" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        p.put("fromMe", false);
        p.put("timestamp", clock.instant().getEpochSecond());
        p.put("body", body);
        publisher.publishEvent(new WhatsAppEvent(this, main.getId(), w.getId(), acme.getId(), "message_create", p,
                null, null));
    }

    private Contact contact(Workspace w, String name, String phone) {
        Contact c = new Contact();
        c.setWorkspaceId(w.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setPhone(phone);
        c.setWaIds(new ArrayList<>(List.of(phone + "@c.us")));
        c.setSource(ContactSource.CHAT);
        return contacts.save(c);
    }

    private WaWebChat chat(Workspace w, Contact contact, String phone) {
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(w.getId());
        c.setPhoneNumberId(main.getId());
        c.setRemoteId(phone + "@c.us");
        c.setContactId(contact.getId());
        c.setStatus(ChatStatus.OPEN);
        c.setAssignedTeamId(sales.getId());
        c.setLastMessageAt(NOW.minusSeconds(3600));
        return chats.save(c);
    }

    private WaWebMessage message(WaWebChat chat, String body) {
        WaWebMessage m = new WaWebMessage();
        m.setCustomerId(chat.getCustomerId());
        m.setWorkspaceId(chat.getWorkspaceId());
        m.setPhoneNumberId(chat.getPhoneNumberId());
        m.setChatId(chat.getId());
        m.setWaMessageId(UUID.randomUUID().toString());
        m.setDirection(MessageDirection.IN);
        m.setSource(MessageSource.CONTACT);
        m.setType("text");
        m.setBody(body);
        m.setTimestamp(NOW.minusSeconds(3600));
        return messages.save(m);
    }

    /** A chatbot waiting on Veenu's chat, which it holds. */
    private ChatbotRun waitingRun(WaWebChat chat) {
        ChatbotRun r = new ChatbotRun();
        r.setCustomerId(acme.getId());
        r.setWorkspaceId(brandA.getId());
        r.setChatbotId("missing-chatbot");
        r.setChatbotName("Welcome bot");
        r.setContactId(chat.getContactId());
        r.setChatId(chat.getId());
        r.setPhoneNumberId(main.getId());
        r.setStatus(ChatbotRunStatus.WAITING);
        r.setCreatedAt(NOW.minusSeconds(600));
        r = mongo.save(r);
        WaWebChat held = chats.findById(chat.getId()).orElseThrow();
        held.setStatus(ChatStatus.BOT);
        held.setAssignedTeamId(null);
        held.setChatbot(new WaWebChat.ActiveChatbot(r.getId(), "missing-chatbot", "Welcome bot", "Ask name"));
        veenuChat = chats.save(held);
        return r;
    }

    private void crmRecords(WaWebChat chat) {
        CrmContact c = new CrmContact();
        c.setWorkspaceId(brandA.getId());
        c.setCustomerId(acme.getId());
        c.setContactId(chat.getContactId());
        c.setChannel(new Channel(main.getId(), MAIN));
        c.setOrigin(new Origin(OriginKind.INBOUND_CHAT, main.getId(), chat.getId(), NOW.minusSeconds(3600), null,
                null));
        c.setChannelHistory(new ArrayList<>(List.of(new me.nageshk.omnichannel.crm.contact.ChannelChange(
                "earlier-number", main.getId(), "someone", NOW.minusSeconds(7200), null))));
        mongo.save(c);
        Document ws = new Document("workspaceId", brandA.getId());
        mongo.insert(new Document(ws).append("phoneNumberId", main.getId()).append("type", "MESSAGE_RECEIVED"),
                "crmActivities");
        mongo.insert(new Document(ws).append("phoneNumberId", main.getId()).append("body", "called"), "crmNotes");
        mongo.insert(new Document(ws).append("phoneNumberId", main.getId()).append("title", "call back"), "crmTasks");
        mongo.insert(new Document(ws).append("channel", new Document("phoneNumberId", main.getId())
                .append("number", MAIN)).append("name", "Course"), "crmDeals");
    }

    private void campaignRecords() {
        CampaignSender s = new CampaignSender();
        s.setPhoneNumberId(main.getId());
        s.setWorkspaceId(brandA.getId());
        mongo.save(s);
        Campaign c = new Campaign();
        c.setWorkspaceId(brandA.getId());
        c.setCustomerId(acme.getId());
        c.setName("Diwali");
        c.setNameKey("diwali");
        c.setSenderIds(new ArrayList<>(List.of(main.getId())));
        c = mongo.save(c);
        CampaignRecipient r = new CampaignRecipient();
        r.setCampaignId(c.getId());
        r.setWorkspaceId(brandA.getId());
        r.setPhone(RAVI);
        r.setSenderId(main.getId());
        r.setPreferredSenderId(main.getId());
        mongo.save(r);
        mongo.insert(new Document("workspaceId", brandA.getId()).append("token", UUID.randomUUID().toString())
                .append("chatId", raviChat.getId()).append("phoneNumberId", main.getId()), "messagePages");
    }

    private void webhookEvent() {
        Document metadata = new Document("phone_number_id", main.getId()).append("display_phone_number", MAIN);
        Document change = new Document("field", "messages").append("value", new Document("metadata", metadata));
        Document entry = new Document("id", brandA.getId()).append("changes", List.of(change));
        mongo.insert(new Document("eventKey", "message:" + UUID.randomUUID()).append("event",
                new Document("object", "whatsapp_web").append("entry", List.of(entry, new Document("id", "no changes")))),
                "channelWebhookEvents");
    }

    private void historyImport() {
        WaWebHistoryImport h = new WaWebHistoryImport();
        h.setCustomerId(acme.getId());
        h.setWorkspaceId(brandA.getId());
        h.setPhoneNumberId(main.getId());
        h.setScopeKey("ALL");
        h.setActive(true);
        h.setStatus(HistoryImportStatus.PAUSED);
        h = mongo.save(h);
        mongo.insert(new Document("importId", h.getId()).append("remoteId", VEENU + "@c.us")
                .append("phoneNumberId", main.getId()).append("status", "PENDING"), "waWebHistoryImportChats");
    }

    /** A message event of brand A still waiting for its retry, as one left by a failed rule run before the move. */
    private void pendingAutomationEvent(WaWebChat chat) {
        Document data = new Document("chat", new Document("id", chat.getId()))
                .append("number", new Document("id", main.getId()).append("number", MAIN).append("label", "Main line"))
                .append("contact", new Document("id", chat.getContactId()));
        mongo.insert(new Document("customerId", acme.getId()).append("workspaceId", brandA.getId())
                .append("kind", "MESSAGE_RECEIVED").append("data", data).append("status", "PENDING")
                .append("attempts", 1).append("notBefore", Instant.parse("2027-01-01T00:00:00Z"))
                .append("createdAt", NOW.minusSeconds(60)), "automationEvents");
    }

    private AutomationRule rule(String name, ConditionNode conditions) {
        AutomationRule r = new AutomationRule();
        r.setCustomerId(acme.getId());
        r.setWorkspaceId(brandA.getId());
        r.setName(name);
        r.setEvent(AutomationEventKind.MESSAGE_RECEIVED);
        r.setConditions(conditions);
        r.setActions(List.of());
        r.setStatus(RuleStatus.DRAFT);
        return rules.save(r);
    }
}
