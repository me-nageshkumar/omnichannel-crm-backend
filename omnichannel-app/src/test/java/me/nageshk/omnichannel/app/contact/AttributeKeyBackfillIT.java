package me.nageshk.omnichannel.app.contact;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.service.contact.AttributeKeyBackfill;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AttributeKeyBackfillIT extends ContactApiTestBase {

    @Autowired AttributeKeyBackfill backfill;

    @Test
    void keylessAttributesGetKeysAndExistingKeysAreKept() {
        ContactAttribute kept = new ContactAttribute();
        kept.setWorkspaceId(brandA.getId());
        kept.setName("City");
        kept.setKey("city");
        attributes.save(kept);
        ContactAttribute clash = noKey(brandA, "CITY!");
        ContactAttribute other = noKey(brandA, "Preferred size");
        ContactAttribute inB = noKey(brandB, "City");

        backfill.run(null);

        assertThat(attributes.findById(kept.getId()).orElseThrow().getKey()).isEqualTo("city");
        assertThat(attributes.findById(clash.getId()).orElseThrow().getKey()).isEqualTo("city_2");
        assertThat(attributes.findById(other.getId()).orElseThrow().getKey()).isEqualTo("preferred_size");
        assertThat(attributes.findById(inB.getId()).orElseThrow().getKey()).isEqualTo("city");

        backfill.run(null);
        assertThat(attributes.findById(clash.getId()).orElseThrow().getKey()).isEqualTo("city_2");
    }

    private ContactAttribute noKey(me.nageshk.omnichannel.db.tenant.Workspace w, String name) {
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(w.getId());
        a.setName(name);
        return attributes.save(a);
    }
}
