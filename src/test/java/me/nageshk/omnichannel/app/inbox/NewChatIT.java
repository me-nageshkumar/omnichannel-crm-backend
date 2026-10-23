package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import me.nageshk.omnichannel.app.support.FakeDevice;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaNumberLookup;
import me.nageshk.omnichannel.db.inbox.WaNumberLookupRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.template.TemplateContent;
import me.nageshk.omnichannel.db.template.TemplateType;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import me.nageshk.omnichannel.service.inbox.InboxSettingService;
import me.nageshk.omnichannel.service.inbox.RoutingSetting;
import me.nageshk.omnichannel.service.rpc.Envelope;
import me.nageshk.omnichannel.service.template.TemplateRequest.PlaceholderSetting;
import me.nageshk.omnichannel.service.template.TemplateRequest;
import me.nageshk.omnichannel.service.template.TemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
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
    @Autowired InboxSettingService settingService;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired WaNumberLookupRepository lookupRows;

    private static final String LID = "103238296682612@lid";

    @BeforeEach
    void clearLookups() {
        lookupRows.deleteAll();
    }

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
        return post(token, body, newChatUrl());
    }

    /** {@code POST /phone-numbers/{pn}/chats}: starting a chat is the one thing still addressed to a number. */
    String newChatUrl() {
        return "http://localhost:" + port + inbox() + "/phone-numbers/" + number.getId() + "/chats";
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

    /**
     * Posts with a connected device; the {@code getNumberId} request the lookup may send first is answered by
     * {@code lookupAnswer}, then the {@code sendMessage} request by {@code answer}. The returned request is the send.
     * A null {@code lookupAnswer} makes it strict: the first frame must be the {@code sendMessage}.
     */
    private Exchange viaDevice(String token, Object body, Function<Envelope, JsonNode> lookupAnswer,
            Function<Envelope, Envelope> answer) throws Exception {
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
            if (lookupAnswer != null && "getNumberId".equals(req.method())) {
                device.send(Envelope.response(req.id(), lookupAnswer.apply(req)));
                req = device.next();
            }
            assertThat(req.method()).isEqualTo("sendMessage");
            device.send(answer.apply(req));
            return new Exchange(http.get(5, TimeUnit.SECONDS), req);
        }
    }

    /** Posts with a connected device that finds the typed phone on WhatsApp, without a lid. */
    private Exchange viaDevice(String token, Object body, Function<Envelope, Envelope> answer) throws Exception {
        return viaDevice(token, body, req -> lookupAnswer(req.params().get("phone").asString() + "@c.us", null),
                answer);
    }

    private ObjectNode lookupAnswer(String phone, String lid) {
        ObjectNode answer = mapper.createObjectNode().put("phone", phone);
        if (lid == null) answer.putNull("lid");
        else answer.put("lid", lid);
        return answer;
    }

    /** Like {@link #sendOk} but fails if the device is asked for a lookup first. */
    private Exchange sendOkWithoutLookup(String token, Object body, String remoteId, String serialized, String text)
            throws Exception {
        return viaDevice(token, body, null, req -> Envelope.response(req.id(), model(remoteId, serialized, text)));
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

    /** Posts with a device that answers the lookup as given and must then receive nothing. */
    private ResponseEntity<JsonNode> postAfterLookup(String token, Object body, JsonNode lookup) throws Exception {
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
            assertThat(req.method()).isEqualTo("getNumberId");
            device.send(lookup == null ? Envelope.errorResponse(req.id(), "PAGE_ERROR", "boom")
                    : Envelope.response(req.id(), lookup));
            ResponseEntity<JsonNode> res = http.get(5, TimeUnit.SECONDS);
            assertThat(device.poll(Duration.ofMillis(300))).isEmpty();
            return res;
        }
    }

    private void seedLookup(String phone, boolean registered, String lid) {
        WaNumberLookup row = new WaNumberLookup();
        row.setWorkspaceId(brand.getId());
        row.setPhoneNumberId(number.getId());
        row.setPhone(phone);
        row.setRegistered(registered);
        row.setWaPhone(registered ? phone + "@c.us" : null);
        row.setLid(lid);
        row.setCheckedAt(Instant.now());
        lookupRows.save(row);
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
        assertThat(contacts.count()).isZero();
    }

    /**
     * WhatsApp addresses most people by their LID: a send to {@code phone@c.us} comes back (model and
     * {@code message_create} event alike) under {@code …@lid}. One chat, one contact carrying both ids.
     */
    private void lidReportedForTheTypedPhone(boolean eventFirst) throws Exception {
        lidReportedForTheTypedPhone(eventFirst, null);
    }

    private void lidReportedForTheTypedPhone(boolean eventFirst, Contact known) throws Exception {
        String lid = "103238296682612@lid";
        String serialized = "true_" + lid + "_9";
        Exchange ex = viaDevice(agentToken, Map.of("phone", "919811111111", "text", "hello"), req -> {
            if (eventFirst) {
                publisher.publishEvent(new WhatsAppEvent(this, number.getId(), brand.getId(), acme.getId(),
                        "message_create", model(lid, serialized, "hello"), null, System.currentTimeMillis()));
            }
            return Envelope.response(req.id(), model(lid, serialized, "hello"));
        });

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.request().params().get("chatId").asString()).isEqualTo("919811111111@c.us");
        assertThat(chats.count()).isEqualTo(1);
        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(number.getId(), lid).orElseThrow();
        assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(chat.getId());
        assertThat(chat.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(chat.getLastMessage().text()).isEqualTo("hello");
        assertThat(messages.findByPhoneNumberIdAndWaMessageId(number.getId(), serialized).orElseThrow().getChatId())
                .isEqualTo(chat.getId());
        assertThat(contacts.count()).isEqualTo(1);
        Contact contact = contacts.findById(chat.getContactId()).orElseThrow();
        assertThat(contact.getPhone()).isEqualTo("919811111111");
        assertThat(contact.getWaId()).isEqualTo(lid);
        if (known != null) {
            assertThat(contact.getId()).isEqualTo(known.getId());
            assertThat(contact.getName()).isEqualTo(known.getName());
        }
    }

    /** A contact added by hand, phone only: the console's send is what teaches us the person's lid. */
    private Contact knownContact() {
        Contact c = new Contact();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setName("Nia Known");
        c.setNameEdited(true);
        c.setPhone("919811111111");
        c.setSource(me.nageshk.omnichannel.db.contact.ContactSource.MANUAL);
        return contacts.save(c);
    }

    @Test
    void typedPhoneOfAKnownContactTakesTheLid() throws Exception {
        lidReportedForTheTypedPhone(false, knownContact());
    }

    @Test
    void typedPhoneOfAKnownContactTakesTheLidWhenTheEventComesFirst() throws Exception {
        lidReportedForTheTypedPhone(true, knownContact());
    }

    @Test
    void lidReportedForTheTypedPhoneWhenTheEventComesFirst() throws Exception {
        lidReportedForTheTypedPhone(true);
    }

    @Test
    void lidReportedForTheTypedPhoneWhenOnlyTheReplyCarriesIt() throws Exception {
        lidReportedForTheTypedPhone(false);
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

        Exchange ex = sendOk(manager2Token, Map.of("phone", "919811111111", "text", "hello"),
                "919811111111@c.us", "true_919811111111@c.us_1", "hello");

        assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(queue.getId());
        WaWebChat after = chats.findById(queue.getId()).orElseThrow();
        assertThat(after.getAssignedUserId()).isEqualTo(manager2.getId());
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId()); // manager2 is a member, team kept
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

    @Test
    void numberOutsideTheCallersTeamsIs404AndTouchesNothing() throws Exception {
        // agent2 is in no team: Support's number is not theirs to write from, even though it is in the brand
        ResponseEntity<JsonNode> res = postUntouched(agent2Token, Map.of("phone", "919811111111", "text", "hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(chats.count()).isZero();
    }

    private void claimOnReply(boolean on) {
        settingService.putDefault(brand.getId(), new RoutingSetting.Request(false, false, null, null, on, null));
    }

    /** The device's own message_create reaches the server before the sendMessage result, which creates the chat row. */
    private void eventBeatsTheReply(boolean claimOnReply) throws Exception {
        claimOnReply(claimOnReply);
        String remote = "919811111111@c.us";
        String serialized = "true_" + remote + "_7";
        Exchange ex = viaDevice(agentToken, Map.of("phone", "919811111111", "text", "hello"), req -> {
            publisher.publishEvent(new WhatsAppEvent(this, number.getId(), brand.getId(), acme.getId(),
                    "message_create", model(remote, serialized, "hello"), null, System.currentTimeMillis()));
            return Envelope.response(req.id(), model(remote, serialized, "hello"));
        });

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(number.getId(), remote).orElseThrow();
        assertThat(chats.count()).isEqualTo(1);
        assertThat(chat.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(chat.getAssignedAt()).isNotNull();
        assertThat(actions.count()).isZero();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(agentToken);
        ResponseEntity<JsonNode> got = rest.exchange("http://localhost:" + port + inbox() + "/chats/" + chat.getId(),
                HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void creatorKeepsTheChatWhenTheEventBeatsTheReplyAndClaimOnReplyIsOff() throws Exception {
        eventBeatsTheReply(false);
    }

    @Test
    void creatorKeepsTheChatWhenTheEventBeatsTheReplyAndClaimOnReplyIsOn() throws Exception {
        eventBeatsTheReply(true);
    }

    @Test
    void unseenChatQueuedToATeamIsRefused() throws Exception {
        // a chat on Support's number that was handed to Sales: the Support agent sees the number, not that queue
        Team sales = team("Sales", agent2);
        WaWebChat queue = chatOf("919811111111@c.us", "Nia", null, sales);

        ResponseEntity<JsonNode> res = postUntouched(agentToken, Map.of("phone", "919811111111", "text", "hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("CHAT_TAKEN");
        WaWebChat after = chats.findById(queue.getId()).orElseThrow();
        assertThat(after.getAssignedUserId()).isNull();
        assertThat(after.getAssignedTeamId()).isEqualTo(sales.getId());
        assertThat(actions.count()).isZero();
    }

    @Test
    void teamMemberClaimsAQueuedChatExplicitlyWhenClaimOnReplyIsOff() throws Exception {
        claimOnReply(false);
        WaWebChat queue = chatOf("919811111111@c.us", "Nia", null, support);

        sendOk(manager2Token, Map.of("phone", "919811111111", "text", "hello"),
                "919811111111@c.us", "true_919811111111@c.us_1", "hello");

        WaWebChat after = chats.findById(queue.getId()).orElseThrow();
        assertThat(after.getAssignedUserId()).isEqualTo(manager2.getId());
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(actions.findAll()).extracting(WaWebChatAction::getType).containsExactly(ActionType.ASSIGNED);
    }

    @Test
    void chatWithNoUserAndNoTeamIsClaimedByAnyone() throws Exception {
        WaWebChat queue = chatOf("919811111111@c.us", "Nia", null, null);

        sendOk(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                "919811111111@c.us", "true_919811111111@c.us_1", "hello");

        assertThat(chats.findById(queue.getId()).orElseThrow().getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(actions.findAll()).extracting(WaWebChatAction::getType).containsExactly(ActionType.ASSIGNED);
    }

    private WaWebChat lidChat(User assignedTo) {
        Contact contact = contact(brand, "Nia", "919811111111");
        WaWebChat c = chat(number, brand, contact, 1);
        c.setRemoteId("99887766@lid");
        c.setAssignedUserId(assignedTo.getId());
        return chats.save(c);
    }

    @Test
    void contactWhoseChatLivesUnderALidIsReused() throws Exception {
        WaWebChat lid = lidChat(agent);

        Exchange ex = sendOk(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                "99887766@lid", "true_99887766@lid_1", "hello");

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.request().params().get("chatId").asString()).isEqualTo("99887766@lid");
        assertThat(chats.count()).isEqualTo(1);
        assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(lid.getId());
    }

    @Test
    void lidChatOfSomeoneElseIsRefused() throws Exception {
        lidChat(agent2);

        ResponseEntity<JsonNode> res = postUntouched(agentToken, Map.of("phone", "919811111111", "text", "hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("CHAT_TAKEN");
        assertThat(chats.count()).isEqualTo(1);
    }

    @Test
    void numberNotOnWhatsAppIs409AndTouchesNothing() throws Exception {
        ResponseEntity<JsonNode> res = postAfterLookup(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                mapper.nullNode());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("NOT_ON_WHATSAPP");
        assertThat(res.getBody().get("message").asString()).isEqualTo("This number is not on WhatsApp");
        assertThat(chats.count()).isZero();
        assertThat(contacts.count()).isZero();
        assertThat(messages.count()).isZero();
        assertThat(lookupRows.findByWorkspaceIdAndPhone(brand.getId(), "919811111111")).isPresent();
    }

    @Test
    void lookupFailureIs502AndSendsNothing() throws Exception {
        ResponseEntity<JsonNode> res = postAfterLookup(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody().get("code").asString()).isEqualTo("LOOKUP_FAILED");
        assertThat(res.getBody().get("message").asString())
                .isEqualTo("Could not check this number on WhatsApp, try again");
        assertThat(chats.count()).isZero();
        assertThat(contacts.count()).isZero();
        assertThat(lookupRows.count()).isZero();
    }

    private void lookupLidPutsTheChatUnderIt(boolean eventFirst, Contact known) throws Exception {
        String serialized = "true_" + LID + "_9";
        Exchange ex = viaDevice(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                req -> lookupAnswer("919811111111@c.us", LID),
                req -> {
                    if (eventFirst) {
                        publisher.publishEvent(new WhatsAppEvent(this, number.getId(), brand.getId(), acme.getId(),
                                "message_create", model(LID, serialized, "hello"), null, System.currentTimeMillis()));
                    }
                    return Envelope.response(req.id(), model(LID, serialized, "hello"));
                });

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.request().params().get("chatId").asString()).isEqualTo(LID); // addressed to the lid, not @c.us
        assertThat(chats.count()).isEqualTo(1);
        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(number.getId(), LID).orElseThrow();
        assertThat(chat.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(contacts.count()).isEqualTo(1);
        Contact contact = contacts.findById(chat.getContactId()).orElseThrow();
        assertThat(contact.getPhone()).isEqualTo("919811111111");
        assertThat(contact.getWaId()).isEqualTo(LID);
        if (known != null) {
            assertThat(contact.getId()).isEqualTo(known.getId());
            assertThat(contact.getName()).isEqualTo(known.getName());
        }
    }

    @Test
    void lookupLidPutsTheChatUnderTheLid() throws Exception {
        lookupLidPutsTheChatUnderIt(false, null);
    }

    @Test
    void lookupLidPutsTheChatUnderTheLidWhenTheEventComesFirst() throws Exception {
        lookupLidPutsTheChatUnderIt(true, null);
    }

    @Test
    void lookupLidOfAKnownContactIsLinked() throws Exception {
        lookupLidPutsTheChatUnderIt(false, knownContact());
    }

    @Test
    void lookupLidOfAKnownContactIsLinkedWhenTheEventComesFirst() throws Exception {
        lookupLidPutsTheChatUnderIt(true, knownContact());
    }

    @Test
    void cachedNotRegisteredDoesNotBlockAnExistingChat() throws Exception {
        seedLookup("919811111111", false, null);
        WaWebChat existing = chatOf("919811111111@c.us", "Nia", agent, null);

        // an existing chat needs no lookup: the first device request must be sendMessage
        Exchange ex = sendOkWithoutLookup(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                "919811111111@c.us", "true_919811111111@c.us_1", "hello");

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(existing.getId());
    }

    @Test
    void cachedLookupSkipsTheDevice() throws Exception {
        seedLookup("919811111111", true, LID);

        // the fresh cached row answers: the first device request must be sendMessage
        Exchange ex = sendOkWithoutLookup(agentToken, Map.of("phone", "919811111111", "text", "hello"), LID,
                "true_" + LID + "_1", "hello");

        assertThat(ex.request().params().get("chatId").asString()).isEqualTo(LID);
    }

    /** A chat ingest created under a lid whose contact never got a phone. */
    private WaWebChat hiddenLidChat(User assignedTo) {
        Contact c = new Contact();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setWaId(LID);
        c.setSource(me.nageshk.omnichannel.db.contact.ContactSource.CHAT);
        Contact hidden = contacts.save(c);
        WaWebChat chat = chat(number, brand, hidden, 1);
        chat.setRemoteId(LID);
        chat.setAssignedUserId(assignedTo == null ? null : assignedTo.getId());
        return chats.save(chat);
    }

    @Test
    void lidOnlyChatIsFoundThroughTheLookup() throws Exception {
        WaWebChat lid = hiddenLidChat(agent);

        Exchange ex = viaDevice(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                req -> lookupAnswer("919811111111@c.us", LID),
                req -> Envelope.response(req.id(), model(LID, "true_" + LID + "_1", "hello")));

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.request().params().get("chatId").asString()).isEqualTo(LID); // addressed by the looked-up id
        assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(lid.getId());
        assertThat(chats.count()).isEqualTo(1);
        // the chat is saved (has an id), and record only touches contacts for unsaved chats: the phone-less
        // hidden contact stays as it is (without the lookup the send would go to @c.us and link the phone)
        assertThat(contacts.count()).isEqualTo(1);
        assertThat(contacts.findById(lid.getContactId()).orElseThrow().getPhone()).isNull();
    }

    @Test
    void lidOnlyChatOfSomeoneElseIsRefusedAfterLookup() throws Exception {
        hiddenLidChat(agent2);

        ResponseEntity<JsonNode> res = postAfterLookup(agentToken, Map.of("phone", "919811111111", "text", "hello"),
                lookupAnswer("919811111111@c.us", LID));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("CHAT_TAKEN");
        assertThat(chats.count()).isEqualTo(1);
    }
}
