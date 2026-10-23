package me.nageshk.omnichannel.app.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

class CampaignCsvIT extends CampaignTestBase {

    private static final String CSV = "Phone,Name,City\n9812345678,Asha,Pune\n919811111111,,Delhi\n";

    private static Map<String, Object> col(String name, String column) {
        return Map.of("name", name, "source", Map.of("kind", "CSV_COLUMN", "column", column), "fallback", "");
    }

    private static Map<String, Object> csvCampaign(String name, String phone, String nameColumn, List<String> templates,
            List<Map<String, Object>> placeholders, List<String> senders) {
        Map<String, Object> audience = new LinkedHashMap<>();
        audience.put("kind", "CSV");
        if (phone != null) audience.put("phoneColumn", phone);
        if (nameColumn != null) audience.put("nameColumn", nameColumn);
        return Map.of("name", name, "audience", audience, "templateIds", templates, "placeholders", placeholders,
                "senderIds", senders);
    }

    private ResponseEntity<JsonNode> post(String phone, String t, List<Map<String, Object>> placeholders) {
        return multipart(POST, "/campaigns", manager,
                csvCampaign("C" + System.nanoTime(), phone, "Name", List.of(t), placeholders, List.of(n1.getId())), CSV);
    }

    private void assertCoded(ResponseEntity<JsonNode> res, String code, String field, String message) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asString()).isEqualTo(code);
        if (field != null) {
            assertThat(res.getBody().at("/fields/" + field).asString()).isEqualTo(message);
        }
    }

    @Test
    void csvDraftStoresTheFileAndHeaders() {
        String t = template("Hello", "Hi {{contact.name}} from {{city}}");
        JsonNode c = multipart(POST, "/campaigns", manager, csvCampaign("Leads Oct", "Phone", "Name", List.of(t),
                List.of(col("contact.name", "Name"), col("city", "City")), List.of(n1.getId())),
                "Phone,Name,City\n9812345678,Asha,Pune\n919811111111,,Delhi\n").getBody();
        assertThat(c.at("/audience/kind").asString()).isEqualTo("CSV");
        assertThat(c.at("/audience/csvHeaders").toString()).isEqualTo("[\"Phone\",\"Name\",\"City\"]");
        assertThat(c.at("/audience/csvRows").asInt()).isEqualTo(2);
        assertThat(c.at("/audience/csvFileName").asString()).isEqualTo("leads.csv");
        assertThat(c.at("/audience/csvFileId").isMissingNode()).isTrue();
        assertThat(gridFsFiles()).isEqualTo(1);
    }

    @Test
    void csvPlaceholdersNeedAColumnOrFixedText() {
        String t = template("Hello", "Hi {{contact.name}} from {{city}}");
        ResponseEntity<JsonNode> unmapped = post("Phone", t, List.of(col("city", "City")));
        assertThat(unmapped.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unmapped.getBody().get("code").asString()).isEqualTo("PLACEHOLDER_UNMAPPED");
        ResponseEntity<JsonNode> missing = post("Phone", t, List.of(col("contact.name", "Name"), col("city", "Town")));
        assertThat(missing.getBody().get("code").asString()).isEqualTo("PLACEHOLDER_UNMAPPED");
        ResponseEntity<JsonNode> fixed = post("Phone", t, List.of(col("contact.name", "Name"),
                Map.of("name", "city", "source", Map.of("kind", "FIXED", "text", "Pune"), "fallback", "")));
        assertThat(fixed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(gridFsFiles()).isEqualTo(1);
    }

    @Test
    void phoneColumnRulesForCsv() {
        String t = template("Hello", "Hi {{contact.name}} from {{city}}");
        List<Map<String, Object>> ok = List.of(col("contact.name", "Name"), col("city", "City"));
        ResponseEntity<JsonNode> none = post(null, t, ok);
        assertThat(none.getBody().get("code").asString()).isEqualTo("PHONE_COLUMN_REQUIRED");
        assertThat(none.getBody().get("message").asString()).isEqualTo("Choose the column that holds the phone number.");
        ResponseEntity<JsonNode> reused = post("Phone", t, List.of(col("contact.name", "Name"), col("city", "Name")));
        assertThat(reused.getBody().get("code").asString()).isEqualTo("MAPPING_COLUMN_REUSED");
        assertThat(reused.getBody().get("message").asString()).isEqualTo("A CSV column can be used only once.");
        assertThat(reused.getBody().get("field").asString()).isEqualTo("placeholders");
        assertThat(post("Phone", t, ok).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertCoded(post("Mobile", t, ok), "VALIDATION", "audience.phoneColumn", "is not a column of the file");
        assertThat(gridFsFiles()).isEqualTo(1);
    }

    @Test
    void contactPhoneMayUseThePhoneColumn() {
        String t = template("Hello", "Number {{contact.phone}}");
        assertThat(post("Phone", t, List.of(col("contact.phone", "Phone"))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void fileLimits() {
        String t = template("Hello", "Hi");
        Map<String, Object> req = csvCampaign("Big", "Phone", null, List.of(t), List.of(), List.of(n1.getId()));
        StringBuilder big = new StringBuilder("Phone\n");
        for (int i = 0; i < 20001; i++) big.append("98123").append(String.format("%05d", i)).append('\n');
        assertCoded(multipart(POST, "/campaigns", manager, req, big.toString()), "VALIDATION", "file",
                "has more than 20000 rows");
        assertCoded(multipart(POST, "/campaigns", manager, req, ""), "VALIDATION", "file",
                "is empty; a header row is required");
        assertCoded(api(POST, "/campaigns", manager, req), "VALIDATION", "file", "required");
        assertCoded(multipart(POST, "/campaigns", manager, req, null), "VALIDATION", "file", "required");
        assertThat(gridFsFiles()).isZero();
    }

    @Test
    void putWithoutAFileKeepsTheStoredCsv() {
        String t = template("Hello", "Hi {{city}}");
        List<Map<String, Object>> ph = List.of(col("city", "City"));
        String id = multipart(POST, "/campaigns", manager, csvCampaign("Leads", "Phone", null, List.of(t), ph,
                List.of(n1.getId())), CSV).getBody().get("id").asString();

        JsonNode kept = multipart(PUT, "/campaigns/" + id, manager, csvCampaign("Leads 2", "Phone", null, List.of(t),
                ph, List.of(n1.getId())), null).getBody();
        assertThat(kept.get("name").asString()).isEqualTo("Leads 2");
        assertThat(kept.at("/audience/csvHeaders").toString()).isEqualTo("[\"Phone\",\"Name\",\"City\"]");
        assertThat(kept.at("/audience/csvFileName").asString()).isEqualTo("leads.csv");
        assertThat(gridFsFiles()).isEqualTo(1);

        JsonNode replaced = multipart(PUT, "/campaigns/" + id, manager, csvCampaign("Leads 2", "Mobile", null,
                List.of(t), List.of(col("city", "Town")), List.of(n1.getId())), "Mobile,Town\n9812345678,Pune\n")
                .getBody();
        assertThat(replaced.at("/audience/csvHeaders").toString()).isEqualTo("[\"Mobile\",\"Town\"]");
        assertThat(gridFsFiles()).isEqualTo(1);

        assertThat(api(DELETE, "/campaigns/" + id, manager, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(gridFsFiles()).isZero();
    }

    /** Only agent placeholders carry an example; a contact placeholder's cell is blank. */
    @Test
    void sampleCsvListsPlaceholders() {
        ResponseEntity<JsonNode> made = api(POST, "/templates", manager, Map.of("name", "S", "type", "TEXT",
                "content", Map.of("body", "Hi {{contact.name}}, order {{order_id}}"),
                "placeholders", List.of(Map.of("name", "contact.name", "fallback", "there"),
                        Map.of("name", "order_id", "label", "Order", "required", false, "example", "A-17"))));
        String t = made.getBody().get("id").asString();
        ResponseEntity<String> res = rest.exchange(
                url("/api/workspaces/" + brand.getId() + "/campaigns/templates/" + t + "/sample-csv"), GET,
                auth(agent, null), String.class);
        assertThat(res.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"sample.csv\"");
        assertThat(res.getBody()).isEqualTo("phone,contact.name,order_id\r\n919812345678,,A-17\r\n");
    }

    private ResponseEntity<JsonNode> preview(User who, String csv, String... templateIds) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", fileResource(csv));
        for (String id : templateIds) parts.add("templateIds", id);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token(who));
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/campaigns/csv/preview"), POST,
                new HttpEntity<>(parts, h), JsonNode.class);
    }

    @Test
    void previewSuggestsEqualNamedColumns() {
        String t = template("Hello", "Hi {{contact.name}} from {{city}}");
        List<String> lines = new ArrayList<>(List.of(" PHONE ,contact.name,City"));
        for (int i = 0; i < 7; i++) lines.add("98123456" + i + "0,N" + i + ",C" + i);
        String csv = String.join("\n", lines) + "\n";
        JsonNode p = preview(manager, csv, t).getBody();
        assertThat(p.get("headers").toString()).isEqualTo("[\"PHONE\",\"contact.name\",\"City\"]");
        assertThat(p.get("sample").size()).isEqualTo(5);
        assertThat(p.get("rows").asInt()).isEqualTo(7);
        assertThat(p.at("/suggested/phone").asString()).isEqualTo("PHONE");
        assertThat(p.at("/suggested/placeholders/contact.name").asString()).isEqualTo("contact.name");
        assertThat(p.at("/suggested/placeholders/city").asString()).isEqualTo("City");
        JsonNode bare = preview(manager, csv).getBody();
        assertThat(bare.at("/suggested/placeholders").size()).isZero();
    }

    @Test
    void previewNeedsCampaignManage() {
        assertThat(preview(agent, CSV).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
