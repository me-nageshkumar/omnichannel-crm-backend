package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
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

/** Routing settings: one brand default, optional team overrides, the effective one per chat (design §3.4). */
@SpringBootTest
class InboxSettingServiceIT {

    @Autowired InboxSettingService settings;
    @Autowired TeamRepository teams;
    @Autowired PhoneNumberRepository phoneNumbers;
    @Autowired TestUsers testUsers;
    @Autowired MongoTemplate mongo;

    Customer acme;
    Workspace brand;
    Workspace other;
    PhoneNumber pn;

    @BeforeEach
    void setUp() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
        pn = testUsers.phoneNumber(acme.getId(), brand.getId(), "9199000" + System.nanoTime() % 100000);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        mongo.remove(new Query(), InboxSetting.class);
    }

    private Team team(Workspace w, String name, User... members) {
        Team t = new Team();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(w.getId());
        t.setName(name);
        t.setMemberIds(new java.util.ArrayList<>(java.util.Arrays.stream(members).map(User::getId).toList()));
        return teams.save(t);
    }

    private User agent(String username) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brand.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
    }

    /** A request with every field set; {@code null} fields keep what the effective setting has. */
    private static RoutingSetting.Request request(Boolean assignToTeam, Boolean roundRobin, String userId,
            Integer stickyDays) {
        return new RoutingSetting.Request(assignToTeam, roundRobin, userId, stickyDays, null, null);
    }

    private void assertField(Runnable call, String field, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.fields()).containsEntry(field, message));
    }

    @Test
    void brandDefaultIsCreatedLazilyWithSpecDefaults() {
        assertThat(mongo.count(new Query(), InboxSetting.class)).isZero();

        InboxSetting s = settings.brandDefault(brand.getId());

        assertThat(s.getId()).isNotNull();
        assertThat(s.getCustomerId()).isEqualTo(acme.getId());
        assertThat(s.getTeamId()).isNull();
        assertThat(s.getRouting()).isEqualTo(new InboxSetting.Routing(RoutingMode.TEAM, null));
        assertThat(s.getStickyDays()).isEqualTo(30);
        assertThat(s.isClaimOnReply()).isTrue();
        assertThat(s.isKeepAssignmentOnClose()).isFalse();
        assertThat(settings.brandDefault(brand.getId()).getId()).isEqualTo(s.getId());
        assertThat(mongo.count(new Query(), InboxSetting.class)).isEqualTo(1);
    }

    @Test
    void effectiveReturnsOverrideWhenPresentElseDefault() {
        Team support = team(brand, "Support");
        Team sales = team(brand, "Sales");
        settings.putDefault(brand.getId(), request(true, false, null, 10));
        settings.putOverride(brand.getId(), sales.getId(), request(true, true, null, 3));

        assertThat(settings.effective(brand.getId(), support.getId()).getRouting().mode()).isEqualTo(RoutingMode.TEAM);
        assertThat(settings.effective(brand.getId(), support.getId()).getStickyDays()).isEqualTo(10);
        assertThat(settings.effective(brand.getId(), sales.getId()).getRouting().mode()).isEqualTo(RoutingMode.ROUND_ROBIN);
        assertThat(settings.effective(brand.getId(), sales.getId()).getStickyDays()).isEqualTo(3);
        assertThat(settings.effective(brand.getId(), null).getStickyDays()).isEqualTo(10);

        settings.deleteOverride(brand.getId(), sales.getId());
        assertThat(settings.effective(brand.getId(), sales.getId()).getStickyDays()).isEqualTo(10);
        assertThat(settings.override(brand.getId(), sales.getId())).isEmpty();
    }

    @Test
    void switchesMapToModes() {
        Team support = team(brand, "Support", agent("a"));
        String a = support.getMemberIds().get(0);
        assertThat(settings.putDefault(brand.getId(), request(false, false, null, null)).getRouting().mode())
                .isEqualTo(RoutingMode.NONE);
        assertThat(settings.putDefault(brand.getId(), request(true, false, null, null)).getRouting().mode())
                .isEqualTo(RoutingMode.TEAM);
        assertThat(settings.putDefault(brand.getId(), request(true, true, null, null)).getRouting().mode())
                .isEqualTo(RoutingMode.ROUND_ROBIN);
        InboxSetting one = settings.putOverride(brand.getId(), support.getId(), request(true, false, a, null));
        assertThat(one.getRouting()).isEqualTo(new InboxSetting.Routing(RoutingMode.USER, a));
        // round robin off again clears nothing else; assign-to-team off drops the member
        assertThat(settings.putOverride(brand.getId(), support.getId(), request(false, false, a, null)).getRouting())
                .isEqualTo(new InboxSetting.Routing(RoutingMode.NONE, null));
    }

    @Test
    void nullFieldsKeepTheEffectiveValues() {
        Team support = team(brand, "Support");
        settings.putDefault(brand.getId(), new RoutingSetting.Request(true, true, null, 7, false, true));

        InboxSetting s = settings.putDefault(brand.getId(), new RoutingSetting.Request(null, null, null, null, null, null));
        assertThat(s.getRouting().mode()).isEqualTo(RoutingMode.ROUND_ROBIN);
        assertThat(s.getStickyDays()).isEqualTo(7);
        assertThat(s.isClaimOnReply()).isFalse();
        assertThat(s.isKeepAssignmentOnClose()).isTrue();

        // a new override starts from the brand default, then applies what was sent
        InboxSetting o = settings.putOverride(brand.getId(), support.getId(), request(null, false, null, null));
        assertThat(o.getRouting().mode()).isEqualTo(RoutingMode.TEAM);
        assertThat(o.getStickyDays()).isEqualTo(7);
        assertThat(o.isKeepAssignmentOnClose()).isTrue();
    }

    @Test
    void overrideUserMustBeMember() {
        Team support = team(brand, "Support", agent("a"));
        User outsider = agent("outsider");
        assertField(() -> settings.putOverride(brand.getId(), support.getId(), request(true, false, outsider.getId(), null)),
                "userId", "must be a member of the team");
        assertField(() -> settings.putOverride(brand.getId(), support.getId(), request(true, false, "nope", null)),
                "userId", "must be a member of the team");
    }

    @Test
    void defaultRefusesUserMode() {
        User a = agent("a");
        assertField(() -> settings.putDefault(brand.getId(), request(true, false, a.getId(), null)),
                "userId", "only a team override may name a member");
    }

    @Test
    void roundRobinAndUserTogetherRejected() {
        Team support = team(brand, "Support", agent("a"));
        assertField(() -> settings.putOverride(brand.getId(), support.getId(),
                request(true, true, support.getMemberIds().get(0), null)),
                "routing", "round robin and one member cannot both be set");
    }

    @Test
    void stickyDaysOutOfRangeRejected() {
        assertField(() -> settings.putDefault(brand.getId(), request(true, false, null, -1)),
                "stickyDays", "must be between 0 and 365");
        assertField(() -> settings.putDefault(brand.getId(), request(true, false, null, 366)),
                "stickyDays", "must be between 0 and 365");
        assertThat(settings.putDefault(brand.getId(), request(true, false, null, 0)).getStickyDays()).isZero();
    }

    @Test
    void overrideNeedsATeamOfTheWorkspace() {
        Team foreign = team(other, "Elsewhere");
        assertThatThrownBy(() -> settings.putOverride(brand.getId(), foreign.getId(), request(true, false, null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> settings.putOverride(brand.getId(), "nope", request(true, false, null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> settings.deleteOverride(brand.getId(), foreign.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void effectiveForChatUsesOwningTeam() {
        Team support = team(brand, "Support");
        settings.putDefault(brand.getId(), request(true, false, null, 10));
        settings.putOverride(brand.getId(), support.getId(), request(true, true, null, 3));
        WaWebChat chat = new WaWebChat();
        chat.setWorkspaceId(brand.getId());
        chat.setPhoneNumberId(pn.getId());

        assertThat(settings.effectiveFor(chat).getStickyDays()).isEqualTo(10);

        pn.setTeamId(support.getId());
        phoneNumbers.save(pn);
        assertThat(settings.effectiveFor(chat).getStickyDays()).isEqualTo(3);
    }

    @Test
    void teamSettingsListEveryTeamWithInheritedFlag() {
        Team support = team(brand, "Support");
        Team sales = team(brand, "Sales");
        settings.putOverride(brand.getId(), sales.getId(), request(true, true, null, null));

        List<RoutingSetting> all = settings.teamSettings(brand.getId());

        assertThat(all).extracting(RoutingSetting::teamId).containsExactly(sales.getId(), support.getId());
        assertThat(all.get(0).inherited()).isFalse();
        assertThat(all.get(0).roundRobin()).isTrue();
        assertThat(all.get(1).inherited()).isTrue();
        assertThat(all.get(1).roundRobin()).isFalse();
    }

    @Test
    void turningRoundRobinOnDropsTheMemberTheOverrideHad() {
        Team support = team(brand, "Support", agent("a"));
        String a = support.getMemberIds().get(0);
        settings.putOverride(brand.getId(), support.getId(), request(true, false, a, null));

        // the natural GET -> edit -> PUT: the console sends the member back as null with round robin on
        InboxSetting s = settings.putOverride(brand.getId(), support.getId(), request(true, true, null, null));

        assertThat(s.getRouting()).isEqualTo(new InboxSetting.Routing(RoutingMode.ROUND_ROBIN, null));
        // but naming a member together with round robin is still refused
        assertField(() -> settings.putOverride(brand.getId(), support.getId(), request(true, true, a, null)),
                "routing", "round robin and one member cannot both be set");
    }
}
