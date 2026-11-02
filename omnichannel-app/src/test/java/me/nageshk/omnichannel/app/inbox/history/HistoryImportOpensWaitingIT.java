package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.A;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.InboxSetting;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.service.inbox.InboxSettingService;
import me.nageshk.omnichannel.service.inbox.RoutingSetting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;

/**
 * A chat a fetch creates lands in Open, and is routed like a new chat, when it waits on us: unread on the phone or
 * last written by the contact, the contact wrote within the last 7 days, and it is not a business. Else it stays
 * Closed and unassigned, and a chat that already existed keeps its state.
 */
class HistoryImportOpensWaitingIT extends HistoryImportTestBase {

    @Autowired TeamRepository teams;
    @Autowired PhoneNumberRepository phoneNumbers;
    @Autowired InboxSettingService settings;

    /** Seconds since the epoch, {@code ago} before the clock. */
    private long ago(Duration ago) {
        return START.minus(ago).getEpochSecond();
    }

    @AfterEach
    void cleanUpRouting() {
        mongo.remove(new Query(), Team.class);
        mongo.remove(new Query(), InboxSetting.class);
    }

    /** A team with {@code agent} owns {@code n1} and routes round robin. */
    private void routedToAgent() {
        Team t = new Team();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(brand.getId());
        t.setName("Support");
        t.setMemberIds(new ArrayList<>(List.of(agent.getId())));
        t = teams.save(t);
        PhoneNumber number = phoneNumbers.findById(n1.getId()).orElseThrow();
        number.setTeamId(t.getId());
        phoneNumbers.save(number);
        settings.putOverride(brand.getId(), t.getId(),
                new RoutingSetting.Request(true, true, null, null, null, null));
    }

    private void fetchAll() throws Exception {
        importAll(null, null);
        runner.tick().get(10, SECONDS);
    }

    private WaWebChat theChat() {
        assertThat(chats.count()).isEqualTo(1);
        return chats.findAll().getFirst();
    }

    private List<WaWebChatAction> actions() {
        return mongo.find(new Query(), WaWebChatAction.class);
    }

    @Test
    void aChatUnreadOnThePhoneOpensAndIsRouted() throws Exception {
        routedToAgent();
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, ago(Duration.ofHours(2)), 1, "is it in stock?")));
            device.unread.put(A, 2);

            fetchAll();

            WaWebChat chat = theChat();
            assertThat(chat.getStatus()).isEqualTo(ChatStatus.OPEN);
            assertThat(chat.getUnreadCount()).isEqualTo(2);
            assertThat(chat.getAssignedUserId()).isEqualTo(agent.getId());
            assertThat(actions()).singleElement().satisfies(a -> {
                assertThat(a.getType()).isEqualTo(ActionType.ROUTED);
                assertThat(a.getReason()).isEqualTo(RoutedReason.FETCHED);
            });
        }
    }

    @Test
    void aChatTheContactWroteLastOpensThoughReadOnThePhone() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, true, ago(Duration.ofDays(2)), 3, "here is the quote"),
                    HistoryDevice.msg(A, false, ago(Duration.ofDays(1)), 1, "thanks, one question")));

            fetchAll();

            WaWebChat chat = theChat();
            assertThat(chat.getStatus()).isEqualTo(ChatStatus.OPEN);
            assertThat(chat.getUnreadCount()).isZero();
            // no team owns the number: open, waiting for anyone to claim it
            assertThat(chat.getAssignedUserId()).isNull();
        }
    }

    @Test
    void aChatWeAnsweredLastStaysClosed() throws Exception {
        routedToAgent();
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, ago(Duration.ofHours(3)), 1, "price?"),
                    HistoryDevice.msg(A, true, ago(Duration.ofHours(2)), 3, "Rs 500")));

            fetchAll();

            WaWebChat chat = theChat();
            assertThat(chat.getStatus()).isEqualTo(ChatStatus.CLOSED);
            assertThat(chat.getAssignedUserId()).isNull();
            assertThat(actions()).isEmpty();
        }
    }

    @Test
    void aChatTheContactLastWroteToOverAWeekAgoStaysClosed() throws Exception {
        routedToAgent();
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, ago(Duration.ofDays(8)), 1, "hello?")));
            device.unread.put(A, 1);

            fetchAll();

            assertThat(theChat().getStatus()).isEqualTo(ChatStatus.CLOSED);
            assertThat(actions()).isEmpty();
        }
    }

    @Test
    void aBusinessChatStaysClosed() throws Exception {
        routedToAgent();
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, ago(Duration.ofHours(1)), 1, "Your OTP is 1234")));
            device.unread.put(A, 1);
            device.business.add(A);

            fetchAll();

            WaWebChat chat = theChat();
            assertThat(chat.getStatus()).isEqualTo(ChatStatus.CLOSED);
            assertThat(chat.getUnreadCount()).isZero();
            assertThat(actions()).isEmpty();
        }
    }

    @Test
    void aChatThatExistedKeepsItsState() throws Exception {
        routedToAgent();
        WaWebChat closed = new WaWebChat();
        closed.setCustomerId(acme.getId());
        closed.setWorkspaceId(brand.getId());
        closed.setPhoneNumberId(n1.getId());
        closed.setRemoteId(A);
        closed.setStatus(ChatStatus.CLOSED);
        chats.save(closed);
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, ago(Duration.ofHours(1)), 1, "are you there?")));
            device.unread.put(A, 1);

            fetchAll();

            WaWebChat chat = theChat();
            assertThat(chat.getStatus()).isEqualTo(ChatStatus.CLOSED);
            assertThat(chat.getAssignedUserId()).isNull();
            assertThat(actions()).isEmpty();
        }
    }

    @Test
    void aSecondFetchDoesNotOpenAChatAgain() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, ago(Duration.ofHours(1)), 1, "hello")));
            device.unread.put(A, 1);
            fetchAll();
            WaWebChat chat = theChat();
            assertThat(chat.getStatus()).isEqualTo(ChatStatus.OPEN);
            chat.setStatus(ChatStatus.CLOSED);
            chats.save(chat);

            fetchAll();

            assertThat(theChat().getStatus()).isEqualTo(ChatStatus.CLOSED);
        }
    }
}
