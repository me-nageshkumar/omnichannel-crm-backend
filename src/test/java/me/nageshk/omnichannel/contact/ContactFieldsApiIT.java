package me.nageshk.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ContactFieldsApiIT extends ContactApiTestBase {

    private String labelsUrl() { return base(brandA) + "/contact-labels"; }

    private String attributesUrl() { return base(brandA) + "/contact-attributes"; }

    @Test
    void managerCreatesLabelAndAttribute() throws Exception {
        send(post(labelsUrl()), wmToken, Map.of("name", "  VIP ", "color", "purple", "description", "Big spenders"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("VIP"))
                .andExpect(jsonPath("$.color").value("purple"))
                .andExpect(jsonPath("$.description").value("Big spenders"))
                .andExpect(jsonPath("$.contactCount").value(0));
        send(post(attributesUrl()), wmToken, Map.of("name", "City"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("City"))
                .andExpect(jsonPath("$.contactCount").value(0));

        send(get(labelsUrl()), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("VIP"));
        send(get(attributesUrl()), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("City"));
    }

    @Test
    void renameKeepsIdAndCountsContacts() throws Exception {
        String id = json(send(post(labelsUrl()), wmToken, Map.of("name", "Lead", "color", "blue"))).get("id").asString();
        Contact c = contact(brandA, "Asha", "919800000001");
        c.getLabels().add(id);
        contacts.save(c);
        send(put(labelsUrl() + "/" + id), wmToken, Map.of("name", "Hot lead", "color", "red", "description", "x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Hot lead"))
                .andExpect(jsonPath("$.contactCount").value(1));
        assertThat(contacts.findById(c.getId()).orElseThrow().getLabels()).containsExactly(id);
    }

    @Test
    void duplicateNameIs409() throws Exception {
        send(post(labelsUrl()), wmToken, Map.of("name", "VIP", "color", "blue")).andExpect(status().isCreated());
        send(post(labelsUrl()), wmToken, Map.of("name", "vip", "color", "red"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.field").value("name"));
        send(post(attributesUrl()), wmToken, Map.of("name", "City")).andExpect(status().isCreated());
        send(post(attributesUrl()), wmToken, Map.of("name", " CITY "))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.field").value("name"));
        // the same name in another brand is fine
        send(post(base(brandB) + "/contact-labels"), wmBToken, Map.of("name", "VIP", "color", "blue"))
                .andExpect(status().isCreated());
        // renaming onto another label's name is a duplicate too
        String other = json(send(post(labelsUrl()), wmToken, Map.of("name", "Other", "color", "blue"))).get("id").asString();
        send(put(labelsUrl() + "/" + other), wmToken, Map.of("name", "VIP", "color", "blue"))
                .andExpect(status().isConflict());
        // renaming a label to its own name in another case is allowed
        send(put(labelsUrl() + "/" + other), wmToken, Map.of("name", "OTHER", "color", "blue"))
                .andExpect(status().isOk());
    }

    @Test
    void badColorIs400() throws Exception {
        send(post(labelsUrl()), wmToken, Map.of("name", "VIP", "color", "pink"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.color").exists());
        send(post(labelsUrl()), wmToken, Map.of("name", "   ", "color", "blue"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists());
        send(post(labelsUrl()), wmToken, Map.of("name", "x".repeat(41), "color", "blue"))
                .andExpect(status().isBadRequest());
        send(post(labelsUrl()), wmToken, Map.of("name", "ok", "color", "blue", "description", "d".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.description").exists());
        send(post(attributesUrl()), wmToken, Map.of("name", "x".repeat(41)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteCascadesFromContacts() throws Exception {
        ContactLabel l = label(brandA, "VIP");
        ContactLabel keep = label(brandA, "Keep");
        ContactAttribute a = attribute(brandA, "City");
        Contact mine = contact(brandA, "Asha", "919800000001");
        mine.getLabels().addAll(java.util.Set.of(l.getId(), keep.getId()));
        mine.getAttributes().put(a.getId(), "Pune");
        contacts.save(mine);
        Contact theirs = contact(brandB, "Bela", "919800000002");
        theirs.getLabels().add(l.getId());
        theirs.getAttributes().put(a.getId(), "Delhi");
        contacts.save(theirs);

        send(delete(labelsUrl() + "/" + l.getId()), wmToken, null).andExpect(status().isNoContent());
        send(delete(attributesUrl() + "/" + a.getId()), wmToken, null).andExpect(status().isNoContent());

        Contact after = contacts.findById(mine.getId()).orElseThrow();
        assertThat(after.getLabels()).containsExactly(keep.getId());
        assertThat(after.getAttributes()).isEmpty();
        Contact other = contacts.findById(theirs.getId()).orElseThrow();
        assertThat(other.getLabels()).containsExactly(l.getId());
        assertThat(other.getAttributes()).containsEntry(a.getId(), "Delhi");
        assertThat(labels.findById(l.getId())).isEmpty();
        assertThat(attributes.findById(a.getId())).isEmpty();
    }

    @Test
    void teamLeadCannotManageFields() throws Exception {
        ContactLabel l = label(brandA, "VIP");
        send(post(labelsUrl()), leadToken, Map.of("name", "X", "color", "blue")).andExpect(status().isForbidden());
        send(put(labelsUrl() + "/" + l.getId()), leadToken, Map.of("name", "X", "color", "blue"))
                .andExpect(status().isForbidden());
        send(delete(labelsUrl() + "/" + l.getId()), leadToken, null).andExpect(status().isForbidden());
        send(post(attributesUrl()), leadToken, Map.of("name", "X")).andExpect(status().isForbidden());
        send(get(labelsUrl()), leadToken, null).andExpect(status().isOk());
    }

    @Test
    void otherBrandIs404() throws Exception {
        ContactLabel l = label(brandB, "Theirs");
        ContactAttribute a = attribute(brandB, "Theirs");
        send(put(labelsUrl() + "/" + l.getId()), wmToken, Map.of("name", "X", "color", "blue"))
                .andExpect(status().isNotFound());
        send(delete(labelsUrl() + "/" + l.getId()), wmToken, null).andExpect(status().isNotFound());
        send(put(attributesUrl() + "/" + a.getId()), wmToken, Map.of("name", "X")).andExpect(status().isNotFound());
        send(delete(attributesUrl() + "/" + a.getId()), wmToken, null).andExpect(status().isNotFound());
        // the manager of brand A has no access to brand B at all
        send(get(base(brandB) + "/contact-labels"), wmToken, null).andExpect(status().isNotFound());
        assertThat(labels.findById(l.getId())).isPresent();
    }
}
