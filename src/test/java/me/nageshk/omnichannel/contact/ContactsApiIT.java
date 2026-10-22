package me.nageshk.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.tenant.Workspace;
import org.junit.jupiter.api.Test;

class ContactsApiIT extends ContactApiTestBase {

    private String url() { return base(brandA) + "/contacts"; }

    private Map<String, Object> body(String name, String phone) {
        Map<String, Object> b = new HashMap<>();
        b.put("name", name);
        b.put("phone", phone);
        return b;
    }

    private String noteUrl(Contact c) { return url() + "/" + c.getId() + "/notes"; }

    private String addNote(Contact c, String token, String text) throws Exception {
        return json(send(post(noteUrl(c)), token, Map.of("text", text)).andExpect(status().isCreated()))
                .get("id").asString();
    }

    @Test
    void managerAddsContact() throws Exception {
        ContactLabel vip = label(brandA, "VIP");
        ContactAttribute city = attribute(brandA, "City");
        Map<String, Object> b = body("  Asha Rao ", "+91 98123-45678");
        b.put("labels", List.of(vip.getId()));
        b.put("attributes", Map.of(city.getId(), "Pune"));
        JsonNode created = json(send(post(url()), leadToken, b)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Asha Rao"))
                .andExpect(jsonPath("$.phone").value("919812345678"))
                .andExpect(jsonPath("$.numberHidden").value(false))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.labels[0]").value(vip.getId()))
                .andExpect(jsonPath("$.attributes." + city.getId()).value("Pune"))
                .andExpect(jsonPath("$.creationTime").isNotEmpty()));
        Contact stored = contacts.findById(created.get("id").asString()).orElseThrow();
        assertThat(stored.isNameEdited()).isTrue();
        assertThat(stored.getWorkspaceId()).isEqualTo(brandA.getId());
        assertThat(stored.getCustomerId()).isEqualTo(acme.getId());

        send(get(url() + "/" + stored.getId()), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("919812345678"));
    }

