package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

class CrmImportIT extends CrmTestBase {

    private ResultActions importContacts(String token, String csv, String pipelineId, String phoneNumberId)
            throws Exception {
        return mvc.perform(multipart(crm() + "/contacts/import").file(file(csv)).param("pipelineId", pipelineId)
                .param("phoneNumberId", phoneNumberId).header("Authorization", "Bearer " + token));
    }

    private ResultActions importCompanies(String csv) throws Exception {
        return mvc.perform(multipart(crm() + "/companies/import").file(file(csv))
                .header("Authorization", "Bearer " + managerToken));
    }

    private static MockMultipartFile file(String csv) {
        return new MockMultipartFile("file", "import.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode contacts() throws Exception {
        return json(send(post(crm() + "/contacts/search"), managerToken, Map.of("sort", "firstName", "dir", "asc"))
                .andExpect(status().isOk())).get("items");
    }

    private JsonNode deals(String pipelineId) throws Exception {
        return json(send(post(crm() + "/deals/search"), managerToken, Map.of("pipelineId", pipelineId))
                .andExpect(status().isOk())).get("items");
    }

    private JsonNode contactByPhone(String phone) throws Exception {
        for (JsonNode c : contacts()) if (phone.equals(c.get("phone").asText())) return c;
        throw new AssertionError("no contact " + phone);
    }

    @Test
    void importsContactsIntoPipelineAndChannel() throws Exception {
        enableCrm();
        send(post(crm() + "/fields"), managerToken, Map.of("entityType", "CONTACT", "key", "birthday",
                "label", "Birthday", "type", "DATE")).andExpect(status().isCreated());
        String csv = "WhatsApp number,First name,Last name,Email,Company,Lifecycle,Birthday (birthday)\n"
                + "919900000401,Asha,Rao,asha@example.com,Acme Traders,Customer,1990-05-01\n"
                + "919900000402,Ravi,,,,,\n";
        importContacts(managerToken, csv, salesId(), number2.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(0)).andExpect(jsonPath("$.errors.length()").value(0));
        JsonNode asha = contactByPhone("919900000401");
        assertThat(asha.at("/origin/kind").asText()).isEqualTo("IMPORT");
        assertThat(asha.at("/channel/phoneNumberId").asText()).isEqualTo(number2.getId());
        assertThat(asha.get("firstName").asText()).isEqualTo("Asha");
        assertThat(asha.get("email").asText()).isEqualTo("asha@example.com");
        assertThat(asha.get("lifecycleStatus").asText()).isEqualTo("customer");
        assertThat(asha.at("/company/name").asText()).isEqualTo("Acme Traders");
        assertThat(asha.at("/customFields/birthday").asText()).isEqualTo("1990-05-01");
        assertThat(contactByPhone("919900000402").get("lifecycleStatus").asText()).isEqualTo("lead");
        String firstLane = stages(salesId()).get("New");
        JsonNode deals = deals(salesId());
        assertThat(deals).hasSize(2);
        deals.forEach(d -> assertThat(d.get("stageId").asText()).isEqualTo(firstLane));
    }

    @Test
    void secondImportUpdatesBlankFieldsOnly() throws Exception {
        enableCrm();
        importContacts(managerToken, "WhatsApp number,First name\n919900000403,Asha\n", salesId(), number.getId())
                .andExpect(jsonPath("$.created").value(1));
        importContacts(managerToken, "WhatsApp number,First name,Last name,Email\n"
                + "919900000403,Changed,Rao,asha@example.com\n", salesId(), number.getId())
                .andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.updated").value(1));
        JsonNode asha = contactByPhone("919900000403");
        assertThat(asha.get("firstName").asText()).isEqualTo("Asha");
        assertThat(asha.get("lastName").asText()).isEqualTo("Rao");
        assertThat(asha.get("email").asText()).isEqualTo("asha@example.com");
        assertThat(deals(salesId())).hasSize(1);
        importContacts(managerToken, "WhatsApp number,First name\n919900000403,Other\n", salesId(), number.getId())
                .andExpect(jsonPath("$.skipped").value(1));
    }

    @Test
    void badPhoneRowReportedNotFatal() throws Exception {
        enableCrm();
        importContacts(managerToken, "WhatsApp number,First name\n919900000404,Asha\nnot-a-phone,Bad\n"
                + ",Empty\n919900000405,Ravi\n", salesId(), number.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4)).andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.errors.length()").value(2)).andExpect(jsonPath("$.errors[0].row").value(2))
                .andExpect(jsonPath("$.errors[1].row").value(3));
        assertThat(contacts()).hasSize(2);
    }

