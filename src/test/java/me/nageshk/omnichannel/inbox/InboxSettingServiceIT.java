package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class InboxSettingServiceIT {

    private static final String PN = "pn-settings";

    @Autowired InboxSettingService settings;
    @Autowired TeamRepository teams;
    @Autowired TestUsers testUsers;
    @Autowired MongoTemplate mongo;

    Customer acme;
    Workspace brand;
    Workspace other;

    @BeforeEach
    void setUp() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        mongo.remove(new Query(), InboxSetting.class);
    }

    private Team team(Workspace w) {
        Team t = new Team();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(w.getId());
        t.setName("Support");
        return teams.save(t);
    }

    private InboxSettingService.SettingsRequest request(RoutingMode mode, String teamId, String userId) {
        return new InboxSettingService.SettingsRequest(new InboxSetting.Routing(mode, teamId, userId), null, null);
    }

    private void assertField(Runnable call, String field, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.fields()).containsEntry(field, message));
    }

    @Test
    void defaultsWhenNothingStored() {
        InboxSetting s = settings.get(brand.getId(), PN);

        assertThat(s.getId()).isNull();
        assertThat(s.getRouting().mode()).isEqualTo(RoutingMode.NONE);
        assertThat(s.isClaimOnReply()).isTrue();
        assertThat(s.isKeepAssignmentOnClose()).isFalse();
        assertThat(mongo.count(new Query(), InboxSetting.class)).isZero();
    }

    @Test
    void putStoresAndGetReturns() {
        Team t = team(brand);

        settings.put(brand.getId(), PN, new InboxSettingService.SettingsRequest(
                new InboxSetting.Routing(RoutingMode.TEAM, t.getId(), "ignored"), false, true));

        InboxSetting s = settings.get(brand.getId(), PN);
        assertThat(s.getId()).isNotNull();
        assertThat(s.getCustomerId()).isEqualTo(acme.getId());
        assertThat(s.getRouting()).isEqualTo(new InboxSetting.Routing(RoutingMode.TEAM, t.getId(), null));
        assertThat(s.isClaimOnReply()).isFalse();
        assertThat(s.isKeepAssignmentOnClose()).isTrue();

        settings.put(brand.getId(), PN, request(RoutingMode.NONE, null, null));
        assertThat(mongo.count(new Query(), InboxSetting.class)).isEqualTo(1);
        assertThat(settings.get(brand.getId(), PN).isClaimOnReply()).isFalse();
    }

    @Test
    void teamAndRoundRobinNeedATeamOfTheWorkspace() {
        Team foreign = team(other);

        for (RoutingMode mode : List.of(RoutingMode.TEAM, RoutingMode.ROUND_ROBIN)) {
            assertField(() -> settings.put(brand.getId(), PN, request(mode, null, null)), "teamId", "required");
            assertField(() -> settings.put(brand.getId(), PN, request(mode, "nope", null)), "teamId", "unknown team");
            assertField(() -> settings.put(brand.getId(), PN, request(mode, foreign.getId(), null)),
                    "teamId", "unknown team");
        }
    }

    @Test
    void userModeNeedsAUserWithAccess() {
        User outsider = testUsers.createInCustomer(acme.getId(), "outsider", null,
                List.of(new WorkspaceRole(other.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
        User insider = testUsers.createInCustomer(acme.getId(), "insider", null,
                List.of(new WorkspaceRole(brand.getId(), testUsers.roleId(BuiltInRoles.AGENT))));

        assertField(() -> settings.put(brand.getId(), PN, request(RoutingMode.USER, null, null)),
                "userId", "required");
        assertField(() -> settings.put(brand.getId(), PN, request(RoutingMode.USER, null, outsider.getId())),
                "userId", "unknown user");

        settings.put(brand.getId(), PN, request(RoutingMode.USER, null, insider.getId()));
        assertThat(settings.get(brand.getId(), PN).getRouting().userId()).isEqualTo(insider.getId());
    }
}
