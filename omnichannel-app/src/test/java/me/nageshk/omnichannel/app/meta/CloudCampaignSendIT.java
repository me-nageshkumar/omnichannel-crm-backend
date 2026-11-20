package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.inbox.CampaignActor;
import me.nageshk.omnichannel.service.inbox.CloudTemplateRequest;
import me.nageshk.omnichannel.service.inbox.NewChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** A Cloud campaign's send treats the chat as a WhatsApp Web campaign does (spec 2026-11-21 §5.3). */
class CloudCampaignSendIT extends CloudCampaignTestBase {

    private static final CampaignActor SPRING = new CampaignActor("c1", "Spring");
    private static final Map<String, String> VALUES = Map.of("body:1", "Veenu", "body:2", "BCA");

    @Autowired NewChatService newChats;

    private void send(String phone, String templateId, Map<String, String> values, String headerMediaId)
            throws Exception {
        newChats.startCloudTemplateForCampaign(SPRING, brand.getId(), numberId, phone,
                new CloudTemplateRequest(templateId, values, headerMediaId)).get(10, TimeUnit.SECONDS);
    }

    @Test
    void freshChatIsMarkedWithTheCampaign() throws Exception {
        String t = syncTemplate(FOLLOW_UP);
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.F1"));
        send("919811100001", t, VALUES, null);
        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(numberId, "919811100001@c.us").orElseThrow();
        assertThat(chat.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(chat.getCampaignId()).isEqualTo("c1");
        assertThat(chat.getAssignedUserId()).isNull();
        List<GraphStubRecorded> calls = calls();
        assertThat(calls).hasSize(1);
        assertThat(mapper.readTree(calls.get(0).body()).get("type").asString()).isEqualTo("template");
    }

    @Test
    void closedChatIsReopenedAndKeepsItsAgent() throws Exception {
        String t = syncTemplate(FOLLOW_UP);
        WaWebChat closed = new WaWebChat();
        closed.setCustomerId(acme.getId());
        closed.setWorkspaceId(brand.getId());
        closed.setPhoneNumberId(numberId);
        closed.setRemoteId("919811100002@c.us");
        closed.setStatus(ChatStatus.CLOSED);
        closed.setAssignedUserId(agent.getId());
        closed = chats.save(closed);
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.F2"));
        send("919811100002", t, VALUES, null);
        WaWebChat after = chats.findById(closed.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(after.getCampaignId()).isNull();
    }

    @Test
    void headerUploadedOncePerNumber() throws Exception {
        String t = syncTemplate(BROCHURE);
        String media = uploadPng();
        GRAPH.enqueue("POST /333/media", 200, "{\"id\":\"meta-media-1\"}");
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.H1"));
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.H2"));
        send("919811100003", t, Map.of(), media);
        send("919811100004", t, Map.of(), media);
        assertThat(GRAPH.recorded("POST /333/media")).hasSize(1);
        for (var call : GRAPH.recorded("POST /333/messages")) {
            JsonNode header = mapper.readTree(call.body()).get("template").get("components").get(0);
            assertThat(header.get("parameters").get(0).get("image").get("id").asString()).isEqualTo("meta-media-1");
        }
    }

    @Test
    void blankValueRefusedBeforeCharge() {
        String t = syncTemplate(FOLLOW_UP);
        assertThatThrownBy(() -> send("919811100005", t, Map.of("body:2", "BCA"), null))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields()).containsKey("values.body:1"));
        assertThat(GRAPH.recorded("POST /333/messages")).isEmpty();
        assertThat(mongo.getCollection("walletEntries").countDocuments()).isZero();
    }

    private List<GraphStubRecorded> calls() {
        return GRAPH.recorded("POST /333/messages").stream().map(r -> new GraphStubRecorded(r.body())).toList();
    }

    private record GraphStubRecorded(String body) {
    }
}
