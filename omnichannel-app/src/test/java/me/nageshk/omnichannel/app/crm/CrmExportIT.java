package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.csv.Csv;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.RoleCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class CrmExportIT extends CrmTestBase {

    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;

    /** Runs the export as the user and returns the parsed rows; asserts the CSV response headers on the way. */
    private String export(String entity, String token, Map<String, Object> body) throws Exception {
        MvcResult started = send(post(crm() + "/" + entity + "/export"), token, body)
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn();
        assertThat(done.getResponse().getHeader("Content-Disposition"))
                .matches("attachment; filename=\"crm-" + entity + "-\\d{8}-\\d{4}\\.csv\"");
        return done.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void contactsExportHasBomHeaderAndCustomFieldColumns() throws Exception {
        enableCrm();
        send(post(crm() + "/fields"), managerToken, Map.of("entityType", "CONTACT", "key", "birthday",
                "label", "Birthday", "type", "DATE")).andExpect(status().isCreated());
        JsonNode contact = captured(contact("Asha", "919900000301"), number).get("contact");
        Map<String, Object> update = new HashMap<>();
        update.put("version", contact.get("version").asLong());
        update.put("email", "asha@example.com");
        update.put("customFields", Map.of("birthday", "1990-05-01"));
        send(put(crm() + "/contacts/" + contact.get("id").asText()), managerToken, update)
                .andExpect(status().isOk());

        String text = export("contacts", managerToken, Map.of());
        assertThat(text).startsWith(Csv.BOM);
        assertThat(text).contains("\r\n");
        List<List<String>> rows = Csv.parse(text);
        assertThat(rows.get(0)).containsExactly("First name", "Last name", "WhatsApp number", "Channel", "Email",
                "Job title", "Company", "Owner", "Lifecycle", "Source", "Open deals", "Open deal amount", "Created",
                "Last activity", "Birthday (birthday)");
        assertThat(rows).hasSize(2);
        List<String> row = rows.get(1);
        assertThat(row.get(2)).isEqualTo("919900000301");
        assertThat(row.get(3)).isEqualTo(number.getNumber());
        assertThat(row.get(4)).isEqualTo("asha@example.com");
        assertThat(row.get(10)).isEqualTo("1");
        assertThat(row.get(14)).isEqualTo("1990-05-01");
    }

    @Test
    void exportFollowsFilters() throws Exception {
        enableCrm();
        captured(contact("Asha", "919900000302"), number);
        captured(contact("Ravi", "919900000303"), number);
        List<List<String>> all = Csv.parse(export("contacts", managerToken, Map.of()));
        assertThat(all).hasSize(3);
        List<List<String>> some = Csv.parse(export("contacts", managerToken, Map.of("q", "919900000303")));
        assertThat(some).hasSize(2);
        assertThat(some.get(1).get(2)).isEqualTo("919900000303");
        List<List<String>> deals = Csv.parse(export("deals", managerToken, Map.of("q", "Ravi", "sort", "title")));
        assertThat(deals.get(0).subList(0, 4)).containsExactly("Title", "Pipeline", "Stage", "Status");
        assertThat(deals).hasSize(2);
        assertThat(deals.get(1).get(1)).isEqualTo("Sales");
        assertThat(deals.get(1).get(3)).isEqualTo("OPEN");
        send(post(crm() + "/companies"), managerToken, Map.of("name", "Acme Traders")).andExpect(status().isOk());
        List<List<String>> companies = Csv.parse(export("companies", managerToken, Map.of()));
        assertThat(companies.get(0).get(0)).isEqualTo("Name");
        assertThat(companies.get(1).get(0)).isEqualTo("Acme Traders");
    }

    @Test
    void restrictedExportOnlyAccessibleDeals() throws Exception {
        enableCrm();
        String second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().isCreated())).get("id").asText();
        JsonNode capture = captured(contact("Asha", "919900000304"), number);
        createDeal(Map.of("title", "Visible", "pipelineId", second, "primaryCrmContactId",
                capture.get("contact").get("id").asText()));
        Role exporter = new Role();
        exporter.setName("CRM Exporter");
        exporter.setLevel(RoleLevel.BRAND);
        exporter.setAuthorities(new HashSet<>(Set.of("crm:view", "crm:export")));
        exporter = roles.save(exporter);
        roleCache.evictAll();
        try {
            User u = testUsers.createInCustomer(acme.getId(), "exporter", null,
                    List.of(new WorkspaceRole(brand.getId(), exporter.getId())));
            assignPipelines(u, second);
            List<List<String>> rows = Csv.parse(export("deals", testUsers.accessToken(u), Map.of()));
            assertThat(rows).hasSize(2);
            assertThat(rows.get(1).get(0)).isEqualTo("Visible");
            assertThat(rows.get(1).get(1)).isEqualTo("Second");
            assertThat(Csv.parse(export("deals", managerToken, Map.of()))).hasSize(3);
        } finally {
            roles.delete(exporter);
            roleCache.evictAll();
        }
    }

    @Test
    void agentWithoutExportIs403() throws Exception {
        enableCrm();
        for (String entity : List.of("contacts", "companies", "deals")) {
            send(post(crm() + "/" + entity + "/export"), agentToken, Map.of()).andExpect(status().isForbidden());
        }
    }

    @Test
    void badSortIs400BeforeStreaming() throws Exception {
        enableCrm();
        send(post(crm() + "/contacts/export"), managerToken, Map.of("sort", "nope"))
                .andExpect(status().isBadRequest());
    }
}
