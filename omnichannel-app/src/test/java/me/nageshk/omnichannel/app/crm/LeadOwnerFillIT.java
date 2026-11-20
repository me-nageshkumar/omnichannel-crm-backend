package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.service.automation.AutomationCrm;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.JsonNode;

/** An unowned CRM lead takes the owner of the agent a chat is given to, never replacing one set by hand. */
class LeadOwnerFillIT extends CrmTestBase {

    @Autowired AutomationCrm crmPort;

    Contact contact;
    String crmContactId;
    String dealId;
    String sales;
    User viewer;
    User viewer2;

    @BeforeEach
    void setUp() throws Exception {
        enableCrm();
        sales = salesId();
        contact = contact("Alice Wonder", "919811111111");
        JsonNode c = captured(contact, number);
        crmContactId = c.get("contact").get("id").asText();
        dealId = c.get("deal").get("id").asText();
        unown("crmContacts", crmContactId);
        unown("crmDeals", dealId);
        viewer = brandUserWith("viewer", "CRM_VIEWER_FILL", Set.of("crm:view", "crm:view-all"));
        viewer2 = brandUserWith("viewer2", "CRM_VIEWER_FILL2", Set.of("crm:view", "crm:view-all"));
    }

    private void unown(String collection, String id) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)), new Update().unset("ownerId"), collection);
    }

    private Document doc(String collection, String id) {
        return mongo.findOne(new Query(Criteria.where("_id").is(id)), Document.class, collection);
    }

    private String owner(String collection, String id) {
        return doc(collection, id).getString("ownerId");
    }

    private String newDeal(String pipelineId) throws Exception {
        String id = createDeal(new LinkedHashMap<>(Map.of("title", "D", "pipelineId", pipelineId,
                "primaryCrmContactId", crmContactId))).get("id").asText();
        unown("crmDeals", id);
        return id;
    }

    private AutomationCrm.FillResult fill(User u) {
        return crmPort.fillOwner(brand.getId(), acme.getId(), contact.getId(), number.getId(), u.getId());
    }

    private List<Document> ownerActivities(String targetId) {
        return mongo.find(new Query(Criteria.where("target.id").is(targetId).and("type").is("FIELD_CHANGED")),
                Document.class, "crmActivities");
    }

    @Test
    void fillsUnownedContactAndOpenDeals() throws Exception {
        String second = newDeal(sales);
        AutomationCrm.FillResult r = fill(viewer);
        assertThat(r.crmContactId()).isEqualTo(crmContactId);
        assertThat(r.dealIds()).containsExactlyInAnyOrder(dealId, second);
        assertThat(owner("crmContacts", crmContactId)).isEqualTo(viewer.getId());
        assertThat(owner("crmDeals", dealId)).isEqualTo(viewer.getId());
        assertThat(owner("crmDeals", second)).isEqualTo(viewer.getId());
        for (String id : List.of(crmContactId, dealId, second)) {
            List<Document> acts = ownerActivities(id);
            assertThat(acts).hasSize(1);
            Document a = acts.get(0);
            assertThat(a.getString("actorType")).isEqualTo("SYSTEM");
            assertThat(a.getString("actorId")).isEqualTo("system");
            assertThat(a.getString("summary")).isEqualTo("Owner set by inbox routing");
            Document change = a.get("payload", Document.class).getList("changes", Document.class).get(0);
            assertThat(change.getString("field")).isEqualTo("ownerId");
            assertThat(change.get("from")).isNull();
            assertThat(change.getString("to")).isEqualTo(viewer.getId());
        }
        assertThat(ownerActivities(dealId).get(0).getString("pipelineId")).isEqualTo(sales);
        assertThat(ownerActivities(dealId).get(0).getString("phoneNumberId")).isEqualTo(number.getId());
    }

    @Test
    void keepsAnOwnerSetByHand() {
        mongo.updateFirst(new Query(Criteria.where("_id").is(crmContactId)), new Update().set("ownerId", "u-other"),
                "crmContacts");
        AutomationCrm.FillResult r = fill(viewer);
        assertThat(owner("crmContacts", crmContactId)).isEqualTo("u-other");
        assertThat(ownerActivities(crmContactId)).isEmpty();
        assertThat(owner("crmDeals", dealId)).isEqualTo(viewer.getId());
        assertThat(r.dealIds()).containsExactly(dealId);
    }

    @Test
    void leavesWonLostAndArchivedDeals() throws Exception {
        String won = newDeal(sales);
        String lost = newDeal(sales);
        String archived = newDeal(sales);
        mongo.updateFirst(new Query(Criteria.where("_id").is(won)), new Update().set("status", "WON"), "crmDeals");
        mongo.updateFirst(new Query(Criteria.where("_id").is(lost)), new Update().set("status", "LOST"), "crmDeals");
        mongo.updateFirst(new Query(Criteria.where("_id").is(archived)), new Update().set("archived", true),
                "crmDeals");
        AutomationCrm.FillResult r = fill(viewer);
        assertThat(r.dealIds()).containsExactly(dealId);
        for (String id : List.of(won, lost, archived)) assertThat(owner("crmDeals", id)).isNull();
    }

    @Test
    void skipsWhatTheAgentCannotSee() throws Exception {
        User blind = brandUserWith("blind", "NO_CRM_FILL", Set.of("contact:view"));
        AutomationCrm.FillResult none = fill(blind);
        assertThat(none.crmContactId()).isNull();
        assertThat(none.dealIds()).isEmpty();
        assertThat(owner("crmContacts", crmContactId)).isNull();
        assertThat(owner("crmDeals", dealId)).isNull();

        String p2 = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        String inP2 = newDeal(p2);
        User narrow = brandUserWith("narrow", "NARROW_FILL", Set.of("crm:view"));
        assignPipelines(narrow, sales);
        AutomationCrm.FillResult r = fill(narrow);
        assertThat(r.dealIds()).containsExactly(dealId);
        assertThat(owner("crmDeals", dealId)).isEqualTo(narrow.getId());
        assertThat(owner("crmDeals", inP2)).isNull();
        assertThat(owner("crmContacts", crmContactId)).isEqualTo(narrow.getId());
    }

    @Test
    void secondFillIsANoOp() {
        fill(viewer);
        AutomationCrm.FillResult again = fill(viewer2);
        assertThat(again.crmContactId()).isNull();
        assertThat(again.dealIds()).isEmpty();
        assertThat(owner("crmContacts", crmContactId)).isEqualTo(viewer.getId());
        assertThat(owner("crmDeals", dealId)).isEqualTo(viewer.getId());
        assertThat(ownerActivities(crmContactId)).hasSize(1);
        assertThat(ownerActivities(dealId)).hasSize(1);
        fill(viewer);
        assertThat(ownerActivities(dealId)).hasSize(1);
    }

    @Test
    void crmOffOrNoContactDoesNothing() {
        AutomationCrm.FillResult noContact = crmPort.fillOwner(brand.getId(), acme.getId(), contact.getId(),
                number2.getId(), viewer.getId());
        assertThat(noContact.crmContactId()).isNull();
        assertThat(noContact.dealIds()).isEmpty();
        assertThatCode(() -> crmPort.fillOwner(other.getId(), acme.getId(), contact.getId(), otherNumber.getId(),
                viewer.getId())).doesNotThrowAnyException();
        AutomationCrm.FillResult off = crmPort.fillOwner(other.getId(), acme.getId(), contact.getId(),
                otherNumber.getId(), viewer.getId());
        assertThat(off.crmContactId()).isNull();
        assertThat(off.dealIds()).isEmpty();
        assertThat(owner("crmContacts", crmContactId)).isNull();
    }
}
