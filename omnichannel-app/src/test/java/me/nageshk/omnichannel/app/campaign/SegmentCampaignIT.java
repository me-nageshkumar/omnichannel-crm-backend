package me.nageshk.omnichannel.app.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.nageshk.omnichannel.crm.segment.SegmentMembers;
import me.nageshk.omnichannel.crm.segment.SegmentResolver;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.campaign.SkipReason;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.DoNotContact;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.access.RoleCache;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Spec D C11–C14, C21, C23: a campaign whose audience is a CRM segment, and sends recorded on CRM timelines. */
class SegmentCampaignIT extends CampaignTestBase {

    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;
    @MockitoSpyBean SegmentResolver resolver;

    private User staff;
    private String template;

    @Override
    List<Class<?>> collections() {
        return super.collections();
    }

    @BeforeEach
    void setUpCrm() {
        for (String name : mongo.getCollectionNames()) if (name.startsWith("crm")) mongo.remove(new Query(), name);
        staff = testUsers.create("staff", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        rest.exchange(url("/api/customers/" + acme.getId() + "/workspaces/" + brand.getId() + "/crm"), PUT,
                auth(staff, Map.of("enabled", true)), JsonNode.class);
        template = template("Seg Hi", "Hi {{contact.name}}");
    }

    private WaWebChat chat(Contact c, PhoneNumber n) {
        WaWebChat chat = new WaWebChat();
        chat.setCustomerId(acme.getId());
        chat.setWorkspaceId(brand.getId());
        chat.setPhoneNumberId(n.getId());
        chat.setRemoteId(UUID.randomUUID() + "@c.us");
        chat.setContactId(c.getId());
        chat.setStatus(ChatStatus.OPEN);
        chat.setLastMessageAt(clock.instant());
        return mongo.save(chat);
    }

    /** Captures the host contact into the CRM on the number (into {@code pipelineId} when given); the CRM contact. */
    private JsonNode captured(Contact c, PhoneNumber n, String pipelineId) {
        Map<String, Object> body = pipelineId == null ? Map.of("chatId", chat(c, n).getId())
                : Map.of("chatId", chat(c, n).getId(), "pipelineId", pipelineId);
        ResponseEntity<JsonNode> res = api(POST, "/crm/contacts/capture", manager, body);
        assertThat(res.getStatusCode()).as(res.toString()).isEqualTo(HttpStatus.OK);
        return res.getBody().get("contact");
    }

    private String segment(String name) {
        ResponseEntity<JsonNode> res = api(POST, "/crm/segments", manager, Map.of("name", name, "description", "",
                "rules", Map.of("combinator", "and", "not", false, "rules",
                        List.of(Map.of("field", "contact.lifecycleStatus", "operator", "is", "value", "lead")))));
        assertThat(res.getStatusCode()).as(res.toString()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().get("id").asString();
    }

    private Map<String, Object> segmentCampaign(String name, String segmentId, PhoneNumber... senders) {
        return Map.of("name", name, "audience", Map.of("kind", "SEGMENT", "segmentId", segmentId),
                "templateIds", List.of(template), "placeholders", List.of(), "senderIds",
                java.util.Arrays.stream(senders).map(PhoneNumber::getId).toList());
    }

    private String created(User who, Map<String, Object> body) {
        ResponseEntity<JsonNode> res = api(POST, "/campaigns", who, body);
        assertThat(res.getStatusCode()).as(res.toString()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().get("id").asString();
    }

    private List<CampaignRecipient> recipientsOf(String campaignId) {
        return mongo.find(new Query(Criteria.where("campaignId").is(campaignId)), CampaignRecipient.class);
    }

    private User userWith(String username, String... authorities) {
        Role r = new Role();
        r.setName("role-" + username);
        r.setLevel(RoleLevel.BRAND);
        r.setAuthorities(new HashSet<>(Set.of(authorities)));
        r = roles.save(r);
        roleCache.evictAll();
        return testUsers.createInCustomer(acme.getId(), username, null, List.of(new WorkspaceRole(brand.getId(), r.getId())));
    }

    private Document crmContact(String id) {
        return mongo.findById(new ObjectId(id), Document.class, "crmContacts");
    }

    private List<Document> campaignSentActivities() {
        return mongo.find(new Query(Criteria.where("workspaceId").is(brand.getId()).and("type").is("CAMPAIGN_SENT")),
                Document.class, "crmActivities");
    }

    @Test
    void createCampaignWithSegmentAudience() {
        String seg = segment("Leads");
        String id = created(manager, segmentCampaign("Diwali", seg, n1));
        JsonNode c = api(GET, "/campaigns/" + id, manager, null).getBody();
        assertThat(c.get("audience").get("kind").asString()).isEqualTo("SEGMENT");
        assertThat(c.get("audience").get("segmentId").asString()).isEqualTo(seg);
        assertThat(c.get("audience").get("segmentName").asString()).isEqualTo("Leads");
    }

    @Test
    void unknownSegmentIs400OnTheField() {
        ResponseEntity<JsonNode> res = api(POST, "/campaigns", manager, segmentCampaign("Bad", new ObjectId().toHexString(), n1));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).contains("audience.segmentId");
        ResponseEntity<JsonNode> missing = api(POST, "/campaigns", manager, Map.of("name", "Bad2", "audience",
                Map.of("kind", "SEGMENT"), "templateIds", List.of(template), "placeholders", List.of(),
                "senderIds", List.of(n1.getId())));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().toString()).contains("audience.segmentId");
    }

    @Test
    void startResolvesForTheStarterAndMaterialises() {
        Contact a = contact("Asha", "919811200001");
        Contact b = contact("Ravi", "919811200002");
        Contact stop = contact("Stop", "919811200003");
        stop.setDoNotContact(new DoNotContact(clock.instant(), DoNotContact.Reason.MANUAL, null));
        mongo.save(stop);
        captured(a, n1, null);
        captured(b, n1, null);
        captured(stop, n1, null);
        contact("Not in CRM", "919811200004");
        String id = created(manager, segmentCampaign("Diwali", segment("Leads"), n1));
        ResponseEntity<JsonNode> res = api(POST, "/campaigns/" + id + "/start", manager, null);
        assertThat(res.getStatusCode()).as(res.toString()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asString()).isEqualTo("RUNNING");
        List<CampaignRecipient> rs = recipientsOf(id);
        assertThat(rs).extracting(CampaignRecipient::getContactId).containsExactlyInAnyOrder(a.getId(), b.getId(), stop.getId());
        assertThat(rs.stream().filter(r -> r.getContactId().equals(stop.getId())).findFirst().orElseThrow())
                .satisfies(r -> {
                    assertThat(r.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
                    assertThat(r.getSkipReason()).isEqualTo(SkipReason.DO_NOT_CONTACT);
                });
        assertThat(rs.stream().filter(r -> r.getContactId().equals(a.getId())).findFirst().orElseThrow())
                .satisfies(r -> {
                    assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
                    assertThat(r.getPreferredSenderId()).as("the chat is on n1").isEqualTo(n1.getId());
                });
    }

    @Test
    void campaignResolvesForTheStarter() {
        String second = api(POST, "/crm/pipelines", manager, Map.of("name", "Second")).getBody().get("id").asString();
        Contact sales = contact("Sales", "919811200011");
        Contact other = contact("Second", "919811200012");
        captured(sales, n1, null);
        captured(other, n1, second);
        String seg = segment("Leads");
        User marketer = userWith("marketer", "crm:view", "crm:segments", "campaign:view", "campaign:manage");
        api(PUT, "/crm/users/" + marketer.getId() + "/pipelines", manager, Map.of("pipelineIds", List.of("seed-sales-" + brand.getId())));
        String id = created(marketer, segmentCampaign("Spring", seg, n1));
        ResponseEntity<JsonNode> res = api(POST, "/campaigns/" + id + "/start", marketer, null);
        assertThat(res.getStatusCode()).as(res.toString()).isEqualTo(HttpStatus.OK);
        assertThat(recipientsOf(id)).extracting(CampaignRecipient::getContactId).containsExactly(sales.getId());
        JsonNode s = api(GET, "/crm/segments/" + seg, manager, null).getBody();
        assertThat(s.get("lastCampaignId").asString()).isEqualTo(id);
        assertThat(s.get("lastCampaignName").asString()).isEqualTo("Spring");
        assertThat(s.get("lastSentAt").isNull()).isFalse();
    }

    @Test
    void starterWithoutSegmentsAuthorityGets403() {
        captured(contact("Asha", "919811200021"), n1, null);
        String seg = segment("Leads");
        User plain = userWith("plain", "crm:view", "campaign:view", "campaign:manage");
        ResponseEntity<JsonNode> create = api(POST, "/campaigns", plain, segmentCampaign("Nope", seg, n1));
        assertThat(create.getStatusCode()).as(create.toString()).isEqualTo(HttpStatus.FORBIDDEN);
        String id = created(manager, segmentCampaign("Theirs", seg, n1));
        ResponseEntity<JsonNode> res = api(POST, "/campaigns/" + id + "/start", plain, null);
        assertThat(res.getStatusCode()).as(res.toString()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(campaign(id).getStatus().name()).isEqualTo("DRAFT");
        assertThat(recipientsOf(id)).isEmpty();
    }

    @Test
    void runningCampaignBlocksDeleteAndKeepsSnapshot() {
        captured(contact("Asha", "919811200031"), n1, null);
        String seg = segment("Leads");
        String id = created(manager, segmentCampaign("Diwali", seg, n1));
        api(POST, "/campaigns/" + id + "/start", manager, null);
        ResponseEntity<JsonNode> del = api(DELETE, "/crm/segments/" + seg, manager, null);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(del.getBody().get("message").asString()).contains("Diwali");
        JsonNode s = api(GET, "/crm/segments/" + seg, manager, null).getBody();
        api(PUT, "/crm/segments/" + seg, manager, Map.of("name", "Leads renamed", "description", "",
                "rules", s.get("rules"), "version", s.get("version").asLong()));
        assertThat(api(GET, "/campaigns/" + id, manager, null).getBody().get("audience").get("segmentName").asString())
                .isEqualTo("Leads");
    }

    @Test
    void truncatedSegmentRefusesToStart() {
        captured(contact("Asha", "919811200041"), n1, null);
        String id = created(manager, segmentCampaign("Huge", segment("Leads"), n1));
        doAnswer(inv -> {
            SegmentMembers real = (SegmentMembers) inv.callRealMethod();
            return new SegmentMembers(real.members(), 60_000, 60_000, true);
        }).when(resolver).resolve(any(), any());
        ResponseEntity<JsonNode> res = api(POST, "/campaigns/" + id + "/start", manager, null);
        assertThat(res.getStatusCode()).as(res.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).contains("audience.segmentId").contains("50,000");
        assertThat(campaign(id).getStatus().name()).isEqualTo("DRAFT");
        assertThat(recipientsOf(id)).isEmpty();
    }

    @Test
    void memberWithoutChatPrefersItsChannelAccount() {
        Contact imported = contact("Imported", "919811200051");
        JsonNode crm = captured(imported, n2, null);
        mongo.remove(new Query(Criteria.where("contactId").is(imported.getId())), WaWebChat.class); // never chatted
        assertThat(crm.get("channel").get("phoneNumberId").asString()).isEqualTo(n2.getId());
        String seg = segment("Leads");
        String both = created(manager, segmentCampaign("Both numbers", seg, n1, n2));
        api(POST, "/campaigns/" + both + "/start", manager, null);
        assertThat(recipientsOf(both)).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(r.getPreferredSenderId()).isEqualTo(n2.getId());
        });
        mongo.remove(new Query(Criteria.where("campaignId").is(both)), CampaignRecipient.class);
        String onlyN1 = created(manager, segmentCampaign("Only n1", seg, n1));
        api(POST, "/campaigns/" + onlyN1 + "/start", manager, null);
        assertThat(recipientsOf(onlyN1)).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(r.getPreferredSenderId()).as("n2 is not a chosen sender").isNull();
        });
    }

    @Test
    void campaignSentWritesTimelineWithoutTouchingActivity() throws Exception {
        Contact asha = contact("Asha", "919811200061");
        JsonNode onN1 = captured(asha, n1, null);
        JsonNode onN2 = captured(asha, n2, null);
        String dealId = api(POST, "/crm/deals/search", manager, Map.of()).getBody().get("items").get(0).get("id").asString();
        mongo.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(dealId))), new Update().set("rotting", true), "crmDeals");
        Instant before1 = crmContact(onN1.get("id").asString()).getDate("lastActivityAt").toInstant();
        Instant before2 = crmContact(onN2.get("id").asString()).getDate("lastActivityAt").toInstant();
        clock.advance(java.time.Duration.ofMinutes(5));
        String seg = segment("Leads");
        try (AutoDevice d = device(n1)) {
            String id = created(manager, segmentCampaign("Diwali", seg, n1, n2));
            api(POST, "/campaigns/" + id + "/start", manager, null);
            tickNow();
            CampaignRecipient r = recipient(id, "919811200061");
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.SENT);
            List<Document> sent = campaignSentActivities();
            assertThat(sent).hasSize(2);
            assertThat(sent).extracting(a -> a.get("target", Document.class).getString("id"))
                    .containsExactlyInAnyOrder(onN1.get("id").asString(), onN2.get("id").asString());
            Document one = sent.get(0);
            assertThat(one.getString("actorType")).isEqualTo("SYSTEM");
            assertThat(one.getString("summary")).isEqualTo("Campaign \"Diwali\" sent");
            Document payload = one.get("payload", Document.class);
            assertThat(payload.getString("campaignId")).isEqualTo(id);
            assertThat(payload.getString("segmentId")).isEqualTo(seg);
            assertThat(payload.getString("segmentName")).isEqualTo("Leads");
            assertThat(payload.getString("channelAccountId")).isEqualTo(n1.getId());
            assertThat(payload.getString("channelKind")).isEqualTo("WHATSAPP_WEB");
            assertThat(one.getString("phoneNumberId")).isEqualTo(n1.getId());
        }
        assertThat(crmContact(onN1.get("id").asString()).getDate("lastActivityAt").toInstant()).isEqualTo(before1);
        assertThat(crmContact(onN2.get("id").asString()).getDate("lastActivityAt").toInstant()).isEqualTo(before2);
        assertThat(mongo.findById(new ObjectId(dealId), Document.class, "crmDeals").getBoolean("rotting")).isTrue();
    }

    @Test
    void observerIsNoOpForNonCrmContacts() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Plain", List.of("919811200071"), n1);
            tickNow();
            assertThat(recipient(id, "919811200071").getStatus()).isEqualTo(RecipientStatus.SENT);
            assertThat(campaignSentActivities()).isEmpty();
        }
    }
}
