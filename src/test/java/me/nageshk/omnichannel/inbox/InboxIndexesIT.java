package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class InboxIndexesIT {

    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        for (Class<?> c : List.of(WaWebChat.class, WaWebMessage.class, WaWebChatAction.class, InboxSetting.class,
                WaNumberLookup.class))
            mongo.remove(new Query(), c);
    }

    private WaWebChat chat(String phoneNumberId, String remoteId) {
        WaWebChat c = new WaWebChat();
        c.setCustomerId("c1");
        c.setWorkspaceId("w1");
        c.setPhoneNumberId(phoneNumberId);
        c.setRemoteId(remoteId);
        c.setStatus(ChatStatus.OPEN);
        return c;
    }

    private WaNumberLookup lookup(String workspaceId, String phone) {
        WaNumberLookup l = new WaNumberLookup();
        l.setWorkspaceId(workspaceId);
        l.setPhoneNumberId("p1");
        l.setPhone(phone);
        l.setRegistered(true);
        l.setCheckedAt(Instant.now());
        return l;
    }

    private WaWebMessage message(String phoneNumberId, String waMessageId) {
        WaWebMessage m = new WaWebMessage();
        m.setCustomerId("c1");
        m.setWorkspaceId("w1");
        m.setPhoneNumberId(phoneNumberId);
        m.setChatId("chat1");
        m.setWaMessageId(waMessageId);
        m.setDirection(MessageDirection.IN);
        m.setSource(MessageSource.CONTACT);
        m.setType("chat");
        m.setTimestamp(Instant.now());
        m.setStatus(MessageStatus.PENDING);
        return m;
    }

    @Test
    void chatIsUniquePerPhoneNumberAndRemoteId() {
        mongo.save(chat("p1", "911@c.us"));
        mongo.save(chat("p2", "911@c.us"));
        assertThatThrownBy(() -> mongo.save(chat("p1", "911@c.us"))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void messageIsUniquePerPhoneNumberAndWaMessageId() {
        mongo.save(message("p1", "true_911@c.us_A"));
        mongo.save(message("p2", "true_911@c.us_A"));
        assertThatThrownBy(() -> mongo.save(message("p1", "true_911@c.us_A"))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void settingIsUniquePerWorkspaceAndTeam() {
        InboxSetting a = new InboxSetting();
        a.setWorkspaceId("w1");
        mongo.save(a);
        InboxSetting b = new InboxSetting();
        b.setWorkspaceId("w1");
        assertThatThrownBy(() -> mongo.save(b)).isInstanceOf(DuplicateKeyException.class);
        InboxSetting c = new InboxSetting();
        c.setWorkspaceId("w1");
        c.setTeamId("t1");
        mongo.save(c);
        InboxSetting d = new InboxSetting();
        d.setWorkspaceId("w1");
        d.setTeamId("t1");
        assertThatThrownBy(() -> mongo.save(d)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void numberLookupIsUniquePerWorkspaceAndPhone() {
        mongo.save(lookup("w1", "919811111111"));
        mongo.save(lookup("w2", "919811111111"));
        assertThatThrownBy(() -> mongo.save(lookup("w1", "919811111111"))).isInstanceOf(DuplicateKeyException.class);
        IndexInfo index = mongo.indexOps(WaNumberLookup.class).getIndexInfo().stream()
                .filter(i -> i.getName().equals("ws_phone")).findFirst().orElseThrow();
        assertThat(index.isUnique()).isTrue();
        assertThat(keys(WaNumberLookup.class, "ws_phone")).containsExactly("workspaceId:ASC", "phone:ASC");
    }

    @Test
    void indexesAreNamedAsDesigned() {
        assertThat(names(WaWebChat.class)).contains("phone_remote_unique", "phone_last_message",
                "phone_user_last_message", "phone_team_last_message", "phone_contact", "workspace_contact");
        assertThat(names(WaWebMessage.class)).contains("phone_wamessage_unique", "chat_timestamp");
        assertThat(names(WaWebChatAction.class)).contains("chat_at");
        assertThat(names(InboxSetting.class)).contains("workspace_team_unique");
        assertThat(names(WaNumberLookup.class)).contains("ws_phone");
    }

    /** The lists sort by the time then {@code _id}, both newest first, so each index must end with both. */
    @Test
    void sortedIndexesCarryTheIdTieBreaker() {
        assertThat(keys(WaWebChat.class, "phone_last_message"))
                .containsExactly("phoneNumberId:ASC", "lastMessageAt:DESC", "_id:DESC");
        assertThat(keys(WaWebChat.class, "phone_user_last_message"))
                .containsExactly("phoneNumberId:ASC", "assignedUserId:ASC", "lastMessageAt:DESC", "_id:DESC");
        assertThat(keys(WaWebChat.class, "phone_team_last_message"))
                .containsExactly("phoneNumberId:ASC", "assignedTeamId:ASC", "lastMessageAt:DESC", "_id:DESC");
        assertThat(keys(WaWebMessage.class, "chat_timestamp"))
                .containsExactly("chatId:ASC", "timestamp:DESC", "_id:DESC");
        assertThat(keys(WaWebChatAction.class, "chat_at")).containsExactly("chatId:ASC", "at:DESC", "_id:DESC");
    }

    private List<String> keys(Class<?> type, String indexName) {
        return mongo.indexOps(type).getIndexInfo().stream().filter(i -> i.getName().equals(indexName)).findFirst()
                .orElseThrow().getIndexFields().stream()
                .map(f -> f.getKey() + ":" + (f.getDirection() == null ? "?" : f.getDirection().name())).toList();
    }

    private List<String> names(Class<?> type) {
        return mongo.indexOps(type).getIndexInfo().stream().map(IndexInfo::getName).toList();
    }
}
