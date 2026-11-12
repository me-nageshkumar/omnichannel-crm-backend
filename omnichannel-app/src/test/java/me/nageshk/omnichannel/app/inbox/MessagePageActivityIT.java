package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessages;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.page.MessagePage;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import me.nageshk.omnichannel.service.page.MessagePageService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** Spec §5.7: a page's first open and each distinct action reach the CRM contact's timeline, once each. */
class MessagePageActivityIT extends InboxApiTestBase {

    @Autowired MessagePageService pages;
    @Autowired CloudMessages cloud;
    @Autowired ApplicationEventPublisher publisher;

    @BeforeEach
    void cleanCrm() {
        for (String name : mongo.getCollectionNames()) if (name.startsWith("crm")) mongo.remove(new Query(), name);
    }

    void enableCrm() throws Exception {
        String staff = testUsers.tokenFor("staff");
        send(put("/api/customers/" + acme.getId() + "/workspaces/" + brand.getId() + "/crm"), staff, Map.of("enabled", true))
                .andExpect(status().is2xxSuccessful());
    }

    String capture(WaWebChat chat) throws Exception {
        JsonNode res = json(send(post(inbox().replace("/inbox", "") + "/crm/contacts/capture"), managerToken,
                Map.of("chatId", chat.getId())).andExpect(status().isOk()));
        return res.get("contact").get("id").asString();
    }

    MessagePage page(WaWebChat chat) throws Exception {
        return pages.drawPreview(pages.create(chat, cloud.parse(mapper.readTree(MessagePageIT.BUTTON))));
    }

    void beacon(String token, String body) throws Exception {
        mvc.perform(post("/t/" + token + "/e").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    List<Document> activities(String type) {
        return mongo.find(new Query(Criteria.where("workspaceId").is(brand.getId()).and("type").is(type)),
                Document.class, "crmActivities");
    }

    @Test
    void firstOpenAndEachDistinctActionWriteOneActivity() throws Exception {
        enableCrm();
        WaWebChat chat = chat("Asha", "919900000201", 1);
        String crmContactId = capture(chat);
        MessagePage p = page(chat);
        beacon(p.getToken(), "{\"k\":\"open\"}");
        beacon(p.getToken(), "{\"k\":\"open\"}");
        beacon(p.getToken(), "{\"k\":\"reply\",\"id\":\"change-button\"}");
        beacon(p.getToken(), "{\"k\":\"reply\",\"id\":\"change-button\"}");
        beacon(p.getToken(), "{\"k\":\"reply\",\"id\":\"cancel-button\"}");

        List<Document> opened = activities("MESSAGE_PAGE_OPENED");
        assertThat(opened).hasSize(1);
        Document o = opened.get(0);
        assertThat(o.get("target", Document.class).getString("id")).isEqualTo(crmContactId);
        assertThat(o.getString("actorType")).isEqualTo("SYSTEM");
        assertThat(o.getString("summary")).isEqualTo("Opened the message page");
        assertThat(o.getString("phoneNumberId")).isEqualTo(number.getId());
        Document payload = o.get("payload", Document.class);
        assertThat(payload.getString("channelAccountId")).isEqualTo(number.getId());
        assertThat(payload.getString("channelKind")).isEqualTo("WHATSAPP_WEB");

        List<Document> acted = activities("MESSAGE_PAGE_ACTION");
        assertThat(acted).extracting(a -> a.getString("summary")).containsExactlyInAnyOrder("Tapped \"Change\"", "Tapped \"Cancel\"");
        Document change = acted.stream().filter(a -> a.getString("summary").contains("Change")).findFirst().orElseThrow();
        Document cp = change.get("payload", Document.class);
        assertThat(cp.getString("action")).isEqualTo("BUTTON_REPLY");
        assertThat(cp.getString("id")).isEqualTo("change-button");
        assertThat(cp.getString("title")).isEqualTo("Change");
    }

    @Test
    void ctaAndLocationHaveTheirOwnWording() throws Exception {
        enableCrm();
        WaWebChat cta = chat("Asha", "919900000202", 1);
        capture(cta);
        MessagePage ctaPage = pages.drawPreview(pages.create(cta, cloud.parse(mapper.readTree(MessagePageIT.CTA))));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/t/" + ctaPage.getToken() + "/a"))
                .andExpect(status().isFound());
        WaWebChat loc = chat("Asha", "919900000203", 1);
        capture(loc);
        MessagePage locPage = pages.drawPreview(pages.create(loc, cloud.parse(mapper.readTree(MessagePageIT.LOCATION))));
        beacon(locPage.getToken(), "{\"k\":\"location\",\"lat\":12.9,\"lng\":77.6}");
        List<Document> acted = activities("MESSAGE_PAGE_ACTION");
        assertThat(acted).extracting(a -> a.getString("summary"))
                .containsExactlyInAnyOrder("Opened the link \"See Dates\"", "Shared location");
        Document shared = acted.stream().filter(a -> a.getString("summary").equals("Shared location")).findFirst().orElseThrow();
        assertThat(shared.get("payload", Document.class).getDouble("latitude")).isEqualTo(12.9);
    }

    @Test
    void nothingWhenCrmIsOff() throws Exception {
        WaWebChat chat = chat("Asha", "919900000204", 1);
        MessagePage p = page(chat);
        beacon(p.getToken(), "{\"k\":\"open\"}");
        beacon(p.getToken(), "{\"k\":\"reply\",\"id\":\"change-button\"}");
        assertThat(activities("MESSAGE_PAGE_OPENED")).isEmpty();
        assertThat(activities("MESSAGE_PAGE_ACTION")).isEmpty();
    }

    @Test
    void typedReplyMatchWritesTheAction() throws Exception {
        enableCrm();
        WaWebChat chat = chat("Asha", "919900000205", 1);
        capture(chat);
        page(chat);
        // this context runs on the real clock: the reply must be after the page, not at the fixture's T0
        me.nageshk.omnichannel.db.inbox.WaWebMessage reply = message(chat, 10, "cancel");
        reply.setTimestamp(java.time.Instant.now());
        reply = messages.save(reply);
        publisher.publishEvent(new InboxEvents.MessageUpserted(chat, reply, true));
        assertThat(activities("MESSAGE_PAGE_ACTION")).extracting(a -> a.getString("summary")).containsExactly("Tapped \"Cancel\"");
    }
}
