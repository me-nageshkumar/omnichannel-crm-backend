package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactFilter;
import me.nageshk.omnichannel.contact.ContactFilterSpec;
import me.nageshk.omnichannel.contact.ContactLabel;
import me.nageshk.omnichannel.contact.FilterKind;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** A campaign over a saved filter keeps the filter as it was when the campaign was saved. */
class CampaignAdvancedAudienceIT extends CampaignTestBase {

    private Map<String, Object> hasLabel(ContactLabel l) {
        return Map.of("combinator", "and", "not", false, "rules",
                List.of(Map.of("field", "labels", "operator", "has", "value", l.getId())));
    }

    private Contact labelled(String name, String phone, ContactLabel l) {
        Contact c = contact(name, phone);
        if (l != null) {
            c.getLabels().add(l.getId());
            mongo.save(c);
        }
        return c;
    }

    private String create(String filterId) {
        String t = templateNamed("Adv Hi", "Hi");
        return api(HttpMethod.POST, "/campaigns", manager, filterCampaign("Adv " + filterId, filterId, List.of(t), List.of(),
                List.of(n1.getId()))).getBody().get("id").asString();
    }

    private List<CampaignRecipient> recipients(String id) {
        return mongo.find(new Query(org.springframework.data.mongodb.core.query.Criteria.where("campaignId").is(id)),
                CampaignRecipient.class);
    }

    @Test
    void createSnapshotsTheAdvancedFilter() {
        ContactLabel vip = label("VIP");
        String f = advancedFilter("VIPs", hasLabel(vip));
        String id = create(f);
        ContactFilter saved = mongo.findById(f, ContactFilter.class);
        Campaign.Audience a = campaign(id).getAudience();
        assertThat(a.filterKind()).isEqualTo(FilterKind.ADVANCED);
        assertThat(a.filterQueryJson()).isEqualTo(saved.getQueryJson());
        assertThat(a.uses().labelIds()).containsExactly(vip.getId());
        ResponseEntity<JsonNode> got = api(HttpMethod.GET, "/campaigns/" + id, manager, null);
        assertThat(got.getBody().at("/audience/filterKind").asString()).isEqualTo("ADVANCED");

        String simple = create(filter("All", Map.of()));
        Campaign.Audience s = campaign(simple).getAudience();
        assertThat(s.filterKind()).isEqualTo(FilterKind.SIMPLE);
        assertThat(s.filterQueryJson()).isNull();
        assertThat(s.uses()).isNull();
    }

    @Test
    void startMaterialisesTheAdvancedAudience() {
        ContactLabel vip = label("VIP");
        labelled("A", "919800000001", vip);
        labelled("B", "919800000002", null);
        labelled("C", null, vip);
        String id = create(advancedFilter("VIPs", hasLabel(vip)));
        assertThat(api(HttpMethod.POST, "/campaigns/" + id + "/start", manager, null).getStatusCode().value())
                .isEqualTo(200);
        List<CampaignRecipient> rs = recipients(id);
        assertThat(rs).hasSize(1);
        assertThat(rs.get(0).getPhone()).isEqualTo("919800000001");
        assertThat(rs.get(0).getStatus()).isEqualTo(RecipientStatus.PENDING);
    }

    @Test
    void startRunsTheSnapshotNotTheEditedFilter() {
        ContactLabel vip = label("VIP");
        labelled("A", "919800000001", vip);
        labelled("B", "919800000002", null);
        String f = advancedFilter("VIPs", hasLabel(vip));
        String id = create(f);
        Map<String, Object> isB = Map.of("combinator", "and", "not", false, "rules",
                List.of(Map.of("field", "name", "operator", "is", "value", "B")));
        assertThat(api(HttpMethod.PUT, "/contact-filters/" + f, manager, Map.of("name", "VIPs", "rules", isB))
                .getStatusCode().is2xxSuccessful()).isTrue();
        api(HttpMethod.POST, "/campaigns/" + id + "/start", manager, null);
        List<CampaignRecipient> rs = recipients(id);
        assertThat(rs).extracting(CampaignRecipient::getPhone).containsExactly("919800000001");
    }

    @Test
    void campaignOverALegacyFilterSnapshotsSimple() {
        contact("A", "919800000001");
        contact("B", "919800000002");
        ContactFilter legacy = new ContactFilter();
        legacy.setWorkspaceId(brand.getId());
        legacy.setName("Legacy");
        legacy.setFilter(new ContactFilterSpec(null, null, null, null, null, null, null, null, null,
                null, null, null));
        mongo.save(legacy);
        String id = create(legacy.getId());
        assertThat(campaign(id).getAudience().filterKind()).isEqualTo(FilterKind.SIMPLE);
        api(HttpMethod.POST, "/campaigns/" + id + "/start", manager, null);
        assertThat(recipients(id)).hasSize(2);
    }
}
