package me.nageshk.omnichannel.crm.demo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.access.PipelineAccessService;
import me.nageshk.omnichannel.crm.activity.TimelineService;
import me.nageshk.omnichannel.crm.activity.dto.LogRequest;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.company.Address;
import me.nageshk.omnichannel.crm.company.CrmCompanyService;
import me.nageshk.omnichannel.crm.company.dto.CompanyRequest;
import me.nageshk.omnichannel.crm.contact.CaptureService;
import me.nageshk.omnichannel.crm.contact.dto.CreateRequest;
import me.nageshk.omnichannel.crm.deal.CrmDeal;
import me.nageshk.omnichannel.crm.deal.DealService;
import me.nageshk.omnichannel.crm.deal.dto.CloseRequest;
import me.nageshk.omnichannel.crm.deal.dto.DealRequest;
import me.nageshk.omnichannel.crm.deal.dto.MoveRequest;
import me.nageshk.omnichannel.crm.field.FieldDefinitionService;
import me.nageshk.omnichannel.crm.field.dto.FieldDefinitionRequest;
import me.nageshk.omnichannel.crm.field.dto.FieldOptionRequest;
import me.nageshk.omnichannel.crm.note.NoteService;
import me.nageshk.omnichannel.crm.note.dto.NoteRequest;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmPipelineService;
import me.nageshk.omnichannel.crm.platform.BrandDirectory;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformBrand;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import me.nageshk.omnichannel.crm.settings.CrmSettingsService;
import me.nageshk.omnichannel.crm.task.TaskService;
import me.nageshk.omnichannel.crm.task.dto.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Demo CRM data, only with {@code omnichannel.seed.demo=true}: switches the CRM on for "Demo Fashion" of
 * "Demo Retail" and adds three companies, eight CRM contacts (the first demo contacts, on the brand's first
 * number), ten deals across the Sales lanes (one won, one lost, one rotting) and two logged calls, and gives
 * {@code demo-agent} the Sales pipeline; adds the DEAL fields {@code tier} and {@code budget}, three tasks for
 * {@code demo-agent} (one overdue, one due today, one whose reminder has passed) and a pinned note. Works through
 * the normal services as the system actor, so activities, counters and positions are real. Skipped when the brand is missing or already has CRM settings.
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "omnichannel.seed.demo", havingValue = "true")
public class CrmDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CrmDemoSeeder.class);
    static final String CUSTOMER = "Demo Retail";
    static final String BRAND = "Demo Fashion";
    private static final String AGENT = "demo-agent";
    private static final String[] COMPANIES = {"Bombay Weaves", "Urban Threads", "Kolkata Cottons"};
    private static final String[] INDUSTRIES = {"Textiles", "Retail", "Wholesale"};
    private static final String[] CONTACTS = {"Aarav Sharma", "Vivaan Gupta", "Aditya Verma", "Vihaan Singh",
        "Arjun Mehta", "Sai Reddy", "Reyansh Iyer", "Krishna Nair"};

    private final BrandDirectory brands;
    private final PeopleDirectory people;
    private final CrmSettingsService settings;
    private final CrmPipelineService pipelines;
    private final CrmAccess access;
    private final CaptureService capture;
    private final CrmCompanyService companies;
    private final DealService deals;
    private final PipelineAccessService pipelineAccess;
    private final TimelineService timeline;
    private final FieldDefinitionService fields;
    private final TaskService tasks;
    private final NoteService notes;
    private final MongoTemplate mongo;

    CrmDemoSeeder(BrandDirectory brands, PeopleDirectory people, CrmSettingsService settings,
            CrmPipelineService pipelines, CrmAccess access, CaptureService capture, CrmCompanyService companies,
            DealService deals, PipelineAccessService pipelineAccess, TimelineService timeline,
            FieldDefinitionService fields, TaskService tasks, NoteService notes, MongoTemplate mongo) {
        this.brands = brands;
        this.people = people;
        this.settings = settings;
        this.pipelines = pipelines;
        this.access = access;
        this.capture = capture;
        this.companies = companies;
        this.deals = deals;
        this.pipelineAccess = pipelineAccess;
        this.timeline = timeline;
        this.fields = fields;
        this.tasks = tasks;
        this.notes = notes;
        this.mongo = mongo;
    }

    @Override
    public void run(ApplicationArguments args) {
        PlatformBrand brand = brands.findByNames(CUSTOMER, BRAND).orElse(null);
        if (brand == null || settings.find(brand.id()).isPresent()) return;
        settings.enableForSystem(brand.id(), brand.customerId());
        CrmPipeline sales = pipelines.ensureDefault(brand.id(), brand.customerId(), "system");
        CrmContext ctx = access.forSystem(brand.id(), brand.customerId());
        if (ctx.numbers().isEmpty()) {
            log.warn("demo seed: {} has no number, CRM switched on without sample records", BRAND);
            return;
        }
        PlatformNumber number = ctx.numbers().get(0);
        String agentId = people.byUsername(AGENT).map(p -> p.id()).orElse(null);
        String salesId = sales.getId();
        List<String> stages = sales.getStages().stream().map(s -> s.getId()).toList();
        // lanes in order: New, Contacted, Qualified, Proposal, Negotiation, Won, Lost
        String contacted = stages.get(1), qualified = stages.get(2), proposal = stages.get(3),
                negotiation = stages.get(4);

        List<String> companyIds = new ArrayList<>();
        for (int i = 0; i < COMPANIES.length; i++) {
            companyIds.add(companies.create(ctx, new CompanyRequest(null, COMPANIES[i],
                    COMPANIES[i].toLowerCase().replace(' ', '-') + ".example.com", null, null, null, null, null,
                    null, new Address(null, i == 0 ? "Mumbai" : i == 1 ? "Delhi" : "Kolkata", null, null, "India"),
                    null, null, null, null, null)).id());
        }

        List<String> contactIds = new ArrayList<>();
        List<String> dealIds = new ArrayList<>();
        for (int i = 0; i < CONTACTS.length; i++) {
            var result = capture.create(ctx, new CreateRequest(String.format("91900000%04d", i + 1), number.id(),
                    CONTACTS[i].split(" ")[0], CONTACTS[i].split(" ")[1], salesId, CONTACTS[i] + " order"));
            contactIds.add(result.contact().id());
            dealIds.add(result.deal().id());
        }
        // two more deals, with an amount, a company and the demo agent as owner
        dealIds.add(deals.create(ctx, new DealRequest("Festive wholesale lot", salesId, qualified,
                new BigDecimal("250000"), null, null, null, "2026-12-15", companyIds.get(0), contactIds.get(0), null,
                agentId, null, "referral", null, null, null)).id());
        String rotting = deals.create(ctx, new DealRequest("Showroom uniforms", salesId, contacted,
                new BigDecimal("84000"), null, null, null, null, companyIds.get(1), contactIds.get(1), null,
                agentId, null, "website", null, null, null)).id();
        dealIds.add(rotting);

        deals.move(ctx, dealIds.get(1), new MoveRequest(contacted, null, null));
        deals.move(ctx, dealIds.get(2), new MoveRequest(qualified, null, null));
        deals.move(ctx, dealIds.get(3), new MoveRequest(proposal, null, null));
        deals.move(ctx, dealIds.get(4), new MoveRequest(negotiation, null, null));
        deals.close(ctx, dealIds.get(5), new CloseRequest("WON", null));
        deals.close(ctx, dealIds.get(6), new CloseRequest("LOST", "price"));
        // a stale card: quiet for three weeks, so the nightly rotting job keeps it flagged
        Instant stale = Instant.now().minus(21, ChronoUnit.DAYS);
        mongo.updateFirst(new Query(Criteria.where("_id").is(rotting).and("workspaceId").is(brand.id())),
                new Update().set("rotting", true).set("lastActivityAt", stale).set("stageEnteredAt", stale),
                CrmDeal.class);

        timeline.logActivity(ctx, new LogRequest(new LogRequest.Target("DEAL", dealIds.get(3)), "CALL",
                "Called to confirm the sample sizes", null));
        timeline.logActivity(ctx, new LogRequest(new LogRequest.Target("DEAL", dealIds.get(4)), "CALL",
                "Negotiated the delivery date", null));

        fields.create(ctx, new FieldDefinitionRequest("DEAL", "tier", "Tier", "SELECT",
                List.of(new FieldOptionRequest("gold", "Gold", "#f59e0b", true),
                        new FieldOptionRequest("silver", "Silver", "#9ca3af", true)),
                false, null, true, true, true, null));
        fields.create(ctx, new FieldDefinitionRequest("DEAL", "budget", "Budget", "CURRENCY", null, false, null,
                true, false, true, null));

        if (agentId != null) {
            pipelineAccess.put(ctx, agentId, List.of(salesId));
            Instant now = Instant.now();
            CrmTarget festive = new CrmTarget(TargetType.DEAL, dealIds.get(8));
            tasks.create(ctx, new TaskRequest("Send the festive price list", null, "follow_up", "HIGH",
                    now.minus(1, ChronoUnit.DAYS), null, agentId, festive, null));
            tasks.create(ctx, new TaskRequest("Call about uniform sizes", null, "call", "NORMAL",
                    now.plus(1, ChronoUnit.HOURS), null, agentId, new CrmTarget(TargetType.DEAL, dealIds.get(3)),
                    null));
            tasks.create(ctx, new TaskRequest("Confirm the delivery date", null, "call", "NORMAL",
                    now.plus(2, ChronoUnit.DAYS), now.minus(10, ChronoUnit.MINUTES), agentId,
                    new CrmTarget(TargetType.DEAL, dealIds.get(4)), null));
        }
        notes.create(ctx, new NoteRequest(new CrmTarget(TargetType.DEAL, dealIds.get(8)),
                "**Festive order** - 400 pieces, delivery before Diwali. Prefers cotton blends.", true, null));
        log.warn("demo seed: CRM switched on in {} with {} companies, {} contacts and {} deals", BRAND,
                companyIds.size(), contactIds.size(), dealIds.size());
    }
}
