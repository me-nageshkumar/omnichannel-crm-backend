package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.deal.CrmDeal;
import me.nageshk.omnichannel.crm.deal.DealStatus;
import me.nageshk.omnichannel.crm.field.FieldType;
import me.nageshk.omnichannel.crm.platform.ChannelContactQuery;
import me.nageshk.omnichannel.crm.segment.SegmentConditionQueries;
import me.nageshk.omnichannel.crm.segment.SegmentFields;
import me.nageshk.omnichannel.crm.segment.SegmentFields.FieldSpec;
import me.nageshk.omnichannel.crm.segment.SegmentFields.Known;
import me.nageshk.omnichannel.crm.segment.SegmentRule;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactLabel;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.JsonNode;

/** One Mongo query per segment condition, scoped to the caller's universe (spec D C5, C22). */
class SegmentConditionQueriesIT extends CrmTestBase {

    static final Known KNOWN = new Known(Set.of("tier"), Map.of("tier", FieldType.SELECT), Set.of("score"),
            Map.of("score", FieldType.NUMBER), Set.of());
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired SegmentConditionQueries queries;
    @Autowired CrmAccess crmAccess;
    @Autowired ChannelContactQuery channel;
    @Autowired ContactLabelRepository labelRepository;
    @Autowired Clock clock;

    private CrmContext manager() {
        return crmAccess.resolve(auth(manager), brand.getId());
    }

    private static FieldSpec spec(String key) {
        return SegmentFields.spec(key, KNOWN).orElseThrow();
    }

    private static SegmentRule rule(String field, String op, Object value) {
        return new SegmentRule(field, op, value);
    }

    private Set<String> contactRule(CrmContext ctx, String field, String op, Object value) {
        return queries.contactRule(ctx, queries.universe(ctx), rule(field, op, value), spec(field), ZONE);
    }

    private Set<String> dealGroup(CrmContext ctx, SegmentRule... rules) {
        return queries.dealGroup(ctx, List.of(rules), Map.of(
                "deal.status", spec("deal.status"), "deal.amount", spec("deal.amount"),
                "deal.daysInStage", spec("deal.daysInStage"), "deal.custom:score", spec("deal.custom:score")), ZONE);
    }

