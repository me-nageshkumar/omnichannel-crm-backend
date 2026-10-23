package me.nageshk.omnichannel.app.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.SenderState;
import me.nageshk.omnichannel.db.enablement.Disabled;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class CampaignDraftIT extends CampaignTestBase {

    private static final Map<String, Object> OFFER = Map.of("name", "offer",
            "source", Map.of("kind", "FIXED", "text", "10% off"), "fallback", "");

    private Map<String, Object> request(String name, String templateId, List<Map<String, Object>> placeholders,
            List<String> senderIds) {
        return filterCampaign(name, filter("F " + name + System.nanoTime(), Map.of()), List.of(templateId),
                placeholders, senderIds);
    }

    private ResponseEntity<JsonNode> create(String templateBody, List<Map<String, Object>> placeholders,
            List<String> senderIds) {
        String t = template("T" + System.nanoTime(), templateBody);
        return api(POST, "/campaigns", manager, request("C" + System.nanoTime(), t, placeholders, senderIds));
    }

    private void assertCoded(ResponseEntity<JsonNode> res, HttpStatus status, String code, String field,
            String message) {
        assertThat(res.getStatusCode()).isEqualTo(status);
        assertThat(res.getBody().get("code").asString()).isEqualTo(code);
        if (field != null) assertThat(res.getBody().get("field").asString()).isEqualTo(field);
        assertThat(res.getBody().get("message").asString()).isEqualTo(message);
    }

    @Test
    void createsADraftWithDefaultContactMappings() {
        String t = template("Hello", "Hi {{contact.name}}, {{offer}}");
        String f = filter("Leads", Map.of());
        ResponseEntity<JsonNode> res = api(POST, "/campaigns", manager, filterCampaign("Diwali", f, List.of(t),
                List.of(Map.of("name", "offer", "source", Map.of("kind", "FIXED", "text", "10% off"), "fallback", "")),
                List.of(n1.getId())));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode c = res.getBody();
        assertThat(c.get("status").asString()).isEqualTo("DRAFT");
        assertThat(c.at("/audience/filterName").asString()).isEqualTo("Leads");
        assertThat(c.at("/templateNames/0").asString()).isEqualTo("Hello");
        assertThat(c.at("/placeholders/0/name").asString()).isEqualTo("contact.name");
        assertThat(c.at("/placeholders/0/source/kind").asString()).isEqualTo("CONTACT_FIELD");
        assertThat(c.at("/placeholders/0/source/field").asString()).isEqualTo("name");
        assertThat(c.at("/counts/total").asInt()).isZero();
        assertThat(c.get("createdBy").asString()).isEqualTo(manager.getUsername());
    }

    @Test
    void templateWithALinkIsRefused() {
        for (String body : List.of("See https://x.in", "See www.x.in", "See HTTP://X.IN")) {
            assertCoded(create(body, List.of(), List.of(n1.getId())), HttpStatus.BAD_REQUEST, "TEMPLATE_HAS_LINK",
                    "templateIds", "Campaign messages cannot contain links.");
        }
    }

    @Test
    void nonTextTemplateIsRefused() {
        ResponseEntity<JsonNode> poll = api(POST, "/templates", manager, Map.of("name", "Poll", "type", "POLL",
                "content", Map.of("question", "Yes?", "options", List.of("a", "b"))));
        assertThat(poll.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String f = filter("Leads", Map.of());
        assertCoded(api(POST, "/campaigns", manager, filterCampaign("P", f, List.of(poll.getBody().get("id").asString()),
                List.of(), List.of(n1.getId()))), HttpStatus.BAD_REQUEST, "TEMPLATE_NOT_TEXT", "templateIds",
                "Only text templates can be used in campaigns.");
    }

    @Test
    void archivedUnknownAndTooManyTemplatesAreValidation() {
        String f = filter("Leads", Map.of());
        String t = template("Old", "Hello");
        MessageTemplate m = mongo.findById(t, MessageTemplate.class);
        m.setStatus(TemplateStatus.ARCHIVED);
        mongo.save(m);
        ResponseEntity<JsonNode> res = api(POST, "/campaigns", manager,
                filterCampaign("A", f, List.of(t), List.of(), List.of(n1.getId())));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().at("/fields/templateIds").asString()).isEqualTo("archived");
        res = api(POST, "/campaigns", manager,
                filterCampaign("B", f, List.of("nope"), List.of(), List.of(n1.getId())));
        assertThat(res.getBody().at("/fields/templateIds").asString()).isEqualTo("unknown template");
        res = api(POST, "/campaigns", manager, filterCampaign("C", f,
                List.of("1", "2", "3", "4", "5", "6"), List.of(), List.of(n1.getId())));
        assertThat(res.getBody().at("/fields/templateIds").asString()).isEqualTo("needs 1 to 5 templates");
    }

    @Test
    void agentPlaceholderNeedsASource() {
        assertCoded(create("Hello {{offer}}", List.of(), List.of(n1.getId())), HttpStatus.BAD_REQUEST,
                "PLACEHOLDER_UNMAPPED", "placeholders", "Every placeholder needs a value.");
        assertCoded(create("Hello {{offer}}", List.of(Map.of("name", "offer",
                "source", Map.of("kind", "CSV_COLUMN", "column", "A"), "fallback", "")), List.of(n1.getId())),
                HttpStatus.BAD_REQUEST, "PLACEHOLDER_UNMAPPED", "placeholders", "Every placeholder needs a value.");
    }

    @Test
    void fixedTextWithALinkIsRefused() {
        assertCoded(create("Hello {{offer}}", List.of(Map.of("name", "offer",
                "source", Map.of("kind", "FIXED", "text", "www.shop.in"), "fallback", "")), List.of(n1.getId())),
                HttpStatus.BAD_REQUEST, "TEMPLATE_HAS_LINK", "placeholders", "Campaign messages cannot contain links.");
    }

    @Test
    void unavailableSenderIsNamed() {
        sender(n2, s -> s.setState(SenderState.RESTING));
        ResponseEntity<JsonNode> res = create("Hello", List.of(), List.of(n1.getId(), n2.getId()));
        assertCoded(res, HttpStatus.BAD_REQUEST, "SENDER_NOT_AVAILABLE", "senderIds",
                "+" + n2.getNumber() + " cannot send campaigns right now.");
        assertThat(res.getBody().get("number").asString()).isEqualTo(n2.getNumber());
    }

    @Test
    void disabledAndOtherBrandNumbers() {
        n2.setDisabled(new Disabled("manager", EnableLevel.BRAND, Instant.parse("2026-10-01T00:00:00Z")));
        mongo.save(n2);
        assertCoded(create("Hello", List.of(), List.of(n2.getId())), HttpStatus.BAD_REQUEST, "SENDER_NOT_AVAILABLE",
                "senderIds", "+" + n2.getNumber() + " cannot send campaigns right now.");
        ResponseEntity<JsonNode> res = create("Hello", List.of(), List.of(otherNumber.getId()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asString()).isEqualTo("VALIDATION");
        assertThat(res.getBody().at("/fields/senderIds").asString()).isEqualTo("unknown number");
        res = create("Hello", List.of(), List.of());
        assertThat(res.getBody().at("/fields/senderIds").asString()).isEqualTo("required");
    }

    @Test
    void duplicateNameIs409() {
        String t = template("Hello", "Hi");
        String f = filter("Leads", Map.of());
        assertThat(api(POST, "/campaigns", manager, filterCampaign("Diwali", f, List.of(t), List.of(),
                List.of(n1.getId()))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> res = api(POST, "/campaigns", manager,
                filterCampaign("diwali ", f, List.of(t), List.of(), List.of(n1.getId())));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("DUPLICATE");
        assertThat(res.getBody().get("field").asString()).isEqualTo("name");
    }

    @Test
    void draftCanBeEditedAndDeleted() {
        String t = template("Hello", "Hi");
        String f = filter("Leads", Map.of());
        String id = api(POST, "/campaigns", manager, filterCampaign("Diwali", f, List.of(t), List.of(),
                List.of(n1.getId()))).getBody().get("id").asString();
        ResponseEntity<JsonNode> same = api(PUT, "/campaigns/" + id, manager,
                filterCampaign("Diwali", f, List.of(t), List.of(), List.of(n1.getId())));
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> res = api(PUT, "/campaigns/" + id, manager,
                filterCampaign("Holi", f, List.of(t), List.of(), List.of(n1.getId(), n2.getId())));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("name").asString()).isEqualTo("Holi");
        assertThat(res.getBody().get("senderIds").size()).isEqualTo(2);
        assertThat(campaign(id).getNameKey()).isEqualTo("holi");
        assertThat(api(DELETE, "/campaigns/" + id, manager, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api(GET, "/campaigns/" + id, manager, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void onlyDraftsAreEditable() {
        String t = template("Hello", "Hi");
        String f = filter("Leads", Map.of());
        Map<String, Object> body = filterCampaign("Diwali", f, List.of(t), List.of(), List.of(n1.getId()));
        String id = api(POST, "/campaigns", manager, body).getBody().get("id").asString();
        Campaign c = campaign(id);
        c.setStatus(CampaignStatus.RUNNING);
        mongo.save(c);
        Map<String, Object> changed = filterCampaign("Changed", f, List.of(t), List.of(), List.of(n1.getId(), n2.getId()));
        assertCoded(api(PUT, "/campaigns/" + id, manager, changed), HttpStatus.CONFLICT, "CAMPAIGN_NOT_EDITABLE", null,
                "Only a draft campaign can be edited.");
        assertCoded(api(DELETE, "/campaigns/" + id, manager, null), HttpStatus.CONFLICT, "CAMPAIGN_NOT_EDITABLE",
                null, "Only a draft campaign can be edited.");
        Campaign after = campaign(id);
        assertThat(after).isNotNull();
        assertThat(after.getName()).isEqualTo("Diwali");
        assertThat(after.getNameKey()).isEqualTo("diwali");
        assertThat(after.getSenderIds()).containsExactly(n1.getId());
        assertThat(after.getStatus()).isEqualTo(CampaignStatus.RUNNING);
        assertThat(after.getVersion()).isEqualTo(c.getVersion());
    }

    @Test
    void draftsNeedCampaignManage() {
        String t = template("Hello", "Hi");
        String f = filter("Leads", Map.of());
        Map<String, Object> body = filterCampaign("Diwali", f, List.of(t), List.of(), List.of(n1.getId()));
        assertThat(api(POST, "/campaigns", agent, body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String id = api(POST, "/campaigns", manager, body).getBody().get("id").asString();
        assertThat(api(GET, "/campaigns/" + id, agent, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> foreign = rest.exchange(url("/api/workspaces/" + other.getId() + "/campaigns/" + id),
                GET, auth(brandUser(other, "other-manager", BuiltInRoles.WORKSPACE_MANAGER), null), JsonNode.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
