package me.nageshk.omnichannel.phone;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.access.AssignmentPolicy.Actor;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.access.RoleRepository;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.CustomerRepository;
import me.nageshk.omnichannel.tenant.CustomerService;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceService;
import me.nageshk.omnichannel.user.UserAdminService;
import me.nageshk.omnichannel.user.UserAdminService.NewLogin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Demo data, only with {@code omnichannel.seed.demo=true}: customer "Demo Retail" (owner
 * {@code demo-admin}) with brands "Demo Fashion" and "Demo Electronics", a Workspace Manager, two agents
 * and Agent in Demo Fashion, and the number {@value #NUMBER} there. Temporary passwords are logged
 * once and must be changed at first login. Skipped when the customer exists; the number is skipped
 * when any brand already has it.
 */
@Component
@Order(5)
@ConditionalOnProperty(name = "omnichannel.seed.demo", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {

    public static final String CUSTOMER = "Demo Retail";
    public static final String NUMBER = "919928088743";

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Actor SEEDER = new Actor(true, null, Set.of(), Map.of());

    private final CustomerRepository customers;
    private final CustomerService customerService;
    private final WorkspaceService workspaceService;
    private final UserAdminService userAdmin;
    private final RoleRepository roles;
    private final PhoneNumberRepository numbers;

    public DemoSeeder(CustomerRepository customers, CustomerService customerService, WorkspaceService workspaceService,
            UserAdminService userAdmin, RoleRepository roles, PhoneNumberRepository numbers) {
        this.customers = customers;
        this.customerService = customerService;
        this.workspaceService = workspaceService;
        this.userAdmin = userAdmin;
        this.roles = roles;
        this.numbers = numbers;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (customers.findAll().stream().anyMatch(c -> CUSTOMER.equals(c.getName()))) return;

        String adminPassword = password();
        CustomerService.Created created = customerService.create(SEEDER, "demo-seed", CUSTOMER, "Demo Fashion",
                new NewLogin("Demo Admin", "demo-admin", null, null, adminPassword));
        Customer customer = created.customer();
        Workspace fashion = customerService.workspacesOf(customer).get(0);
        workspaceService.create(customer.getId(), "Demo Electronics");
        log.warn("demo seed: demo-admin / {} (Customer Admin, change at first login)", adminPassword);

        person("Demo Manager", "demo-manager", BuiltInRoles.WORKSPACE_MANAGER, customer, fashion);
        person("Demo Lead", "demo-lead", BuiltInRoles.AGENT, customer, fashion);
        person("Demo Agent", "demo-agent", BuiltInRoles.AGENT, customer, fashion);

        if (numbers.existsByNumber(NUMBER)) {
            log.warn("demo seed: +{} is already onboarded elsewhere; not adding it to Demo Fashion", NUMBER);
            return;
        }
        PhoneNumber n = new PhoneNumber();
        n.setNumber(NUMBER);
        n.setLabel("Main line");
        n.setConnectionType(ConnectionType.WHATSAPP_WEB);
        n.setCustomerId(customer.getId());
        n.setWorkspaceId(fashion.getId());
        n.setCreatedBy("demo-seed");
        numbers.insert(n);
        log.warn("demo seed: +{} onboarded in Demo Fashion; get a connect code from its Phone numbers page", NUMBER);
    }

    private void person(String name, String username, String roleName, Customer customer, Workspace brand) {
        String pw = password();
        String roleId = roles.findByName(roleName).orElseThrow().getId();
        userAdmin.createInWorkspace(SEEDER, new NewLogin(name, username, null, null, pw), customer.getId(),
                brand.getId(), roleId);
        log.warn("demo seed: {} / {} ({} in {}, change at first login)", username, pw, roleName, brand.getName());
    }

    private static String password() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
