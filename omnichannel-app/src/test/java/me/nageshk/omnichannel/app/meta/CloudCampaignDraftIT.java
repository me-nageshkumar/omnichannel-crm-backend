package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.contact.Contact;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Storing and checking a Cloud campaign (spec 2026-11-21 §5.1, §5.7). */
class CloudCampaignDraftIT extends CloudCampaignTestBase {

    private ResponseEntity<JsonNode> create(Map<String, Object> body) {
        return ws(HttpMethod.POST, "/campaigns", manager, body);
    }

    private Map<String, Object> request(String templateId, String headerMediaId, List<Map<String, Object>> mappings,
            List<String> senders) {
        return cloudRequest("Spring intake " + COUNTER.incrementAndGet(), allContactsFilter("All " + COUNTER.get()),
                templateId, headerMediaId, mappings, senders);
    }

    /** The field a 400 names: a coded error's {@code field}, or the first of a validation error's {@code fields}. */
    private static String badField(ResponseEntity<JsonNode> res) {
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode b = res.getBody();
        if (b.hasNonNull("field")) return b.get("field").asString();
        return b.get("fields").propertyNames().iterator().next();
    }

    @Test
    void createsCloudDraft() {
        String t = syncTemplate(FOLLOW_UP);
        ResponseEntity<JsonNode> res = create(request(t, null, nameIntoBody1(), List.of(numberId)));
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
        JsonNode v = res.getBody();
        assertThat(v.get("channel").asString()).isEqualTo("WHATSAPP_CLOUD_API");
        assertThat(v.at("/cloudTemplate/cloudTemplateId").asString()).isEqualTo(t);
        assertThat(v.at("/cloudTemplate/name").asString()).isEqualTo("course_follow_up");
        assertThat(v.at("/cloudTemplate/language").asString()).isEqualTo("en");
        assertThat(v.at("/cloudTemplate/status").asString()).isEqualTo("APPROVED");
        assertThat(v.get("templateIds")).isEmpty();
        assertThat(v.get("placeholders").findValuesAsString("name")).containsExactly("body:1", "body:2");
    }

    @Test
    void oldCampaignReadsAsWeb() {
        Campaign c = new Campaign();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setName("Before Cloud");
        c.setNameKey("before cloud");
        c.setStatus(CampaignStatus.DRAFT);
        c.setCounts(Campaign.Counts.ZERO);
        c.setTemplateIds(List.of());
        c.setTemplateNames(List.of());
        c.setPlaceholders(List.of());
        c.setSenderIds(List.of(n1.getId()));
        c = mongo.save(c);
        JsonNode v = ws(HttpMethod.GET, "/campaigns/" + c.getId(), manager, null).getBody();
        assertThat(v.get("channel").asString()).isEqualTo("WHATSAPP_WEB");
        assertThat(v.get("cloudTemplate").isNull()).isTrue();
    }

    @Test
    void refusesMixedKinds() {
        String t = syncTemplate(FOLLOW_UP);
        assertThat(badField(create(request(t, null, nameIntoBody1(), List.of(numberId, n1.getId())))))
                .isEqualTo("senderIds");
    }

    @Test
    void refusesTwoWabas() {
        String t = syncTemplate(FOLLOW_UP);
        String second = addCloudNumber(connectionId, "Second", "334", "+91 98765 43211").get("id").asString();
        mongo.updateFirst(new Query(Criteria.where("phoneNumberId").is(second)), new Update().set("wabaId", "999"),
                "cloudAccounts");
        ResponseEntity<JsonNode> res = create(request(t, null, nameIntoBody1(), List.of(numberId, second)));
        assertThat(badField(res)).isEqualTo("senderIds");
        assertThat(res.getBody().toString()).contains("one WhatsApp account");
    }

    @Test
    void refusesUnapprovedTemplate() {
        String t = syncTemplate(PENDING);
        assertThat(badField(create(request(t, null, List.of(), List.of(numberId)))))
                .isEqualTo("cloudTemplate.cloudTemplateId");
    }

    @Test
    void refusesUnmappedVariable() {
        String t = syncTemplate(FOLLOW_UP);
        mongo.updateFirst(new Query(Criteria.where("_id").is(t)), new Update().push("fills",
                Map.of("key", "body:1", "kind", "AGENT")), "cloudTemplates");
        List<Map<String, Object>> onlySecond = List.of(nameIntoBody1().get(1));
        assertThat(badField(create(request(t, null, onlySecond, List.of(numberId))))).isEqualTo("placeholders.body:1");
    }

    @Test
    void refusesMissingHeaderFile() {
        String t = syncTemplate(BROCHURE);
        assertThat(badField(create(request(t, null, List.of(), List.of(numberId)))))
                .isEqualTo("cloudTemplate.headerMediaId");
    }

    @Test
    void refusesTemplateIdsOnCloud() {
        String t = syncTemplate(FOLLOW_UP);
        Map<String, Object> body = new LinkedHashMap<>(request(t, null, nameIntoBody1(), List.of(numberId)));
        body.put("templateIds", List.of(template("Hi", "Hi {{contact.name}}")));
        assertThat(badField(create(body))).isEqualTo("templateIds");
    }

    @Test
    void refusesCloudTemplateOnWeb() {
        String t = syncTemplate(FOLLOW_UP);
        Map<String, Object> body = new LinkedHashMap<>(request(t, null, List.of(), List.of(n1.getId())));
        body.put("channel", "WHATSAPP_WEB");
        body.put("templateIds", List.of(template("Hi", "Hi {{contact.name}}")));
        assertThat(badField(create(body))).isEqualTo("cloudTemplate");
    }

    @Test
    void estimateUsesDailyLimit() {
        String t = syncTemplate(FOLLOW_UP);
        List<Contact> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            Contact c = new Contact();
            c.setWorkspaceId(brand.getId());
            c.setCustomerId(acme.getId());
            c.setName("Lead " + i);
            c.setPhone("9190000" + String.format("%05d", i));
            many.add(c);
        }
        mongo.insertAll(many);
        String id = cloudCampaign("Big intake", t, List.of(), nameIntoBody1(), numberId);
        start(id);
        JsonNode v = ws(HttpMethod.GET, "/campaigns/" + id, manager, null).getBody();
        assertThat(v.at("/estimate/perDay").asInt()).isEqualTo(250);
        assertThat(v.at("/estimate/days").asInt()).isEqualTo(2);
        assertThat(v.get("cloudSenders").findValuesAsString("phoneNumberId")).containsExactly(numberId);
        assertThat(v.get("senders")).isEmpty();
    }

    @Test
    void materialisedRecipientsHaveNoTemplateId() {
        String t = syncTemplate(FOLLOW_UP);
        String id = cloudCampaign("Small intake", t, List.of("919811100001", "919811100002"), nameIntoBody1(),
                numberId);
        start(id);
        List<CampaignRecipient> rs = recipients(id);
        assertThat(rs).hasSize(2).allSatisfy(r -> {
            assertThat(r.getTemplateId()).isNull();
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
        });
    }
}
