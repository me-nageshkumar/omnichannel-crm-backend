package me.nageshk.omnichannel.app.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactLabel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.JsonNode;

/** Saved advanced filters run through contact search, count, bulk and export. */
class AdvancedFilterExecutionIT extends ContactApiTestBase {

    ContactLabel vip;
    ContactLabel lead;
    ContactAttribute city;
    Contact asha;
    Contact bela;
    Contact chitra;
    Contact dev;
    Contact ashaB;

    @BeforeEach
    void data() {
        vip = label(brandA, "VIP");
        lead = label(brandA, "lead");
        city = attribute(brandA, "City");
        asha = contact(brandA, "Asha", "919812345678");
        asha.getLabels().add(vip.getId());
        asha.getAttributes().put(city.getId(), "Pune");
        asha = contacts.save(asha);
        bela = contact(brandA, "Bela", "919800000002");
        bela.getLabels().add(vip.getId());
        bela.getAttributes().put(city.getId(), "Mumbai");
        bela = contacts.save(bela);
        chitra = contact(brandA, "Chitra", "919800000003");
        chitra.getAttributes().put(city.getId(), "Pune");
        chitra = contacts.save(chitra);
        dev = contact(brandA, null, "919800000004");
        ContactAttribute cityB = attribute(brandB, "City");
        ashaB = contact(brandB, "Asha", "919800000005");
        ashaB.getAttributes().put(cityB.getId(), "Pune");
        ashaB = contacts.save(ashaB);
    }

    @AfterEach
    void cleanFilters() {
        filters.deleteAll();
        mongo.remove(new Query(), "campaignSettings");
    }

    static Map<String, Object> group(String combinator, boolean not, Object... rules) {
        return Map.of("combinator", combinator, "not", not, "rules", List.of(rules));
    }

    static Map<String, Object> rule(String field, String operator, Object value) {
        return Map.of("field", field, "operator", operator, "value", value);
    }

    private int saved;

    String advanced(Map<String, Object> rules) throws Exception {
        return json(send(post(base(brandA) + "/contact-filters"), leadToken,
                Map.of("name", "F" + ++saved, "rules", rules))
                .andExpect(status().isCreated())).get("id").asString();
    }

    Map<String, Object> puneOrChi() {
        return group("or", false,
                group("and", false, rule("labels", "has", vip.getId()), rule("attributes." + city.getId(), "is", "pune")),
                rule("name", "startsWith", "chi"));
    }

    List<String> names(Map<String, Object> request) throws Exception {
        JsonNode body = json(send(post(base(brandA) + "/contacts/search"), agentToken, request)
                .andExpect(status().isOk()));
        List<String> out = new ArrayList<>();
        body.get("items").forEach(i -> out.add(i.path("name").asString(null)));
        return out;
    }

    List<String> rulesNames(Map<String, Object> rules) throws Exception {
        return names(Map.of("filterId", advanced(rules)));
    }