    @Test
    void errorRowCreatesNothing() throws Exception {
        enableCrm();
        send(post(crm() + "/fields"), managerToken, Map.of("entityType", "CONTACT", "key", "birthday",
                "label", "Birthday", "type", "DATE")).andExpect(status().isCreated());
        importContacts(managerToken, "WhatsApp number,First name,Job title,Birthday (birthday)\n"
                + "919900000411,Asha,,1990-05-01\n"
                + "919900000412,Bad date,,someday\n"
                + "919900000413,Long title," + "x".repeat(101) + ",\n", salesId(), number.getId())
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.errors.length()").value(2)).andExpect(jsonPath("$.errors[0].row").value(2))
                .andExpect(jsonPath("$.errors[1].row").value(3));
        assertThat(contacts()).hasSize(1);
        assertThat(deals(salesId())).hasSize(1);
    }

    @Test
    void missingHeaderIs400() throws Exception {
        enableCrm();
        importContacts(managerToken, "Name,Email\nAsha,a@example.com\n", salesId(), number.getId())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMPORT_BAD_HEADER"))
                .andExpect(jsonPath("$.missing").value("WhatsApp number"));
        importCompanies("Domain\nacme.com\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.missing").value("Name"));
    }

    @Test
    void tooManyRowsIs400() throws Exception {
        enableCrm();
        StringBuilder b = new StringBuilder("WhatsApp number\n");
        for (int i = 0; i < 1001; i++) b.append("91990001").append(String.format("%04d", i)).append('\n');
        importContacts(managerToken, b.toString(), salesId(), number.getId()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_TOO_LARGE")).andExpect(jsonPath("$.limit").value("1000"));
        assertThat(contacts()).isEmpty();
        StringBuilder c = new StringBuilder("Name\n");
        for (int i = 0; i < 2001; i++) c.append("Company ").append(i).append('\n');
        importCompanies(c.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.limit").value("2000"));
    }

    @Test
    void importToleratesBomCrlfAndQuotes() throws Exception {
        enableCrm();
        String csv = "﻿WhatsApp number,First name,Last name\r\n919900000406,Asha,\"Rao, Jr\"\r\n"
                + "\"919900000407\",\"Ravi \"\"R\"\"\",Kumar\r\n\r\n";
        importContacts(managerToken, csv, salesId(), number.getId()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.created").value(2)).andExpect(jsonPath("$.errors.length()").value(0));
        assertThat(contactByPhone("919900000406").get("lastName").asText()).isEqualTo("Rao, Jr");
        assertThat(contactByPhone("919900000407").get("firstName").asText()).isEqualTo("Ravi \"R\"");
    }

    @Test
    void companiesUpsertByNameThenDomain() throws Exception {
        enableCrm();
        send(post(crm() + "/companies"), managerToken, Map.of("name", "Acme Traders", "domain", "acme.com",
                "website", "https://acme.com")).andExpect(status().isOk());
        String csv = "Name,Domain,Website,City\n"
                + "acme traders,,,Pune\n"
                + "Acme Holdings,ACME.com,,,\n"
                + "Beta Ltd,beta.com,https://beta.com,Delhi\n";
        importCompanies(csv).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.skipped").value(1));
        JsonNode list = json(send(post(crm() + "/companies/search"), managerToken, Map.of("sort", "name",
                "dir", "asc"))).get("items");
        List<String> names = new ArrayList<>();
        list.forEach(c -> names.add(c.get("name").asText()));
        assertThat(names).containsExactly("Acme Traders", "Beta Ltd");
        assertThat(list.get(0).at("/address/city").asText()).isEqualTo("Pune");
        assertThat(list.get(0).get("website").asText()).isEqualTo("https://acme.com");
    }

    @Test
    void agentCannotImportOnNumberTheyDontWork() throws Exception {
        enableCrm();
        assignPipelines(agent, salesId());
        importContacts(agentToken, "WhatsApp number\n919900000408\n", salesId(), number2.getId())
                .andExpect(status().isNotFound());
        importContacts(agentToken, "WhatsApp number\n919900000408\n", salesId(), number.getId())
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
    }

    // --- templates (spec F C13, C14) -------------------------------------------------------------------------------

    private String template(String entity) throws Exception {
        MvcResult done = send(get(crm() + "/" + entity + "/import/template"), managerToken, null)
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn();
        assertThat(done.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"crm-" + entity + "-template.csv\"");
        return done.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void customField(String entity, String key, String label) throws Exception {
        send(post(crm() + "/fields"), managerToken, Map.of("entityType", entity, "key", key, "label", label,
                "type", "TEXT")).andExpect(status().isCreated());
    }

    private JsonNode firstActive(String picklist) throws Exception {
        JsonNode list = json(send(get(crm() + "/settings"), managerToken, null)).get(picklist);
        return list.valueStream().filter(i -> i.get("active").asBoolean()).findFirst().orElseThrow();
    }

    private String firstActiveLabel(String picklist) throws Exception {
        return firstActive(picklist).get("label").asText();
    }

    private String firstActiveKey(String picklist) throws Exception {
        return firstActive(picklist).get("key").asText();
    }

    @Test
    void templateListsColumnsAndActiveFields() throws Exception {
        enableCrm();
        customField("CONTACT", "tier", "Tier");
        String hidden = json(send(post(crm() + "/fields"), managerToken, Map.of("entityType", "CONTACT", "key", "old",
                "label", "Old", "type", "TEXT")).andExpect(status().isCreated())).get("id").asText();
        JsonNode def = json(send(get(crm() + "/fields?entityType=CONTACT"), managerToken, null)).valueStream()
                .filter(f -> f.get("id").asText().equals(hidden)).findFirst().orElseThrow();
        Map<String, Object> off = new java.util.HashMap<>(mapper.convertValue(def,
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        off.put("active", false);
        send(put(crm() + "/fields/" + hidden), managerToken, off).andExpect(status().isOk());
        assertThat(template("contacts")).isEqualTo("\uFEFFWhatsApp number,First name,Last name,Email,Job title,Company,"
                + "Lifecycle,Source,Tier (tier)\r\n");
        assertThat(template("companies")).isEqualTo("\uFEFFName,Domain,Website,Industry,Size,Annual revenue,Phone,Email,"
                + "City,Country,Description\r\n");
    }

    @Test
    void contactTemplateRoundTrips() throws Exception {
        enableCrm();
        customField("CONTACT", "tier", "Tier");
        String header = template("contacts").replace("\uFEFF", "");
        String lifecycle = firstActiveLabel("lifecycleStatuses");
        String row = "919900000501,Asha,Rao,asha@example.com,Buyer,Acme Traders," + lifecycle + ","
                + firstActiveLabel("dealSources") + ",Gold\r\n";
        importContacts(managerToken, header + row, salesId(), number.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.errors.length()").value(0));
        JsonNode asha = contactByPhone("919900000501");
        assertThat(asha.get("firstName").asText()).isEqualTo("Asha");
        assertThat(asha.get("lastName").asText()).isEqualTo("Rao");
        assertThat(asha.get("email").asText()).isEqualTo("asha@example.com");
        assertThat(asha.get("jobTitle").asText()).isEqualTo("Buyer");
        assertThat(asha.at("/company/name").asText()).isEqualTo("Acme Traders");
        assertThat(asha.get("lifecycleStatus").asText()).isEqualTo(firstActiveKey("lifecycleStatuses"));
        assertThat(asha.get("source").asText()).isEqualTo(firstActiveKey("dealSources"));
        assertThat(asha.at("/customFields/tier").asText()).isEqualTo("Gold");
    }

    @Test
    void companyTemplateRoundTrips() throws Exception {
        enableCrm();
        customField("COMPANY", "tier", "Tier");
        Map<String, Object> settings = new java.util.HashMap<>(mapper.convertValue(json(send(get(crm() + "/settings"),
                managerToken, null)), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        settings.put("industries", java.util.List.of(Map.of("key", "retail", "label", "Retail", "position", 0, "active",
                true, "isDefault", false)));
        send(put(crm() + "/settings"), managerToken, settings).andExpect(status().isOk());
        String header = template("companies").replace("\uFEFF", "");
        String row = "Acme,acme.example,https://acme.example,Retail"
                + ",11-50,1000,919900000502,hi@acme.example,Pune,India,Makes things,Gold\r\n";
        importCompanies(header + row).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.errors.length()").value(0));
        JsonNode acme = json(send(post(crm() + "/companies/search"), managerToken, Map.of())).get("items").get(0);
        assertThat(acme.get("domain").asText()).isEqualTo("acme.example");
        assertThat(acme.get("industry").asText()).isEqualTo("retail");
        assertThat(acme.get("website").asText()).isEqualTo("https://acme.example");
        assertThat(acme.get("size").asText()).isEqualTo("11-50");
        assertThat(acme.get("annualRevenue").decimalValue()).isEqualByComparingTo("1000");
        assertThat(acme.at("/address/city").asText()).isEqualTo("Pune");
        assertThat(acme.at("/address/country").asText()).isEqualTo("India");
        assertThat(acme.get("phone").asText()).isEqualTo("919900000502");
        assertThat(acme.get("email").asText()).isEqualTo("hi@acme.example");
        assertThat(acme.get("description").asText()).isEqualTo("Makes things");
        assertThat(acme.at("/customFields/tier").asText()).isEqualTo("Gold");
    }

    @Test
    void templateNeedsEdit() throws Exception {
        enableCrm();
        me.nageshk.omnichannel.db.user.User viewer = brandUserWith("viewer", "CRM_VIEWER", java.util.Set.of("crm:view"));
        send(get(crm() + "/contacts/import/template"), testUsers.accessToken(viewer), null)
                .andExpect(status().isForbidden());
        send(get(crm() + "/companies/import/template"), testUsers.accessToken(viewer), null)
                .andExpect(status().isForbidden());
    }
}
