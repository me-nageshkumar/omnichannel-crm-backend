package me.nageshk.omnichannel.app.crm;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.platform.ContactDirectory;
import me.nageshk.omnichannel.db.contact.Contact;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Spec F C7: naming a deal's contacts for the timeline reads the directory in one batch, not per contact. */
class DealContactNamesIT extends CrmTestBase {

    @MockitoSpyBean ContactDirectory directory;

    @Test
    void savingAContactListDoesNotAskTheDirectoryPerContact() throws Exception {
        enableCrm();
        JsonNode alice = captured(contact("Alice", "919833333301"), number);
        String dealId = alice.get("deal").get("id").asText();
        List<String> crmContactIds = new ArrayList<>(List.of(alice.get("contact").get("id").asText()));
        List<Contact> linked = List.of(contact("Dave", "919833333302"), contact("Mira", "919833333303"));
        for (Contact c : linked) crmContactIds.add(captured(c, number).get("contact").get("id").asText());
        long version = json(send(get(crm() + "/deals/" + dealId), managerToken, null)).get("version").asLong();
        clearInvocations(directory);
        send(put(crm() + "/deals/" + dealId), managerToken, Map.of("version", version, "crmContactIds", crmContactIds))
                .andExpect(status().isOk());
        for (Contact c : linked) verify(directory, never()).find(anyString(), eq(c.getId()));
    }
}
