package me.nageshk.omnichannel.app.inbox.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportRepository;
import me.nageshk.omnichannel.service.inbox.InboxCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class HistoryImportIndexesIT {

    @Autowired MongoTemplate mongo;
    @Autowired WaWebHistoryImportRepository imports;
    @Autowired WaWebHistoryImportChatRepository items;
    @Autowired InboxCleanup cleanup;

    @BeforeEach
    @AfterEach
    void clean() {
        mongo.remove(new Query(), WaWebHistoryImport.class);
        mongo.remove(new Query(), WaWebHistoryImportChat.class);
    }

    private WaWebHistoryImport job(String phoneNumberId, String scopeKey, boolean active) {
        WaWebHistoryImport j = new WaWebHistoryImport();
        j.setCustomerId("c1");
        j.setWorkspaceId("w1");
        j.setPhoneNumberId(phoneNumberId);
        j.setScopeKey(scopeKey);
        j.setActive(active);
        j.setStatus(HistoryImportStatus.QUEUED);
        j.setCounts(WaWebHistoryImport.Counts.ZERO);
        return j;
    }

    private WaWebHistoryImportChat item(String importId, String phoneNumberId, String remoteId) {
        WaWebHistoryImportChat i = new WaWebHistoryImportChat();
        i.setImportId(importId);
        i.setWorkspaceId("w1");
        i.setPhoneNumberId(phoneNumberId);
        i.setRemoteId(remoteId);
        i.setStatus(HistoryChatStatus.PENDING);
        i.setCounts(WaWebHistoryImportChat.Counts.ZERO);
        return i;
    }

    @Test
    void secondActiveIdenticalImportIsRejected() {
        mongo.save(job("p1", "ALL", true));
        assertThatThrownBy(() -> mongo.save(job("p1", "ALL", true))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void inactiveDuplicateIsAllowed() {
        mongo.save(job("p1", "ALL", false));
        mongo.save(job("p1", "ALL", true));
        assertThat(mongo.findAll(WaWebHistoryImport.class)).hasSize(2);
    }

    @Test
    void chatItemsUniquePerImport() {
        mongo.save(item("i1", "p1", "1@c.us"));
        assertThatThrownBy(() -> mongo.save(item("i1", "p1", "1@c.us"))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void cleanupDeletesImports() {
        WaWebHistoryImport j = imports.save(job("p1", "ALL", true));
        items.save(item(j.getId(), "p1", "1@c.us"));
        cleanup.deleteForNumber("p1");
        assertThat(imports.findAll()).isEmpty();
        assertThat(items.findAll()).isEmpty();
    }
}
