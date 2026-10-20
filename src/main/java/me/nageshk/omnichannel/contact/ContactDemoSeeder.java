package me.nageshk.omnichannel.contact;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.phone.DemoSeeder;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.CustomerRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Demo contacts, only with {@code omnichannel.seed.demo=true}: in "Demo Fashion" of "Demo Retail", four
 * labels, three attributes, 40 contacts (four with hidden numbers) and six notes. Skipped when that brand
 * is missing or already has a label, so it also fills databases seeded before contacts existed.
 */
@Component
@Order(6)
@ConditionalOnProperty(name = "omnichannel.seed.demo", havingValue = "true")
public class ContactDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ContactDemoSeeder.class);
    private static final String BRAND = "Demo Fashion";
    private static final String NOTE_AUTHOR = "demo-manager";

    private static final String[] NAMES = {
        "Aarav Sharma", "Vivaan Gupta", "Aditya Verma", "Vihaan Singh", "Arjun Mehta", "Sai Reddy", "Reyansh Iyer",
        "Krishna Nair", "Ishaan Joshi", "Shaurya Kapoor", "Ananya Patel", "Diya Desai", "Aadhya Rao", "Saanvi Menon",
        "Kiara Bhatt", "Myra Kulkarni", "Anika Chopra", "Navya Pillai", "Riya Banerjee", "Prisha Agarwal",
        "Rohan Malhotra", "Kabir Khanna", "Dev Saxena", "Yash Tiwari", "Harsh Pandey", "Neha Jain", "Pooja Shetty",
        "Sneha Kaur", "Tanvi Bose", "Meera Das", "Karan Thakur", "Rahul Mishra", "Nikhil Chauhan", "Amit Yadav",
        "Sunita Hegde", "Lakshmi Naidu", "Farhan Qureshi", "Zoya Ansari", "Imran Sheikh", "Gurpreet Sandhu"};
    private static final String[] CITIES = {"Mumbai", "Delhi", "Bengaluru", "Pune", "Chennai", "Hyderabad", "Kolkata"};
    private static final String[] SIZES = {"S", "M", "L", "XL"};
    private static final String[] NOTES = {
        "Asked about the festive collection.", "Prefers WhatsApp over calls.", "Returned one item; refund done.",
        "Interested in bulk order for a wedding.", "Wants a callback on Monday.", "Loyal customer, send early access."};

    private final CustomerRepository customers;
    private final WorkspaceRepository workspaces;
    private final ContactRepository contacts;
    private final ContactLabelRepository labels;
    private final ContactAttributeRepository attributes;
    private final ContactNoteRepository notes;
    private final UserRepository users;
    private final Clock clock;

    public ContactDemoSeeder(CustomerRepository customers, WorkspaceRepository workspaces, ContactRepository contacts,
            ContactLabelRepository labels, ContactAttributeRepository attributes, ContactNoteRepository notes,
            UserRepository users, Clock clock) {
        this.customers = customers;
        this.workspaces = workspaces;
        this.contacts = contacts;
        this.labels = labels;
        this.attributes = attributes;
        this.notes = notes;
        this.users = users;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Customer customer = customers.findAll().stream().filter(c -> DemoSeeder.CUSTOMER.equals(c.getName()))
                .findFirst().orElse(null);
        if (customer == null) return;
        Workspace brand = workspaces.findByCustomerIdOrderByName(customer.getId()).stream()
                .filter(w -> BRAND.equals(w.getName())).findFirst().orElse(null);
        if (brand == null || !labels.findByWorkspaceIdOrderByNameKey(brand.getId()).isEmpty()) return;

        ContactLabel vip = label(brand, "VIP", "purple");
        ContactLabel lead = label(brand, "Lead", "blue");
        ContactLabel customerLabel = label(brand, "Customer", "green");
        ContactLabel followUp = label(brand, "Follow-up", "orange");
        Set<String> keys = new HashSet<>();
        ContactAttribute city = attribute(brand, "City", keys);
        ContactAttribute email = attribute(brand, "Email", keys);
        ContactAttribute size = attribute(brand, "Preferred size", keys);
        ContactLabel[] cycle = {customerLabel, lead, vip, followUp};

        Instant now = clock.instant();
        List<Contact> saved = new ArrayList<>();
        int hidden = 0;
        for (int i = 0; i < NAMES.length; i++) {
            Contact c = new Contact();
            c.setWorkspaceId(brand.getId());
            c.setCustomerId(customer.getId());
            c.setName(NAMES[i]);
            c.setSource(ContactSource.values()[i % 3]);
            if (i % 10 == 9) {
                hidden++;
                c.setWaId(String.format("9000000000000%02d@lid", hidden));
                c.setSource(ContactSource.CHAT);
            } else {
                c.setPhone(String.format("91900000%04d", i + 1));
                if (c.getSource() == ContactSource.CHAT) c.setWaId(c.getPhone() + "@c.us");
            }
            c.setNameEdited(c.getSource() != ContactSource.CHAT);
            c.getLabels().add(cycle[i % cycle.length].getId());
            if (i % 5 == 0) c.getLabels().add(cycle[(i + 1) % cycle.length].getId());
            if (i % 4 != 3) c.getAttributes().put(city.getId(), CITIES[i % CITIES.length]);
            if (i % 3 == 0) c.getAttributes().put(email.getId(),
                    NAMES[i].toLowerCase().replace(' ', '.') + "@example.com");
            if (i % 2 == 0) c.getAttributes().put(size.getId(), SIZES[i % SIZES.length]);
            c.setLastMessageAt(now.minus(Duration.ofHours(18L * i + 1)));
            saved.add(contacts.save(c));
        }

        String authorName = users.findByUsername(NOTE_AUTHOR).map(u -> u.getName()).orElse("Demo Manager");
        for (int i = 0; i < NOTES.length; i++) {
            ContactNote n = new ContactNote();
            n.setWorkspaceId(brand.getId());
            n.setContactId(saved.get(i * 5).getId());
            n.setText(NOTES[i]);
            n.setCreatedByUsername(NOTE_AUTHOR);
            n.setCreatedByName(authorName);
            notes.save(n);
        }
        log.warn("demo seed: {} contacts, 4 labels and 3 attributes added to {}", saved.size(), BRAND);
    }

    private ContactLabel label(Workspace brand, String name, String color) {
        ContactLabel l = new ContactLabel();
        l.setWorkspaceId(brand.getId());
        l.setName(name);
        l.setColor(color);
        return labels.save(l);
    }

    private ContactAttribute attribute(Workspace brand, String name, Set<String> taken) {
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(brand.getId());
        a.setName(name);
        a.setKey(AttributeKeys.generate(name, taken));
        taken.add(a.getKey());
        return attributes.save(a);
    }
}
