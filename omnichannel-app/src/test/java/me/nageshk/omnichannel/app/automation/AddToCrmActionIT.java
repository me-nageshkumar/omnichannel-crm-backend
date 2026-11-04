package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.crm.contact.CrmContact;
import me.nageshk.omnichannel.crm.contact.OriginKind;
import me.nageshk.omnichannel.crm.deal.CrmDeal;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import me.nageshk.omnichannel.crm.pipeline.StageType;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.automation.ActionContext;
import me.nageshk.omnichannel.service.automation.ActionResult;
import me.nageshk.omnichannel.service.automation.AddToCrmHandler;
import me.nageshk.omnichannel.service.automation.DealTitles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;

/** The add-to-CRM action puts the person who wrote in into the CRM, with a deal, owned by the chat's assignee. */
class AddToCrmActionIT extends AutomationTestBase {

    private static final String ASHA = "919811100001@c.us";

    @Autowired AddToCrmHandler handler;

    private AutomationAction add(String pipelineId, String stageId, String title) {
        return new AutomationAction(AutomationActionType.ADD_TO_CRM, null, null, null, null, null, null, pipelineId,
                stageId, title);
    }

    private ActionContext ctx(WaWebChat chat, AutomationEventKind kind) {
        EventData.ChatData c = new EventData.ChatData(chat.getId(), false, chat.getStatus(), null, null);
        me.nageshk.omnichannel.db.contact.Contact person = mongo.findById(chat.getContactId(),
                me.nageshk.omnichannel.db.contact.Contact.class);
        EventData.ContactData who = new EventData.ContactData(person.getId(), person.getName(), person.getPhone(),
                false, false);
        return new ActionContext(brand.getId(), acme.getId(), kind, "m-1",
                new EventData(c, null, new EventData.NumberData(n1.getId(), null, null), who, null, null, null, null),
                "r1", "Lead rule", false);
    }

    private ActionContext ctx(WaWebChat chat) {
        return ctx(chat, AutomationEventKind.MESSAGE_RECEIVED);
    }

    private WaWebChat asha() {
        enableCrm();
        return incoming(n1, ASHA, "chat", "Hi", "Asha");
    }

    private List<CrmContact> contacts() {
        return mongo.findAll(CrmContact.class);
    }

    private List<CrmDeal> deals() {
        return mongo.findAll(CrmDeal.class);
    }

    private CrmPipeline pipeline(String name, boolean active, String... lanes) {
        CrmPipeline p = new CrmPipeline();
        p.setWorkspaceId(brand.getId());
        p.setCustomerId(acme.getId());
        p.setName(name);
        p.setActive(active);
        p.setIsDefault(false);
        int i = 0;
        for (String lane : lanes) {
            CrmStage s = new CrmStage();
            s.setId("s" + name + i);
            s.setName(lane);
            s.setPosition(++i);
            s.setProbability(10);
            s.setType(StageType.OPEN);
            p.getStages().add(s);
        }
        return mongo.save(p);
    }

    private CrmPipeline defaultPipeline() {
        CrmSettings s = mongo.findOne(new Query(Criteria.where("workspaceId").is(brand.getId())), CrmSettings.class);
        return mongo.findById(s.getDefaultPipelineId(), CrmPipeline.class);
    }

    @Test
    void addsLeadToDefaultPipelineFirstLane() {
        WaWebChat chat = asha();

        ActionResult r = handler.run(ctx(chat), add(null, null, null));

        // the action made the brand's default pipeline; no CRM request came first
        CrmPipeline sales = defaultPipeline();
        CrmStage first = sales.getStages().stream().filter(s -> s.getType() == StageType.OPEN)
                .min(java.util.Comparator.comparingDouble(CrmStage::getPosition)).orElseThrow();
        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(r.reason()).isEqualTo("Added to Sales › " + first.getName());
        assertThat(contacts()).singleElement().satisfies(c -> {
            assertThat(c.getContactId()).isEqualTo(chat.getContactId());
            assertThat(c.getChannel().phoneNumberId()).isEqualTo(n1.getId());
            assertThat(c.getOrigin().kind()).isEqualTo(OriginKind.INBOUND_CHAT);
            assertThat(c.getOrigin().chatId()).isEqualTo(chat.getId());
        });
        assertThat(deals()).singleElement().satisfies(d -> {
            assertThat(d.getTitle()).isEqualTo("Asha – WhatsApp");
            assertThat(d.getPipelineId()).isEqualTo(sales.getId());
            assertThat(d.getStageId()).isEqualTo(first.getId());
        });
    }

