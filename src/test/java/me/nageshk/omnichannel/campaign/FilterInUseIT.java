package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.contact.ContactLabel;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** A label or attribute used by an advanced filter or an unfinished campaign cannot be deleted. */
class FilterInUseIT extends CampaignTestBase {

    private static final String MESSAGE = "This is used by a saved filter or a campaign. Remove it there first.";

    private Map<String, Object> hasLabel(ContactLabel l) {
        return Map.of("combinator", "and", "not", false, "rules",
                List.of(Map.of("field", "labels", "operator", "has", "value", l.getId())));
    }

    private ResponseEntity<JsonNode> deleteLabel(ContactLabel l) {
        return api(HttpMethod.DELETE, "/contact-labels/" + l.getId(), manager, null);
    }

    private void assertInUse(ResponseEntity<JsonNode> res, List<List<String>> filters, List<List<String>> campaigns) {
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        JsonNode b = res.getBody();
        assertThat(b.get("code").asString()).isEqualTo("FILTER_IN_USE");
        assertThat(b.get("message").asString()).isEqualTo(MESSAGE);
        assertThat(refs(b.get("filters"))).isEqualTo(filters);
        assertThat(refs(b.get("campaigns"))).isEqualTo(campaigns);
    }

    private List<List<String>> refs(JsonNode arr) {
        List<List<String>> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(List.of(n.get("id").asString(), n.get("name").asString())));
        return out;
    }

    private String campaignOver(String name, String filterId) {
        String t = templateNamed("Use Hi", "Hi");
        return api(HttpMethod.POST, "/campaigns", manager, filterCampaign(name, filterId, List.of(t), List.of(),
                List.of(n1.getId()))).getBody().get("id").asString();
    }

    private void setStatus(String campaignId, CampaignStatus status) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(campaignId)), new Update().set("status", status),
                Campaign.class);
    }

    @Test
    void labelUsedByAnAdvancedFilterCannotBeDeleted() {
        ContactLabel l = label("VIP");
        String f = advancedFilter("VIPs", hasLabel(l));
        assertInUse(deleteLabel(l), List.of(List.of(f, "VIPs")), List.of());
        assertThat(api(HttpMethod.GET, "/contact-labels", manager, null).getBody().size()).isEqualTo(1);
    }

    @Test
    void labelUsedByAnUnfinishedCampaignCannotBeDeleted() {
        ContactLabel l = label("VIP");
        String f = advancedFilter("VIPs", hasLabel(l));
        String c = campaignOver("Diwali", f);
        api(HttpMethod.PUT, "/contact-filters/" + f, manager,
                Map.of("name", "x", "rules", Map.of("combinator", "and", "not", false, "rules",
                        List.of(Map.of("field", "name", "operator", "is", "value", "x")))));
        assertInUse(deleteLabel(l), List.of(), List.of(List.of(c, "Diwali")));
        setStatus(c, CampaignStatus.RUNNING);
        assertInUse(deleteLabel(l), List.of(), List.of(List.of(c, "Diwali")));
        setStatus(c, CampaignStatus.PAUSED);
        assertInUse(deleteLabel(l), List.of(), List.of(List.of(c, "Diwali")));
        setStatus(c, CampaignStatus.COMPLETED);
        assertThat(deleteLabel(l).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void terminatedCampaignDoesNotGuard() {
        ContactLabel l = label("VIP");
        String f = advancedFilter("VIPs", hasLabel(l));
        String c = campaignOver("Diwali", f);
        api(HttpMethod.DELETE, "/contact-filters/" + f, manager, null);
        setStatus(c, CampaignStatus.TERMINATED);
        assertThat(deleteLabel(l).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void attributeUsedByAnAdvancedFilterCannotBeDeleted() {
        String a = api(HttpMethod.POST, "/contact-attributes", manager, Map.of("name", "City")).getBody().get("id")
                .asString();
        String f = advancedFilter("Pune", Map.of("combinator", "and", "not", false, "rules",
                List.of(Map.of("field", "attributes." + a, "operator", "is", "value", "Pune"))));
        assertInUse(api(HttpMethod.DELETE, "/contact-attributes/" + a, manager, null),
                List.of(List.of(f, "Pune")), List.of());
    }

    @Test
    void simpleFilterDoesNotGuard() {
        ContactLabel l = label("VIP");
        String f = filter("Simple", Map.of("labelsAny", List.of(l.getId())));
        assertThat(deleteLabel(l).getStatusCode().value()).isEqualTo(204);
        ResponseEntity<JsonNode> list = api(HttpMethod.GET, "/contact-filters", manager, null);
        assertThat(list.getBody().get(0).get("id").asString()).isEqualTo(f);
    }
}
