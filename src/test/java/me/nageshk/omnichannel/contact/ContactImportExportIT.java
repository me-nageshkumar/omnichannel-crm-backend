package me.nageshk.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

class ContactImportExportIT extends ContactApiTestBase {

    @Autowired ContactExportController exporter;

    ContactLabel vip;
    ContactLabel lead;
    ContactAttribute city;
    ContactAttribute email;

    @BeforeEach
    void data() {
        vip = label(brandA, "VIP");
        lead = label(brandA, "Lead");
        city = attribute(brandA, "City");
        email = attribute(brandA, "Email");
    }

    @AfterEach
    void resetLimit() {
        exporter.setExportLimit(100_000);
    }

    private String importUrl() { return base(brandA) + "/contacts/import"; }

    private String exportUrl() { return base(brandA) + "/contacts/export"; }

    private org.springframework.test.web.servlet.ResultActions upload(String token, byte[] content) throws Exception {
        return mvc.perform(multipart(importUrl()).file(new MockMultipartFile("file", "c.csv", "text/csv", content))
                .header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions upload(String token, String csv) throws Exception {
        return upload(token, csv.getBytes(StandardCharsets.UTF_8));
    }

    private String download(Map<String, Object> request, String token) throws Exception {
        MvcResult started = send(post(exportUrl()), token, request).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void importCreatesAndUpdates() throws Exception {
        Contact existing = contact(brandA, "Old Name", "919812345678");
        existing.getLabels().add(lead.getId());
        existing.getAttributes().put(email.getId(), "keep@x.com");
        contacts.save(existing);

        String csv = "phone,name,labels,City\n"
                + "919812345678,,VIP,Pune\n"
                + "+91 97000-00001,Bela Shah,vip;lead,Delhi\n";
        upload(leadToken, csv).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.unchanged").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.problems.length()").value(0))
                .andExpect(jsonPath("$.unknownColumns.length()").value(0))
                .andExpect(jsonPath("$.unknownLabels.length()").value(0));

        Contact updated = contacts.findById(existing.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Old Name");
        assertThat(updated.getLabels()).containsExactlyInAnyOrder(lead.getId(), vip.getId());
        assertThat(updated.getAttributes()).containsEntry(city.getId(), "Pune").containsEntry(email.getId(), "keep@x.com");
        Contact created = contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919700000001").orElseThrow();
        assertThat(created.getName()).isEqualTo("Bela Shah");
        assertThat(created.isNameEdited()).isTrue();
        assertThat(created.getSource()).isEqualTo(ContactSource.IMPORT);
        assertThat(created.getCustomerId()).isEqualTo(acme.getId());
        assertThat(created.getLabels()).containsExactlyInAnyOrder(vip.getId(), lead.getId());

        // the same file again changes nothing
        upload(leadToken, csv).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.unchanged").value(2));
        // a non-empty name column replaces the name
        upload(leadToken, "phone,name\n919812345678,New Name\n").andExpect(jsonPath("$.updated").value(1));
        Contact renamed = contacts.findById(existing.getId()).orElseThrow();
        assertThat(renamed.getName()).isEqualTo("New Name");
        assertThat(renamed.isNameEdited()).isTrue();
        assertThat(contacts.count()).isEqualTo(2);
    }

    @Test
    void importReportsUnknownColumnsAndLabels() throws Exception {
        String csv = "Phone,Nickname,Labels,city,Shoe\n"
                + "919812345678,Ash,\"VIP, Ghost\",Pune,9\n"
                + "919812345679,Bel,Phantom;Ghost,,\n";
        upload(leadToken, csv).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.unknownColumns[0]").value("Nickname"))
                .andExpect(jsonPath("$.unknownColumns[1]").value("Shoe"))
                .andExpect(jsonPath("$.unknownLabels.length()").value(2))
                .andExpect(jsonPath("$.unknownLabels[0]").value("Ghost"))
                .andExpect(jsonPath("$.unknownLabels[1]").value("Phantom"));
        Contact c = contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345678").orElseThrow();
        assertThat(c.getLabels()).containsExactly(vip.getId());
        assertThat(c.getAttributes()).containsOnlyKeys(city.getId());
        assertThat(labels.findByWorkspaceIdOrderByNameKey(brandA.getId())).hasSize(2);
        assertThat(attributes.findByWorkspaceIdOrderByNameKey(brandA.getId())).hasSize(2);
    }

    @Test
    void importIgnoresLabelsAndAttributesOfOtherBrands() throws Exception {
        label(brandB, "Secret");
        attribute(brandB, "Hidden");
        upload(leadToken, "phone,labels,Hidden\n919812345678,Secret,x\n").andExpect(status().isOk())
                .andExpect(jsonPath("$.unknownColumns[0]").value("Hidden"))
                .andExpect(jsonPath("$.unknownLabels[0]").value("Secret"));
        Contact c = contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345678").orElseThrow();
        assertThat(c.getLabels()).isEmpty();
        assertThat(c.getAttributes()).isEmpty();
    }

    @Test
    void importSkipsBadPhonesWithRowNumbers() throws Exception {
        String csv = "phone,name\n"
                + "919812345678,Good\n"
                + "12345,Short\n"
                + "abc,Letters\n"
                + ",Blank\n"
                + "919812345679," + "x".repeat(101) + "\n"
                + "919812345680,Fine\n";
        JsonNode r = json(upload(leadToken, csv).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.skipped").value(4)));
        assertThat(r.get("problems")).hasSize(4);
        assertThat(r.get("problems").get(0).get("row").asInt()).isEqualTo(3);
        assertThat(r.get("problems").get(0).get("reason").asText()).contains("phone");
        assertThat(r.get("problems").get(1).get("row").asInt()).isEqualTo(4);
        assertThat(r.get("problems").get(2).get("row").asInt()).isEqualTo(5);
        assertThat(r.get("problems").get(3).get("row").asInt()).isEqualTo(6);
        assertThat(r.get("problems").get(3).get("reason").asText()).contains("name");
    }

    @Test
    void problemListIsCappedAtTwoHundred() throws Exception {
        StringBuilder csv = new StringBuilder("phone\n");
        for (int i = 0; i < 250; i++) csv.append("bad").append(i).append('\n');
        JsonNode r = json(upload(leadToken, csv.toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped").value(250)));
        assertThat(r.get("problems")).hasSize(200);
    }

    @Test
    void importHandlesMessyCsv() throws Exception {
        String csv = "﻿Phone , NAME ,Labels,City\r\n"
                + "\r\n"
                + "\"+91 98123-45678\",\"Rao, Asha\",\"VIP;Lead\",\"Pune\"\r\n"
                + "\r\n"
                + "  (91) 97000 00001 ,\"Say \"\"Hi\"\"\",,\"Line one\nline two\"\r\n"
                + "\r\n";
        upload(leadToken, csv).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.skipped").value(0));
        Contact a = contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919812345678").orElseThrow();
        assertThat(a.getName()).isEqualTo("Rao, Asha");
        assertThat(a.getLabels()).containsExactlyInAnyOrder(vip.getId(), lead.getId());
        Contact b = contacts.findByWorkspaceIdAndPhone(brandA.getId(), "919700000001").orElseThrow();
        assertThat(b.getName()).isEqualTo("Say \"Hi\"");
        assertThat(b.getAttributes().get(city.getId())).isEqualTo("Line one\nline two");
    }

    @Test
    void importRejectsBadFiles() throws Exception {
        upload(leadToken, "name,city\nAsha,Pune\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.file").exists());
        upload(leadToken, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.file").exists());
        mvc.perform(multipart(importUrl()).header("Authorization", "Bearer " + leadToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importRejectsTooLarge() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        upload(leadToken, big).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.file").exists());

        StringBuilder rows = new StringBuilder("phone\n");
        for (int i = 0; i < 20_001; i++) rows.append(String.format("9198%08d%n", i));
        upload(leadToken, rows.toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.file").exists());
        assertThat(contacts.count()).isZero();
    }

    @Test
    void exportColumnsAndQuoting() throws Exception {
        Contact a = contact(brandA, "Rao, Asha \"AR\"", "919812345678");
        a.getLabels().add(vip.getId());
        a.getLabels().add(lead.getId());
        a.getAttributes().put(city.getId(), "Line1\nLine2");
        a.setLastMessageAt(java.time.Instant.parse("2026-03-04T05:06:07Z"));
        contacts.save(a);
        Contact hidden = new Contact();
        hidden.setWorkspaceId(brandA.getId());
        hidden.setWaId("111@lid");
        hidden.setSource(ContactSource.CHAT);
        contacts.save(hidden);
        Contact other = contact(brandB, "Other brand", "919800000009");

        MvcResult started = send(post(exportUrl()), leadToken, Map.of()).andExpect(request().asyncStarted()).andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andReturn();
        String today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(done.getResponse().getHeader("Content-Disposition"))
                .contains("attachment").contains("contacts-Brand-A-" + today + ".csv");
        String body = done.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿");
        String csv = body.substring(1);
        List<List<String>> rows = ContactCsv.parse(csv).stream().map(ContactCsv.Row::fields).toList();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsExactly("phone", "name", "number_hidden", "source", "labels", "City", "Email",
                "created", "last_message");
        // most recent message first, the contact that never had one last
        List<String> first = rows.get(1);
        assertThat(first.get(0)).isEqualTo("919812345678");
        assertThat(first.get(1)).isEqualTo("Rao, Asha \"AR\"");
        assertThat(first.get(2)).isEqualTo("false");
        assertThat(first.get(3)).isEqualTo("MANUAL");
        assertThat(first.get(4)).isEqualTo("Lead;VIP");
        assertThat(first.get(5)).isEqualTo("Line1\nLine2");
        assertThat(first.get(6)).isEmpty();
        assertThat(first.get(7)).isNotEmpty();
        assertThat(first.get(8)).isEqualTo("2026-03-04T05:06:07Z");
        List<String> second = rows.get(2);
        assertThat(second.get(0)).isEmpty();
        assertThat(second.get(2)).isEqualTo("true");
        assertThat(second.get(3)).isEqualTo("CHAT");
        assertThat(second.get(8)).isEmpty();
        assertThat(csv).doesNotContain("Other brand");
        assertThat(csv).contains("\r\n");
    }

    @Test
    void exportRespectsFilter() throws Exception {
        Contact vipContact = contact(brandA, "Vip One", "919812345678");
        vipContact.getLabels().add(vip.getId());
        contacts.save(vipContact);
        contact(brandA, "Plain Two", "919812345679");

        String all = download(Map.of(), leadToken);
        assertThat(all).contains("Vip One").contains("Plain Two");
        String some = download(Map.of("filter", Map.of("labelsAny", List.of(vip.getId()))), leadToken);
        assertThat(some).contains("Vip One").doesNotContain("Plain Two");

        ContactFilter saved = new ContactFilter();
        saved.setWorkspaceId(brandA.getId());
        saved.setName("Plain");
        saved.setFilter(new ContactFilterSpec("plain", null, null, null, null, null, null, null, null, null, null));
        saved = filters.save(saved);
        String viaSaved = download(Map.of("filterId", saved.getId()), wmToken);
        assertThat(viaSaved).contains("Plain Two").doesNotContain("Vip One");
    }

    @Test
    void exportTooManyRowsIs400() throws Exception {
        contact(brandA, "One", "919812345678");
        contact(brandA, "Two", "919812345679");
        contact(brandA, "Three", "919812345670");
        exporter.setExportLimit(2);
        send(post(exportUrl()), leadToken, Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"));
        // narrowing the filter under the limit works
        assertThat(download(Map.of("filter", Map.of("q", "one")), leadToken)).contains("One");
    }

    @Test
    void exportAndImportOfOtherBrandOrRoleAreRefused() throws Exception {
        send(post(exportUrl()), agentToken, Map.of()).andExpect(status().isForbidden());
        upload(agentToken, "phone\n919812345678\n").andExpect(status().isForbidden());
        send(post(exportUrl()), wmBToken, Map.of()).andExpect(status().isNotFound());
        assertThat(contacts.count()).isZero();
        ContactFilter theirs = new ContactFilter();
        theirs.setWorkspaceId(brandB.getId());
        theirs.setName("Theirs");
        theirs = filters.save(theirs);
        send(post(exportUrl()), leadToken, Map.of("filterId", theirs.getId())).andExpect(status().isNotFound());
    }
}