    @Test
    void adChatGetsCtwaOrigin() {
        WaWebChat chat = asha();
        chat.setAdClick(new me.nageshk.omnichannel.db.inbox.AdClick("120001", "https://fb.me/ad1", "FB", "Summer sale",
                "Big", "FB_Ads", "click-1", java.time.Instant.now(), null));
        chats.save(chat);

        ActionResult r = handler.run(ctx(chat), add(null, null, null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(contacts()).singleElement().satisfies(c -> {
            assertThat(c.getOrigin().kind()).isEqualTo(OriginKind.CTWA_AD);
            assertThat(c.getOrigin().ad().sourceId()).isEqualTo("120001");
            assertThat(c.getOrigin().chatId()).isEqualTo(chat.getId());
        });
    }

    @Test
    void chosenPipelineAndLane() {
        WaWebChat chat = asha();
        CrmPipeline leads = pipeline("Leads", true, "New", "Qualified");

        ActionResult r = handler.run(ctx(chat), add(leads.getId(), "sLeads1", "{{name}} ({{phone}})"));

        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(r.reason()).isEqualTo("Added to Leads › Qualified");
        assertThat(deals()).singleElement().satisfies(d -> {
            assertThat(d.getTitle()).isEqualTo("Asha (+919811100001)");
            assertThat(d.getPipelineId()).isEqualTo(leads.getId());
            assertThat(d.getStageId()).isEqualTo("sLeads1");
        });
    }

    @Test
    void ownerFollowsTheAssignment() {
        WaWebChat chat = asha();
        chat.setAssignedUserId(agent.getId());
        chats.save(chat);

        handler.run(ctx(chat), add(null, null, null));

        assertThat(contacts()).singleElement().satisfies(c -> assertThat(c.getOwnerId()).isEqualTo(agent.getId()));
        assertThat(deals()).singleElement().satisfies(d -> assertThat(d.getOwnerId()).isEqualTo(agent.getId()));

        mongo.remove(new Query(), CrmContact.class);
        mongo.remove(new Query(), CrmDeal.class);
        chat.setAssignedUserId(null);
        chats.save(chat);

        handler.run(ctx(chat), add(null, null, null));

        assertThat(contacts()).singleElement().satisfies(c -> assertThat(c.getOwnerId()).isNull());
        assertThat(deals()).singleElement().satisfies(d -> assertThat(d.getOwnerId()).isNull());
    }

    @Test
    void alreadyInCrmDoesNothing() {
        WaWebChat chat = asha();

        assertThat(handler.run(ctx(chat), add(null, null, null)).outcome()).isEqualTo(StepOutcome.DONE);
        ActionResult second = handler.run(ctx(chat), add(null, null, null));

        assertThat(second.outcome()).isEqualTo(StepOutcome.SKIPPED);
        assertThat(second.reason()).isEqualTo("Already in the CRM");
        assertThat(contacts()).hasSize(1);
        assertThat(deals()).hasSize(1);
    }

    @Test
    void crmOffFails() {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");

        ActionResult r = handler.run(ctx(chat), add(null, null, null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("CRM is off for this brand");
    }

    @Test
    void onlyIncomingMessages() {
        WaWebChat chat = asha();

        ActionResult r = handler.run(ctx(chat, AutomationEventKind.DEAL_LANE_CHANGED), add(null, null, null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("Add to CRM works on incoming messages only");
        assertThat(contacts()).isEmpty();
    }

    @Test
    void missingLaneFails() {
        WaWebChat chat = asha();
        CrmPipeline leads = pipeline("Leads", true, "New");

        ActionResult r = handler.run(ctx(chat), add(leads.getId(), "gone", null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("The lane no longer exists");
        assertThat(contacts()).isEmpty();
        assertThat(deals()).isEmpty();
    }

    @Test
    void missingPipelineFails() {
        WaWebChat chat = asha();

        ActionResult r = handler.run(ctx(chat), add("gone", null, null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("The pipeline no longer exists");
    }

    @Test
    void inactivePipelineFails() {
        WaWebChat chat = asha();
        CrmPipeline old = pipeline("Old", false, "New");

        ActionResult r = handler.run(ctx(chat), add(old.getId(), null, null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("The pipeline is not active");
        assertThat(contacts()).isEmpty();
    }

    @Test
    void titleFallsBackWithoutNameOrPhone() {
        enableCrm();
        call(HttpMethod.GET, "/api/workspaces/" + brand.getId() + "/crm/pipelines", manager, null);
        WaWebChat chat = incoming(n1, "1234567890123@lid", "chat", "Hi", null);

        ActionResult r = handler.run(ctx(chat), add(null, null, null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(deals()).singleElement()
                .satisfies(d -> assertThat(d.getTitle()).isEqualTo("WhatsApp contact – WhatsApp"));
        assertThat(DealTitles.fill("{{name}} – WhatsApp", null, "919811100001")).isEqualTo("+919811100001 – WhatsApp");
    }

    @Test
    void createdDealFiresNoLaneEvent() {
        WaWebChat chat = asha();
        rule("Lane moved", AutomationEventKind.DEAL_LANE_CHANGED, RuleStatus.PUBLISHED, 1, null,
                new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null, null, null, null, null, null, null,
                        null));
        mongo.remove(new Query(), me.nageshk.omnichannel.db.automation.AutomationEvent.class);

        handler.run(ctx(chat), add(null, null, null));

        assertThat(deals()).hasSize(1);
        assertThat(queued()).isEmpty();
    }
}
