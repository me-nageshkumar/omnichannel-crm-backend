package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class CloudIngestIT extends MetaTestBase {

    @Autowired ChannelWebhookEventStore store;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    me.nageshk.omnichannel.service.rpc.RpcDispatcher dispatcher;

    private String key;
    private String numberId;

    @BeforeEach
    void cloudNumber() {
        mongo.remove(new Query(), "channelWebhookEvents");
        JsonNode connection = connect("111");
        key = keyOf(connection);
        numberId = addCloudNumber(connection.get("id").asString(), "Admissions", "333", "+91 98765 43210").get("id")
                .asString();
    }

    private static String value(String inner) {
        return "{\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":"
                + "{\"display_phone_number\":\"919876543210\",\"phone_number_id\":\"333\"}," + inner + "}}";
    }

    private static String status(String wamid, String state, String extra) {
        return value("\"statuses\":[{\"id\":\"" + wamid + "\",\"status\":\"" + state + "\",\"timestamp\":\""
                + Instant.now().getEpochSecond() + "\",\"recipient_id\":\"919799750743\"" + extra + "}]");
    }

    private List<WaWebMessage> messages() {
        return mongo.find(new Query(Criteria.where("phoneNumberId").is(numberId)), WaWebMessage.class);
    }

    @Test
    void statusForUnknownCloudMessageIsKept() throws Exception {
        ResponseEntity<String> res = webhook(key, envelope(status("wamid.X", "delivered", "")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        await(() -> !store.statusesFor("wamid.X").isEmpty());
        assertThat(store.statusesFor("wamid.X")).hasSize(1);
        assertThat(store.statusesFor("wamid.X").get(0).entry().get(0).changes().get(0).value().statuses().get(0)
                .status()).isEqualTo("delivered");
    }

    @Test
    void storedEventsNameOurNumber() throws Exception {
        webhook(key, envelope(textChange("333", "919876543210", "919799750743", "wamid.A", "Hi")));

        await(() -> !messages().isEmpty());
        org.bson.Document doc = mongo.getCollection("channelWebhookEvents").find().first();
        assertThat(doc.toJson()).contains("\"phone_number_id\": \"" + numberId + "\"");
        // Meta's own payload is kept as it came
        assertThat(doc.get("raw", org.bson.Document.class).toJson()).contains("\"phone_number_id\": \"333\"");
    }

    @Test
    void userIdSenderGetsUserChat() throws Exception {
        webhook(key, envelope(value("\"contacts\":[{\"profile\":{\"name\":\"Asha\"},\"user_id\":\"US.123\"}],"
                + "\"messages\":[{\"from_user_id\":\"US.123\",\"id\":\"wamid.U\",\"timestamp\":\""
                + Instant.now().getEpochSecond() + "\",\"type\":\"text\",\"text\":{\"body\":\"hello\"}}]")));

        await(() -> !messages().isEmpty());
        WaWebChat chat = chats.findById(messages().get(0).getChatId()).orElseThrow();
        assertThat(chat.getRemoteId()).isEqualTo("US.123@user");
    }

    @Test
    void incomingImageIsDownloadedToChatMedia() throws Exception {
        byte[] jpeg = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);
        GRAPH.enqueue("GET /media-1", 200, "{\"url\":\"" + GRAPH.baseUrl() + "/files/1\",\"mime_type\":\"image/jpeg\","
                + "\"file_size\":" + jpeg.length + ",\"id\":\"media-1\"}");
        GRAPH.enqueueBytes("GET /files/1", jpeg, "image/jpeg");

        webhook(key, envelope(value("\"contacts\":[{\"profile\":{\"name\":\"Veenu\"},\"wa_id\":\"919799750743\"}],"
                + "\"messages\":[{\"from\":\"919799750743\",\"id\":\"wamid.I\",\"timestamp\":\""
                + Instant.now().getEpochSecond() + "\",\"type\":\"image\",\"image\":{\"id\":\"media-1\","
                + "\"mime_type\":\"image/jpeg\",\"caption\":\"my marksheet\"}}]")));

        await(() -> !messages().isEmpty() && messages().get(0).getMedia() != null
                && messages().get(0).getMedia().mediaId() != null);
        WaWebMessage m = messages().get(0);
        assertThat(m.getMedia().mimetype()).isEqualTo("image/jpeg");
        assertThat(GRAPH.recorded("GET /files/1").get(0).authorization()).isEqualTo("Bearer " + TOKEN);
        ResponseEntity<byte[]> file = rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/"
                + m.getChatId() + "/messages/" + m.getId() + "/media"), HttpMethod.GET, auth(manager, null),
                byte[].class);
        assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(file.getBody()).isEqualTo(jpeg);
    }

    @Test
    void failedStatusStoresMetaReason() throws Exception {
        webhook(key, envelope(textChange("333", "919876543210", "919799750743", "wamid.A", "Hi")));
        await(() -> !messages().isEmpty());
        WaWebMessage in = messages().get(0);
        WaWebMessage out = new WaWebMessage();
        out.setCustomerId(in.getCustomerId());
        out.setWorkspaceId(in.getWorkspaceId());
        out.setPhoneNumberId(numberId);
        out.setChatId(in.getChatId());
        out.setWaMessageId("wamid.F");
        out.setDirection(MessageDirection.OUT);
        out.setSource(MessageSource.CONSOLE);
        out.setType("text");
        out.setBody("reply");
        out.setTimestamp(Instant.now());
        mongo.save(out);

        webhook(key, envelope(status("wamid.F", "failed",
                ",\"errors\":[{\"code\":131026,\"title\":\"Message undeliverable\",\"message\":\"Message undeliverable\","
                        + "\"error_data\":{\"details\":\"Receiver is incapable of receiving this message\"}}]")));

        await(() -> mongo.findById(out.getId(), WaWebMessage.class).getFailure() != null);
        WaWebMessage failed = mongo.findById(out.getId(), WaWebMessage.class);
        assertThat(failed.getFailure().code()).isEqualTo(131026);
        assertThat(failed.getFailure().title()).isEqualTo("Message undeliverable");
        assertThat(failed.getFailure().details()).contains("incapable");
        assertThat(failed.getStatusTimes().getFailedAt()).isNotNull();
    }

    @Test
    void noDeviceCallsForCloudChats() throws Exception {
        webhook(key, envelope(textChange("333", "919876543210", "919799750743", "wamid.A", "Hi")));

        await(() -> !messages().isEmpty());
        Thread.sleep(300);
        org.mockito.Mockito.verify(dispatcher, org.mockito.Mockito.never()).call(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
