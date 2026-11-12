package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessages;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.page.MessagePage;
import me.nageshk.omnichannel.db.page.MessagePageRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import me.nageshk.omnichannel.service.inbox.InboxStreamBroker;
import me.nageshk.omnichannel.service.inbox.InboxViews;
import me.nageshk.omnichannel.service.inbox.MessageView;
import me.nageshk.omnichannel.service.page.MessagePageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** Spec §5.3–5.5: the public page, its preview and media, the beacons, the redirect, the view and the live push. */
class MessagePageIT extends InboxApiTestBase {

    @Autowired MessagePageService pages;
    @Autowired MessagePageRepository repo;
    @Autowired CloudMessages cloud;
    @Autowired CloudMediaService media;
    @Autowired AccessService access;
    @Autowired InboxViews views;
    @Autowired InboxStreamBroker broker;

    static final String BUTTON = """
            {"messaging_product":"whatsapp-web","recipient_type":"individual","to":"919900000001","type":"interactive",
             "interactive":{"type":"button","body":{"text":"Hi Pablo! Tap a button."},"footer":{"text":"Lucky Shrub"},
             "action":{"buttons":[{"type":"reply","reply":{"id":"change-button","title":"Change"}},
                                  {"type":"reply","reply":{"id":"cancel-button","title":"Cancel"}}]}}}""";
    static final String CTA = """
            {"messaging_product":"whatsapp-web","to":"919900000001","type":"interactive",
             "interactive":{"type":"cta_url","body":{"text":"See dates"},
             "action":{"name":"cta_url","parameters":{"display_text":"See Dates","url":"https://example.com/d?x=1"}}}}""";
    static final String LOCATION = """
            {"messaging_product":"whatsapp-web","to":"919900000001","type":"interactive",
             "interactive":{"type":"location_request_message","body":{"text":"Where are you?"},
             "action":{"name":"send_location"}}}""";

    CloudMessage parse(String json) throws Exception {
        return cloud.parse(mapper.readTree(json));
    }

    /** A page for {@code json} on a fresh chat, with its preview drawn and an OUT message pointing at it. */
    private static final java.util.concurrent.atomic.AtomicInteger PHONES = new java.util.concurrent.atomic.AtomicInteger(100);

    MessagePage seeded(String json) throws Exception {
        WaWebChat chat = chat("Asha", "91990000" + PHONES.incrementAndGet(), 1);
        MessagePage page = pages.drawPreview(pages.create(chat, parse(json)));
        WaWebMessage m = new WaWebMessage();
        m.setCustomerId(chat.getCustomerId());
        m.setWorkspaceId(chat.getWorkspaceId());
        m.setPhoneNumberId(chat.getPhoneNumberId());
        m.setChatId(chat.getId());
        m.setWaMessageId("wa-" + page.getToken());
        m.setDirection(MessageDirection.OUT);
        m.setSource(MessageSource.CONSOLE);
        m.setType("text");
        m.setBody(pages.link(page));
        m.setTimestamp(Instant.now());
        m.setMessagePageId(page.getId());
        m = messages.save(m);
        pages.attachMessage(page.getId(), m.getId());
        return repo.findById(page.getId()).orElseThrow();
    }

    String withImageHeader() throws Exception {
        CloudMediaService.Stored s = media.upload(access.load(agent), brand.getId(), "image",
                new MockMultipartFile("file", "a.png", "application/octet-stream", CloudMediaIT.png(100)));
        return BUTTON.replace("\"interactive\":{\"type\":\"button\",",
                "\"interactive\":{\"type\":\"button\",\"header\":{\"type\":\"image\",\"image\":{\"id\":\"" + s.id() + "\"}},");
    }

    ResultActions beacon(String token, String json) throws Exception {
        return mvc.perform(post("/t/" + token + "/e").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    MessagePage reload(MessagePage p) {
        return repo.findById(p.getId()).orElseThrow();
    }

    MessageView view(MessagePage p) {
        return views.message(messages.findById(p.getMessageId()).orElseThrow());
    }

    @Test
    void servesTheHtmlPageWithoutAuth() throws Exception {
        MessagePage p = seeded(BUTTON);
        mvc.perform(get("/t/" + p.getToken())).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://wa.me/" + number.getNumber() + "?text=Change")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("http://pages.test/t/" + p.getToken() + "/e")));
    }

    @Test
    void unknownTokenIs404Html() throws Exception {
        mvc.perform(get("/t/nope")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("wa.me"))));
    }

