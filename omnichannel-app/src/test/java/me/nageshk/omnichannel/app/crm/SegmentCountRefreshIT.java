package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.segment.AudienceMembers;
import me.nageshk.omnichannel.crm.segment.CrmSegment;
import me.nageshk.omnichannel.crm.segment.SegmentAudienceApi;
import me.nageshk.omnichannel.crm.segment.SegmentCountRefreshJob;
import me.nageshk.omnichannel.crm.segment.SegmentResolver;
import me.nageshk.omnichannel.crm.segment.SegmentRule;
import me.nageshk.omnichannel.crm.segment.SegmentRuleGroup;
import me.nageshk.omnichannel.crm.segment.SegmentScope;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.RoleCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Spec D C9 (cached counts) and C12/C14 (the module's door for campaigns). */
class SegmentCountRefreshIT extends CrmTestBase {

    @Autowired SegmentCountRefreshJob job;
    @Autowired SegmentAudienceApi audiences;
    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;
    @MockitoSpyBean SegmentResolver resolver;

    private String segments() {
        return crm() + "/segments";
    }

    private static Map<String, Object> leads(String name) {
        return Map.of("name", name, "description", "", "rules", Map.of("combinator", "and", "not", false, "rules",
                List.of(Map.of("field", "contact.lifecycleStatus", "operator", "is", "value", "lead"))));
    }

    private String create(Map<String, Object> body) throws Exception {
        return json(send(post(segments()), managerToken, body).andExpect(status().isCreated())).get("id").asText();
    }

    private CrmSegment stored(String id) {
        return mongo.findById(id, CrmSegment.class);
    }

    private User userWith(String username, String... authorities) {
        Role r = new Role();
        r.setName("role-" + username);
        r.setLevel(RoleLevel.BRAND);
        r.setAuthorities(new HashSet<>(Set.of(authorities)));
        r = roles.save(r);
        roleCache.evictAll();
        return testUsers.createInCustomer(acme.getId(), username, null, List.of(new WorkspaceRole(brand.getId(), r.getId())));
    }

    @Test
    void jobStoresBrandWideCountsPerChannelAccount() throws Exception {
        enableCrm();
        String a = create(leads("Leads"));
        String b = create(leads("Also leads"));
        // saving counted them; clear so the job's own write is visible
        mongo.updateMulti(new org.springframework.data.mongodb.core.query.Query(),
                new org.springframework.data.mongodb.core.query.Update().unset("cachedCounts"), "crmSegments");
        captured(contact("Asha", "919900001001"), number);
        captured(contact("Ravi", "919900001002"), number2);
        job.run();
        for (String id : List.of(a, b)) {
            CrmSegment s = stored(id);
            assertThat(s.getCachedCounts()).isNotNull();
            assertThat(s.getCachedCounts().total()).isEqualTo(2);
            assertThat(s.getCachedCounts().people()).isEqualTo(2);
            assertThat(s.getCachedCounts().at()).isNotNull();
            assertThat(s.getCachedCounts().channelAccounts()).extracting("channelAccountId", "count")
                    .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(number.getId(), 1L),
                            org.assertj.core.groups.Tuple.tuple(number2.getId(), 1L));
            assertThat(s.getVersion()).as("a count is bookkeeping, not an edit").isEqualTo(0L);
        }
    }

    @Test
    void aFailingSegmentDoesNotStopTheOthers() throws Exception {
        enableCrm();
        String bad = create(leads("Breaks"));
        String good = create(leads("Works"));
        mongo.updateMulti(new org.springframework.data.mongodb.core.query.Query(),
                new org.springframework.data.mongodb.core.query.Update().unset("cachedCounts"), "crmSegments");
        captured(contact("Asha", "919900001011"), number);
        doAnswer(inv -> {
            CrmContext ctx = inv.getArgument(0);
            SegmentRuleGroup rules = inv.getArgument(1);
            SegmentScope scope = inv.getArgument(2);
            if (ctx.workspaceId().equals(brand.getId()) && stored(bad).getRules().equals(rules)
                    && !stored(good).getRules().equals(rules)) {
                throw new IllegalStateException("boom");
            }
            return inv.callRealMethod();
        }).when(resolver).resolve(any(), any(), any());
        // the two segments share one rule tree, so tell them apart by renaming the bad one's rule value
        mongo.updateFirst(new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(new org.bson.types.ObjectId(bad))),
                new org.springframework.data.mongodb.core.query.Update().set("rules.rules.0.value", "prospect"), "crmSegments");
        job.run();
        assertThat(stored(good).getCachedCounts()).isNotNull();
        assertThat(stored(good).getCachedCounts().total()).isEqualTo(1);
        assertThat(stored(bad).getCachedCounts()).isNull();
    }

    @Test
    void saveRefreshesCountsInline() throws Exception {
        enableCrm();
        captured(contact("Asha", "919900001021"), number);
        JsonNode created = json(send(post(segments()), managerToken, leads("Leads")).andExpect(status().isCreated()));
        assertThat(created.get("cachedCounts").get("total").asLong()).isEqualTo(1);
        assertThat(created.get("cachedCounts").get("channelAccounts").get(0).get("channelAccountId").asText())
                .isEqualTo(number.getId());
    }

    @Test
    void audienceApiResolvesForTheActorAndStampsLastSent() throws Exception {
        enableCrm();
        String second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().isCreated())).get("id").asText();
        captured(contact("Sales", "919900001031"), number);
        Contact c2 = contact("Second", "919900001032");
        send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", chat(c2, number, null, null).getId(),
                "pipelineId", second)).andExpect(status().isOk());
        String id = create(leads("Leads"));
        assertThat(audiences.find(brand.getId(), id)).hasValueSatisfying(ref -> assertThat(ref.name()).isEqualTo("Leads"));
        assertThat(audiences.find(brand.getId(), "missing")).isEmpty();
        assertThat(audiences.find(other.getId(), id)).isEmpty();

        AudienceMembers all = audiences.resolve(brand.getId(), id, auth(manager), "k1", "Diwali");
        assertThat(all.contactIds()).hasSize(2);
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.truncated()).isFalse();
        assertThat(all.channelAccountByContactId()).containsValues(number.getId());
        CrmSegment s = stored(id);
        assertThat(s.getLastSentAt()).isNotNull();
        assertThat(s.getLastCampaignId()).isEqualTo("k1");
        assertThat(s.getLastCampaignName()).isEqualTo("Diwali");
        assertThat(s.getVersion()).isEqualTo(0L);

        User marketer = userWith("marketer", "crm:view", "crm:segments");
        assignPipelines(marketer, salesId());
        AudienceMembers mine = audiences.resolve(brand.getId(), id, auth(marketer), "k2", "Spring");
        assertThat(mine.contactIds()).hasSize(1);
        assertThat(stored(id).getLastCampaignId()).isEqualTo("k2");
    }

    @Test
    void audienceApiRefusesWithoutAuthority() throws Exception {
        enableCrm();
        String id = create(leads("Leads"));
        assertThatThrownBy(() -> audiences.resolve(brand.getId(), id, auth(agent), "k1", "x"))
                .isInstanceOf(ForbiddenException.class);
        assertThat(stored(id).getLastSentAt()).isNull();
    }

    @Test
    void audienceApiDedupesContactIds() throws Exception {
        enableCrm();
        Contact both = contact("Both", "919900001041");
        captured(both, number);
        captured(both, number2);
        String id = create(leads("Leads"));
        AudienceMembers m = audiences.resolve(brand.getId(), id, auth(manager), "k1", "x");
        assertThat(m.total()).isEqualTo(2);
        assertThat(m.people()).isEqualTo(1);
        assertThat(m.contactIds()).containsExactly(both.getId());
        assertThat(m.channelAccountByContactId()).containsEntry(both.getId(), number.getId());
    }
}