    @Test
    void invalidOrDuplicatePhone() throws Exception {
        for (String bad : new String[] {"12345", "abc", "0123456789", "", "9".repeat(16)}) {
            send(post(url()), leadToken, body("X", bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fields.phone").exists());
        }
        send(post(url()), leadToken, body(null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.phone").exists());
        send(post(url()), leadToken, body("x".repeat(101), "919812345678")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists());

        send(post(url()), leadToken, body("A", "919812345678")).andExpect(status().isCreated());
        send(post(url()), leadToken, body("B", "+91 9812345678".replace("9812345678", "9812 345678")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.field").value("phone"));
        // another brand may have the same number
        send(post(base(brandB) + "/contacts"), wmBToken, body("A", "919812345678")).andExpect(status().isCreated());
    }

    @Test
    void foreignLabelOrAttributeOnCreateIs400() throws Exception {
        ContactLabel theirs = label(brandB, "Theirs");
        ContactAttribute theirAttr = attribute(brandB, "Theirs");
        Map<String, Object> b = body("A", "919812345678");
        b.put("labels", List.of(theirs.getId()));
        send(post(url()), leadToken, b).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.labels").exists());
        Map<String, Object> c = body("A", "919812345678");
        c.put("attributes", Map.of(theirAttr.getId(), "x"));
        send(post(url()), leadToken, c).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.attributes").exists());
        assertThat(contacts.count()).isZero();
    }

    @Test
    void blankNameClearsName() throws Exception {
        Contact c = contact(brandA, "Pushname", "919812345679");
        send(put(url() + "/" + c.getId()), leadToken, Map.of("attributes", Map.of())).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pushname"));
        send(put(url() + "/" + c.getId()), leadToken, Map.of("name", "   ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").doesNotExist());
        Contact after = contacts.findById(c.getId()).orElseThrow();
        assertThat(after.getName()).isNull();
        assertThat(after.isNameEdited()).isTrue();
    }

    @Test
    void blankAttributeValueClearsAttribute() throws Exception {
        ContactAttribute city = attribute(brandA, "City");
        Contact c = contact(brandA, "Asha", "919812345680");
        c.getAttributes().put(city.getId(), "Pune");
        contacts.save(c);
        send(put(url() + "/" + c.getId()), leadToken, Map.of("attributes", Map.of(city.getId(), "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes." + city.getId()).doesNotExist());
        assertThat(contacts.findById(c.getId()).orElseThrow().getAttributes()).doesNotContainKey(city.getId());
    }

    @Test
    void updateSetsNameAndMergesAttributes() throws Exception {
        ContactAttribute city = attribute(brandA, "City");
        ContactAttribute size = attribute(brandA, "Size");
        Contact c = contact(brandA, "Pushname", "919812345678");
        c.getAttributes().put(city.getId(), "Pune");
        contacts.save(c);

        Map<String, Object> change = new HashMap<>();
        change.put("name", "Typed Name");
        Map<String, String> attrs = new HashMap<>();
        attrs.put(size.getId(), "M");
        attrs.put(city.getId(), null);
        change.put("attributes", attrs);
        send(put(url() + "/" + c.getId()), leadToken, change).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Typed Name"))
                .andExpect(jsonPath("$.attributes." + size.getId()).value("M"))
                .andExpect(jsonPath("$.attributes." + city.getId()).doesNotExist());
        Contact after = contacts.findById(c.getId()).orElseThrow();
        assertThat(after.isNameEdited()).isTrue();
        assertThat(after.getAttributes()).containsOnlyKeys(size.getId());

        send(put(url() + "/" + c.getId()), leadToken, Map.of("attributes", Map.of(size.getId(), "x".repeat(501))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.attributes").exists());
        send(put(url() + "/" + c.getId()), leadToken, Map.of("attributes", Map.of("not-mine", "x")))
                .andExpect(status().isBadRequest());
        send(put(url() + "/" + c.getId()), leadToken, Map.of("name", "x".repeat(101)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void agentTagsAndNotes() throws Exception {
        ContactLabel vip = label(brandA, "VIP");
        Contact c = contact(brandA, "Asha", "919812345678");

        send(post(url() + "/" + c.getId() + "/labels/" + vip.getId()), agentToken, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.labels[0]").value(vip.getId()));
        // adding twice keeps one
        send(post(url() + "/" + c.getId() + "/labels/" + vip.getId()), agentToken, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.labels.length()").value(1));
        send(delete(url() + "/" + c.getId() + "/labels/" + vip.getId()), agentToken, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.labels.length()").value(0));

        String noteId = addNote(c, agentToken, "  called back ");
        send(get(noteUrl(c)), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(noteId))
                .andExpect(jsonPath("$[0].text").value("called back"))
                .andExpect(jsonPath("$[0].contactId").value(c.getId()))
                .andExpect(jsonPath("$[0].createdBy.username").value("agent"))
                .andExpect(jsonPath("$[0].createdBy.name").value("Test agent"))
                .andExpect(jsonPath("$[0].mine").value(true));
        send(put(noteUrl(c) + "/" + noteId), agentToken, Map.of("text", "edited")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("edited"));
        send(get(noteUrl(c)), wmToken, null).andExpect(jsonPath("$[0].mine").value(false));
        send(delete(noteUrl(c) + "/" + noteId), agentToken, null).andExpect(status().isNoContent());
        assertThat(notes.count()).isZero();

        send(post(noteUrl(c)), agentToken, Map.of("text", "   ")).andExpect(status().isBadRequest());
        send(post(noteUrl(c)), agentToken, Map.of("text", "x".repeat(2001))).andExpect(status().isBadRequest());
    }

    @Test
    void notesAreNewestFirst() throws Exception {
        Contact c = contact(brandA, "Asha", "919812345678");
        addNote(c, agentToken, "first");
        Thread.sleep(5);
        addNote(c, agentToken, "second");
        send(get(noteUrl(c)), agentToken, null).andExpect(jsonPath("$[0].text").value("second"))
                .andExpect(jsonPath("$[1].text").value("first"));
    }

    @Test
    void agentCannotEditAttributes() throws Exception {
        Contact c = contact(brandA, "Asha", "919812345678");
        send(put(url() + "/" + c.getId()), agentToken, Map.of("name", "X")).andExpect(status().isForbidden());
        send(post(url()), agentToken, body("A", "919812345679")).andExpect(status().isForbidden());
        send(delete(url() + "/" + c.getId()), agentToken, null).andExpect(status().isForbidden());
        assertThat(contacts.findById(c.getId()).orElseThrow().getName()).isEqualTo("Asha");
    }

    @Test
    void onlyAuthorEditsNote() throws Exception {
        Contact c = contact(brandA, "Asha", "919812345678");
        String noteId = addNote(c, agentToken, "mine");
        send(put(noteUrl(c) + "/" + noteId), leadToken, Map.of("text", "hijack")).andExpect(status().isForbidden());
        send(put(noteUrl(c) + "/" + noteId), wmToken, Map.of("text", "hijack")).andExpect(status().isForbidden());
        assertThat(notes.findById(noteId).orElseThrow().getText()).isEqualTo("mine");
    }

    @Test
    void managerDeletesAnyNote() throws Exception {
        Contact c = contact(brandA, "Asha", "919812345678");
        String n1 = addNote(c, agentToken, "one");
        String n2 = addNote(c, leadToken, "two");
        send(delete(noteUrl(c) + "/" + n1), wmToken, null).andExpect(status().isNoContent());
        send(delete(noteUrl(c) + "/" + n2), bossToken, null).andExpect(status().isNoContent());
        assertThat(notes.count()).isZero();
        send(delete(noteUrl(c) + "/" + n2), bossToken, null).andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesNotes() throws Exception {
        Contact c = contact(brandA, "Asha", "919812345678");
        Contact keep = contact(brandA, "Keep", "919812345679");
        addNote(c, agentToken, "one");
        addNote(keep, agentToken, "two");
        send(delete(url() + "/" + c.getId()), wmToken, null).andExpect(status().isNoContent());
        assertThat(contacts.findById(c.getId())).isEmpty();
        assertThat(notes.findAll()).extracting(ContactNote::getContactId).containsExactly(keep.getId());
        send(get(url() + "/" + c.getId()), wmToken, null).andExpect(status().isNotFound());
    }

    @Test
    void otherBrandContactIs404() throws Exception {
        Contact theirs = contact(brandB, "Bela", "919812345678");
        ContactLabel mine = label(brandA, "Mine");
        ContactLabel theirLabel = label(brandB, "Theirs");
        Contact c = contact(brandA, "Asha", "919812345679");

        send(get(url() + "/" + theirs.getId()), wmToken, null).andExpect(status().isNotFound());
        send(put(url() + "/" + theirs.getId()), wmToken, Map.of("name", "X")).andExpect(status().isNotFound());
        send(delete(url() + "/" + theirs.getId()), wmToken, null).andExpect(status().isNotFound());
        send(post(url() + "/" + theirs.getId() + "/labels/" + mine.getId()), wmToken, null)
                .andExpect(status().isNotFound());
        send(get(noteUrl(theirs)), wmToken, null).andExpect(status().isNotFound());
        send(post(noteUrl(theirs)), wmToken, Map.of("text", "x")).andExpect(status().isNotFound());
        // a label of another brand cannot be put on my contact
        send(post(url() + "/" + c.getId() + "/labels/" + theirLabel.getId()), wmToken, null)
                .andExpect(status().isNotFound());
        // a manager of brand B reaching for brand A's contact through brand B's URL
        send(get(base(brandB) + "/contacts/" + c.getId()), wmBToken, null).andExpect(status().isNotFound());
        assertThat(contacts.findById(theirs.getId())).isPresent();
        assertThat(contacts.findById(c.getId()).orElseThrow().getLabels()).isEmpty();
    }

    private Contact plain(Workspace brand, String phone) {
        Contact c = new Contact();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setPhone(phone);
        c.setSource(ContactSource.MANUAL);
        return contacts.save(c);
    }

    @Test
    void markAndClearDoNotContact() throws Exception {
        Contact c = plain(brandA, "919800000001");
        String dnc = url() + "/" + c.getId() + "/do-not-contact";
        send(put(dnc), wmToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.doNotContact.reason").value("MANUAL"))
                .andExpect(jsonPath("$.doNotContact.campaignId").doesNotExist())
                .andExpect(jsonPath("$.doNotContact.at").isNotEmpty());
        send(get(url() + "/" + c.getId()), wmToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.doNotContact.reason").value("MANUAL"));
        send(delete(dnc), wmToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.doNotContact").doesNotExist());
        assertThat(contacts.findById(c.getId()).orElseThrow().getDoNotContact()).isNull();
    }

    @Test
    void markingTwiceKeepsTheFirstMark() throws Exception {
        Contact c = plain(brandA, "919800000002");
        String dnc = url() + "/" + c.getId() + "/do-not-contact";
        String first = json(send(put(dnc), wmToken, null).andExpect(status().isOk())).get("doNotContact").get("at").asString();
        Thread.sleep(5);
        send(put(dnc), wmToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.doNotContact.at").value(first));
    }

    @Test
    void doNotContactNeedsContactEdit() throws Exception {
        Contact c = plain(brandA, "919800000003");
        String dnc = url() + "/" + c.getId() + "/do-not-contact";
        send(put(dnc), agentToken, null).andExpect(status().isForbidden());
        send(delete(dnc), agentToken, null).andExpect(status().isForbidden());
    }

    @Test
    void doNotContactOfAnotherBrandIs404() throws Exception {
        Contact theirs = plain(brandB, "919800000004");
        String dnc = url() + "/" + theirs.getId() + "/do-not-contact";
        send(put(dnc), wmToken, null).andExpect(status().isNotFound());
        send(delete(dnc), wmToken, null).andExpect(status().isNotFound());
    }
}
