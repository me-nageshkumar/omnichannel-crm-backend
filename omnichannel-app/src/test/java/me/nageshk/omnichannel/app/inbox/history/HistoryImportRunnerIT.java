package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.HistorySkipReason;
import me.nageshk.omnichannel.db.inbox.HistoryWaiting;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The history import runner with the default settings: one page per chat, no message cap in reach. */
class HistoryImportRunnerIT extends HistoryImportTestBase {

    static final String A = "919811100001@c.us";
    static final String B = "919811100002@c.us";

    static void seedTwoChats(HistoryDevice device) {
        device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "a1"), HistoryDevice.msg(A, true, 200, 3, "a2"),
                HistoryDevice.msg(A, false, 300, 1, "a3")));
        device.chats.put(B, List.of(HistoryDevice.msg(B, false, 150, 1, "b1"),
                HistoryDevice.msg(B, true, 250, 2, "b2")));
    }

    @Test
    void importedMessagesKeepWhatsAppsTime() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            String remote = "919811100001@c.us";
            device.chats.put(remote, List.of(HistoryDevice.msg(remote, false, 1_700_000_100L, 0, "oldest"),
                    HistoryDevice.msg(remote, false, 1_700_000_200L, 0, "middle"),
                    HistoryDevice.msg(remote, false, 1_700_000_300L, 0, "newest")));
            importAll(null, null);

            runner.tick().get(10, SECONDS);

            assertThat(messages.findAll()).extracting(m -> m.getTimestamp().getEpochSecond())
                    .containsExactlyInAnyOrder(1_700_000_100L, 1_700_000_200L, 1_700_000_300L);
            WaWebChat chat = chats.findAll().get(0);
            assertThat(chat.getLastMessageAt().getEpochSecond()).isEqualTo(1_700_000_300L);
            assertThat(chat.getLastMessage().text()).isEqualTo("newest");
        }
    }

    @Test
    void aFetchedPageIsStoredThroughTheConverter() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            // a voice note a person sent, which the phone has marked as played
            ObjectNode ptt = HistoryDevice.msg(A, false, 1_700_000_100L, 2, null).put("type", "ptt")
                    .put("directPath", "/v/t62/ptt").put("mimetype", "audio/ogg; codecs=opus").put("size", 8000);
            ptt.remove("body");
            device.chats.put(A, List.of(ptt));
            importAll(null, null);

            runner.tick().get(10, SECONDS);

            WaWebMessage stored = messages.findAll().getFirst();
            assertThat(stored.getType()).isEqualTo("ptt");
            assertThat(stored.getDirection()).isEqualTo(MessageDirection.IN);
            assertThat(stored.getMedia()).isNotNull();
            assertThat(stored.getMedia().mimetype()).isEqualTo("audio/ogg; codecs=opus");
            assertThat(stored.getMedia().size()).isEqualTo(8000L);
            // an inbound message has no delivery state, and an import records no status times
            assertThat(stored.getStatus()).isEqualTo(MessageStatus.PENDING);
            assertThat(stored.getStatusTimes()).isNull();
        }
    }

    @Test
    void importsAllChatsAndCompletes() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedTwoChats(device);
            WaWebHistoryImport imp = importAll(null, null);

            runner.tick().get(10, SECONDS);

            WaWebHistoryImport done = reload(imp);
            assertThat(done.getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(done.isActive()).isFalse();
            assertThat(done.getFinishedAt()).isNotNull();
            assertThat(itemsOf(imp)).hasSize(2).allSatisfy(i -> {
                assertThat(i.getStatus()).isEqualTo(HistoryChatStatus.DONE);
                assertThat(i.getFinishedAt()).isNotNull();
            });
            assertThat(messages.count()).isEqualTo(5);
            List<WaWebChat> all = chats.findAll();
            assertThat(all).hasSize(2).allSatisfy(c -> {
                assertThat(c.getStatus()).isEqualTo(ChatStatus.CLOSED);
                assertThat(c.getUnreadCount()).isZero();
            });
            assertThat(done.getCounts().messagesInserted()).isEqualTo(5);
            assertThat(done.getCounts().chatsTotal()).isEqualTo(2);
            assertThat(done.getCounts().chatsDone()).isEqualTo(2);
        }
    }

    @Test
    void secondRunInsertsNothing() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedTwoChats(device);
            importAll(null, null);
            runner.tick().get(10, SECONDS);

            WaWebHistoryImport again = importAll(null, null);
            runner.tick().get(10, SECONDS);

            WaWebHistoryImport done = reload(again);
            assertThat(done.getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(done.getCounts().messagesInserted()).isZero();
            assertThat(done.getCounts().messagesSeen()).isEqualTo(5);
            assertThat(messages.count()).isEqualTo(5);
        }
    }

    @Test
    void dateRangeIsPassedAndRespected() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "old"),
                    HistoryDevice.msg(A, false, 200, 1, "in"), HistoryDevice.msg(A, false, 300, 1, "new")));
            WaWebHistoryImport imp = importAll(Instant.ofEpochSecond(150), Instant.ofEpochSecond(250));

            runner.tick().get(10, SECONDS);

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            List<WaWebMessage> rows = messages.findAll();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getBody()).isEqualTo("in");
            JsonNode params = device.requests("fetchChatHistory").get(0).params();
            assertThat(params.get("from").asLong()).isEqualTo(150);
            assertThat(params.get("to").asLong()).isEqualTo(250);
        }
    }

    @Test
    void chatsScopeSkipsUnknownChat() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "a1")));
            WaWebHistoryImport imp = importChats("919811100001", "919811100002");

            runner.tick().get(10, SECONDS);

            WaWebHistoryImportChat present = item(imp, A);
            WaWebHistoryImportChat absent = item(imp, B);
            assertThat(present.getStatus()).isEqualTo(HistoryChatStatus.DONE);
            assertThat(absent.getStatus()).isEqualTo(HistoryChatStatus.SKIPPED);
            assertThat(absent.getSkipReason()).isEqualTo(HistorySkipReason.NO_CHAT);
            WaWebHistoryImport done = reload(imp);
            assertThat(done.getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(done.getCounts().chatsSkipped()).isEqualTo(1);
            assertThat(device.requests("listChats")).isEmpty();
        }
    }

    @Test
    void noDeviceSetsWaiting() throws Exception {
        WaWebHistoryImport imp = importChats("919811100001");

        runner.tick().get(10, SECONDS);

        WaWebHistoryImport waiting = reload(imp);
        assertThat(waiting.getStatus()).isEqualTo(HistoryImportStatus.QUEUED);
        assertThat(waiting.getWaiting()).isEqualTo(HistoryWaiting.NO_DEVICE);

        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "a1")));
            runner.tick().get(10, SECONDS);

            WaWebHistoryImport done = reload(imp);
            assertThat(done.getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(done.getWaiting()).isNull();
            assertThat(messages.count()).isEqualTo(1);
        }
    }

    @Test
    void cancelMidImportReleasesItem() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "a1")));
            device.override = req -> "fetchChatHistory".equals(req.method()) ? null : device.answer(req);
            WaWebHistoryImport imp = importChats("919811100001");

            CompletableFuture<Void> tick = runner.tick();
            awaitRequest(device, "fetchChatHistory");
            Thread.sleep(300);
            service.cancel(brand.getId(), imp.getId());
            tick.get(10, SECONDS);

            WaWebHistoryImport cancelled = reload(imp);
            assertThat(cancelled.getStatus()).isEqualTo(HistoryImportStatus.CANCELLED);
            assertThat(cancelled.isActive()).isFalse();
            assertThat(cancelled.getWaiting()).isNull();
            assertThat(item(imp, A).getStatus()).isEqualTo(HistoryChatStatus.PENDING);
            assertThat(messages.count()).isZero();
        }
    }

    @Test
    void oneImportPerNumberAtATime() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedTwoChats(device);
            WaWebHistoryImport first = importAll(null, null);
            WaWebHistoryImport second = importAll(Instant.ofEpochSecond(50), null);
            assertThat(first.getId()).isLessThan(second.getId());
            // same creation time, and the first stored after the second: only the id can order them
            Instant created = Instant.parse("2026-10-21T05:00:00Z");
            WaWebHistoryImport stored = reload(first);
            stored.setCreationTime(created);
            mongo.remove(stored);
            mongo.updateFirst(new Query(Criteria.where("_id").is(second.getId())),
                    new Update().set("creationTime", created), WaWebHistoryImport.class);
            mongo.insert(stored);
            assertThat(reload(first).getCreationTime()).isEqualTo(reload(second).getCreationTime());

            runner.tick().get(10, SECONDS);

            assertThat(reload(first).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(reload(second).getStatus()).isEqualTo(HistoryImportStatus.QUEUED);

            runner.tick().get(10, SECONDS);

            assertThat(reload(second).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(messages.count()).isEqualTo(5);
        }
    }

    @Test
    void timeoutsAreBoundedAndDoNotHoldUpOtherChats() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedTwoChats(device);
            // A sorts first and never answers; B answers
            device.override = req -> "fetchChatHistory".equals(req.method())
                    && A.equals(req.params().path("chatId").asString()) ? null : device.answer(req);
            WaWebHistoryImport imp = importChats("919811100001", "919811100002");

            runner.tick().get(10, SECONDS);

            WaWebHistoryImportChat a = item(imp, A);
            assertThat(a.getStatus()).isEqualTo(HistoryChatStatus.PENDING);
            assertThat(a.getTimeouts()).isEqualTo(1);
            assertThat(a.getAttemptedAt()).isNotNull();
            assertThat(item(imp, B).getStatus()).isEqualTo(HistoryChatStatus.PENDING);

            runner.tick().get(10, SECONDS);

            // B was never attempted, so it is claimed before A
            assertThat(item(imp, B).getStatus()).isEqualTo(HistoryChatStatus.DONE);
            assertThat(item(imp, A).getTimeouts()).isEqualTo(2);
            assertThat(item(imp, A).getStatus()).isEqualTo(HistoryChatStatus.PENDING);

            runner.tick().get(10, SECONDS);

            a = item(imp, A);
            assertThat(a.getStatus()).isEqualTo(HistoryChatStatus.FAILED);
            assertThat(a.getError()).isEqualTo("TIMEOUT");
            assertThat(a.getTimeouts()).isEqualTo(3);
            WaWebHistoryImport done = reload(imp);
            assertThat(done.getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(done.getCounts().chatsFailed()).isEqualTo(1);
        }
    }

    @Test
    void aDeviceThatAnswersNothingIsWaitedForNotChargedToTheChat() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedTwoChats(device);
            // connected, but the page answers nothing at all: not a page, not its state
            device.override = req -> null;
            WaWebHistoryImport imp = importChats("919811100001");

            for (int tick = 0; tick < 3; tick++) runner.tick().get(20, SECONDS);

            WaWebHistoryImportChat a = item(imp, A);
            assertThat(a.getStatus()).isEqualTo(HistoryChatStatus.PENDING);
            assertThat(a.getTimeouts()).isZero();
            WaWebHistoryImport waiting = reload(imp);
            assertThat(waiting.getStatus()).isEqualTo(HistoryImportStatus.RUNNING);
            assertThat(waiting.getWaiting()).isEqualTo(HistoryWaiting.WHATSAPP_NOT_READY);
            assertThat(device.requests("getState")).isNotEmpty();
        }
    }

    @Test
    void anAllChatsImportWhoseListingBrokeHalfWayIsListedAgain() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedTwoChats(device);
            WaWebHistoryImport imp = importAll(null, null);
            // the listing stored A, then the database failed before B
            WaWebHistoryImportChat a = new WaWebHistoryImportChat();
            a.setImportId(imp.getId());
            a.setWorkspaceId(imp.getWorkspaceId());
            a.setPhoneNumberId(imp.getPhoneNumberId());
            a.setRemoteId(A);
            a.setStatus(HistoryChatStatus.PENDING);
            a.setCounts(WaWebHistoryImportChat.Counts.ZERO);
            items.insert(a);

            runner.tick().get(10, SECONDS);

            assertThat(itemsOf(imp)).extracting(WaWebHistoryImportChat::getRemoteId).containsExactlyInAnyOrder(A, B);
            assertThat(itemsOf(imp)).allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(HistoryChatStatus.DONE));
            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
        }
    }

    @Test
    void aPageWhoseWhatsAppIsReconnectingIsWaitedForNotChargedToTheChat() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedTwoChats(device);
            // the page answers, but WhatsApp Web itself is not connected: its pages time out
            device.override = req -> switch (req.method()) {
                case "fetchChatHistory" -> null;
                case "getState" -> Envelope.response(req.id(),
                        tools.jackson.databind.node.JsonNodeFactory.instance.textNode("OPENING"));
                default -> device.answer(req);
            };
            WaWebHistoryImport imp = importChats("919811100001");

            runner.tick().get(20, SECONDS);

            assertThat(item(imp, A).getTimeouts()).isZero();
            assertThat(reload(imp).getWaiting()).isEqualTo(HistoryWaiting.WHATSAPP_NOT_READY);
        }
    }

    @Test
    void releaseStuckResetsRunningItems() {
        WaWebHistoryImport imp = importChats("919811100001");
        WaWebHistoryImportChat stuck = item(imp, A);
        stuck.setStatus(HistoryChatStatus.RUNNING);
        stuck.setAttemptedAt(Instant.now());
        stuck.setCursor(new WaWebHistoryImportChat.Cursor(123L, List.of("x")));
        items.save(stuck);

        assertThat(runner.releaseStuck()).isEqualTo(1);

        WaWebHistoryImportChat released = item(imp, A);
        assertThat(released.getStatus()).isEqualTo(HistoryChatStatus.PENDING);
        assertThat(released.getAttemptedAt()).isNull();
        assertThat(released.getCursor().beforeT()).isEqualTo(123L);
    }

    @Test
    void importStopsAtTheRequestTime() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            WaWebHistoryImport imp = importChats("919811100001");
            // creationTime comes from Mongo auditing (the system clock), so the messages are placed around it
            long created = reload(imp).getCreationTime().getEpochSecond();
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, created - 100, 1, "before"),
                    HistoryDevice.msg(A, false, created + 100, 1, "after")));

            runner.tick().get(10, SECONDS);

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(messages.findAll()).extracting(WaWebMessage::getBody).containsExactly("before");
            assertThat(device.requests("fetchChatHistory").get(0).params().get("to").asLong()).isEqualTo(created);
        }
    }

    @Test
    void timeoutCounterResetsAfterASuccessfulPage() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            // three full pages of 100: every page is answered only after a timeout
            List<ObjectNode> history = new ArrayList<>();
            for (int i = 0; i < 300; i++) history.add(HistoryDevice.msg(A, false, 1000 + i, 1, "m" + i));
            device.chats.put(A, history);
            AtomicInteger fetches = new AtomicInteger();
            device.override = req -> "fetchChatHistory".equals(req.method()) && fetches.getAndIncrement() % 2 == 0
                    ? null : device.answer(req);
            WaWebHistoryImport imp = importChats("919811100001");

            for (int tick = 0; tick < 6 && reload(imp).isActive(); tick++) runner.tick().get(20, SECONDS);

            WaWebHistoryImportChat a = item(imp, A);
            assertThat(a.getError()).isNull();
            assertThat(a.getStatus()).isEqualTo(HistoryChatStatus.DONE);
            assertThat(a.getTimeouts()).isZero();
            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(messages.count()).isEqualTo(300);
        }
    }

    @Test
    void runningItemLeftBehindIsReleasedOnNextTick() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "a1"),
                    HistoryDevice.msg(A, false, 200, 1, "a2"), HistoryDevice.msg(A, false, 300, 1, "a3")));
            WaWebHistoryImport imp = importChats("919811100001");
            mongo.updateFirst(new Query(Criteria.where("_id").is(imp.getId())),
                    new Update().set("status", HistoryImportStatus.RUNNING), WaWebHistoryImport.class);
            // a DB blip after a page left the item RUNNING with its cursor
            WaWebHistoryImportChat stuck = item(imp, A);
            stuck.setStatus(HistoryChatStatus.RUNNING);
            stuck.setAttemptedAt(Instant.now());
            stuck.setCursor(new WaWebHistoryImportChat.Cursor(250L, List.of()));
            items.save(stuck);

            runner.tick().get(10, SECONDS);

            assertThat(item(imp, A).getStatus()).isEqualTo(HistoryChatStatus.DONE);
            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(device.requests("fetchChatHistory").get(0).params().get("before").asLong()).isEqualTo(250);
            assertThat(messages.findAll()).extracting(WaWebMessage::getBody).containsExactlyInAnyOrder("a1", "a2");
        }
    }

    WaWebHistoryImportChat item(WaWebHistoryImport imp, String remoteId) {
        return itemsOf(imp).stream().filter(i -> remoteId.equals(i.getRemoteId())).findFirst().orElseThrow();
    }

    static void awaitRequest(HistoryDevice device, String method) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (device.requests(method).isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertThat(device.requests(method)).isNotEmpty();
    }

    static List<String> ids(Envelope request) {
        return request.params().path("excludeIds").valueStream().map(JsonNode::asString).toList();
    }
}
