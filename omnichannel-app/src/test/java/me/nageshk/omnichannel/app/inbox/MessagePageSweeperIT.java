package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessages;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.page.MessagePage;
import me.nageshk.omnichannel.db.page.MessagePageRepository;
import me.nageshk.omnichannel.service.page.MessagePageService;
import me.nageshk.omnichannel.service.page.MessagePageSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

/** Spec §5.3: an expired page's preview image is swept; a live page keeps its og.png. */
class MessagePageSweeperIT extends InboxApiTestBase {

    @Autowired MessagePageService pages;
    @Autowired MessagePageRepository repo;
    @Autowired CloudMessages cloud;
    @Autowired MessagePageSweeper sweeper;
    @Autowired @Qualifier("messagePagePreviewsGridFs") GridFsTemplate previews;

    MessagePage page(String phone) throws Exception {
        WaWebChat chat = chat("Asha", phone, 1);
        return pages.drawPreview(pages.create(chat, cloud.parse(mapper.readTree(MessagePageIT.BUTTON))));
    }

    @Test
    void sweepRemovesExpiredPreviewsAndKeepsLiveOnes() throws Exception {
        MessagePage live = page("919900000301");
        MessagePage expired = page("919900000302");
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        repo.save(expired);

        int swept = pages.sweepExpiredPreviews(Instant.now());

        assertThat(swept).isEqualTo(1);
        assertThat(previews.findOne(new Query(Criteria.where("metadata.pageId").is(expired.getId())))).isNull();
        assertThat(previews.findOne(new Query(Criteria.where("metadata.pageId").is(live.getId())))).isNotNull();
        MessagePage after = repo.findById(expired.getId()).orElseThrow();
        assertThat(after.isPreviewReady()).isFalse();
        assertThat(after.getPreviewFileId()).isNull();
        mvc.perform(get("/t/" + expired.getToken() + "/og.png")).andExpect(status().isNotFound());
        mvc.perform(get("/t/" + live.getToken() + "/og.png")).andExpect(status().isOk());
        assertThat(pages.sweepExpiredPreviews(Instant.now())).isZero();
    }

    @Test
    void scheduledSweepRunsBothSweeps() throws Exception {
        MessagePage expired = page("919900000303");
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        repo.save(expired);
        sweeper.sweep();
        assertThat(repo.findById(expired.getId()).orElseThrow().isPreviewReady()).isFalse();
    }
}
