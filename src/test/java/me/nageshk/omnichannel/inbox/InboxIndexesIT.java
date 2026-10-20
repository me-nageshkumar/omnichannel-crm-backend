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
        for (Class<?> c : List.of(WaWebChat.class, WaWebMessage.class, WaWebChatAction.class, InboxSetting.class))
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
    void settingIsUniquePerPhoneNumber() {
        InboxSetting a = new InboxSetting();
        a.setWorkspaceId("w1");
        a.setPhoneNumberId("p1");
        mongo.save(a);
        InboxSetting b = new InboxSetting();
        b.setWorkspaceId("w1");
        b.setPhoneNumberId("p1");
        assertThatThrownBy(() -> mongo.save(b)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void indexesAreNamedAsDesigned() {
        assertThat(names(WaWebChat.class)).contains("phone_remote_unique", "phone_last_message",
                "phone_user_last_message", "phone_team_last_message", "workspace_contact");
        assertThat(names(WaWebMessage.class)).contains("phone_wamessage_unique", "chat_timestamp");
        assertThat(names(WaWebChatAction.class)).contains("chat_at");
        assertThat(names(InboxSetting.class)).contains("phone_unique");
    }

    private List<String> names(Class<?> type) {
        return mongo.indexOps(type).getIndexInfo().stream().map(IndexInfo::getName).toList();
    }
}
