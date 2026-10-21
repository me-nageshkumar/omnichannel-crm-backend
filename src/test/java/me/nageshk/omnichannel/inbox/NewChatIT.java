package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.Role;
import me.nageshk.omnichannel.access.RoleLevel;
import me.nageshk.omnichannel.access.RoleRepository;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.rpc.Envelope;
import me.nageshk.omnichannel.support.FakeDevice;
import me.nageshk.omnichannel.template.TemplateContent;
import me.nageshk.omnichannel.template.TemplateRequest;
import me.nageshk.omnichannel.template.TemplateRequest.PlaceholderSetting;
import me.nageshk.omnichannel.template.TemplateService;
import me.nageshk.omnichannel.template.TemplateType;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Starting a chat from the console: {@code POST /phone-numbers/{pn}/chats} through a {@link FakeDevice}. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
class NewChatIT extends InboxApiTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired AccessService access;
    @Autowired TemplateService templateService;
    @Autowired RoleRepository roles;

    /** What the device saw and what the console got back. */
    record Exchange(ResponseEntity<JsonNode> response, Envelope request) {
    }

    private ResponseEntity<JsonNode> post(String token, Object body, String url) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(mapper.writeValueAsString(body), h),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String token, Object body) throws Exception {
        return post(token, body, chatsUrl());
    }

    @Override
    String chatsUrl() {
        return "http://localhost:" + port + super.chatsUrl();
    }

    private ObjectNode ready() {
        return mapper.createObjectNode().put("pushname", "N").put("wid", number.getNumber() + "@c.us");
    }

    /** A sent-message model the way whatsapp-web.js reports it. */
    private ObjectNode model(String remoteId, String serialized, String body) {
        ObjectNode m = mapper.createObjectNode();
        m.set("id", mapper.createObjectNode().put("_serialized", serialized).put("fromMe", true)
                .put("remote", remoteId));
        m.put("fromMe", true).put("body", body).put("type", "chat").put("ack", 1)
                .put("timestamp", Instant.now().getEpochSecond());
        return m;
    }

    /** Posts with a connected device and answers the {@code sendMessage} request it receives. */
    private Exchange viaDevice(String token, Object body, Function<Envelope, Envelope> answer) throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() -> {
                try {
                    return post(token, body);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Envelope req = device.next();
            assertThat(req.method()).isEqualTo("sendMessage");
            device.send(answer.apply(req));
            return new Exchange(http.get(5, TimeUnit.SECONDS), req);
        }
    }

    private Exchange sendOk(String token, Object body, String remoteId, String serialized, String text)
            throws Exception {
        return viaDevice(token, body, req -> Envelope.response(req.id(), model(remoteId, serialized, text)));
    }

    /** A request with a connected, ready device that must not be asked anything. */
    private ResponseEntity<JsonNode> postUntouched(String token, Object body) throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            ResponseEntity<JsonNode> res = post(token, body);
            assertThat(device.poll(Duration.ofMillis(300))).isEmpty();
            return res;
        }
    }

    private AuthenticatedUser managerMe() {
        return access.load(manager);
    }

    private PlaceholderSetting nameSetting() {
        return new PlaceholderSetting("contact.name", "there", null, null, null);
    }

    private String textTemplate(String name, String body, PlaceholderSetting... settings) {
        TemplateContent c = new TemplateContent();
        c.setBody(body);
        return templateService.create(managerMe(), brand.getId(),
                new TemplateRequest(name, null, TemplateType.TEXT, c, null, List.of(settings), null)).id();
    }

    private WaWebChat chatOf(String remoteId, String name, User user, Team team) {
        WaWebChat c = chat(name, "919811111111", 1, user, team);
        c.setRemoteId(remoteId);
        return chats.save(c);
    }

    @Test
    void newNumberCreatesContactChatAndMessageAssignedToTheSender() throws Exception {
        Exchange ex = sendOk(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                "919811111111@c.us", "true_919811111111@c.us_1", "hello");

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.request().params().get("chatId").asString()).isEqualTo("919811111111@c.us");
        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(number.getId(), "919811111111@c.us").orElseThrow();
        assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(chat.getId());
        assertThat(chat.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(chat.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(chat.getAssignedAt()).isNotNull();
        assertThat(chat.getLastMessage().text()).isEqualTo("hello");
        Contact contact = contacts.findById(chat.getContactId()).orElseThrow();
        assertThat(contact.getPhone()).isEqualTo("919811111111");
        assertThat(contact.getWaId()).isEqualTo("919811111111@c.us");
        assertThat(actions.count()).isZero();
    }

    @Test
    void formattedPhoneIsNormalised() throws Exception {
        Exchange ex = sendOk(agentToken, Map.of("phone", "+91 98111-11111", "text", "hello"),
                "919811111111@c.us", "true_919811111111@c.us_1", "hello");
        assertThat(ex.request().params().get("chatId").asString()).isEqualTo("919811111111@c.us");
    }

    @Test
    void invalidPhoneIs400AndTouchesNothing() throws Exception {
        ResponseEntity<JsonNode> res = postUntouched(agentToken, Map.of("phone", "12", "text", "hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").get("phone").asString())
                .isEqualTo("must be 8 to 15 digits with the country code, e.g. 919812345678");
        assertThat(chats.count()).isZero();
        assertThat(contacts.count()).isZero();
    }

    @Test
    void noDeviceIs409BeforeAnythingIsWritten() throws Exception {
        ResponseEntity<JsonNode> res = post(agentToken, Map.of("phone", "919811111111", "text", "hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("NO_DEVICE");
        assertThat(chats.count()).isZero();
        assertThat(contacts.count()).isZero();
    }

    @Test
    void deviceFailureLeavesNoChat() throws Exception {
        Exchange ex = viaDevice(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                req -> Envelope.errorResponse(req.id(), "CHAT_NOT_FOUND", "Chat not found"));

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(chats.count()).isZero();
        assertThat(messages.count()).isZero();
    }

    @Test
    void chatAssignedToSomeoneElseIsRefused() throws Exception {
        chatOf("919811111111@c.us", "Nia", agent2, null);

        ResponseEntity<JsonNode> res = postUntouched(agentToken, Map.of("phone", "919811111111", "text", "hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("CHAT_TAKEN");
        assertThat(res.getBody().get("message").asString())
                .isEqualTo("This number already has a chat assigned to someone else");
        assertThat(chats.count()).isEqualTo(1);
    }

    @Test
    void visibleChatOfSomeoneElseIsUsedWithoutReassigning() throws Exception {
        WaWebChat theirs = chatOf("919811111111@c.us", "Nia", agent2, null);

        Exchange ex = sendOk(managerToken, Map.of("phone", "919811111111", "text", "hello"),
                "919811111111@c.us", "true_919811111111@c.us_1", "hello");

        assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(theirs.getId());
        assertThat(chats.count()).isEqualTo(1);
        assertThat(chats.findById(theirs.getId()).orElseThrow().getAssignedUserId()).isEqualTo(agent2.getId());
        assertThat(actions.count()).isZero();
    }

    @Test
    void unassignedChatIsClaimedByTheCaller() throws Exception {
        WaWebChat queue = chatOf("919811111111@c.us", "Nia", null, support);

        Exchange ex = sendOk(leadToken, Map.of("phone", "919811111111", "text", "hello"),
                "919811111111@c.us", "true_919811111111@c.us_1", "hello");

        assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(queue.getId());
        WaWebChat after = chats.findById(queue.getId()).orElseThrow();
        assertThat(after.getAssignedUserId()).isEqualTo(lead.getId());
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId()); // lead is a member, team kept
        assertThat(actions.findAll()).extracting(WaWebChatAction::getType).containsExactly(ActionType.ASSIGNED);
    }

    @Test
    void closedChatIsReopened() throws Exception {
        WaWebChat closed = chatOf("919811111111@c.us", "Nia", agent, null);
        closed.setStatus(ChatStatus.CLOSED);
        closed = chats.save(closed);

        Exchange ex = sendOk(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                "919811111111@c.us", "true_919811111111@c.us_1", "hello");

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chats.findById(closed.getId()).orElseThrow().getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(actions.findAll()).extracting(WaWebChatAction::getType).containsExactly(ActionType.REOPENED);
    }

    @Test
    void templateIsRenderedForTheNewContact() throws Exception {
        String templateId = textTemplate("Welcome", "Hi {{contact.name}}", nameSetting());

        Exchange ex = sendOk(agentToken, Map.of("phone", "919811111111", "templateId", templateId, "values", Map.of()),
                "919811111111@c.us", "true_919811111111@c.us_1", "Hi there");

        assertThat(ex.request().params().get("content").asString()).isEqualTo("Hi there");
        assertThat(ex.response().getBody().get("templateId").asString()).isEqualTo(templateId);
    }

    @Test
    void requiresInboxSend() throws Exception {
        Role viewOnly = new Role();
        viewOnly.setName("INBOX_VIEWER");
        viewOnly.setLevel(RoleLevel.BRAND);
        viewOnly.setAuthorities(Set.of("inbox:view"));
        viewOnly = roles.save(viewOnly);
        User viewer = testUsers.createInCustomer(acme.getId(), "viewer", null,
                List.of(new WorkspaceRole(brand.getId(), viewOnly.getId())));

        ResponseEntity<JsonNode> res = post(testUsers.accessToken(viewer),
                Map.of("phone", "919811111111", "text", "hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(chats.count()).isZero();
    }

    @Test
    void numberOfAnotherBrandIs404() throws Exception {
        ResponseEntity<JsonNode> res = post(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                "http://localhost:" + port + inbox() + "/phone-numbers/" + otherNumber.getId() + "/chats");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
