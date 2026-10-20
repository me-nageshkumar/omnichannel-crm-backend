package me.nageshk.omnichannel.support;

import java.util.List;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.access.RoleCache;
import me.nageshk.omnichannel.access.RoleRepository;
import me.nageshk.omnichannel.access.RoleSeeder;
import me.nageshk.omnichannel.auth.JwtService;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.CustomerRepository;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.user.TwoFactor;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Test-only helper that inserts users straight into the (embedded) database. */
@Component
@Profile("test")
public class TestUsers {

    /** A fixed Base32 TOTP secret so tests can generate matching codes. */
    public static final String TEST_SECRET = "JBSWY3DPEHPK3PXP";

    private final UserRepository users;
    private final CustomerRepository customers;
    private final WorkspaceRepository workspaces;
    private final TeamRepository teams;
    private final RoleRepository roles;
    private final RoleSeeder roleSeeder;
    private final MongoTemplate mongo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AccessService access;
    private final RoleCache roleCache;

    public TestUsers(UserRepository users, CustomerRepository customers, WorkspaceRepository workspaces,
            TeamRepository teams, RoleRepository roles, RoleSeeder roleSeeder, MongoTemplate mongo,
            PasswordEncoder encoder, JwtService jwt, AccessService access, RoleCache roleCache) {
        this.users = users;
        this.customers = customers;
        this.workspaces = workspaces;
        this.teams = teams;
        this.roles = roles;
        this.roleSeeder = roleSeeder;
        this.mongo = mongo;
        this.encoder = encoder;
        this.jwt = jwt;
        this.access = access;
        this.roleCache = roleCache;
    }

    /** An access token for an already-saved user. */
    public String accessToken(User user) {
        return jwt.issueAccessToken(access.load(user)).token();
    }

    /** Creates a two-factor-off ACCOUNT_MANAGER user and returns an access token for it. */
    public String tokenFor(String username) {
        return accessToken(create(username, "pw", BuiltInRoles.ACCOUNT_MANAGER, null));
    }

    /** Creates an active platform user with the named role; {@code totpSecret} null means two-factor off. */
    public User create(String username, String rawPassword, String roleName, String totpSecret) {
        User u = base(username, rawPassword, totpSecret);
        u.setRoleId(roleId(roleName));
        return users.save(u);
    }

    /** Creates an active user inside a customer, with an optional company role and workspace roles. */
    public User createInCustomer(String customerId, String username, String companyRoleName,
            List<WorkspaceRole> workspaceRoles) {
        User u = base(username, "pw", null);
        u.setCustomerId(customerId);
        if (companyRoleName != null) u.setRoleId(roleId(companyRoleName));
        u.setWorkspaceRoles(workspaceRoles);
        return users.save(u);
    }

    public Customer customer(String name) {
        Customer c = new Customer();
        c.setName(name);
        c.setActive(true);
        return customers.save(c);
    }

    public Workspace workspace(String customerId, String name) {
        Workspace w = new Workspace();
        w.setCustomerId(customerId);
        w.setName(name);
        return workspaces.save(w);
    }

    public String roleId(String name) {
        return roles.findByName(name).orElseThrow(() -> new IllegalStateException("no role " + name)).getId();
    }

    /** Clears every collection by removing documents (indexes stay), then reseeds the default roles. */
    public void deleteAll() {
        users.deleteAll();
        customers.deleteAll();
        workspaces.deleteAll();
        teams.deleteAll();
        roles.deleteAll();
        mongo.remove(new Query(), "system_state");
        mongo.remove(new Query(), "authorities");
        mongo.remove(new Query(), "phone_numbers");
        mongo.remove(new Query(), "connect_codes");
        for (String c : List.of("contacts", "contact_labels", "contact_attributes", "contact_notes", "contact_filters",
                "message_templates", "template_media.files", "template_media.chunks"))
            mongo.remove(new Query(), c);
        roleSeeder.seed();
        roleCache.evictAll();
    }

    private User base(String username, String rawPassword, String totpSecret) {
        User u = new User();
        u.setName("Test " + username);
        u.setUsername(username);
        u.setPassword(encoder.encode(rawPassword));
        u.setActive(true);
        u.setTwoFactor(totpSecret == null ? TwoFactor.disabled() : TwoFactor.enabledWith(totpSecret));
        return u;
    }
}
