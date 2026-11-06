package me.nageshk.omnichannel.common.channel.whatsapp.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class WebhookEventJsonTest {

    private static final ObjectMapper mapper = JsonMapper.builder().build();

    static Stream<Path> fixtures() throws IOException, URISyntaxException {
        Path dir = Paths.get(WebhookEventJsonTest.class.getResource("/webhook/meta").toURI());
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    private static WebhookEvent read(String fixture) {
        try {
            Path dir = Paths.get(WebhookEventJsonTest.class.getResource("/webhook/meta").toURI());
            return mapper.readValue(Files.readString(dir.resolve(fixture)), WebhookEvent.class);
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Value first(WebhookEvent e) {
        return e.entry().get(0).changes().get(0).value();
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyRealMetaBodyDeserialises(Path fixture) throws IOException {
        WebhookEvent e = mapper.readValue(Files.readString(fixture), WebhookEvent.class);
        assertThat(e.object()).isEqualTo("whatsapp_business_account");
        assertThat(e.entry()).hasSize(1);
        assertThat(e.entry().get(0).changes().get(0).value().messagingProduct()).isEqualTo("whatsapp");
    }

    @Test
    void anInboundTextIsTyped() {
        Message m = first(read("text_inbound.json")).messages().get(0);
        assertThat(m.from()).isEqualTo("919928088743");
        assertThat(m.id()).startsWith("wamid.");
        assertThat(m.type()).isEqualTo("text");
        assertThat(m.text().body()).isEqualTo("Hi");
        assertThat(first(read("text_inbound.json")).contacts().get(0).profile().name())
                .isEqualTo("Nagesh Kumar");
    }

    @Test
    void aStatusIsTyped() {
        Status s = first(read("status_sent_marketing.json")).statuses().get(0);
        assertThat(s.status()).isEqualTo("sent");
        assertThat(s.recipientId()).isEqualTo("919928088743");
        assertThat(s.conversation().origin().type()).isEqualTo("marketing");
        assertThat(s.pricing().billable()).isTrue();
    }

    @Test
    void aReferralAndAUserIdAreTyped() {
        Message ad = first(read("text_with_referral.json")).messages().get(0);
        assertThat(ad.referral().sourceId()).isEqualTo("120234880794530738");
        assertThat(ad.referral().ctwaClid()).isNotBlank();
        Message lid = first(read("text_with_user_id.json")).messages().get(0);
        assertThat(lid.fromUserId()).isEqualTo("IN.1976699579621058");
    }

    @Test
    void theOtherTypeObjectsAreTyped() {
        assertThat(first(read("image_forwarded.json")).messages().get(0).context().forwarded()).isTrue();
        assertThat(first(read("audio_voice.json")).messages().get(0).audio().voice()).isTrue();
        assertThat(first(read("document.json")).messages().get(0).document().filename()).endsWith(".pdf");
        assertThat(first(read("location.json")).messages().get(0).location().latitude()).isBetween(26.0, 27.0);
        assertThat(first(read("contacts.json")).messages().get(0).contacts().get(0).phones().get(0).waId())
                .isNotBlank();
        assertThat(first(read("button.json")).messages().get(0).button().text()).isEqualTo("Order T-shirt");
        assertThat(first(read("interactive_nfm_reply.json")).messages().get(0).interactive().nfmReply().name())
                .isEqualTo("flow");
        assertThat(first(read("order.json")).messages().get(0).order().productItems().get(0).quantity())
                .isEqualTo(1);
        assertThat(first(read("unsupported.json")).messages().get(0).errors().get(0).errorData().details())
                .isNotBlank();
        assertThat(first(read("system_changed_number.json")).messages().get(0).system().type())
                .isEqualTo("user_changed_number");
        assertThat(first(read("status_failed.json")).statuses().get(0).errors().get(0).code()).isEqualTo(131047);
        assertThat(first(read("status_read_user_id.json")).statuses().get(0).recipientUserId())
                .startsWith("IN.");
        MessageEcho echo = first(read("smb_message_echo_text.json")).messageEchoes().get(0);
        assertThat(echo.to()).isEqualTo("919928088743");
        assertThat(echo.text().body()).isEqualTo("test message");
    }

    @Test
    void ourOwnEventRoundTripsInSnakeCaseWithoutNulls() {
        ChannelContext ctx = new ChannelContext(ChannelKind.WHATSAPP_WEB, "cust", "ws", "pn-1", "919928088743");
        WebhookEvent e = WebhookEvent.of(
                ctx,
                List.of(new Change(
                        "messages",
                        Value.of(ctx)
                                .withMessages(List.of(Message.text(
                                        "false_919912345678@c.us_ABC",
                                        "919912345678",
                                        null,
                                        "1700000000",
                                        "hello",
                                        new WaWeb(
                                                "919912345678@c.us", 0, "chat", null, null, null, null, null,
                                                null, null, null, null, null, null)))))));
        JsonNode json = mapper.valueToTree(e);
        JsonNode value = json.at("/entry/0/changes/0/value");
        assertThat(value.path("messaging_product").asString()).isEqualTo("whatsapp");
        assertThat(value.at("/metadata/phone_number_id").asString()).isEqualTo("pn-1");
        assertThat(value.at("/messages/0/wa_web/chat_id").asString()).isEqualTo("919912345678@c.us");
        assertThat(value.at("/messages/0").has("from_user_id")).isFalse();
        assertThat(mapper.treeToValue(json, WebhookEvent.class)).isEqualTo(e);
    }

    @Test
    void aConvertedEventSaysWhereItCameFrom() {
        ChannelContext web = new ChannelContext(ChannelKind.WHATSAPP_WEB, "cust", "ws", "pn-1", "919928088743");
        WebhookEvent e = WebhookEvent.of(web, List.of(new Change("messages", null)));
        assertThat(e.object()).isEqualTo("whatsapp_web_account");
        assertThat(e.entry().get(0).id()).isEqualTo("ws");
        ChannelContext cloud = new ChannelContext(ChannelKind.WHATSAPP_CLOUD, "cust", "ws", "pn-1", "919928088743");
        assertThat(WebhookEvent.of(cloud, List.of(new Change("messages", null))).object())
                .isEqualTo("whatsapp_business_account");
    }

    @Test
    void anEstimatedReceiptSaysSoAndALiveStatusDoesNot() {
        ChannelContext ctx = new ChannelContext(ChannelKind.WHATSAPP_WEB, "cust", "ws", "pn-1", "919928088743");
        Status estimated = new Status("true_919912345678@c.us_ABC", "read", "1700000000", "919912345678", null, null,
                null, null, null, new WaWeb("919912345678@c.us", 3, null, null, null, null, null, null, null, null,
                        1_700_000_000_000L, null, true, true));
        WebhookEvent e = WebhookEvent.of(ctx,
                List.of(new Change("messages", Value.of(ctx).withStatuses(List.of(estimated)))));
        JsonNode json = mapper.valueToTree(e);
        assertThat(json.at("/entry/0/changes/0/value/statuses/0/wa_web/estimated").asBoolean()).isTrue();
        assertThat(mapper.treeToValue(json, WebhookEvent.class)).isEqualTo(e);
        Status live = new Status("id", "read", "1700000000", null, null, null, null, null, null,
                new WaWeb("919912345678@c.us", 3, null, null, null, null, null, null, null, null, null, null, null,
                        null));
        assertThat(mapper.valueToTree(live).path("wa_web").has("estimated")).isFalse();
    }
}
