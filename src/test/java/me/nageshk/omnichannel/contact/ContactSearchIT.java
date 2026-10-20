package me.nageshk.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class ContactSearchIT extends ContactApiTestBase {

    @Autowired MongoTemplate mongo;

    ContactLabel vip;
    ContactLabel lead;
    ContactLabel customer;
    ContactAttribute city;
    ContactAttribute email;

    @BeforeEach
    void data() {
        vip = label(brandA, "VIP");
        lead = label(brandA, "Lead");
        customer = label(brandA, "Customer");
        city = attribute(brandA, "City");
        email = attribute(brandA, "Email");
    }

    private String searchUrl() { return base(brandA) + "/contacts/search"; }

    private Contact make(String name, String phone, ContactSource source, Instant lastMessage, String... labelIds) {
        Contact c = new Contact();
        c.setWorkspaceId(brandA.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setPhone(phone);
        if (phone == null) c.setWaId(name.toLowerCase().replace(' ', '-') + "@lid");
        c.setSource(source);
        c.setLastMessageAt(lastMessage);
        for (String l : labelIds) c.getLabels().add(l);
        return contacts.save(c);
    }

    private Contact make(String name, String phone) {
        return make(name, phone, ContactSource.CHAT, null);
    }

    private void created(Contact c, Instant at) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(c.getId())), new Update().set("creationTime", at),
                Contact.class);
    }

    private List<String> names(Map<String, Object> request) throws Exception {
        JsonNode body = json(send(post(searchUrl()), agentToken, request).andExpect(status().isOk()));
        List<String> out = new ArrayList<>();
        body.get("items").forEach(i -> out.add(i.path("name").asString()));
        return out;
    }

    private List<String> filtered(Map<String, Object> filter) throws Exception {
        return names(Map.of("filter", filter, "sort", "NAME"));
    }

    @Test
    void emptySearchReturnsTheBrandsContactsOnly() throws Exception {
        make("Asha", "919800000001");
        Contact other = new Contact();
        other.setWorkspaceId(brandB.getId());
        other.setName("Other brand");
        other.setPhone("919800000002");
        other.setSource(ContactSource.CHAT);
        contacts.save(other);
        JsonNode body = json(send(post(searchUrl()), agentToken, Map.of()).andExpect(status().isOk()));
        assertThat(body.get("total").asInt()).isEqualTo(1);
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("items").get(0).get("name").asString()).isEqualTo("Asha");
    }

    @Test
    void qMatchesNameOrPhoneDigits() throws Exception {
        make("Asha Rao", "919812345678");
        make("Bela Shah", "919700000001");
        assertThat(filtered(Map.of("q", "ASHA"))).containsExactly("Asha Rao");
        assertThat(filtered(Map.of("q", "sha"))).containsExactly("Asha Rao", "Bela Shah");
        assertThat(filtered(Map.of("q", "98123"))).containsExactly("Asha Rao");
        assertThat(filtered(Map.of("q", "+91 97"))).containsExactly("Bela Shah");
        assertThat(filtered(Map.of("q", "nobody"))).isEmpty();
    }

    @Test
    void regexCharactersInQAreLiteral() throws Exception {
        make("a.b", "919800000001");
        make("axb", "919800000002");
        make("Cost (new", "919800000003");
        assertThat(filtered(Map.of("q", "a.b"))).containsExactly("a.b");
        assertThat(filtered(Map.of("q", "("))).containsExactly("Cost (new");
        assertThat(filtered(Map.of("q", ".*"))).isEmpty();
    }

    @Test
    void labelsAnyAllNone() throws Exception {
        make("OnlyVip", "919800000001", ContactSource.CHAT, null, vip.getId());
        make("VipLead", "919800000002", ContactSource.CHAT, null, vip.getId(), lead.getId());
        make("OnlyLead", "919800000003", ContactSource.CHAT, null, lead.getId());
        make("Nothing", "919800000004");
        assertThat(filtered(Map.of("labelsAny", List.of(vip.getId(), lead.getId()))))
                .containsExactly("OnlyLead", "OnlyVip", "VipLead");
        assertThat(filtered(Map.of("labelsAll", List.of(vip.getId(), lead.getId())))).containsExactly("VipLead");
        assertThat(filtered(Map.of("labelsNone", List.of(vip.getId())))).containsExactly("Nothing", "OnlyLead");
        assertThat(filtered(Map.of("labelsAny", List.of()))).hasSize(4);
    }

    @Test
    void attributeOps() throws Exception {
        Contact pune = make("Pune", "919800000001");
        pune.getAttributes().put(city.getId(), "Pune");
        contacts.save(pune);
        Contact navi = make("Navi", "919800000002");
        navi.getAttributes().put(city.getId(), "New Pune East");
        navi.getAttributes().put(email.getId(), "a@b.c");
        contacts.save(navi);
        make("None", "919800000003");

        assertThat(filtered(Map.of("attributes", List.of(attr(city, "EQUALS", "Pune"))))).containsExactly("Pune");
        assertThat(filtered(Map.of("attributes", List.of(attr(city, "CONTAINS", "pune"))))).containsExactly("Navi", "Pune");
        assertThat(filtered(Map.of("attributes", List.of(attr(city, "EXISTS", null))))).containsExactly("Navi", "Pune");
        assertThat(filtered(Map.of("attributes", List.of(attr(city, "NOT_EXISTS", null))))).containsExactly("None");
        assertThat(filtered(Map.of("attributes", List.of(attr(city, "EXISTS", null), attr(email, "EXISTS", null)))))
                .containsExactly("Navi");
        assertThat(filtered(Map.of("attributes", List.of(attr(city, "CONTAINS", ".*"))))).isEmpty();
        send(post(searchUrl()), agentToken, Map.of("filter", Map.of("attributes", List.of(attr(city, "EQUALS", null)))))
                .andExpect(status().isBadRequest());
    }

    private Map<String, Object> attr(ContactAttribute a, String op, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("attributeId", a.getId());
        m.put("op", op);
        m.put("value", value);
        return m;
    }

    @Test
    void sourceAndNumberHidden() throws Exception {
        make("Chatty", "919800000001", ContactSource.CHAT, null);
        make("Imported", "919800000002", ContactSource.IMPORT, null);
        make("Manual", "919800000003", ContactSource.MANUAL, null);
        make("Hidden", null, ContactSource.CHAT, null);
        assertThat(filtered(Map.of("source", List.of("IMPORT", "MANUAL")))).containsExactly("Imported", "Manual");
        assertThat(filtered(Map.of("numberHidden", true))).containsExactly("Hidden");
        assertThat(filtered(Map.of("numberHidden", false))).containsExactly("Chatty", "Imported", "Manual");
    }

    @Test
    void dateRanges() throws Exception {
        Contact old = make("Old", "919800000001", ContactSource.CHAT, Instant.parse("2026-01-10T00:00:00Z"));
        created(old, Instant.parse("2026-01-01T00:00:00Z"));
        Contact mid = make("Mid", "919800000002", ContactSource.CHAT, Instant.parse("2026-02-10T00:00:00Z"));
        created(mid, Instant.parse("2026-02-01T00:00:00Z"));
        Contact fresh = make("Fresh", "919800000003", ContactSource.CHAT, Instant.parse("2026-03-10T00:00:00Z"));
        created(fresh, Instant.parse("2026-03-01T00:00:00Z"));
        make("NeverMessaged", "919800000004");

        assertThat(filtered(Map.of("createdFrom", "2026-02-01T00:00:00Z"))).contains("Mid", "Fresh").doesNotContain("Old");
        assertThat(filtered(Map.of("createdTo", "2026-02-01T00:00:00Z"))).containsExactly("Mid", "Old");
        assertThat(filtered(Map.of("createdFrom", "2026-01-15T00:00:00Z", "createdTo", "2026-02-15T00:00:00Z")))
                .containsExactly("Mid");
        assertThat(filtered(Map.of("lastMessageFrom", "2026-02-01T00:00:00Z"))).containsExactly("Fresh", "Mid");
        assertThat(filtered(Map.of("lastMessageTo", "2026-02-01T00:00:00Z"))).containsExactly("Old");
        assertThat(filtered(Map.of("lastMessageFrom", "2026-01-15T00:00:00Z", "lastMessageTo", "2026-02-15T00:00:00Z")))
                .containsExactly("Mid");
    }

    @Test
    void combinedFiltersAnd() throws Exception {
        make("Asha VIP", "919800000001", ContactSource.IMPORT, null, vip.getId());
        make("Asha Plain", "919800000002", ContactSource.IMPORT, null);
        make("Bela VIP", "919800000003", ContactSource.IMPORT, null, vip.getId());
        make("Asha VIP Chat", "919800000004", ContactSource.CHAT, null, vip.getId());
        assertThat(filtered(Map.of("q", "asha", "labelsAny", List.of(vip.getId()), "source", List.of("IMPORT"))))
                .containsExactly("Asha VIP");
    }

    @Test
    void sortAndPaging() throws Exception {
        for (int i = 0; i < 60; i++) {
            make(String.format("C%02d", i), String.format("9198000%05d", i), ContactSource.CHAT,
                    Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i));
        }
        created(make("NoMessage", "919899999999"), Instant.parse("2030-01-01T00:00:00Z"));

        JsonNode p0 = json(send(post(searchUrl()), agentToken, Map.of()).andExpect(status().isOk()));
        assertThat(p0.get("total").asInt()).isEqualTo(61);
        assertThat(p0.get("items")).hasSize(50);
        assertThat(p0.get("items").get(0).get("name").asString()).isEqualTo("C59");
        JsonNode p1 = json(send(post(searchUrl()), agentToken, Map.of("page", 1)).andExpect(status().isOk()));
        assertThat(p1.get("page").asInt()).isEqualTo(1);
        assertThat(p1.get("items")).hasSize(11);
        // contacts with no message come last in the default order
        assertThat(p1.get("items").get(10).get("name").asString()).isEqualTo("NoMessage");

        JsonNode byName = json(send(post(searchUrl()), agentToken, Map.of("sort", "NAME")).andExpect(status().isOk()));
        assertThat(byName.get("items").get(0).get("name").asString()).isEqualTo("C00");
        assertThat(byName.get("items").get(49).get("name").asString()).isEqualTo("C49");
        JsonNode byCreated = json(send(post(searchUrl()), agentToken, Map.of("sort", "CREATED")).andExpect(status().isOk()));
        assertThat(byCreated.get("items").get(0).get("name").asString()).isEqualTo("NoMessage");
        send(post(searchUrl()), agentToken, Map.of("sort", "BOGUS")).andExpect(status().isBadRequest());
    }

    @Test
    void sortByNameIgnoresCase() throws Exception {
        make("banana", "919800000001");
        make("Apple", "919800000002");
        make("cherry", "919800000003");
        assertThat(names(Map.of("sort", "NAME"))).containsExactly("Apple", "banana", "cherry");
    }

    @Test
    void savedFilterRoundTrip() throws Exception {
        make("Asha VIP", "919800000001", ContactSource.CHAT, null, vip.getId());
        make("Bela", "919800000002");
        String url = base(brandA) + "/contact-filters";

        String id = json(send(post(url), leadToken, Map.of("name", " VIPs ",
                "filter", Map.of("labelsAny", List.of(vip.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("VIPs"))
                .andExpect(jsonPath("$.createdBy").value("lead"))
                .andExpect(jsonPath("$.filter.labelsAny[0]").value(vip.getId()))
                .andExpect(jsonPath("$.creationTime").isNotEmpty())).get("id").asString();

        send(get(url), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].name").value("VIPs"));
        assertThat(names(Map.of("filterId", id))).containsExactly("Asha VIP");

        send(post(url), leadToken, Map.of("name", "vips", "filter", Map.of())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.field").value("name"));
        send(put(url + "/" + id), wmToken, Map.of("name", "Everyone", "filter", Map.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Everyone"));
        assertThat(names(Map.of("filterId", id))).hasSize(2);
        // an inline filter alongside a saved one narrows it further
        assertThat(names(Map.of("filterId", id, "filter", Map.of("q", "bela")))).containsExactly("Bela");

        send(delete(url + "/" + id), wmToken, null).andExpect(status().isNoContent());
        send(get(url), agentToken, null).andExpect(jsonPath("$.length()").value(0));
        send(post(searchUrl()), agentToken, Map.of("filterId", id)).andExpect(status().isNotFound());
    }

    @Test
    void savedFilterValidation() throws Exception {
        String url = base(brandA) + "/contact-filters";
        send(post(url), leadToken, Map.of("name", "  ", "filter", Map.of())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists());
        send(post(url), leadToken, Map.of("name", "x".repeat(61), "filter", Map.of())).andExpect(status().isBadRequest());
        Map<String, Object> noFilter = new HashMap<>();
        noFilter.put("name", "ok");
        send(post(url), leadToken, noFilter).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.filter").exists());
    }

    @Test
    void agentCannotSaveFilter() throws Exception {
        String url = base(brandA) + "/contact-filters";
        send(post(url), agentToken, Map.of("name", "x", "filter", Map.of())).andExpect(status().isForbidden());
        ContactFilter f = new ContactFilter();
        f.setWorkspaceId(brandA.getId());
        f.setName("Existing");
        f = filters.save(f);
        send(put(url + "/" + f.getId()), agentToken, Map.of("name", "y", "filter", Map.of()))
                .andExpect(status().isForbidden());
        send(delete(url + "/" + f.getId()), agentToken, null).andExpect(status().isForbidden());
        send(get(url), agentToken, null).andExpect(status().isOk());
    }

    @Test
    void otherBrandsFilterIdIs404() throws Exception {
        ContactFilter theirs = new ContactFilter();
        theirs.setWorkspaceId(brandB.getId());
        theirs.setName("Theirs");
        theirs.setFilter(new ContactFilterSpec(null, null, null, null, null, null, null, null, null, null, null));
        theirs = filters.save(theirs);
        make("Asha", "919800000001");
        send(post(searchUrl()), agentToken, Map.of("filterId", theirs.getId())).andExpect(status().isNotFound());
        String url = base(brandA) + "/contact-filters/" + theirs.getId();
        send(put(url), wmToken, Map.of("name", "x", "filter", Map.of())).andExpect(status().isNotFound());
        send(delete(url), wmToken, null).andExpect(status().isNotFound());
        assertThat(filters.findById(theirs.getId())).isPresent();
    }

    @Test
    void foreignLabelIdMatchesNothing() throws Exception {
        ContactLabel foreign = label(brandB, "Foreign");
        make("Asha", "919800000001", ContactSource.CHAT, null, vip.getId());
        // even a contact that (wrongly) carries the foreign id is not matched, because the id is dropped
        Contact odd = make("Odd", "919800000002");
        odd.getLabels().add(foreign.getId());
        contacts.save(odd);

        assertThat(filtered(Map.of("labelsAny", List.of(foreign.getId())))).isEmpty();
        assertThat(filtered(Map.of("labelsAll", List.of(foreign.getId())))).isEmpty();
        assertThat(filtered(Map.of("labelsAny", List.of(foreign.getId(), vip.getId())))).containsExactly("Asha");
        // a foreign id in labelsNone is ignored
        assertThat(filtered(Map.of("labelsNone", List.of(foreign.getId())))).containsExactly("Asha", "Odd");

        ContactAttribute foreignAttr = attribute(brandB, "Foreign");
        assertThat(filtered(Map.of("attributes", List.of(attr(foreignAttr, "EXISTS", null))))).isEmpty();
    }
}