    @Test
    void searchThroughAnAdvancedFilter() throws Exception {
        String id = advanced(puneOrChi());
        send(post(base(brandA) + "/contacts/search"), agentToken, Map.of("filterId", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        assertThat(names(Map.of("filterId", id))).containsExactlyInAnyOrder("Asha", "Chitra");
    }

    @Test
    void advancedFilterIsAndedWithAnInlineSpec() throws Exception {
        String id = advanced(puneOrChi());
        assertThat(names(Map.of("filterId", id, "filter", Map.of("q", "chitra")))).containsExactly("Chitra");
    }

    @Test
    void negativeTextRulesMatchContactsWithoutTheField() throws Exception {
        assertThat(rulesNames(group("and", false, rule("name", "isNot", "asha"))))
                .containsExactlyInAnyOrder("Bela", "Chitra", null);
        assertThat(rulesNames(group("and", false, rule("attributes." + city.getId(), "notContains", "pun"))))
                .containsExactlyInAnyOrder("Bela", null);
    }

    @Test
    void notGroupExcludesItsMatches() throws Exception {
        assertThat(rulesNames(group("and", true, rule("labels", "has", vip.getId()))))
                .containsExactlyInAnyOrder("Chitra", null);
    }

    @Test
    void regexCharactersAreLiteral() throws Exception {
        String odd = "a.*+?()[]{}|^$\\b";
        contact(brandA, odd, "919800000006");
        contact(brandA, "axb", "919800000007");
        assertThat(rulesNames(group("and", false, rule("name", "contains", odd)))).containsExactly(odd);
        assertThat(rulesNames(group("and", false, rule("name", "contains", "a.b")))).isEmpty();
    }

    @Test
    void phoneRulesMatchDigits() throws Exception {
        assertThat(rulesNames(group("and", false, rule("phone", "contains", "+91 98123")))).containsExactly("Asha");
        assertThat(rulesNames(group("and", false, rule("phone", "is", "919812345678")))).containsExactly("Asha");
    }

    @Test
    void datesUseDayBoundariesInTheZone() throws Exception {
        mongo.updateFirst(new Query(Criteria.where("_id").is(asha.getId())),
                new Update().set("creationTime", Instant.parse("2026-10-20T19:00:00Z")), Contact.class);
        assertThat(rulesNames(group("and", false, rule("creationTime", "after", "2026-10-20")))).contains("Asha");
        assertThat(rulesNames(group("and", false, rule("creationTime", "before", "2026-10-21"))))
                .doesNotContain("Asha");
        assertThat(rulesNames(group("and", false,
                rule("creationTime", "between", List.of("2026-10-21", "2026-10-21"))))).contains("Asha");
    }

    @Test
    void countThroughAnAdvancedFilter() throws Exception {
        String id = advanced(puneOrChi());
        send(post(base(brandA) + "/contacts/count"), agentToken, Map.of("selection", Map.of("filterId", id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void bulkThroughAnAdvancedFilter() throws Exception {
        String id = advanced(puneOrChi());
        send(post(base(brandA) + "/contacts/bulk"), leadToken, Map.of("selection", Map.of("filterId", id),
                "action", "ADD_LABELS", "labelIds", List.of(lead.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(2))
                .andExpect(jsonPath("$.changed").value(2));
        assertThat(contacts.findById(asha.getId()).orElseThrow().getLabels()).contains(lead.getId());
        assertThat(contacts.findById(chitra.getId()).orElseThrow().getLabels()).contains(lead.getId());
        assertThat(contacts.findById(bela.getId()).orElseThrow().getLabels()).doesNotContain(lead.getId());
        assertThat(contacts.findById(ashaB.getId()).orElseThrow().getLabels()).isEmpty();
    }

    @Test
    void exportThroughAnAdvancedFilter() throws Exception {
        String csv = download(Map.of("filterId", advanced(puneOrChi())), leadToken);
        assertThat(csv).contains("phone,name").contains("Asha").contains("Chitra")
                .doesNotContain("Bela").doesNotContain("919800000004");
    }

    @Test
    void countAndBulkAndTheFilterWithAnInlineSpec() throws Exception {
        String id = advanced(puneOrChi());
        Map<String, Object> selection = Map.of("filterId", id, "filter", Map.of("q", "chitra"));
        send(post(base(brandA) + "/contacts/count"), agentToken, Map.of("selection", selection))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        send(post(base(brandA) + "/contacts/bulk"), leadToken, Map.of("selection", selection,
                "action", "ADD_LABELS", "labelIds", List.of(lead.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.changed").value(1));
        assertThat(contacts.findById(chitra.getId()).orElseThrow().getLabels()).contains(lead.getId());
        assertThat(contacts.findById(asha.getId()).orElseThrow().getLabels()).doesNotContain(lead.getId());
    }

    @Test
    void idsStayExclusive() throws Exception {
        send(post(base(brandA) + "/contacts/count"), agentToken, Map.of("selection",
                Map.of("ids", List.of(asha.getId()), "filterId", "x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.selection").value("give ids, or filter and/or filterId"));
    }

    @Test
    void filterWithAMissingLabelRunsAsCompiled() throws Exception {
        Map<String, Object> rules = group("and", false, rule("labels", "has", vip.getId()));
        String id = advanced(rules);
        // the label is gone, as after a delete that skipped the in-use guard (the contacts lose it as well)
        labels.deleteById(vip.getId());
        mongo.updateMulti(new Query(), new Update().pull("labels", vip.getId()), Contact.class);
        assertThat(names(Map.of("filterId", id))).isEmpty();
        send(get(base(brandA) + "/contact-filters/" + id), agentToken, null).andExpect(status().isOk());
        send(put(base(brandA) + "/contact-filters/" + id), leadToken, Map.of("name", "F1", "rules", rules))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['rules[0].value']").value("is not a label of this brand"));
    }
}
