package me.nageshk.omnichannel.security.entitlement;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.billing.GroupCategory;
import me.nageshk.omnichannel.security.access.Authority;

/**
 * The feature groups a new installation starts with. Every built-in COMPANY or BRAND authority is in exactly one,
 * so a customer on a plan is never cut off from a feature only because nobody grouped a new authority.
 */
public final class SeedGroups {

    public static final String CORE = "core";

    /**
     * @param holds which built-in authorities the group starts with
     */
    public record Seed(String code, String name, String description, GroupCategory category, Set<String> requires,
            Predicate<Authority> holds, int sortOrder) {}

    private static final List<Seed> ALL = List.of(
            seed(CORE, "Core", "Brands, users, teams, WhatsApp numbers and the billing page", GroupCategory.DEFAULT,
                    Set.of(), 10, "workspace", "company-user", "user", "team", "phone-number", "billing"),
            seed("inbox", "Inbox", "Conversations with customers", GroupCategory.DEFAULT, Set.of(), 20, "inbox"),
            seed("contacts", "Contacts", "Contacts, labels and attributes", GroupCategory.DEFAULT, Set.of(), 30,
                    "contact", "contact-field"),
            seed("templates", "Templates", "Message templates", GroupCategory.DEFAULT, Set.of(), 40, "template"),
            seed("campaigns", "Campaigns", "Bulk messages to many contacts", GroupCategory.PAID, Set.of(), 50,
                    "campaign"),
            seed("automations", "Automations", "Rules that act on events", GroupCategory.PAID, Set.of(), 60,
                    "automation"),
            // a chatbot is started by an automation rule, so it is of no use without automations
            seed("chatbots", "Chatbots", "Chatbot flows", GroupCategory.PAID, Set.of("automations"), 70, "chatbot"),
            seed("ai", "AI", "AI connections, profiles and the AI node", GroupCategory.PAID, Set.of("chatbots"), 80,
                    "ai"),
            seed("crm", "CRM", "Deals, pipelines, companies and tasks", GroupCategory.PAID, Set.of(), 90, "crm"));

    private SeedGroups() {}

    public static List<Seed> all() {
        return ALL;
    }

    /** The built-in authorities Core always holds: without them a Customer Admin could not manage users or see billing. */
    public static Set<String> coreBuiltIns() {
        return authoritiesOf(ALL.get(0));
    }

    public static Set<String> authoritiesOf(Seed seed) {
        return Arrays.stream(Authority.values()).filter(seed.holds()).map(Authority::code)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Seed seed(String code, String name, String description, GroupCategory category,
            Set<String> requires, int sortOrder, String... prefixes) {
        Set<String> wanted = Set.of(prefixes);
        return new Seed(code, name, description, category, requires,
                a -> a.level() != RoleLevel.PLATFORM && wanted.contains(a.group()), sortOrder);
    }
}