    private void setContact(String crmContactId, Object... kv) {
        Update u = new Update();
        for (int i = 0; i < kv.length; i += 2) u.set((String) kv[i], kv[i + 1]);
        mongo.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(crmContactId))), u, "crmContacts");
    }

    private void setDeal(String dealId, Object... kv) {
        Update u = new Update();
        for (int i = 0; i < kv.length; i += 2) u.set((String) kv[i], kv[i + 1]);
        mongo.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(dealId))), u, "crmDeals");
    }

    /** A second deal of the same contact, copied from the captured one. */
    private String anotherDeal(String dealId, DealStatus status, String amount) {
        CrmDeal d = mongo.findById(dealId, CrmDeal.class);
        d.setId(null);
        d.setVersion(null);
        d.setStatus(status);
        d.setAmount(new java.math.BigDecimal(amount));
        return mongo.insert(d).getId();
    }

    @Test
    void contactPickAndNumberRules() throws Exception {
        enableCrm();
        JsonNode a = captured(contact("Asha", "919900000801"), number).get("contact");
        JsonNode b = captured(contact("Ravi", "919900000802"), number).get("contact");
        setContact(a.get("id").asText(), "lifecycleStatus", "customer", "openDealAmount", new Decimal128(new java.math.BigDecimal("60000")));
        setContact(b.get("id").asText(), "lifecycleStatus", "lead", "openDealAmount", new Decimal128(new java.math.BigDecimal("100")));
        CrmContext ctx = manager();
        assertThat(contactRule(ctx, "contact.lifecycleStatus", "is", "customer")).containsExactly(a.get("id").asText());
        assertThat(contactRule(ctx, "contact.lifecycleStatus", "isNot", "customer")).containsExactly(b.get("id").asText());
        assertThat(contactRule(ctx, "contact.lifecycleStatus", "in", List.of("lead", "customer"))).hasSize(2);
        assertThat(contactRule(ctx, "contact.openDealAmount", "gte", "50000")).containsExactly(a.get("id").asText());
        assertThat(contactRule(ctx, "contact.openDealAmount", "between", List.of("50", "200"))).containsExactly(b.get("id").asText());
        assertThat(contactRule(ctx, "contact.email", "isNotSet", null)).hasSize(2);
        assertThat(contactRule(ctx, "contact.origin.kind", "is", "INBOUND_CHAT")).hasSize(2);
        assertThat(contactRule(ctx, "contact.channelAccountId", "is", number.getId())).hasSize(2);
        assertThat(contactRule(ctx, "contact.channelAccountId", "is", number2.getId())).isEmpty();
        assertThat(contactRule(ctx, "contact.creationTime", "withinLastDays", "1")).hasSize(2);
        assertThat(contactRule(ctx, "contact.creationTime", "moreThanDaysAgo", "1")).isEmpty();
    }

    @Test
    void contactCustomFieldRuleUsesCustomFieldsPath() throws Exception {
        enableCrm();
        JsonNode a = captured(contact("Asha", "919900000811"), number).get("contact");
        captured(contact("Ravi", "919900000812"), number);
        setContact(a.get("id").asText(), "customFields.tier", "gold");
        CrmContext ctx = manager();
        assertThat(contactRule(ctx, "contact.custom:tier", "is", "gold")).containsExactly(a.get("id").asText());
        assertThat(contactRule(ctx, "contact.custom:tier", "isNotSet", null)).hasSize(1);
        assertThat(contactRule(ctx, "contact.custom:tier", "in", List.of("gold", "silver"))).containsExactly(a.get("id").asText());
    }

    @Test
    void dealRulesInOneGroupMatchTheSameDeal() throws Exception {
        enableCrm();
        JsonNode cap = captured(contact("Asha", "919900000821"), number);
        String a = cap.get("contact").get("id").asText();
        String won = cap.get("deal").get("id").asText();
        setDeal(won, "status", "WON", "amount", new Decimal128(new java.math.BigDecimal("100")));
        anotherDeal(won, DealStatus.OPEN, "500");
        CrmContext ctx = manager();
        assertThat(dealGroup(ctx, rule("deal.status", "is", "OPEN"), rule("deal.amount", "gt", "400"))).containsExactly(a);
        assertThat(dealGroup(ctx, rule("deal.status", "is", "WON"), rule("deal.amount", "gt", "400"))).isEmpty();
        assertThat(dealGroup(ctx, rule("deal.status", "isNot", "WON"), rule("deal.amount", "lt", "600"))).containsExactly(a);
    }

    @Test
    void dealRulesInAnOrGroupAreSeparateDeals() throws Exception {
        enableCrm();
        JsonNode cap = captured(contact("Asha", "919900000831"), number);
        String a = cap.get("contact").get("id").asText();
        String won = cap.get("deal").get("id").asText();
        setDeal(won, "status", "WON", "amount", new Decimal128(new java.math.BigDecimal("100")));
        anotherDeal(won, DealStatus.OPEN, "500");
        CrmContext ctx = manager();
        // each rule its own "has a deal where": the union holds A
        Set<String> wonSet = dealGroup(ctx, rule("deal.status", "is", "WON"));
        Set<String> bigSet = dealGroup(ctx, rule("deal.amount", "gt", "400"));
        assertThat(wonSet).containsExactly(a);
        assertThat(bigSet).containsExactly(a);
    }

    @Test
    void dealDaysInStageInverts() throws Exception {
        enableCrm();
        JsonNode cap = captured(contact("Asha", "919900000841"), number);
        String a = cap.get("contact").get("id").asText();
        setDeal(cap.get("deal").get("id").asText(), "stageEnteredAt", Instant.now().minus(10, ChronoUnit.DAYS));
        CrmContext ctx = manager();
        assertThat(dealGroup(ctx, rule("deal.daysInStage", "gt", "7"))).containsExactly(a);
        assertThat(dealGroup(ctx, rule("deal.daysInStage", "gt", "14"))).isEmpty();
        assertThat(dealGroup(ctx, rule("deal.daysInStage", "lt", "14"))).containsExactly(a);
        assertThat(dealGroup(ctx, rule("deal.daysInStage", "between", List.of("8", "12")))).containsExactly(a);
        assertThat(dealGroup(ctx, rule("deal.daysInStage", "between", List.of("11", "12")))).isEmpty();
    }

    @Test
    void channelRuleMapsHostContactsToCrmContacts() throws Exception {
        enableCrm();
        ContactLabel vip = new ContactLabel();
        vip.setWorkspaceId(brand.getId());
        vip.setName("VIP");
        vip.setNameKey("vip");
        vip = labelRepository.save(vip);
        Contact hostA = contact("Asha", "919900000851");
        hostA.setLabels(Set.of(vip.getId()));
        contacts.save(hostA);
        Contact loose = contact("Loose", "919900000852"); // labelled, but never captured into the CRM
        loose.setLabels(Set.of(vip.getId()));
        contacts.save(loose);
        JsonNode a = captured(hostA, number).get("contact");
        captured(contact("Ravi", "919900000853"), number);
        CrmContext ctx = manager();
        Criteria universe = queries.universe(ctx);
        assertThat(queries.channelRule(ctx, universe, rule("channel.label", "has", vip.getId()), ZONE))
                .containsExactly(a.get("id").asText());
        assertThat(queries.channelRule(ctx, universe, rule("channel.lastMessageAt", "isNotSet", null), ZONE)).hasSize(2);
        assertThat(queries.channelRule(ctx, universe, rule("channel.lastMessageAt", "moreThanDaysAgo", "30"), ZONE)).isEmpty();
        assertThat(queries.channelRule(ctx, universe, rule("channel.doNotContact", "is", "false"), ZONE)).hasSize(2);
    }

    @Test
    void restrictedUniverseExcludesOtherPipelines() throws Exception {
        enableCrm();
        String second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().isCreated())).get("id").asText();
        JsonNode inSales = captured(contact("Asha", "919900000861"), number).get("contact");
        Contact c2 = contact("Ravi", "919900000862");
        var chat = chat(c2, number, null, null);
        JsonNode inSecond = json(send(post(crm() + "/contacts/capture"), managerToken,
                Map.of("chatId", chat.getId(), "pipelineId", second)).andExpect(status().isOk())).get("contact");
        assignPipelines(agent, second);
        CrmContext agentCtx = crmAccess.resolve(auth(agent), brand.getId());
        assertThat(contactRule(agentCtx, "contact.lifecycleStatus", "is", "lead")).containsExactly(inSecond.get("id").asText());
        assertThat(queries.universeIds(agentCtx, queries.universe(agentCtx))).containsExactly(inSecond.get("id").asText());
        assertThat(queries.universeIds(manager(), queries.universe(manager())))
                .containsExactlyInAnyOrder(inSales.get("id").asText(), inSecond.get("id").asText());
        assertThat(dealGroup(agentCtx, rule("deal.status", "is", "OPEN"))).containsExactly(inSecond.get("id").asText());
    }

    @Test
    void tooBroadWhenOverTheCap() throws Exception {
        enableCrm();
        for (int i = 0; i < 3; i++) captured(contact("C" + i, "91990000087" + i), number);
        SegmentConditionQueries small = new SegmentConditionQueries(mongo, channel, clock, 2);
        CrmContext ctx = manager();
        assertThatThrownBy(() -> small.contactRule(ctx, small.universe(ctx), rule("contact.lifecycleStatus", "is", "lead"),
                spec("contact.lifecycleStatus"), ZONE)).isInstanceOf(CodedException.class)
                .satisfies(e -> assertThat(((CodedException) e).code()).isEqualTo("SEGMENT_TOO_BROAD"));
        assertThatThrownBy(() -> small.universeIds(ctx, small.universe(ctx))).isInstanceOf(CodedException.class);
        assertThat(queries.contactRule(ctx, queries.universe(ctx), rule("contact.lifecycleStatus", "is", "lead"),
                spec("contact.lifecycleStatus"), ZONE)).hasSize(3);
    }

    @Test
    void contactTextRulesMatchCaseInsensitively() throws Exception {
        enableCrm();
        JsonNode a = captured(contact("Asha", "919900000881"), number).get("contact");
        JsonNode b = captured(contact("Ravi", "919900000882"), number).get("contact");
        setContact(a.get("id").asText(), "email", "Asha@Acme.com", "customFields.motto", "Keep (it) simple");
        setContact(b.get("id").asText(), "email", "ravi@other.org");
        Known known = new Known(Set.of("motto"), Map.of("motto", FieldType.TEXT), Set.of(), Map.of(), Set.of());
        FieldSpec motto = SegmentFields.spec("contact.custom:motto", known).orElseThrow();
        CrmContext ctx = manager();
        Criteria universe = queries.universe(ctx);
        assertThat(contactRule(ctx, "contact.email", "contains", "acme")).containsExactly(a.get("id").asText());
        assertThat(contactRule(ctx, "contact.email", "notContains", "acme")).containsExactly(b.get("id").asText());
        assertThat(contactRule(ctx, "contact.email", "is", "asha@acme.com")).containsExactly(a.get("id").asText());
        assertThat(contactRule(ctx, "contact.email", "isNot", "asha@acme.com")).containsExactly(b.get("id").asText());
        assertThat(contactRule(ctx, "contact.email", "startsWith", "ASHA")).containsExactly(a.get("id").asText());
        assertThat(queries.contactRule(ctx, universe, rule("contact.custom:motto", "contains", "(it)"), motto, ZONE))
                .as("regex characters in the value are quoted").containsExactly(a.get("id").asText());
        assertThat(queries.contactRule(ctx, universe, rule("contact.custom:motto", "is", "keep (it) SIMPLE"), motto, ZONE))
                .containsExactly(a.get("id").asText());
    }
}
