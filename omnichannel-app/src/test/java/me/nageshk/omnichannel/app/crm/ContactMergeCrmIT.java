package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.crm.contact.CrmContact;
import me.nageshk.omnichannel.crm.contact.CrmContactService;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.service.contact.ContactMergeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** A merge of two brand contacts moves their CRM records to the survivor, unless the survivor has one there. */
@ExtendWith(OutputCaptureExtension.class)
class ContactMergeCrmIT extends CrmTestBase {

    private static final String LID = "123456789012345@lid";

    @Autowired ContactMergeService merges;
    @Autowired CrmContactService crmContacts;

    Contact survivor;
    Contact lidOnly;

    @BeforeEach
    void setUp() {
        enableCrm();
        survivor = contact("Asha", "919812345678");
        lidOnly = contact("Asha WA", null);
        lidOnly.setWaIds(new ArrayList<>(List.of(LID)));
        lidOnly = contacts.save(lidOnly);
    }

    private CrmContact crmContact(String id) {
        return mongo.findById(id, CrmContact.class);
    }

    @Test
    void crmRecordFollowsTheSurvivor() throws Exception {
        String record = captured(lidOnly, number).get("contact").get("id").asText();

        merges.merge(brand.getId(), survivor.getId(), lidOnly.getId());

        assertThat(crmContact(record).getContactId()).isEqualTo(survivor.getId());
        assertThat(contacts.findById(lidOnly.getId())).isEmpty();
    }

    @Test
    void conflictingCrmRecordIsLeftAndWarned(CapturedOutput output) throws Exception {
        String kept = captured(survivor, number).get("contact").get("id").asText();
        String left = captured(lidOnly, number).get("contact").get("id").asText();
        String moved = captured(lidOnly, number2).get("contact").get("id").asText();

        merges.merge(brand.getId(), survivor.getId(), lidOnly.getId());

        assertThat(crmContact(kept).getContactId()).isEqualTo(survivor.getId());
        assertThat(crmContact(left).getContactId()).isEqualTo(lidOnly.getId());
        assertThat(crmContact(moved).getContactId()).isEqualTo(survivor.getId());
        assertThat(output).contains("CRM contact " + left + " was not moved");
        assertThat(crmContacts.repointContact(brand.getId(), lidOnly.getId(), survivor.getId())).isZero();
    }
}
