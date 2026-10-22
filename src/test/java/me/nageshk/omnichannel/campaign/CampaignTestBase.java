package me.nageshk.omnichannel.campaign;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactAttribute;
import me.nageshk.omnichannel.contact.ContactFilter;
import me.nageshk.omnichannel.contact.ContactLabel;
import me.nageshk.omnichannel.inbox.InboxSetting;
import me.nageshk.omnichannel.inbox.WaNumberLookup;
import me.nageshk.omnichannel.inbox.WaWebChat;
import me.nageshk.omnichannel.inbox.WaWebChatAction;
import me.nageshk.omnichannel.inbox.WaWebMessage;
import org.springframework.data.mongodb.core.query.Criteria;
import me.nageshk.omnichannel.template.MessageTemplate;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.support.MutableClock;
import me.nageshk.omnichannel.support.TestDevices;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
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

/**
 * Fixture of the campaign tests: a customer with two workspaces, three numbers and three brand users, a movable
 * clock, and a REST client against the running server.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
@Import(CampaignTestBase.Fixtures.class)
abstract class CampaignTestBase {

    /** Wednesday 2026-10-21, 11:00 in Asia/Kolkata. */
    static final Instant WED_11_IST = Instant.parse("2026-10-21T05:30:00Z");

    @TestConfiguration
    static class Fixtures {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(WED_11_IST);
        }

        @Bean
        @Primary
        FixedRandom fixedRandom() {
            return new FixedRandom();
        }
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired MutableClock clock;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired MongoTemplate mongo;
    @Autowired FixedRandom random;
    @Autowired @Qualifier("campaignCsvGridFs") GridFsTemplate csvFiles;
    @Autowired CampaignSenderService senders;
    @Autowired CampaignSenderRepository senderRepository;
    @Autowired CampaignRunner runner;

    Customer acme;
    Workspace brand;
    Workspace other;
    PhoneNumber n1;
    PhoneNumber n2;
    PhoneNumber otherNumber;
    User manager;
    User lead;
    User agent;

    /** The collections each test starts without; later tasks add their documents here. */
    List<Class<?>> collections() {
        return List.of(CampaignSettings.class, CampaignSender.class, CampaignRecipient.class, Campaign.class,
                Contact.class, ContactFilter.class, ContactAttribute.class, ContactLabel.class,
                MessageTemplate.class, WaWebChat.class, WaWebMessage.class, WaWebChatAction.class, WaNumberLookup.class, InboxSetting.class);
    }

    @BeforeEach
    void setUpCampaigns() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
        n1 = testDevices.number(brand, "9198" + (System.nanoTime() % 100000000));
        n2 = testDevices.number(brand, "9197" + (System.nanoTime() % 100000000));
        otherNumber = testDevices.number(other, "9196" + (System.nanoTime() % 100000000));
        manager = brandUser(brand, "manager", BuiltInRoles.WORKSPACE_MANAGER);
        lead = brandUser(brand, "lead", BuiltInRoles.TEAM_LEAD);
        agent = brandUser(brand, "agent", BuiltInRoles.AGENT);
    }

    @AfterEach
    void cleanUpCampaigns() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : collections()) mongo.remove(new Query(), c);
        csvFiles.delete(new Query());
        clock.set(WED_11_IST);
        random.offset = 0;
        random.max = false;
    }

    /** The number's sender document, created when absent. */
    CampaignSender sender(PhoneNumber n) {
        senders.ensure(n.getWorkspaceId(), n.getId());
        return senderRepository.findByPhoneNumberId(n.getId()).orElseThrow();
    }

    /** Loads the number's sender, applies {@code change} and saves it. */
    void sender(PhoneNumber n, Consumer<CampaignSender> change) {
        CampaignSender s = sender(n);
        change.accept(s);
        senderRepository.save(s);
    }

    Campaign campaign(String id) {
        return mongo.findById(id, Campaign.class);
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
        ResponseEntity<JsonNode> res = api(HttpMethod.POST, "/templates", manager,
                Map.of("name", name, "type", "TEXT", "content", Map.of("body", body), "placeholders", settings));
        return res.getBody().get("id").asString();
    }

    /** A saved contact filter of the brand; returns its id. */
    String filter(String name, Map<String, Object> spec) {
        ResponseEntity<JsonNode> res = api(HttpMethod.POST, "/contact-filters", manager,
                Map.of("name", name, "filter", spec));
        return res.getBody().get("id").asString();
    }

    /** A label of the brand. */
    ContactLabel label(String name) {
        ContactLabel l = new ContactLabel();
        l.setWorkspaceId(brand.getId());
        l.setName(name);
        l.setColor("#336699");
        return mongo.save(l);
    }

    /** A saved ADVANCED filter of the brand over the rules; returns its id. */
    String advancedFilter(String name, Map<String, Object> rules) {
        ResponseEntity<JsonNode> res = api(HttpMethod.POST, "/contact-filters", manager,
                Map.of("name", name, "rules", rules));
        return res.getBody().get("id").asString();
    }

    Contact contact(String name, String phone) {
        Contact c = new Contact();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setPhone(phone);
        return mongo.save(c);
    }


    /** An unsaved PENDING recipient of the brand. */
    CampaignRecipient newRecipient(String campaignId, String phone) {
        CampaignRecipient r = new CampaignRecipient();
        r.setCampaignId(campaignId);
        r.setWorkspaceId(brand.getId());
        r.setPhone(phone);
        r.setStatus(RecipientStatus.PENDING);
        return r;
    }

    /** The stored recipient of the campaign with this phone. */
    CampaignRecipient recipient(String campaignId, String phone) {
        return mongo.findOne(new Query(Criteria.where("campaignId").is(campaignId).and("phone").is(phone)),
                CampaignRecipient.class);
    }

    /** A connected, ready device for the number that answers on its own; close it. */
    AutoDevice device(PhoneNumber n) throws Exception {
        return new AutoDevice(port, testDevices, n);
    }

    /** Runs one tick and waits until every sender's work of it has finished. */
    void tickNow() throws Exception {
        runner.tick().get(10, TimeUnit.SECONDS);
    }

    /** A fresh cached lookup of the brand for the phone, as n1 answered it. */
    void seedLookup(String phone, boolean registered) {
        WaNumberLookup row = new WaNumberLookup();
        row.setWorkspaceId(brand.getId());
        row.setPhoneNumberId(n1.getId());
        row.setPhone(phone);
        row.setRegistered(registered);
        row.setWaPhone(registered ? phone + "@c.us" : null);
        row.setCheckedAt(clock.instant());
        mongo.save(row);
    }

    /** The brand's template of this name, created when absent; returns its id. */
    String templateNamed(String name, String body) {
        MessageTemplate t = mongo.findOne(new Query(Criteria.where("workspaceId").is(brand.getId()).and("name").is(name)),
                MessageTemplate.class);
        return t != null ? t.getId() : template(name, body);
    }

    /** The brand's contact with this phone, created (as {@code name}) when absent. */
    Contact contactWithPhone(String name, String phone) {
        Contact c = mongo.findOne(new Query(Criteria.where("workspaceId").is(brand.getId()).and("phone").is(phone)),
                Contact.class);
        return c != null ? c : contact(name, phone);
    }

    /** Creates and starts a campaign over the whole brand's contacts, after making sure one exists per phone. */
    String startedFilterCampaign(String name, List<String> phones, PhoneNumber... senders) {
        for (int i = 0; i < phones.size(); i++) contactWithPhone("Lead " + i, phones.get(i));
        String filterId = filter("All " + name, Map.of());
        String t = templateNamed("Start Hi", "Hi {{contact.name}}");
        String id = api(HttpMethod.POST, "/campaigns", manager, filterCampaign(name, filterId, List.of(t), List.of(),
                java.util.Arrays.stream(senders).map(PhoneNumber::getId).toList())).getBody().get("id").asString();
        api(HttpMethod.POST, "/campaigns/" + id + "/start", manager, null);
        return id;
    }

    /** Creates and starts a CSV campaign whose phone column is {@code phone}. */
    String startedCsvCampaign(String name, String csv, String nameColumn, PhoneNumber... senders) {
        String t = templateNamed("Start Hi", "Hi {{contact.name}}");
        Map<String, Object> source = nameColumn == null ? Map.of("kind", "FIXED", "text", "there")
                : Map.of("kind", "CSV_COLUMN", "column", nameColumn);
        Map<String, Object> audience = new java.util.LinkedHashMap<>();
        audience.put("kind", "CSV");
        audience.put("phoneColumn", "phone");
        if (nameColumn != null) audience.put("nameColumn", nameColumn);
        Map<String, Object> body = Map.of("name", name, "audience", audience, "templateIds", List.of(t),
                "placeholders", List.of(Map.of("name", "contact.name", "source", source, "fallback", "")),
                "senderIds", java.util.Arrays.stream(senders).map(PhoneNumber::getId).toList());
        String id = multipart(HttpMethod.POST, "/campaigns", manager, body, csv).getBody().get("id").asString();
        api(HttpMethod.POST, "/campaigns/" + id + "/start", manager, null);
        return id;
    }

    /** The body of a campaign request aimed at a saved filter. */
    Map<String, Object> filterCampaign(String name, String filterId, List<String> templateIds,
            List<Map<String, Object>> placeholders, List<String> senderIds) {
        return Map.of("name", name, "audience", Map.of("kind", "FILTER", "filterId", filterId),
                "templateIds", templateIds, "placeholders", placeholders, "senderIds", senderIds);
    }

    User brandUser(Workspace w, String username, String roleName) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(w.getId(), testUsers.roleId(roleName))));
    }

    /** A fresh token: it carries the clock's current time. */
    String token(User u) {
        return testUsers.accessToken(u);
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpEntity<Object> auth(User who, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token(who));
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    /** {@code path} is relative to the brand's {@code /api/workspaces/{id}}. */
    ResponseEntity<JsonNode> api(HttpMethod m, String path, User who, Object body) {
        return rest.exchange(url("/api/workspaces/" + brand.getId() + path), m, auth(who, body), JsonNode.class);
    }

    /** Number of files in the {@code campaignCsv} bucket. */
    long gridFsFiles() {
        return mongo.getCollection("campaignCsv.files").countDocuments();
    }

    /**
     * A multipart request: part {@code campaign} as JSON and, unless {@code csv} is null, part {@code file}
     * named {@code leads.csv}. {@code path} is relative to the brand's {@code /api/workspaces/{id}}.
     */
    ResponseEntity<JsonNode> multipart(HttpMethod m, String path, User who, Object campaignJson, String csv) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        if (campaignJson != null) parts.add("campaign", new HttpEntity<>(campaignJson, json));
        if (csv != null) parts.add("file", fileResource(csv));
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token(who));
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(url("/api/workspaces/" + brand.getId() + path), m, new HttpEntity<>(parts, h),
                JsonNode.class);
    }

    ByteArrayResource fileResource(String csv) {
        return new ByteArrayResource(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "leads.csv";
            }
        };
    }
}