    @Test
    void expiredIs410WithoutMessageText() throws Exception {
        MessagePage p = seeded(BUTTON);
        p.setExpiresAt(Instant.now().minusSeconds(1));
        repo.save(p);
        mvc.perform(get("/t/" + p.getToken())).andExpect(status().isGone())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("This message has expired")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Pablo"))));
    }

    @Test
    void ogPngExistsRightAfterCreate() throws Exception {
        MessagePage p = seeded(BUTTON);
        assertThat(p.isPreviewReady()).isTrue();
        mvc.perform(get("/t/" + p.getToken() + "/og.png")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "public, max-age=86400"));
        mvc.perform(get("/t/nope/og.png")).andExpect(status().isNotFound());
    }

    @Test
    void headerMediaStreams() throws Exception {
        MessagePage p = seeded(withImageHeader());
        mvc.perform(get("/t/" + p.getToken() + "/m")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(CloudMediaIT.png(100)));
        MessagePage noMedia = seeded(BUTTON);
        mvc.perform(get("/t/" + noMedia.getToken() + "/m")).andExpect(status().isNotFound());
    }

    @Test
    void openSetsOpenedAtOnce() throws Exception {
        MessagePage p = seeded(BUTTON);
        beacon(p.getToken(), "{\"k\":\"open\"}").andExpect(status().isNoContent());
        Instant first = reload(p).getOpenedAt();
        assertThat(first).isNotNull();
        beacon(p.getToken(), "{\"k\":\"open\"}").andExpect(status().isNoContent());
        MessagePage after = reload(p);
        assertThat(after.getOpenedAt()).isEqualTo(first);
        assertThat(after.getOpenCount()).isEqualTo(2);
        assertThat(after.getActions()).hasSize(2);
        assertThat(after.getActions().get(0).uaHash()).hasSize(12);
        assertThat(view(after).messagePage().openCount()).isEqualTo(2);
        assertThat(view(after).messagePage().url()).isEqualTo("http://pages.test/t/" + p.getToken());
    }

    @Test
    void replyRecordsAndIsVisibleInTheView() throws Exception {
        MessagePage p = seeded(BUTTON);
        beacon(p.getToken(), "{\"k\":\"reply\",\"id\":\"change-button\"}").andExpect(status().isNoContent());
        MessagePage after = reload(p);
        assertThat(after.getActions()).extracting(MessagePage.Action::kind).containsExactly(MessagePage.ActionKind.BUTTON_REPLY);
        assertThat(after.getActions().get(0).title()).isEqualTo("Change");
        MessageView v = view(after);
        assertThat(v.messagePageId()).isEqualTo(p.getId());
        assertThat(v.messagePage().buttons()).extracting(c -> c.id() + ":" + c.count() + ":" + c.replied())
                .containsExactly("change-button:1:false", "cancel-button:0:false");
        assertThat(v.messagePage().rows()).isEmpty();
        assertThat(v.messagePage().cta()).isNull();
    }

    @Test
    void ignoresUnknownReplyId() throws Exception {
        MessagePage p = seeded(BUTTON);
        beacon(p.getToken(), "{\"k\":\"reply\",\"id\":\"zzz\"}").andExpect(status().isNoContent());
        beacon(p.getToken(), "{\"k\":\"dance\"}").andExpect(status().isNoContent());
        assertThat(reload(p).getActions()).isEmpty();
    }

    @Test
    void capsActionsAtFifty() throws Exception {
        MessagePage p = seeded(BUTTON);
        for (int i = 0; i < 51; i++) beacon(p.getToken(), "{\"k\":\"reply\",\"id\":\"cancel-button\"}").andExpect(status().isNoContent());
        assertThat(reload(p).getActions()).hasSize(50);
        assertThat(view(reload(p)).messagePage().buttons().get(1).count()).isEqualTo(50);
    }

    @Test
    void locationOnlyOnLocationPages() throws Exception {
        MessagePage button = seeded(BUTTON);
        beacon(button.getToken(), "{\"k\":\"location\",\"lat\":12.9,\"lng\":77.6}").andExpect(status().isNoContent());
        assertThat(reload(button).getActions()).isEmpty();
        MessagePage loc = seeded(LOCATION);
        beacon(loc.getToken(), "{\"k\":\"location\",\"lat\":95,\"lng\":77.6}").andExpect(status().isNoContent());
        assertThat(reload(loc).getActions()).isEmpty();
        beacon(loc.getToken(), "{\"k\":\"location\",\"lat\":12.9,\"lng\":77.6}").andExpect(status().isNoContent());
        MessagePage after = reload(loc);
        assertThat(after.getActions()).extracting(MessagePage.Action::kind).containsExactly(MessagePage.ActionKind.LOCATION);
        assertThat(view(after).messagePage().location().latitude()).isEqualTo(12.9);
    }

    @Test
    void ctaRedirectsAndRecords() throws Exception {
        MessagePage p = seeded(CTA);
        mvc.perform(get("/t/" + p.getToken() + "/a")).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/d?x=1"));
        MessagePage after = reload(p);
        assertThat(after.getActions()).extracting(MessagePage.Action::kind).containsExactly(MessagePage.ActionKind.CTA_URL);
        assertThat(view(after).messagePage().cta().count()).isEqualTo(1);
        MessagePage button = seeded(BUTTON);
        mvc.perform(get("/t/" + button.getToken() + "/a")).andExpect(status().isNotFound());
        p.setExpiresAt(Instant.now().minusSeconds(1));
        repo.save(p);
        mvc.perform(get("/t/" + p.getToken() + "/a")).andExpect(status().isGone());
    }

    @Test
    void beaconOnExpiredIsSilent() throws Exception {
        MessagePage p = seeded(BUTTON);
        p.setExpiresAt(Instant.now().minusSeconds(1));
        repo.save(p);
        beacon(p.getToken(), "{\"k\":\"open\"}").andExpect(status().isNoContent());
        beacon("nope", "{\"k\":\"open\"}").andExpect(status().isNoContent());
        assertThat(reload(p).getActions()).isEmpty();
    }

    @Test
    void pageWithoutMessageStillRenders() throws Exception {
        WaWebChat chat = chat("Asha", "91990000" + PHONES.incrementAndGet(), 1);
        MessagePage p = pages.drawPreview(pages.create(chat, parse(BUTTON)));
        mvc.perform(get("/t/" + p.getToken())).andExpect(status().isOk());
        beacon(p.getToken(), "{\"k\":\"open\"}").andExpect(status().isNoContent());
        assertThat(reload(p).getOpenCount()).isEqualTo(1);
    }

    @Test
    void previewFailureDeletesThePage() throws Exception {
        WaWebChat chat = chat("Asha", "91990000" + PHONES.incrementAndGet(), 1);
        String missing = BUTTON.replace("\"interactive\":{\"type\":\"button\",",
                "\"interactive\":{\"type\":\"button\",\"header\":{\"type\":\"image\",\"image\":{\"id\":\"6ad7380562bdf17ec6a0a2ed\"}},");
        MessagePage created = pages.create(chat, parse(missing));
        assertThatThrownBy(() -> pages.drawPreview(created)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preview image could not be generated");
        assertThat(repo.findById(created.getId())).isEmpty();
    }

    @Test
    void liveEventPushesTheMessage() throws Exception {
        MessagePage p = seeded(BUTTON);
        MvcResult stream = send(get(inbox() + "/stream"), agentToken, null).andExpect(request().asyncStarted()).andReturn();
        assertThat(broker.subscribers(brand.getId())).isEqualTo(1);
        beacon(p.getToken(), "{\"k\":\"open\"}").andExpect(status().isNoContent());
        String sent = stream.getResponse().getContentAsString();
        assertThat(sent).contains("event:message").contains("\"openCount\":1").contains("\"messagePageId\":\"" + p.getId() + "\"");
    }

    @Test
    void tokenIsUrlSafeAndLong() throws Exception {
        MessagePage p = seeded(BUTTON);
        assertThat(p.getToken()).matches("[A-Za-z0-9_-]{22}");
        assertThat(p.getSenderDigits()).isEqualTo(number.getNumber());
        assertThat(p.getExpiresAt()).isAfter(Instant.now().plusSeconds(89L * 86400));
        assertThat(p.getMessage()).containsKey("messaging_product");
        assertThat(((Map<?, ?>) p.getMessage().get("interactive")).get("type")).isEqualTo("button");
        assertThat(cloud.fromMap(p.getMessage()).interactive().action().buttons()).hasSize(2);
    }
}
