package me.nageshk.omnichannel.app.automation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.app.support.MutableClock;
import me.nageshk.omnichannel.app.support.TestDevices;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationEvent;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationEventRecord;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.AutomationTest;
import me.nageshk.omnichannel.db.automation.ConditionNode;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.enablement.EnablementChange;
import me.nageshk.omnichannel.db.inbox.InboxSetting;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fixture of the automation tests: a customer with two brands, two numbers (n1 owned by team {@code support}, whose
 * members are agent and agent2; n2 owned by nobody), brand users of every role, a movable clock at 2026-11-03 11:00
 * IST, and a REST client against the running server.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
@Import(AutomationTestBase.Fixtures.class)
abstract class AutomationTestBase {

    static final Instant NOW = Instant.parse("2026-11-03T05:30:00Z");

    @TestConfiguration
    static class Fixtures {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired MutableClock clock;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired WaWebChatRepository chats;
    @Autowired TeamRepository teams;
    @Autowired PhoneNumberRepository phoneNumbers;

    Customer acme;
    Workspace brand;
    Workspace other;
    Team support;
    PhoneNumber n1;
    PhoneNumber n2;
    User manager;
    User admin;
    User agent;
    User agent2;
    User outsider;
    User staff;

    /** The collections each test starts without. */
    List<Class<?>> collections() {
        return List.of(AutomationEvent.class, AutomationEventRecord.class, AutomationRule.class,
                AutomationRun.class, AutomationTest.class, Contact.class, WaWebChat.class, WaWebMessage.class,
                WaWebChatAction.class, MessageTemplate.class, InboxSetting.class, EnablementChange.class);
    }

    @BeforeEach
    void setUpAutomations() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
        n1 = testDevices.number(brand, "9198" + (System.nanoTime() % 100000000));
        n2 = testDevices.number(brand, "9197" + (System.nanoTime() % 100000000));
        manager = brandUser(brand, "manager", BuiltInRoles.WORKSPACE_MANAGER);
        admin = testUsers.createInCustomer(acme.getId(), "admin", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        agent = brandUser(brand, "agent", BuiltInRoles.AGENT);
        agent2 = brandUser(brand, "agent2", BuiltInRoles.AGENT);
        outsider = brandUser(brand, "outsider", BuiltInRoles.AGENT);
        staff = testUsers.create("staff", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        Team t = new Team();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(brand.getId());
        t.setName("support");
        t.setMemberIds(new ArrayList<>(List.of(agent.getId(), agent2.getId())));
        support = teams.save(t);
        n1.setTeamId(support.getId());
        n1 = phoneNumbers.save(n1);
    }

    @AfterEach
    void cleanUpAutomations() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : collections()) mongo.remove(new Query(), c);
        for (String name : mongo.getCollectionNames())
            if (name.startsWith("crm")) mongo.remove(new Query(), name);
        clock.set(NOW);
    }

    User brandUser(Workspace w, String username, String roleName) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(w.getId(), testUsers.roleId(roleName))));
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpEntity<Object> auth(User who, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(testUsers.accessToken(who));
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    /** {@code path} is relative to the brand's {@code /api/workspaces/{id}/automations}. */
    ResponseEntity<JsonNode> api(HttpMethod m, String path, User who, Object body) {
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/automations" + path), m, auth(who, body),
                JsonNode.class);
    }

    /** A request to any path of the server, as {@code who}. */
    ResponseEntity<JsonNode> call(HttpMethod m, String path, User who, Object body) {
        return rest.exchange(url(path), m, auth(who, body), JsonNode.class);
    }

    /** The payload of a {@code message_create} event for a message of the chat with {@code remote}. */
    ObjectNode payload(String remote, boolean fromMe, String type, String body, String notifyName) {
        ObjectNode p = mapper.createObjectNode();
        ObjectNode id = p.putObject("id");
        id.put("fromMe", fromMe);
        id.put("remote", remote);
        id.put("_serialized", fromMe + "_" + remote + "_" + COUNTER.incrementAndGet());
        p.put("fromMe", fromMe);
        p.put("timestamp", clock.instant().getEpochSecond());
        p.put("type", type);
        if (body != null) p.put("body", body);
        if (notifyName != null) p.put("notifyName", notifyName);
        return p;
    }

    /** Publishes the payload as a {@code message_create} event of the number; returns the stored chat or null. */
    WaWebChat publish(PhoneNumber n, ObjectNode payload) {
        publisher.publishEvent(new WhatsAppEvent(this, n.getId(), brand.getId(), acme.getId(), "message_create",
                payload, null, null));
        String remote = payload.get("id").get("remote").asString();
        return chats.findByPhoneNumberIdAndRemoteId(n.getId(), remote).orElse(null);
    }

    /** An incoming message of the type from the remote chat; returns the stored chat. */
    WaWebChat incoming(PhoneNumber n, String remote, String type, String body, String notifyName) {
        return publish(n, payload(remote, false, type, body, notifyName));
    }

    /** A rule of the brand, saved directly. */
    AutomationRule rule(String name, AutomationEventKind event, RuleStatus status, Integer priority,
            ConditionNode conditions, AutomationAction... actions) {
        AutomationRule r = new AutomationRule();
        r.setCustomerId(acme.getId());
        r.setWorkspaceId(brand.getId());
        r.setName(name);
        r.setEvent(event);
        r.setStatus(status);
        r.setPriority(priority);
        r.setConditions(conditions);
        r.setActions(new ArrayList<>(List.of(actions)));
        if (status == RuleStatus.PUBLISHED) r.setPublishedAt(clock.instant());
        return mongo.save(r);
    }

    /** A TEXT template of the brand with the given body; returns its id. */
    String template(String name, String body) {
        List<Map<String, Object>> settings = new ArrayList<>();
        Matcher m = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}").matcher(body);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String ph = m.group(1);
            if (!seen.add(ph)) continue;
            settings.add(ph.startsWith("contact.") ? Map.of("name", ph, "fallback", "there")
                    : Map.of("name", ph, "label", ph, "required", false));
        }
        ResponseEntity<JsonNode> res = call(HttpMethod.POST, "/api/workspaces/" + brand.getId() + "/templates",
                manager, Map.of("name", name, "type", "TEXT", "content", Map.of("body", body), "placeholders",
                        settings));
        return res.getBody().get("id").asString();
    }

    /** Switches the brand's CRM on, as the platform user. */
    void enableCrm() {
        call(HttpMethod.PUT, "/api/customers/" + acme.getId() + "/workspaces/" + brand.getId() + "/crm", staff,
                Map.of("enabled", true));
    }

    static ConditionNode leaf(String field, String op, Object value) {
        return new ConditionNode(null, null, null, field, op, value);
    }

    static ConditionNode all(ConditionNode... rules) {
        return new ConditionNode("and", null, List.of(rules), null, null, null);
    }

    static ConditionNode any(ConditionNode... rules) {
        return new ConditionNode("or", null, List.of(rules), null, null, null);
    }

    List<AutomationEvent> queued() {
        return mongo.findAll(AutomationEvent.class);
    }

    AutomationTest waitingTest(AutomationEventKind kind, String targetContactId, String targetPhone) {
        AutomationTest t = new AutomationTest();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(brand.getId());
        t.setEvent(kind);
        t.setActive(true);
        t.setStatus(me.nageshk.omnichannel.db.automation.TestStatus.WAITING);
        t.setTargetContactId(targetContactId);
        t.setTargetPhone(targetPhone);
        t.setWaitingSince(clock.instant());
        t.setLastActivityAt(clock.instant());
        return mongo.save(t);
    }
}
