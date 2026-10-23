package me.nageshk.omnichannel.service.template;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.template.MessageTemplateRepository;
import me.nageshk.omnichannel.db.template.TemplateContent;
import me.nageshk.omnichannel.db.template.TemplateType;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.phone.DemoSeeder;
import me.nageshk.omnichannel.service.template.TemplateRequest.PlaceholderSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Demo templates, only with {@code omnichannel.seed.demo=true}: five templates in each of "Demo Fashion" and
 * "Demo Electronics", created as {@code demo-manager} through the services so validation runs. A brand that
 * already has any template is skipped, as is everything when the demo customer, brands or user are missing.
 */
@Component
@Order(8)
@ConditionalOnProperty(name = "omnichannel.seed.demo", havingValue = "true")
public class TemplateDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TemplateDemoSeeder.class);
    private static final List<String> BRANDS = List.of("Demo Fashion", "Demo Electronics");
    private static final String AUTHOR = "demo-manager";
    private static final String IMAGE = "demo/new-collection.png";

    private final CustomerRepository customers;
    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final AccessService access;
    private final MessageTemplateRepository templates;
    private final ContactAttributeRepository attributes;
    private final TemplateService service;
    private final TemplateMediaService media;

    public TemplateDemoSeeder(CustomerRepository customers, WorkspaceRepository workspaces, UserRepository users,
            AccessService access, MessageTemplateRepository templates, ContactAttributeRepository attributes,
            TemplateService service, TemplateMediaService media) {
        this.customers = customers;
        this.workspaces = workspaces;
        this.users = users;
        this.access = access;
        this.templates = templates;
        this.attributes = attributes;
        this.service = service;
        this.media = media;
    }

    @Override
    public void run(ApplicationArguments args) {
        Customer customer = customers.findAll().stream().filter(c -> DemoSeeder.CUSTOMER.equals(c.getName()))
                .findFirst().orElse(null);
        User author = users.findByUsername(AUTHOR).orElse(null);
        if (customer == null || author == null) return;
        AuthenticatedUser me = access.load(author);
        List<Workspace> brands = workspaces.findByCustomerIdOrderByName(customer.getId());
        for (String name : BRANDS) {
            Workspace brand = brands.stream().filter(w -> name.equals(w.getName())).findFirst().orElse(null);
            if (brand == null || templates.existsByWorkspaceId(brand.getId())) continue;
            seed(me, brand);
            log.warn("demo seed: 5 message templates added to {}", name);
        }
    }

    private void seed(AuthenticatedUser me, Workspace brand) {
        String ws = brand.getId();
        String name = brand.getName();

        TemplateContent welcome = new TemplateContent();
        welcome.setBody("Hi {{contact.name}}, welcome to " + name + "! Reply here any time.");
        service.create(me, ws, new TemplateRequest("Welcome", "Greeting", TemplateType.TEXT, welcome, null,
                List.of(fallback("contact.name", "there")), null));

        TemplateContent shipped = new TemplateContent();
        shipped.setBody("Hi {{contact.name}}, your order *{{order_id}}* has shipped and arrives by {{delivery_date}}.");
        service.create(me, ws, new TemplateRequest("Order shipped", "Orders", TemplateType.TEXT, shipped, null,
                List.of(fallback("contact.name", "there"),
                        new PlaceholderSetting("order_id", null, "Order ID", "FS-10234", true),
                        new PlaceholderSetting("delivery_date", null, "Delivery date", "25 Oct", true)),
                null));

        boolean hasCity = attributes.findByWorkspaceIdAndKey(ws, "city").isPresent();
        TemplateContent collection = new TemplateContent();
        List<PlaceholderSetting> collectionSettings;
        if (hasCity) {
            collection.setCaption("Our new collection is in store in {{contact.attributes.city}}!");
            collectionSettings = List.of(fallback("contact.attributes.city", "your city"));
        } else {
            collection.setCaption("{{contact.name}}, our new collection is in store!");
            collectionSettings = List.of(fallback("contact.name", "there"));
        }
        MediaView image = media.upload(me, ws, "new-collection.png", imageBytes());
        service.create(me, ws, new TemplateRequest("New collection", "Promotion", TemplateType.IMAGE, collection,
                image.mediaId(), collectionSettings, null));

        TemplateContent location = new TemplateContent();
        location.setLatitude(19.0760);
        location.setLongitude(72.8777);
        location.setName(name + " flagship");
        location.setAddress("Linking Road, Bandra West, Mumbai");
        service.create(me, ws, new TemplateRequest("Store location", "Store", TemplateType.LOCATION, location, null,
                List.of(), null));

        TemplateContent poll = new TemplateContent();
        poll.setQuestion("How was your last visit, {{contact.name}}?");
        poll.setOptions(List.of("Great", "Okay", "Not good"));
        poll.setAllowMultiple(false);
        service.create(me, ws, new TemplateRequest("Feedback poll", "Feedback", TemplateType.POLL, poll, null,
                List.of(fallback("contact.name", "there")), null));
    }

    private static PlaceholderSetting fallback(String placeholder, String fallback) {
        return new PlaceholderSetting(placeholder, fallback, null, null, null);
    }

    private static byte[] imageBytes() {
        try (InputStream in = new ClassPathResource(IMAGE).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
