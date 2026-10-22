package me.nageshk.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import me.nageshk.omnichannel.tenant.Workspace;
import org.springframework.mock.web.MockMultipartFile;

class ContactBulkIT extends ContactApiTestBase {

    @Autowired MongoTemplate mongo;
    @Autowired ContactBulkService bulkService;

    ContactLabel vip;
    ContactLabel lead;
    ContactAttribute city;
    Contact asha;
    Contact bela;
    Contact chitra;
    Contact otherBrand;

    @BeforeEach
    void data() {
        vip = label(brandA, "VIP");
        lead = label(brandA, "Lead");
        city = attribute(brandA, "City");
        asha = contact(brandA, "Asha", "919800000001");
        bela = contact(brandA, "Bela", "919800000002");
        chitra = contact(brandA, "Chitra", "919800000003");
        otherBrand = contact(brandB, "Other", "919800000004");
        bela.getLabels().add(vip.getId());
        contacts.save(bela);
    }

    private String bulk() { return base(brandA) + "/contacts/bulk"; }

    private Map<String, Object> ids(String... ids) {
        return Map.of("ids", List.of(ids));
    }

    private Map<String, Object> request(Map<String, Object> selection, String action, Object... extra) {
        Map<String, Object> m = new HashMap<>();
        m.put("selection", selection);
        m.put("action", action);
        for (int i = 0; i < extra.length; i += 2) m.put((String) extra[i], extra[i + 1]);
        return m;
    }

    private Contact reload(Contact c) {
        return contacts.findById(c.getId()).orElseThrow();
    }

