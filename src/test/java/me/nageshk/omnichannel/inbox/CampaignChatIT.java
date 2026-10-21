package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.events.WhatsAppEvent;
import me.nageshk.omnichannel.rpc.Envelope;
import me.nageshk.omnichannel.support.FakeDevice;
import me.nageshk.omnichannel.template.TemplateContent;
import me.nageshk.omnichannel.template.TemplateRequest;
import me.nageshk.omnichannel.template.TemplateRequest.PlaceholderSetting;
import me.nageshk.omnichannel.template.TemplateService;
import me.nageshk.omnichannel.template.TemplateType;
import me.nageshk.omnichannel.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.node.ObjectNode;

/** A campaign sends through the inbox without a user: {@link NewChatService#startForCampaign}, via a fake device. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
class CampaignChatIT extends InboxApiTestBase {

    private static final CampaignActor DIWALI = new CampaignActor("c-1", "Diwali");
    private static final String PHONE = "919811111111";
    private static final String REMOTE = PHONE + "@c.us";

    @LocalServerPort int port;
    @Autowired NewChatService newChats;
    @Autowired AccessService access;
    @Autowired TemplateService templateService;
    @Autowired InboxSettingService settingService;
    @Autowired ChatAssignmentService assignment;
    @Autowired ApplicationEventPublisher publisher;

    /** What the device saw and what the campaign got back. */
    record Exchange(MessageView view, Envelope request) {
    }

    private ObjectNode ready() {
        return mapper.createObjectNode().put("pushname", "N").put("wid", number.getNumber() + "@c.us");
    }

    /** A message model the way whatsapp-web.js reports it. */
    private ObjectNode model(String remoteId, String serialized, String body, boolean fromMe) {
        ObjectNode m = mapper.createObjectNode();
        m.set("id", mapper.createObjectNode().put("_serialized", serialized).put("fromMe", fromMe)
                .put("remote", remoteId));
        m.put("fromMe", fromMe).put("body", body).put("type", "chat").put("ack", 1)
                .put("timestamp", Instant.now().getEpochSecond());
        return m;
    }

    private void event(ObjectNode payload) {
        publisher.publishEvent(new WhatsAppEvent(this, number.getId(), brand.getId(), acme.getId(), "message_create",
                payload, null, System.currentTimeMillis()));
    }

    private void inbound(String key) {
        event(model(REMOTE, "false_" + REMOTE + "_" + key, "hello back", false));
    }

    private Exchange campaignSend(String phone, String remoteId, String templateId, Map<String, String> values)
            throws Exception {
        return campaignSend(phone, remoteId, templateId, values, req -> { });
    }

    /**
     * Starts the campaign send on another thread with a connected device, runs {@code beforeAnswer} once the
     * {@code sendMessage} request arrived, then answers it with a model of the rendered text.
     */
    private Exchange campaignSend(String phone, String remoteId, String templateId, Map<String, String> values,
            Consumer<Envelope> beforeAnswer) throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            CompletableFuture<MessageView> sent = CompletableFuture.supplyAsync(() -> newChats.startForCampaign(
                    DIWALI, brand.getId(), number.getId(), phone, remoteId,
                    new SendRequest(null, templateId, values)).join());
            Envelope req = device.next();
            assertThat(req.method()).isEqualTo("sendMessage");
            beforeAnswer.accept(req);
            String content = req.params().get("content").asString();
            device.send(Envelope.response(req.id(), model(remoteId, "true_" + remoteId + "_1", content, true)));
            return new Exchange(sent.get(5, TimeUnit.SECONDS), req);
        }
    }

    private String templateId(String body) {
        TemplateContent c = new TemplateContent();
        c.setBody(body);
        return templateService.create(access.load(manager), brand.getId(),
                new TemplateRequest("Greeting", null, TemplateType.TEXT, c, null,
                        List.of(new PlaceholderSetting("contact.name", "there", null, null, null)), null)).id();
    }

    private WaWebChat chatOf(String name, User user) {
        WaWebChat c = chat(name, PHONE, 1, user, null);
        c.setRemoteId(REMOTE);
        return chats.save(c);
    }

    private void routeToAgent() {
        settingService.put(brand.getId(), number.getId(), new InboxSettingService.SettingsRequest(
                new InboxSetting.Routing(RoutingMode.USER, null, agent.getId()), null, null));
    }

    private long routedActions() {
        return actions.findAll().stream().filter(a -> a.getType() == ActionType.ROUTED).count();
    }

    @Test
    void freshCampaignChatIsUnassignedAndMarked() throws Exception {
        settingService.put(brand.getId(), number.getId(), new InboxSettingService.SettingsRequest(
                new InboxSetting.Routing(RoutingMode.NONE, null, null), true, null)); // claimOnReply on
        Exchange ex = campaignSend(PHONE, REMOTE, templateId("Hi {{contact.name}}"), Map.of("contact.name", "Asha"));
        MessageView sent = ex.view();
        WaWebChat chat = chats.findById(sent.chatId()).orElseThrow();
        assertThat(chat.getCampaignId()).isEqualTo("c-1");
        assertThat(chat.getAssignedUserId()).isNull();
        WaWebMessage m = messages.findById(sent.id()).orElseThrow();
        assertThat(m.getSource()).isEqualTo(MessageSource.CAMPAIGN);
        assertThat(m.getCampaignId()).isEqualTo("c-1");
        assertThat(m.getSentByUserId()).isNull();
        assertThat(sent.sentBy().id()).isEqualTo("c-1");
        assertThat(sent.sentBy().name()).isEqualTo("Campaign: Diwali");
        assertThat(ex.request().params().get("content").asString()).isEqualTo("Hi Asha");
        assertThat(actions.count()).isZero();
        assertThat(contacts.findByWorkspaceIdAndPhone(brand.getId(), PHONE)).isPresent();
    }

    @Test
    void existingAssignedChatKeepsItsAssignment() throws Exception {
        WaWebChat theirs = chatOf("Nia", agent2);

        Exchange ex = campaignSend(PHONE, REMOTE, templateId("Hi {{contact.name}}"), Map.of("contact.name", "Nia"));

        assertThat(ex.view().chatId()).isEqualTo(theirs.getId());
        assertThat(chats.count()).isEqualTo(1);
        WaWebChat after = chats.findById(theirs.getId()).orElseThrow();
        assertThat(after.getAssignedUserId()).isEqualTo(agent2.getId());
        assertThat(after.getCampaignId()).isNull();
        assertThat(messages.findById(ex.view().id()).orElseThrow().getSource()).isEqualTo(MessageSource.CAMPAIGN);
        assertThat(actions.count()).isZero();
    }

    @Test
    void closedChatIsReopenedByTheCampaign() throws Exception {
        WaWebChat closed = chatOf("Nia", agent);
        closed.setStatus(ChatStatus.CLOSED);
        closed = chats.save(closed);

        campaignSend(PHONE, REMOTE, templateId("Hi {{contact.name}}"), Map.of("contact.name", "Nia"));

        WaWebChat after = chats.findById(closed.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getAssignedUserId()).isEqualTo(agent.getId());
        List<WaWebChatAction> all = actions.findAll();
        assertThat(all).extracting(WaWebChatAction::getType).containsExactly(ActionType.REOPENED);
        assertThat(all.get(0).getByUserId()).isNull();
        assertThat(all.get(0).getByName()).isEqualTo("Campaign: Diwali");
    }

    @Test
    void campaignSendSurvivesTheEventComingFirst() throws Exception {
        String serialized = "true_" + REMOTE + "_1";
        campaignSend(PHONE, REMOTE, templateId("Hi {{contact.name}}"), Map.of("contact.name", "Asha"),
                req -> event(model(REMOTE, serialized, req.params().get("content").asString(), true)));

        assertThat(chats.count()).isEqualTo(1);
        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(number.getId(), REMOTE).orElseThrow();
        assertThat(chat.getCampaignId()).isEqualTo("c-1");
        assertThat(chat.getAssignedUserId()).isNull();
        assertThat(messages.count()).isEqualTo(1);
        WaWebMessage m = messages.findByPhoneNumberIdAndWaMessageId(number.getId(), serialized).orElseThrow();
        assertThat(m.getSource()).isEqualTo(MessageSource.CAMPAIGN);
        assertThat(m.getSentByName()).isEqualTo("Campaign: Diwali");
        assertThat(m.getCampaignId()).isEqualTo("c-1");
    }

    @Test
    void firstReplyRoutesACampaignChat() {
        routeToAgent();
        WaWebChat c = chatOf("Nia", null);
        c.setCampaignId("c-1");
        c = chats.save(c);

        inbound("1");

        WaWebChat routed = chats.findById(c.getId()).orElseThrow();
        assertThat(routed.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(routedActions()).isEqualTo(1);

        assignment.unassign(access.load(manager), routed);
        inbound("2");

        assertThat(chats.findById(c.getId()).orElseThrow().getAssignedUserId()).isNull();
        assertThat(routedActions()).isEqualTo(1);
    }

    @Test
    void closedChatReopenedByTheCampaignIsRoutedOnReply() throws Exception {
        routeToAgent();
        WaWebChat closed = chatOf("Nia", null);
        closed.setStatus(ChatStatus.CLOSED);
        closed.setLastInboundAt(Instant.now().minusSeconds(86_400));
        closed = chats.save(closed);

        campaignSend(PHONE, REMOTE, templateId("Hi {{contact.name}}"), Map.of("contact.name", "Nia"));

        WaWebChat reopened = chats.findById(closed.getId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(reopened.getCampaignId()).isEqualTo("c-1");
        assertThat(reopened.getAssignedUserId()).isNull();

        inbound("1");

        assertThat(chats.findById(closed.getId()).orElseThrow().getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(routedActions()).isEqualTo(1);
    }

    @Test
    void firstReplyOnAPlainChatIsNotRouted() {
        routeToAgent();
        WaWebChat c = chatOf("Nia", null);

        inbound("1");

        assertThat(chats.findById(c.getId()).orElseThrow().getAssignedUserId()).isNull();
        assertThat(routedActions()).isZero();
    }
}
