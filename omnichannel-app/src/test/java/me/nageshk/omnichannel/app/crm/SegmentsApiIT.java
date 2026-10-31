package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabel;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.RoleCache;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.JsonNode;

/** Spec D §5: segments CRUD, the field catalogue and the per-caller preview. */
class SegmentsApiIT extends CrmTestBase {

    @Autowired ContactLabelRepository labelRepository;
    @Autowired ContactAttributeRepository attributeRepository;
    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;

    private String segments() {
        return crm() + "/segments";
    }

    static Map<String, Object> rule(String field, String op, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("operator", op);
        m.put("value", value);
        return m;
    }

    static Map<String, Object> group(String combinator, boolean not, Object... rules) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("combinator", combinator);
        m.put("not", not);
        m.put("rules", List.of(rules));
        return m;
    }

    static Map<String, Object> segment(String name, Map<String, Object> rules) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", "about " + name);
        m.put("rules", rules);
        return m;
    }

    private JsonNode create(String token, Map<String, Object> body) throws Exception {
        return json(send(post(segments()), token, body).andExpect(status().isCreated()));
    }

    private JsonNode preview(String token, Map<String, Object> rules) throws Exception {
        return json(send(post(segments() + "/preview"), token, Map.of("rules", rules)).andExpect(status().isOk()));
    }

    private String secondPipeline() throws Exception {
        return json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second")).andExpect(status().isCreated()))
                .get("id").asText();
    }

    private JsonNode tierField(String entityType) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityType", entityType);
        m.put("key", "tier");
        m.put("label", "Tier");
        m.put("type", "SELECT");
        m.put("options", List.of(Map.of("key", "gold", "label", "Gold"), Map.of("key", "silver", "label", "Silver")));
        return json(send(post(crm() + "/fields"), managerToken, m).andExpect(status().isCreated()));
    }

    private ContactLabel label(String name) {
        ContactLabel l = new ContactLabel();
        l.setWorkspaceId(brand.getId());
        l.setName(name);
        l.setNameKey(name.toLowerCase());
        return labelRepository.save(l);
    }

    private ContactAttribute attribute(String name) {
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(brand.getId());
        a.setName(name);
        a.setNameKey(name.toLowerCase());
        a.setKey(name.toLowerCase());
        return attributeRepository.save(a);
    }

    private void setContact(String crmContactId, Object... kv) {
        Update u = new Update();
        for (int i = 0; i < kv.length; i += 2) u.set((String) kv[i], kv[i + 1]);
        mongo.updateFirst(new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(crmContactId))), u, "crmContacts");
    }

    /** The user {@link #tokenWithAuthorities} created last. */
    private User lastUser;

    /** A brand user whose role has exactly these authorities (the agent role has no crm:segments). */
    private String tokenWithAuthorities(String username, String... authorities) {
        Role r = new Role();
        r.setName("role-" + username);
        r.setLevel(RoleLevel.BRAND);
        r.setAuthorities(new java.util.HashSet<>(Set.of(authorities)));
        r = roles.save(r);
        roleCache.evictAll();
        lastUser = testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brand.getId(), r.getId())));
        return testUsers.accessToken(lastUser);
    }

    private static JsonNode field(JsonNode catalog, String key) {
        for (JsonNode g : catalog.get("groups")) for (JsonNode f : g.get("fields")) if (f.get("key").asText().equals(key)) return f;
        return null;
    }

    private static List<String> values(JsonNode options) {
        List<String> out = new ArrayList<>();
        if (options != null) for (JsonNode o : options) out.add(o.get("value").asText());
        return out;
    }

    @Test
    void createListGetUpdateDelete() throws Exception {
        enableCrm();
        captured(contact("Asha", "919900000901"), number);
        JsonNode created = create(managerToken, segment("Zeta leads", group("and", false, rule("contact.lifecycleStatus", "is", "lead"))));
        assertThat(created.get("id").asText()).isNotBlank();
        assertThat(created.get("version").asLong()).isEqualTo(0);
        assertThat(created.get("cachedCounts").get("total").asLong()).isEqualTo(1);
        assertThat(created.get("cachedCounts").get("people").asLong()).isEqualTo(1);
        assertThat(created.get("cachedCounts").get("at").isNull()).isFalse();
        assertThat(created.get("lastSentAt").isNull()).isTrue();
        create(managerToken, segment("Alpha all", group("and", false, rule("contact.email", "isNotSet", null))));
        JsonNode list = json(send(get(segments()), managerToken, null).andExpect(status().isOk()));
        assertThat(list).extracting(n -> n.get("name").asText()).containsExactly("Alpha all", "Zeta leads");
        String id = created.get("id").asText();
        JsonNode got = json(send(get(segments() + "/" + id), managerToken, null).andExpect(status().isOk()));
        assertThat(got.get("rules").get("rules").get(0).get("field").asText()).isEqualTo("contact.lifecycleStatus");
        assertThat(got.get("rules").get("rules").get(0).has("missing")).isFalse();
        Map<String, Object> update = segment("Zeta customers", group("and", false, rule("contact.lifecycleStatus", "is", "customer")));
        update.put("version", 0);
        JsonNode updated = json(send(put(segments() + "/" + id), managerToken, update).andExpect(status().isOk()));
        assertThat(updated.get("version").asLong()).isEqualTo(1);
        assertThat(updated.get("cachedCounts").get("total").asLong()).isEqualTo(0);
        send(put(segments() + "/" + id), managerToken, update).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        send(delete(segments() + "/" + id), managerToken, null).andExpect(status().isNoContent());
        send(get(segments() + "/" + id), managerToken, null).andExpect(status().isNotFound());
    }

    @Test
    void nameTakenIgnoresCase() throws Exception {
        enableCrm();
        create(managerToken, segment("Gold leads", group("and", false, rule("contact.email", "isSet", null))));
        send(post(segments()), managerToken, segment(" GOLD LEADS ", group("and", false, rule("contact.email", "isSet", null))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEGMENT_NAME_TAKEN"));
        Map<String, Object> blank = segment("", group("and", false, rule("contact.email", "isSet", null)));
        JsonNode e = json(send(post(segments()), managerToken, blank).andExpect(status().isBadRequest()));
        assertThat(e.get("code").asText()).isEqualTo("INVALID_SEGMENT");
        assertThat(e.get("name").asText()).contains("1 to 80");
    }

    @Test
    void invalidRulesReportEveryPath() throws Exception {
        enableCrm();
        Map<String, Object> body = segment("Bad", group("and", false,
                group("and", false, rule("contact.email", "is", "x"), rule("contact.openDealCount", "contains", "3")),
                rule("contact.phone", "is", "y")));
        JsonNode e = json(send(post(segments()), managerToken, body).andExpect(status().isBadRequest()));
        assertThat(e.get("code").asText()).isEqualTo("INVALID_SEGMENT");
        assertThat(e.get("rules[0].rules[1].operator").asText()).contains("not an operator");
        assertThat(e.get("rules[1].field").asText()).isEqualTo("is not a field segments can use");
        send(post(segments() + "/preview"), managerToken, Map.of("rules", group("and", false, rule("deal.stageId", "is", "s1"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SEGMENT"));
    }

    @Test
    void fieldsCatalogueForManager() throws Exception {
        enableCrm();
        String second = secondPipeline();
        tierField("CONTACT");
        tierField("DEAL");
        ContactLabel vip = label("VIP");
        ContactAttribute city = attribute("City");
        JsonNode c = json(send(get(segments() + "/fields"), managerToken, null).andExpect(status().isOk()));
        assertThat(c.get("groups")).extracting(g -> g.get("key").asText()).containsExactly("contact", "deal", "channel");
        assertThat(c.get("groups").get(1).get("label").asText()).isEqualTo("Has a deal where");
        JsonNode stage = field(c, "deal.stageId");
        assertThat(stage.get("dependsOn").asText()).isEqualTo("deal.pipelineId");
        assertThat(stage.get("optionsByParent").has(salesId())).isTrue();
        assertThat(stage.get("optionsByParent").has(second)).isTrue();
        assertThat(stage.get("optionsByParent").get(salesId())).isNotEmpty();
        assertThat(values(field(c, "deal.pipelineId").get("options"))).containsExactlyInAnyOrder(salesId(), second);
        assertThat(values(field(c, "channel.label").get("options"))).containsExactly(vip.getId());
        JsonNode attr = field(c, "channel.attribute:" + city.getId());
        assertThat(attr.get("label").asText()).isEqualTo("City");
        assertThat(attr.get("type").asText()).isEqualTo("TEXT");
        JsonNode tier = field(c, "contact.custom:tier");
        assertThat(tier.get("type").asText()).isEqualTo("PICK");
        assertThat(values(tier.get("options"))).containsExactly("gold", "silver");
        assertThat(field(c, "deal.custom:tier").get("label").asText()).isEqualTo("Tier");
        assertThat(values(field(c, "contact.lifecycleStatus").get("options"))).contains("lead", "customer");
        assertThat(values(field(c, "contact.channelAccountId").get("options"))).containsExactlyInAnyOrder(number.getId(), number2.getId());
        assertThat(values(field(c, "deal.status").get("options"))).containsExactly("OPEN", "WON", "LOST");
        assertThat(field(c, "contact.ownerId").has("options")).isFalse();
        assertThat(field(c, "contact.openDealAmount").get("operators")).extracting(JsonNode::asText).contains("gte", "between");
    }

    @Test
    void fieldsCatalogueForAgentOffersOnlyTheirPipelines() throws Exception {
        enableCrm();
        String second = secondPipeline();
        String marketer = tokenWithAuthorities("marketer", "crm:view", "crm:segments");
        JsonNode mine = json(send(get(segments() + "/fields"), marketer, null).andExpect(status().isOk()));
        assertThat(values(field(mine, "deal.pipelineId").get("options"))).isEmpty();
        assignPipelines(lastUser, second);
        JsonNode after = json(send(get(segments() + "/fields"), marketer, null).andExpect(status().isOk()));
        assertThat(values(field(after, "deal.pipelineId").get("options"))).containsExactly(second);
        assertThat(field(after, "deal.stageId").get("optionsByParent").has(salesId())).isFalse();
    }

    @Test
    void previewGroupsByChannelAccount() throws Exception {
        enableCrm();
        number.setLabel("Sales line");
        phoneNumbers.save(number);
        Contact both = contact("Both", "919900000911");
        captured(both, number);
        captured(both, number2);
        captured(contact("One", "919900000912"), number);
        JsonNode p = preview(managerToken, group("and", false, rule("contact.lifecycleStatus", "is", "lead")));
        assertThat(p.get("total").asLong()).isEqualTo(3);
        assertThat(p.get("people").asLong()).isEqualTo(2);
        assertThat(p.get("truncated").asBoolean()).isFalse();
        long sum = 0;
        for (JsonNode row : p.get("channelAccounts")) sum += row.get("count").asLong();
        assertThat(sum).isEqualTo(3);
        JsonNode sales = null;
        for (JsonNode row : p.get("channelAccounts")) if (row.get("channelAccountId").asText().equals(number.getId())) sales = row;
        assertThat(sales.get("label").asText()).isEqualTo("Sales line");
        assertThat(sales.get("kind").asText()).isEqualTo("WHATSAPP_WEB");
        assertThat(sales.get("count").asLong()).isEqualTo(2);
    }

    @Test
    void previewOfASavedSegment() throws Exception {
        enableCrm();
        captured(contact("Asha", "919900000921"), number);
        String id = create(managerToken, segment("Leads", group("and", false, rule("contact.lifecycleStatus", "is", "lead"))))
                .get("id").asText();
        JsonNode p = json(send(post(segments() + "/" + id + "/preview"), managerToken, null).andExpect(status().isOk()));
        assertThat(p.get("total").asLong()).isEqualTo(1);
        send(post(segments() + "/missing/preview"), managerToken, null).andExpect(status().isNotFound());
    }

    @Test
    void restrictedCallerComplementsWithinOwnUniverse() throws Exception {
        enableCrm();
        String second = secondPipeline();
        JsonNode salesLead = captured(contact("Sales lead", "919900000931"), number).get("contact");
        JsonNode salesCustomer = captured(contact("Sales customer", "919900000932"), number).get("contact");
        setContact(salesCustomer.get("id").asText(), "lifecycleStatus", "customer");
        Contact c3 = contact("Second lead", "919900000933");
        send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", chat(c3, number, null, null).getId(),
                "pipelineId", second)).andExpect(status().isOk());
        String marketer = tokenWithAuthorities("marketer", "crm:view", "crm:segments");
        assignPipelines(lastUser, salesId());
        Map<String, Object> notCustomers = group("and", true, rule("contact.lifecycleStatus", "is", "customer"));
        assertThat(preview(marketer, notCustomers).get("total").asLong()).as("Sales lead only").isEqualTo(1);
        assertThat(preview(managerToken, notCustomers).get("total").asLong()).as("both leads").isEqualTo(2);
        // a negative channel rule is a complement within the caller's universe too
        Map<String, Object> noLabel = group("and", false, rule("channel.label", "hasNone", List.of(label("VIP").getId())));
        assertThat(preview(marketer, noLabel).get("total").asLong()).isEqualTo(2);
        assertThat(preview(managerToken, noLabel).get("total").asLong()).isEqualTo(3);
        assertThat(salesLead).isNotNull();
    }

    @Test
    void inaccessiblePipelineRuleMatchesNothingAndIsFlagged() throws Exception {
        enableCrm();
        String second = secondPipeline();
        Contact c = contact("Second lead", "919900000941");
        send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", chat(c, number, null, null).getId(),
                "pipelineId", second)).andExpect(status().isOk());
        Map<String, Object> rules = group("and", false, group("and", false, rule("deal.pipelineId", "is", second),
                rule("deal.status", "is", "OPEN")));
        String id = create(managerToken, segment("Second pipeline deals", rules)).get("id").asText();
        String marketer = tokenWithAuthorities("marketer", "crm:view", "crm:segments");
        assignPipelines(lastUser, salesId());
        JsonNode theirs = json(send(get(segments() + "/" + id), marketer, null).andExpect(status().isOk()));
        assertThat(theirs.get("rules").get("rules").get(0).get("rules").get(0).get("inaccessible").asBoolean()).isTrue();
        JsonNode mine = json(send(get(segments() + "/" + id), managerToken, null).andExpect(status().isOk()));
        assertThat(mine.get("rules").get("rules").get(0).get("rules").get(0).has("inaccessible")).isFalse();
        assertThat(json(send(post(segments() + "/" + id + "/preview"), marketer, null).andExpect(status().isOk()))
                .get("total").asLong()).isEqualTo(0);
        assertThat(json(send(post(segments() + "/" + id + "/preview"), managerToken, null).andExpect(status().isOk()))
                .get("total").asLong()).isEqualTo(1);
    }

    @Test
    void aDeletedPipelineIsMissingNotInaccessibleForAViewAllUser() throws Exception {
        enableCrm();
        String second = secondPipeline();
        Map<String, Object> rules = group("and", false, rule("deal.pipelineId", "is", second));
        String id = create(managerToken, segment("Second pipeline", rules)).get("id").asText();
        send(delete(crm() + "/pipelines/" + second), managerToken, null).andExpect(status().is2xxSuccessful());
        JsonNode rule = json(send(get(segments() + "/" + id), managerToken, null).andExpect(status().isOk()))
                .get("rules").get("rules").get(0);
        assertThat(rule.get("missing").asBoolean()).isTrue();
        assertThat(rule.has("inaccessible")).isFalse();
    }

    @Test
    void aRuleOnAnInactivePipelineIsFlaggedAndMatchesNothing() throws Exception {
        enableCrm();
        JsonNode second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().isCreated()));
        String secondId = second.get("id").asText();
        Contact c = contact("Second lead", "919900000942");
        send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", chat(c, number, null, null).getId(),
                "pipelineId", secondId)).andExpect(status().isOk());
        Map<String, Object> rules = group("and", false, rule("deal.pipelineId", "is", secondId));
        String id = create(managerToken, segment("Second pipeline", rules)).get("id").asText();
        assertThat(preview(managerToken, rules).get("total").asLong()).isEqualTo(1);
        send(put(crm() + "/pipelines/" + secondId), managerToken, Map.of("name", "Second", "active", false,
                "version", second.get("version").asLong())).andExpect(status().isOk());
        JsonNode rule = json(send(get(segments() + "/" + id), managerToken, null).andExpect(status().isOk()))
                .get("rules").get("rules").get(0);
        assertThat(rule.get("missing").asBoolean()).isTrue();
        assertThat(preview(managerToken, rules).get("total").asLong()).isEqualTo(0);
    }

    @Test
    void missingCustomFieldIsFlagged() throws Exception {
        enableCrm();
        JsonNode tier = tierField("CONTACT");
        JsonNode a = captured(contact("Asha", "919900000951"), number).get("contact");
        setContact(a.get("id").asText(), "customFields.tier", "gold");
        Map<String, Object> rules = group("and", false, rule("contact.custom:tier", "is", "gold"));
        String id = create(managerToken, segment("Gold", rules)).get("id").asText();
        assertThat(preview(managerToken, rules).get("total").asLong()).isEqualTo(1);
        send(delete(crm() + "/fields/" + tier.get("id").asText()), managerToken, null).andExpect(status().is2xxSuccessful());
        JsonNode got = json(send(get(segments() + "/" + id), managerToken, null).andExpect(status().isOk()));
        assertThat(got.get("rules").get("rules").get(0).get("missing").asBoolean()).isTrue();
        assertThat(preview(managerToken, rules).get("total").asLong()).isEqualTo(0);
        // the saved segment still updates (the rule is not a shape error)
        Map<String, Object> update = segment("Gold renamed", rules);
        update.put("version", 0);
        send(put(segments() + "/" + id), managerToken, update).andExpect(status().isOk());
    }

    @Test
    void deleteRefusedWhileACampaignUsesIt() throws Exception {
        enableCrm();
        String id = create(managerToken, segment("Used", group("and", false, rule("contact.email", "isSet", null)))).get("id").asText();
        mongo.insert(new Document("workspaceId", brand.getId()).append("name", "Diwali offer").append("nameKey", "diwali offer")
                .append("status", "RUNNING").append("audience", new Document("kind", "SEGMENT").append("segmentId", id)
                        .append("segmentName", "Used")), "campaigns");
        JsonNode e = json(send(delete(segments() + "/" + id), managerToken, null).andExpect(status().isConflict()));
        assertThat(e.get("code").asText()).isEqualTo("SEGMENT_IN_USE");
        assertThat(e.get("message").asText()).contains("Diwali offer");
        assertThat(e.get("campaignIds").asText()).isNotBlank();
        mongo.updateFirst(new Query(Criteria.where("name").is("Diwali offer")), new Update().set("status", "COMPLETED"), "campaigns");
        send(delete(segments() + "/" + id), managerToken, null).andExpect(status().isNoContent());
    }

    @Test
    void needsSegmentsAuthority() throws Exception {
        enableCrm();
        send(get(segments()), agentToken, null).andExpect(status().isForbidden());
        send(get(segments() + "/fields"), agentToken, null).andExpect(status().isForbidden());
        String id = create(managerToken, segment("Theirs", group("and", false, rule("contact.email", "isSet", null))))
                .get("id").asText();
        send(get(segments() + "/" + id), agentToken, null).andExpect(status().isForbidden());
        Map<String, Object> changed = segment("Changed", group("and", false, rule("contact.email", "isSet", null)));
        changed.put("version", 0);
        send(put(segments() + "/" + id), agentToken, changed).andExpect(status().isForbidden());
        send(delete(segments() + "/" + id), agentToken, null).andExpect(status().isForbidden());
        send(post(segments() + "/" + id + "/preview"), agentToken, null).andExpect(status().isForbidden());
        send(post(segments() + "/preview"), agentToken, Map.of("rules", group("and", false, rule("contact.email", "isSet", null))))
                .andExpect(status().isForbidden());
        String viewer = tokenWithAuthorities("viewer", "crm:segments");
        send(get(segments()), viewer, null).andExpect(status().isForbidden());
        String marketer = tokenWithAuthorities("marketer", "crm:view", "crm:segments");
        send(get(segments()), marketer, null).andExpect(status().isOk());
    }
}