    @Test
    void addLabelsToIds() throws Exception {
        send(post(bulk()), leadToken, request(ids(asha.getId(), bela.getId()), "ADD_LABELS",
                "labelIds", List.of(vip.getId(), lead.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(2))
                .andExpect(jsonPath("$.changed").value(2));
        assertThat(reload(asha).getLabels()).containsExactlyInAnyOrder(vip.getId(), lead.getId());
        assertThat(reload(bela).getLabels()).containsExactlyInAnyOrder(vip.getId(), lead.getId());
        assertThat(reload(chitra).getLabels()).isEmpty();

        // running it again changes nothing
        send(post(bulk()), leadToken, request(ids(asha.getId(), bela.getId()), "ADD_LABELS",
                "labelIds", List.of(vip.getId(), lead.getId())))
                .andExpect(jsonPath("$.matched").value(2)).andExpect(jsonPath("$.changed").value(0));
    }

    @Test
    void addLabelsToFilter() throws Exception {
        Map<String, Object> sel = Map.of("filter", Map.of("q", "h"));
        send(post(bulk()), leadToken, request(sel, "ADD_LABELS", "labelIds", List.of(lead.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(2)).andExpect(jsonPath("$.changed").value(2));
        assertThat(reload(asha).getLabels()).containsExactly(lead.getId());
        assertThat(reload(chitra).getLabels()).containsExactly(lead.getId());
        assertThat(reload(bela).getLabels()).doesNotContain(lead.getId());
        assertThat(reload(otherBrand).getLabels()).isEmpty();
    }

    @Test
    void addLabelsToSavedFilter() throws Exception {
        ContactFilter f = new ContactFilter();
        f.setWorkspaceId(brandA.getId());
        f.setName("VIPs");
        f.setFilter(new ContactFilterSpec(null, List.of(vip.getId()), null, null, null, null, null, null, null, null, null, null));
        f = filters.save(f);
        send(post(bulk()), wmToken, request(Map.of("filterId", f.getId()), "ADD_LABELS", "labelIds", List.of(lead.getId())))
                .andExpect(jsonPath("$.matched").value(1)).andExpect(jsonPath("$.changed").value(1));
        assertThat(reload(bela).getLabels()).contains(lead.getId());
        assertThat(reload(asha).getLabels()).isEmpty();
    }

    @Test
    void removeLabels() throws Exception {
        send(post(bulk()), leadToken, request(Map.of("filter", Map.of("labelsAny", List.of(vip.getId()))),
                "REMOVE_LABELS", "labelIds", List.of(vip.getId())))
                .andExpect(jsonPath("$.matched").value(1)).andExpect(jsonPath("$.changed").value(1));
        assertThat(reload(bela).getLabels()).isEmpty();
    }

    @Test
    void setAndClearAttribute() throws Exception {
        send(post(bulk()), leadToken, request(ids(asha.getId(), bela.getId()), "SET_ATTRIBUTE",
                "attributeId", city.getId(), "value", "  Pune "))
                .andExpect(jsonPath("$.matched").value(2)).andExpect(jsonPath("$.changed").value(2));
        assertThat(reload(asha).getAttributes()).containsEntry(city.getId(), "Pune");
        assertThat(reload(chitra).getAttributes()).isEmpty();
        // same value again: matched, not changed
        send(post(bulk()), leadToken, request(ids(asha.getId(), bela.getId()), "SET_ATTRIBUTE",
                "attributeId", city.getId(), "value", "Pune"))
                .andExpect(jsonPath("$.matched").value(2)).andExpect(jsonPath("$.changed").value(0));

        send(post(bulk()), leadToken, request(Map.of("filter", Map.of("q", "")), "CLEAR_ATTRIBUTE",
                "attributeId", city.getId()))
                .andExpect(jsonPath("$.matched").value(3)).andExpect(jsonPath("$.changed").value(2));
        assertThat(reload(asha).getAttributes()).isEmpty();
    }

    @Test
    void setAttributeNeedsAValueAndAnAttribute() throws Exception {
        send(post(bulk()), leadToken, request(ids(asha.getId()), "SET_ATTRIBUTE", "attributeId", city.getId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.value").exists());
        send(post(bulk()), leadToken, request(ids(asha.getId()), "SET_ATTRIBUTE", "attributeId", city.getId(),
                "value", "x".repeat(501))).andExpect(status().isBadRequest());
        send(post(bulk()), leadToken, request(ids(asha.getId()), "SET_ATTRIBUTE", "value", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.attributeId").exists());
        send(post(bulk()), leadToken, request(ids(asha.getId()), "ADD_LABELS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.labelIds").exists());
    }

    @Test
    void deleteByIdsRemovesNotes() throws Exception {
        ContactNote n1 = note(asha);
        ContactNote n2 = note(chitra);
        send(post(bulk()), wmToken, request(ids(asha.getId(), bela.getId()), "DELETE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(2)).andExpect(jsonPath("$.changed").value(2));
        assertThat(contacts.findAll()).extracting(Contact::getName).containsExactlyInAnyOrder("Chitra", "Other");
        assertThat(notes.findAll()).extracting(ContactNote::getId).containsExactly(n2.getId());
        assertThat(n1.getId()).isNotNull();
    }

    @Test
    void deleteByFilter() throws Exception {
        note(asha);
        send(post(bulk()), bossToken, request(Map.of("filter", Map.of("q", "h")), "DELETE"))
                .andExpect(jsonPath("$.matched").value(2)).andExpect(jsonPath("$.changed").value(2));
        assertThat(contacts.findAll()).extracting(Contact::getName).containsExactlyInAnyOrder("Bela", "Other");
        assertThat(notes.count()).isZero();
    }

    @Test
    void bulkDeleteByFilterDeletesAllInBatches() throws Exception {
        List<Contact> matching = new ArrayList<>();
        for (int i = 0; i < 2500; i++) matching.add(bulkContact(brandA, "Zed " + i, String.format("9190%08d", i)));
        Contact keep = bulkContact(brandA, "Keep", "919099999998");
        Contact foreign = bulkContact(brandB, "Zed foreign", "919099999999");
        mongo.insertAll(matching);
        mongo.insertAll(List.of(keep, foreign));
        List<ContactNote> noteDocs = new ArrayList<>();
        for (int i = 0; i < 2500; i += 250) noteDocs.add(noteDoc(brandA, matching.get(i).getId()));
        noteDocs.add(noteDoc(brandA, keep.getId()));
        noteDocs.add(noteDoc(brandB, foreign.getId()));
        mongo.insertAll(noteDocs);
        long before = contacts.count();

        bulkService.setDeleteBatchSize(700);
        try {
            send(post(bulk()), bossToken, request(Map.of("filter", Map.of("q", "Zed")), "DELETE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.matched").value(2500)).andExpect(jsonPath("$.changed").value(2500));
        } finally {
            bulkService.setDeleteBatchSize(ContactBulkService.MAX_IDS);
        }
        assertThat(contacts.count()).isEqualTo(before - 2500);
        assertThat(contacts.findById(keep.getId())).isPresent();
        assertThat(contacts.findById(foreign.getId())).isPresent();
        assertThat(notes.findAll()).extracting(ContactNote::getContactId)
                .containsExactlyInAnyOrder(keep.getId(), foreign.getId());
    }

    private Contact bulkContact(Workspace w, String name, String phone) {
        Contact c = new Contact();
        c.setWorkspaceId(w.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setPhone(phone);
        c.setSource(ContactSource.MANUAL);
        return c;
    }

    private ContactNote noteDoc(Workspace w, String contactId) {
        ContactNote n = new ContactNote();
        n.setWorkspaceId(w.getId());
        n.setContactId(contactId);
        n.setText("note");
        n.setCreatedByUsername("agent");
        return n;
    }

    private ContactNote note(Contact c) {
        ContactNote n = new ContactNote();
        n.setWorkspaceId(brandA.getId());
        n.setContactId(c.getId());
        n.setText("note");
        n.setCreatedByUsername("agent");
        return notes.save(n);
    }

    @Test
    void idsOfOtherBrandUntouched() throws Exception {
        send(post(bulk()), wmToken, request(ids(asha.getId(), otherBrand.getId()), "ADD_LABELS",
                "labelIds", List.of(lead.getId())))
                .andExpect(jsonPath("$.matched").value(1)).andExpect(jsonPath("$.changed").value(1));
        assertThat(reload(otherBrand).getLabels()).isEmpty();
        send(post(bulk()), wmToken, request(ids(otherBrand.getId()), "DELETE"))
                .andExpect(jsonPath("$.matched").value(0)).andExpect(jsonPath("$.changed").value(0));
        assertThat(contacts.findById(otherBrand.getId())).isPresent();
        send(post(bulk()), wmToken, request(ids(otherBrand.getId()), "SET_ATTRIBUTE", "attributeId", city.getId(),
                "value", "x")).andExpect(jsonPath("$.changed").value(0));
        assertThat(reload(otherBrand).getAttributes()).isEmpty();
    }

    @Test
    void foreignLabelOrAttributeIs404() throws Exception {
        ContactLabel theirs = label(brandB, "Theirs");
        ContactAttribute theirAttr = attribute(brandB, "Theirs");
        send(post(bulk()), wmToken, request(ids(asha.getId()), "ADD_LABELS", "labelIds", List.of(theirs.getId())))
                .andExpect(status().isNotFound());
        send(post(bulk()), wmToken, request(ids(asha.getId()), "REMOVE_LABELS", "labelIds",
                List.of(lead.getId(), theirs.getId()))).andExpect(status().isNotFound());
        send(post(bulk()), wmToken, request(ids(asha.getId()), "SET_ATTRIBUTE", "attributeId", theirAttr.getId(),
                "value", "x")).andExpect(status().isNotFound());
        send(post(bulk()), wmToken, request(ids(asha.getId()), "CLEAR_ATTRIBUTE", "attributeId", theirAttr.getId()))
                .andExpect(status().isNotFound());
        ContactFilter theirFilter = new ContactFilter();
        theirFilter.setWorkspaceId(brandB.getId());
        theirFilter.setName("Theirs");
        theirFilter = filters.save(theirFilter);
        send(post(bulk()), wmToken, request(Map.of("filterId", theirFilter.getId()), "DELETE"))
                .andExpect(status().isNotFound());
        assertThat(contacts.count()).isEqualTo(4);
    }

    @Test
    void tooManyIdsIs400() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 1001; i++) many.add("id" + i);
        send(post(bulk()), wmToken, request(Map.of("ids", many), "DELETE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.selection").exists());
        assertThat(contacts.count()).isEqualTo(4);
        // exactly 1000 is fine
        many.remove(1000);
        send(post(bulk()), wmToken, request(Map.of("ids", many), "ADD_LABELS", "labelIds", List.of(vip.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(0));
    }

    @Test
    void exactlyOneSelection() throws Exception {
        Map<String, Object> none = new HashMap<>();
        none.put("action", "DELETE");
        send(post(bulk()), wmToken, none).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.selection").exists());
        send(post(bulk()), wmToken, request(Map.of(), "DELETE")).andExpect(status().isBadRequest());
        send(post(bulk()), wmToken, request(Map.of("ids", List.of()), "DELETE")).andExpect(status().isBadRequest());
        send(post(bulk()), wmToken, request(Map.of("ids", List.of(asha.getId()), "filter", Map.of()), "DELETE"))
                .andExpect(status().isBadRequest());
        send(post(bulk()), wmToken, request(Map.of("filter", Map.of(), "filterId", "x"), "DELETE"))
                .andExpect(status().isNotFound());
        send(post(bulk()), wmToken, request(ids(asha.getId()), "BOGUS")).andExpect(status().isBadRequest());
        send(post(bulk()), wmToken, request(ids(asha.getId()), null)).andExpect(status().isBadRequest());
        assertThat(contacts.count()).isEqualTo(4);
    }

    @Test
    void countsTheSelection() throws Exception {
        String url = base(brandA) + "/contacts/count";
        send(post(url), agentToken, Map.of("selection", Map.of("filter", Map.of())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));
        send(post(url), agentToken, Map.of("selection", Map.of("filter", Map.of("labelsAny", List.of(vip.getId())))))
                .andExpect(jsonPath("$.count").value(1));
        send(post(url), agentToken, Map.of("selection", ids(asha.getId(), otherBrand.getId())))
                .andExpect(jsonPath("$.count").value(1));
        send(post(url), agentToken, Map.of("selection", Map.of())).andExpect(status().isBadRequest());
        send(post(url), agentToken, Map.of("selection", Map.of("filterId", "nope"))).andExpect(status().isNotFound());
    }

    @Test
    void deleteNeedsContactDelete() throws Exception {
        send(post(bulk()), agentToken, request(ids(asha.getId()), "DELETE")).andExpect(status().isForbidden());
        send(post(bulk()), agentToken, request(Map.of("filter", Map.of()), "DELETE")).andExpect(status().isForbidden());
        assertThat(contacts.count()).isEqualTo(4);
    }

    @Test
    void agentCannotExportImportBulkOrDelete() throws Exception {
        send(post(base(brandA) + "/contacts/export"), agentToken, Map.of()).andExpect(status().isForbidden());
        mvc.perform(multipart(base(brandA) + "/contacts/import")
                .file(new MockMultipartFile("file", "c.csv", "text/csv", "phone\n919812345678\n".getBytes()))
                .header("Authorization", "Bearer " + agentToken)).andExpect(status().isForbidden());
        send(post(bulk()), agentToken, request(ids(asha.getId()), "ADD_LABELS", "labelIds", List.of(vip.getId())))
                .andExpect(status().isForbidden());
        send(post(bulk()), agentToken, request(ids(asha.getId()), "DELETE")).andExpect(status().isForbidden());
        send(delete(base(brandA) + "/contacts/" + asha.getId()), agentToken, null).andExpect(status().isForbidden());
        assertThat(contacts.count()).isEqualTo(4);
        assertThat(reload(asha).getLabels()).isEmpty();
    }
}
