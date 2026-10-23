package me.nageshk.omnichannel.app.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.PausedBy;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.campaign.SenderState;
import me.nageshk.omnichannel.db.campaign.SkipReason;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class CampaignReadIT extends CampaignTestBase {

    @Autowired RoleRepository roles;

    private Campaign stored(String name, CampaignStatus status, Instant created, PausedBy pausedBy) {
        Campaign c = new Campaign();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setNameKey(name.toLowerCase());
        c.setStatus(status);
        c.setPausedBy(pausedBy);
        c.setCreationTime(created);
        c.setSenderIds(List.of());
        c.setCounts(new Campaign.Counts(0, 0, 0, 0, 0, 0, 0, 0));
        return mongo.save(c);
    }

    private CampaignRecipient row(String campaignId, String phone, RecipientStatus status) {
        CampaignRecipient r = newRecipient(campaignId, phone);
        r.setStatus(status);
        return mongo.save(r);
    }

    @Test
    void detailCarriesCountsEstimateAndSenders() {
        List<String> phones = new ArrayList<>();
        for (int i = 0; i < 12; i++) phones.add("9198111100" + String.format("%02d", i));
        String id = startedFilterCampaign("Detail", phones, n1, n2);
        sender(n2, s -> {
            s.setState(SenderState.RESTING);
            s.setRestUntil(clock.instant().plusSeconds(86400));
        });
        CampaignRecipient r = mongo.findOne(new Query(Criteria.where("campaignId").is(id)), CampaignRecipient.class);
        mongo.updateFirst(new Query(Criteria.where("_id").is(r.getId())),
                new Update().set("status", RecipientStatus.SENT), CampaignRecipient.class);

        JsonNode body = api(HttpMethod.GET, "/campaigns/" + id, agent, null).getBody();
        assertThat(body.get("estimate").get("remaining").asInt()).isEqualTo(11);
        assertThat(body.get("estimate").get("perDay").asInt()).isEqualTo(5);
        assertThat(body.get("estimate").get("days").asInt()).isEqualTo(3);
        assertThat(body.get("estimate").get("finishBy").asString()).isEqualTo("2026-10-23");
        assertThat(body.get("counts").get("total").asInt()).isEqualTo(12);
        assertThat(body.get("counts").get("pending").asInt()).isEqualTo(11);
        assertThat(body.get("counts").get("sent").asInt()).isEqualTo(1);
        assertThat(body.get("senders")).hasSize(2);
        Set<String> states = new java.util.HashSet<>();
        body.get("senders").forEach(s -> states.add(s.get("state").asString()));
        assertThat(states).containsExactlyInAnyOrder("WARMING", "RESTING");
    }

    @Test
    void listIsNewestFirstAndFiltersByStatus() {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        stored("Old", CampaignStatus.DRAFT, t, null);
        stored("Mid", CampaignStatus.RUNNING, t.plusSeconds(60), null);
        stored("New", CampaignStatus.DRAFT, t.plusSeconds(120), null);

        JsonNode all = api(HttpMethod.GET, "/campaigns?page=0", agent, null).getBody();
        assertThat(all.get("items").get(0).get("name").asString()).isEqualTo("New");
        assertThat(all.get("items").get(1).get("name").asString()).isEqualTo("Mid");
        assertThat(all.get("items").get(2).get("name").asString()).isEqualTo("Old");
        assertThat(all.get("items").get(0).get("estimate").isNull()).isTrue();
        assertThat(all.get("items").get(0).get("senders").isNull()).isTrue();
        assertThat(all.get("systemPaused").asBoolean()).isFalse();

        JsonNode drafts = api(HttpMethod.GET, "/campaigns?status=DRAFT&page=0", agent, null).getBody();
        assertThat(drafts.get("items")).hasSize(2);
        assertThat(drafts.get("total").asInt()).isEqualTo(2);
    }

    @Test
    void listPagesBy50() {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        for (int i = 0; i < 51; i++) stored("C" + i, CampaignStatus.DRAFT, t.plusSeconds(i), null);
        JsonNode p0 = api(HttpMethod.GET, "/campaigns?page=0", agent, null).getBody();
        JsonNode p1 = api(HttpMethod.GET, "/campaigns?page=1", agent, null).getBody();
        assertThat(p0.get("items")).hasSize(50);
        assertThat(p0.get("total").asInt()).isEqualTo(51);
        assertThat(p1.get("items")).hasSize(1);
        assertThat(p1.get("page").asInt()).isEqualTo(1);
        assertThat(p1.get("items").get(0).get("name").asString()).isEqualTo("C0");
    }

    @Test
    void systemPausedBanner() {
        stored("Stuck", CampaignStatus.PAUSED, Instant.parse("2026-10-01T00:00:00Z"), PausedBy.SYSTEM);
        stored("Mine", CampaignStatus.PAUSED, Instant.parse("2026-10-02T00:00:00Z"), PausedBy.USER);
        JsonNode body = api(HttpMethod.GET, "/campaigns?status=DRAFT&page=0", agent, null).getBody();
        assertThat(body.get("systemPaused").asBoolean()).isTrue();
        assertThat(body.get("items")).isEmpty();
    }

    @Test
    void recipientsShowNamesSendersAndSkips() {
        Campaign c = stored("Rows", CampaignStatus.RUNNING, Instant.parse("2026-10-01T00:00:00Z"), null);
        var contact = contact("Asha", "919811110001");
        CampaignRecipient sent = newRecipient(c.getId(), "919811110001");
        sent.setStatus(RecipientStatus.SENT);
        sent.setContactId(contact.getId());
        sent.setSenderId(n1.getId());
        sent.setSentAt(Instant.parse("2026-10-21T05:30:00Z"));
        mongo.save(sent);
        CampaignRecipient dup = newRecipient(c.getId(), null);
        dup.setRawPhone("98111 10001");
        dup.setStatus(RecipientStatus.SKIPPED);
        dup.setSkipReason(SkipReason.DUPLICATE);
        mongo.save(dup);

        JsonNode skipped = api(HttpMethod.GET, "/campaigns/" + c.getId() + "/recipients?status=SKIPPED&page=0", agent,
                null).getBody();
        assertThat(skipped.get("total").asInt()).isEqualTo(1);
        assertThat(skipped.get("items").get(0).get("phone").asString()).isEqualTo("98111 10001");
        assertThat(skipped.get("items").get(0).get("skipReason").asString()).isEqualTo("DUPLICATE");

        JsonNode sentRows = api(HttpMethod.GET, "/campaigns/" + c.getId() + "/recipients?status=SENT&page=0", agent,
                null).getBody();
        JsonNode item = sentRows.get("items").get(0);
        assertThat(item.get("contactName").asString()).isEqualTo("Asha");
        assertThat(item.get("senderNumber").asString()).isEqualTo(n1.getNumber());
        assertThat(item.get("phone").asString()).isEqualTo("919811110001");

        for (int i = 0; i < 50; i++) row(c.getId(), "9198222200" + String.format("%02d", i), RecipientStatus.PENDING);
        JsonNode p0 = api(HttpMethod.GET, "/campaigns/" + c.getId() + "/recipients?page=0", agent, null).getBody();
        JsonNode p1 = api(HttpMethod.GET, "/campaigns/" + c.getId() + "/recipients?page=1", agent, null).getBody();
        assertThat(p0.get("items")).hasSize(50);
        assertThat(p0.get("total").asInt()).isEqualTo(52);
        assertThat(p1.get("items")).hasSize(2);
    }

    @Test
    void readsNeedCampaignView() {
        Role noCampaigns = new Role();
        noCampaigns.setName("INBOX_VIEWER");
        noCampaigns.setLevel(RoleLevel.BRAND);
        noCampaigns.setAuthorities(Set.of("inbox:view"));
        noCampaigns = roles.save(noCampaigns);
        User viewer = testUsers.createInCustomer(acme.getId(), "viewer", null,
                List.of(new WorkspaceRole(brand.getId(), noCampaigns.getId())));
        Campaign c = stored("Guarded", CampaignStatus.DRAFT, Instant.parse("2026-10-01T00:00:00Z"), null);
        for (String path : List.of("/campaigns", "/campaigns/" + c.getId(), "/campaigns/" + c.getId() + "/recipients")) {
            ResponseEntity<JsonNode> res = api(HttpMethod.GET, path, viewer, null);
            assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(api(HttpMethod.GET, path, agent, null).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        }
        roles.delete(noCampaigns);
    }
}
