package me.nageshk.omnichannel.app.inbox.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.MessageStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import me.nageshk.omnichannel.service.inbox.InboxIngestService;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportContext;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportOutcome;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
class ImportMessageIT {

    private static final String REMOTE = "919911111111@c.us";

    /** Collects every Spring event of the inbox. */
    static class Recorder {
        final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(InboxEvents.ChatCreated e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.ChatReopened e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.CampaignChatFirstReply e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.MessageUpserted e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.ChatChanged e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.ActionRecorded e) {
            events.add(e);
        }
    }

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        Recorder importRecorder() {
            return new Recorder();
        }
    }

    @Autowired InboxIngestService ingest;
    @Autowired ContactRepository contacts;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebMessageRepository messages;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired Recorder recorder;

    private final ImportContext ctx = new ImportContext("cust-1", "ws-1", "pn-1");

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (Class<?> c : List.of(WaWebChat.class, WaWebMessage.class, WaWebChatAction.class, Contact.class))
            mongo.remove(new Query(), c);
        recorder.events.clear();
    }

    private ObjectNode msg(String id, String remote, boolean fromMe, long t, int ack, String body) {
        ObjectNode p = mapper.createObjectNode();
        ObjectNode idNode = p.putObject("id");
        idNode.put("_serialized", id);
        idNode.put("remote", remote);
        idNode.put("fromMe", fromMe);
        p.put("fromMe", fromMe);
        p.put("body", body);
        p.put("type", "chat");
        p.put("t", t);
        p.put("ack", ack);
        return p;
    }

    private ObjectNode call(String id, String subtype) {
        ObjectNode p = msg(id, "919811100001@c.us", false, 100, 0, null).put("type", "call_log");
        p.remove("body");
        if (subtype != null) p.put("subtype", subtype);
        return p;
    }

    private ObjectNode poll(String id, long votedAt) {
        ObjectNode p = msg(id, "919811100001@c.us", true, 100, 1, null).put("type", "poll_creation")
                .put("pollName", "Lunch?");
        p.putArray("pollOptions").addObject().put("name", "Yes").put("localId", 0);
        ObjectNode vote = p.putArray("pollVotes").addObject().put("pollMessageId", id).put("voter", "1@lid")
                .put("fromMe", false).put("at", votedAt);
        vote.putArray("options").add(0);
        return p;
    }

    private WaWebChat onlyChat() {
        assertThat(chats.count()).isEqualTo(1);
        return chats.findAll().getFirst();
    }

    private WaWebMessage onlyMessage() {
        assertThat(messages.count()).isEqualTo(1);
        return messages.findAll().getFirst();
    }

    @Test
    void aNoticeIsNotImported() {
        ObjectNode p = msg("n1", "919811100001@c.us", false, 100, 0, null).put("type", "notification_template");
        assertThat(ingest.importMessage(ctx, p).outcome()).isEqualTo(ImportOutcome.SKIPPED);
        assertThat(messages.count()).isZero();
    }

    @Test
    void aCallIsStoredWithWhatIsKnownOfIt() {
        ingest.importMessage(ctx, call("c1", "miss"));
        assertThat(onlyMessage().getCall()).isEqualTo(new WaWebMessage.Call(false, true, null));
    }

    @Test
    void aCallStoredBeforeCallsWereReadIsFilledIn() {
        ingest.importMessage(ctx, call("c1", "miss"));
        WaWebMessage stored = onlyMessage();
        stored.setCall(null);
        messages.save(stored);

        ImportResult r = ingest.importMessage(ctx, call("c1", "miss"));

        assertThat(r.outcome()).isEqualTo(ImportOutcome.UPDATED);
        assertThat(onlyMessage().getCall()).isEqualTo(new WaWebMessage.Call(false, true, null));
    }

    @Test
    void aCallAlreadyFilledInIsUnchanged() {
        ingest.importMessage(ctx, call("c1", "miss"));
        assertThat(ingest.importMessage(ctx, call("c1", "miss")).outcome()).isEqualTo(ImportOutcome.UNCHANGED);
    }

    @Test
    void aFetchedPollBringsItsVotes() {
        ingest.importMessage(ctx, poll("p1", 5000));
        assertThat(onlyMessage().getPollVotes()).extracting(WaWebMessage.PollVote::voter).containsExactly("1@lid");
    }

    @Test
    void aFetchOfAStoredPollAddsItsVotes() {
        ObjectNode bare = poll("p1", 5000);
        bare.remove("pollVotes");
        ingest.importMessage(ctx, bare);

        ImportResult r = ingest.importMessage(ctx, poll("p1", 5000));

        assertThat(r.outcome()).isEqualTo(ImportOutcome.UPDATED);
        assertThat(onlyMessage().getPollVotes()).hasSize(1);
    }

    @Test
    void aFetchOfAPollWithTheSameVotesIsUnchanged() {
        ingest.importMessage(ctx, poll("p1", 5000));
        assertThat(ingest.importMessage(ctx, poll("p1", 5000)).outcome()).isEqualTo(ImportOutcome.UNCHANGED);
    }

    @Test
    void anOlderFetchDoesNotUndoALaterVote() {
        ingest.importMessage(ctx, poll("p1", 9000));
        ingest.importMessage(ctx, poll("p1", 5000));
        assertThat(onlyMessage().getPollVotes().getFirst().at()).isEqualTo(java.time.Instant.ofEpochMilli(9000));
    }

    @Test
    void insertsMessageAndCreatesClosedChat() {
        ImportResult r = ingest.importMessage(ctx, msg("m1", REMOTE, false, 100, 0, "hello"));

        assertThat(r.outcome()).isEqualTo(ImportOutcome.INSERTED);
        WaWebChat chat = onlyChat();
        assertThat(chat.getStatus()).isEqualTo(ChatStatus.CLOSED);
        assertThat(chat.getUnreadCount()).isZero();
        assertThat(chat.getLastMessage().text()).isEqualTo("hello");
        assertThat(chat.getLastInboundAt()).isEqualTo(Instant.ofEpochSecond(100));
        WaWebMessage m = onlyMessage();
        assertThat(m.getSource()).isEqualTo(MessageSource.CONTACT);
        assertThat(m.getDirection()).isEqualTo(MessageDirection.IN);
        assertThat(m.getStatusTimes()).isNull();
        assertThat(m.getStatus()).isEqualTo(MessageStatus.PENDING);
        assertThat(contacts.findByWorkspaceIdAndPhone("ws-1", "919911111111")).isPresent();
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void secondRunIsUnchanged() {
        ObjectNode p = msg("m1", REMOTE, false, 100, 0, "hello");
        ingest.importMessage(ctx, p);

        ImportResult r = ingest.importMessage(ctx, p);

        assertThat(r.outcome()).isEqualTo(ImportOutcome.UNCHANGED);
        assertThat(messages.count()).isEqualTo(1);
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void higherAckUpdatesStatusWithoutTimes() {
        ingest.importMessage(ctx, msg("m1", REMOTE, true, 100, 1, "hello"));

        ImportResult r = ingest.importMessage(ctx, msg("m1", REMOTE, true, 100, 3, "hello"));

        assertThat(r.outcome()).isEqualTo(ImportOutcome.UPDATED);
        WaWebMessage m = onlyMessage();
        assertThat(m.getStatus()).isEqualTo(MessageStatus.READ);
        assertThat(m.getStatusTimes()).isNull();
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void liveStatusIsKeptWhenImportSeesLower() {
        ingest.ingestMessage(new WhatsAppEvent(this, "pn-1", "ws-1", "cust-1", "message_create",
                msg("m1", REMOTE, true, 100, 3, "hello"), null, null));
        Instant readAt = onlyMessage().getStatusTimes().getReadAt();
        recorder.events.clear();

        ImportResult r = ingest.importMessage(ctx, msg("m1", REMOTE, true, 100, 1, "hello"));

        assertThat(r.outcome()).isEqualTo(ImportOutcome.UNCHANGED);
        WaWebMessage m = onlyMessage();
        assertThat(m.getStatus()).isEqualTo(MessageStatus.READ);
        assertThat(readAt).isNotNull();
        assertThat(m.getStatusTimes().getReadAt()).isEqualTo(readAt);
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void aFetchKeepsWhoSentAStoredMessage() {
        ingest.importMessage(ctx, msg("m1", REMOTE, true, 100, 1, "hello"));
        WaWebMessage stored = onlyMessage();
        stored.setSource(MessageSource.CONSOLE);
        stored.setSentByUserId("user-7");
        stored.setSentByName("Asha");
        messages.save(stored);

        ImportResult r = ingest.importMessage(ctx, msg("m1", REMOTE, true, 100, 3, "hello again"));

        assertThat(r.outcome()).isEqualTo(ImportOutcome.UPDATED);
        WaWebMessage m = onlyMessage();
        assertThat(m.getSource()).isEqualTo(MessageSource.CONSOLE);
        assertThat(m.getSentByUserId()).isEqualTo("user-7");
        assertThat(m.getSentByName()).isEqualTo("Asha");
        assertThat(m.getBody()).isEqualTo("hello");
        assertThat(m.getStatus()).isEqualTo(MessageStatus.READ);
    }

    @Test
    void anAssignedChatStaysWithItsAgent() {
        WaWebChat open = new WaWebChat();
        open.setCustomerId("cust-1");
        open.setWorkspaceId("ws-1");
        open.setPhoneNumberId("pn-1");
        open.setRemoteId(REMOTE);
        open.setStatus(ChatStatus.OPEN);
        open.setAssignedUserId("agent-1");
        open.setAssignedTeamId("team-1");
        chats.save(open);

        ingest.importMessage(ctx, msg("m1", REMOTE, false, 100, 0, "hello"));

        WaWebChat chat = onlyChat();
        assertThat(chat.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(chat.getAssignedUserId()).isEqualTo("agent-1");
        assertThat(chat.getAssignedTeamId()).isEqualTo("team-1");
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void severalContactCardsAreStoredAsTheirCards() {
        ObjectNode p = msg("v1", REMOTE, false, 100, 0, "").put("type", "multi_vcard");
        p.putArray("vcardList").add(mapper.createObjectNode().put("vcard", "BEGIN:VCARD\nFN:A\nEND:VCARD"))
                .add(mapper.createObjectNode().put("vcard", "BEGIN:VCARD\nFN:B\nEND:VCARD"));

        ingest.importMessage(ctx, p);

        assertThat(onlyMessage().getBody()).isEqualTo("BEGIN:VCARD\nFN:A\nEND:VCARD\nBEGIN:VCARD\nFN:B\nEND:VCARD");
        assertThat(onlyChat().getLastMessage().text()).isEqualTo("👤 2 contacts");
    }

    @Test
    void existingOpenChatKeepsStatusAndUnread() {
        WaWebChat open = new WaWebChat();
        open.setCustomerId("cust-1");
        open.setWorkspaceId("ws-1");
        open.setPhoneNumberId("pn-1");
        open.setRemoteId(REMOTE);
        open.setStatus(ChatStatus.OPEN);
        open.setUnreadCount(4);
        chats.save(open);

        ingest.importMessage(ctx, msg("m1", REMOTE, false, 100, 0, "hello"));

        WaWebChat chat = onlyChat();
        assertThat(chat.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(chat.getUnreadCount()).isEqualTo(4);
        assertThat(chat.getLastMessageAt()).isEqualTo(Instant.ofEpochSecond(100));
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void olderMessageDoesNotOverwritePreview() {
        ingest.importMessage(ctx, msg("m2", REMOTE, false, 200, 0, "new"));
        ingest.importMessage(ctx, msg("m1", REMOTE, false, 100, 0, "old"));

        WaWebChat chat = onlyChat();
        assertThat(chat.getLastMessage().text()).isEqualTo("new");
        assertThat(chat.getLastMessageAt()).isEqualTo(Instant.ofEpochSecond(200));
        assertThat(messages.count()).isEqualTo(2);
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void importDoesNotRenameAContactThatHasAName() {
        Contact asha = new Contact();
        asha.setWorkspaceId("ws-1");
        asha.setCustomerId("cust-1");
        asha.setPhone("919911111111");
        asha.setName("Asha");
        asha.setSource(ContactSource.CHAT);
        contacts.save(asha);
        ObjectNode old = msg("m1", REMOTE, false, 100, 0, "hello");
        old.put("notifyName", "Old Name");

        ingest.importMessage(ctx, old);

        assertThat(contacts.findByWorkspaceIdAndPhone("ws-1", "919911111111").orElseThrow().getName())
                .isEqualTo("Asha");
    }

    @Test
    void importNamesANamelessContact() {
        ObjectNode old = msg("m1", REMOTE, false, 100, 0, "hello");
        old.put("notifyName", "Old Name");

        ingest.importMessage(ctx, old);

        assertThat(contacts.findByWorkspaceIdAndPhone("ws-1", "919911111111").orElseThrow().getName())
                .isEqualTo("Old Name");
    }

    @Test
    void importCreatedChatIsReopenedAndRoutedByALaterLiveMessage() {
        ingest.importMessage(ctx, msg("m1", REMOTE, false, 100, 0, "old"));
        assertThat(onlyChat().getStatus()).isEqualTo(ChatStatus.CLOSED);

        ingest.ingestMessage(new WhatsAppEvent(this, "pn-1", "ws-1", "cust-1", "message_create",
                msg("m2", REMOTE, false, 200, 0, "new"), null, null));

        WaWebChat chat = onlyChat();
        assertThat(chat.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(chat.getUnreadCount()).isEqualTo(1);
        assertThat(chat.getLastMessage().text()).isEqualTo("new");
        assertThat(recorder.events).hasAtLeastOneElementOfType(InboxEvents.ChatReopened.class)
                .hasAtLeastOneElementOfType(InboxEvents.MessageUpserted.class);
        assertThat(recorder.events).noneMatch(e -> e instanceof InboxEvents.ChatCreated);
    }

    @Test
    void skipsGroupsAndMissingIds() {
        ImportResult group = ingest.importMessage(ctx, msg("g1", "120363000000000000@g.us", false, 100, 0, "hi"));
        ObjectNode noId = msg("m1", REMOTE, false, 100, 0, "hi");
        noId.remove("id");
        ImportResult missing = ingest.importMessage(ctx, noId);

        assertThat(group.outcome()).isEqualTo(ImportOutcome.SKIPPED);
        assertThat(group.chat()).isNull();
        assertThat(group.message()).isNull();
        assertThat(missing.outcome()).isEqualTo(ImportOutcome.SKIPPED);
        assertThat(chats.count()).isZero();
        assertThat(messages.count()).isZero();
        assertThat(contacts.count()).isZero();
        assertThat(recorder.events).isEmpty();
    }
}
