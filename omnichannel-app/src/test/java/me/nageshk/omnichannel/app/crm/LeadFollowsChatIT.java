package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.app.chatbot.ChatbotTestBase;
import me.nageshk.omnichannel.crm.contact.CrmContact;
import me.nageshk.omnichannel.crm.deal.CrmDeal;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.inbox.ChatAssignmentService;
import me.nageshk.omnichannel.service.inbox.InboxSettingService;
import me.nageshk.omnichannel.service.inbox.RoutingSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Spec 2026-11-20 §6.1: a lead added before anyone holds the chat takes the owner of whoever gets the chat. */
class LeadFollowsChatIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";
    static final String RAHUL = "919811122233@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;
    @Autowired InboxSettingService settings;
    @Autowired ChatAssignmentService assignment;
    @Autowired AccessService access;

    @BeforeEach
    void crmFor() {
        enableCrm();
        String sales = "seed-sales-" + brand.getId();
        for (var u : List.of(agent, agent2)) {
            call(HttpMethod.PUT, "/api/workspaces/" + brand.getId() + "/crm/users/" + u.getId() + "/pipelines",
                    manager, Map.of("pipelineIds", List.of(sales)));
        }
    }

    private void routing(boolean roundRobin, String userId) {
        settings.putOverride(brand.getId(), support.getId(),
                new RoutingSetting.Request(true, roundRobin, userId, null, null, null));
    }

    private String publish(JsonNode bot) {
        assertThat(bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null)
                .getStatusCode().is2xxSuccessful()).isTrue();
        return bot.get("id").asString();
    }

    /** A chat nobody holds, in the support team's queue. */
    private WaWebChat unheld(String remote) {
        WaWebChat c = incoming(n1, remote, "chat", "hello", "Customer " + remote.substring(2, 6));
        c = chats.findById(c.getId()).orElseThrow();
        c.setAssignedUserId(null);
        c.setAssignedTeamId(support.getId());
        return chats.save(c);
    }

    private ChatbotRun start(String chatbotId, WaWebChat chat) throws Exception {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()),
                null, IfRunning.SKIP, true);
        assertThat(r.started()).as(r.reason()).isTrue();
        r.settled().get(5, TimeUnit.SECONDS);
        return runRepo.findById(r.run().getId()).orElseThrow();
    }

    private String leadThenHandOver() {
        return publish(createChatbot("Lead then hand over", flow(List.of(
                node("c", "add_to_crm", "Lead", Map.of()),
                node("a", "assign_chat", "Hand", Map.of("mode", "NUMBER_ROUTING"))), List.of(edge("c", "next", "a")))));
    }

    private String leadThenWait() {
        return publish(createChatbot("Lead then wait", flow(List.of(
                node("c", "add_to_crm", "Lead", Map.of()),
                node("q", "ask_question", "Ask", Map.of("text", "Name?", "answerType", "TEXT"))),
                List.of(edge("c", "next", "q")))));
    }

    private CrmContact crmContact(WaWebChat chat) {
        return mongo.findAll(CrmContact.class).stream().filter(c -> chat.getContactId().equals(c.getContactId()))
                .findFirst().orElseThrow();
    }

    private CrmDeal deal(CrmContact c) {
        return mongo.findAll(CrmDeal.class).stream().filter(d -> c.getId().equals(d.getPrimaryCrmContactId()))
                .findFirst().orElseThrow();
    }

    @Test
    void handOverWithNumberRoutingGivesTheLeadToTheMember() throws Exception {
        routing(false, agent.getId());
        WaWebChat chat = unheld(VEENU);
        ChatbotRun run = start(leadThenHandOver(), chat);
        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.HANDED_OVER);
        assertThat(chats.findById(chat.getId()).orElseThrow().getAssignedUserId()).isEqualTo(agent.getId());
        CrmContact lead = crmContact(chat);
        assertThat(lead.getOwnerId()).isEqualTo(agent.getId());
        assertThat(deal(lead).getOwnerId()).isEqualTo(agent.getId());
    }

    @Test
    void roundRobinGivesTwoLeadsToTwoMembers() throws Exception {
        String id = leadThenHandOver();
        // both chats exist before routing is switched on, so ingestion does not move the round robin
        WaWebChat first = unheld(VEENU);
        WaWebChat second = unheld(RAHUL);
        routing(true, null);
        start(id, first);
        start(id, second);
        String a = crmContact(first).getOwnerId();
        String b = crmContact(second).getOwnerId();
        assertThat(List.of(a, b)).containsExactlyInAnyOrder(agent.getId(), agent2.getId());
    }

    @Test
    void manualAssignAndClaimFillTheOwner() throws Exception {
        String id = leadThenWait();
        WaWebChat first = unheld(VEENU);
        start(id, first);
        assertThat(chats.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(ChatStatus.BOT);
        assertThat(crmContact(first).getOwnerId()).isNull();
        assertThat(deal(crmContact(first)).getOwnerId()).isNull();
        assignment.assign(access.load(manager), chats.findById(first.getId()).orElseThrow(), agent.getId(), null);
        assertThat(crmContact(first).getOwnerId()).isEqualTo(agent.getId());
        assertThat(deal(crmContact(first)).getOwnerId()).isEqualTo(agent.getId());

        WaWebChat second = unheld(RAHUL);
        start(id, second);
        WaWebChat open = chats.findById(second.getId()).orElseThrow();
        open.setStatus(ChatStatus.OPEN);
        open.setAssignedUserId(null);
        chats.save(open);
        assertThat(crmContact(second).getOwnerId()).isNull();
        assignment.claimOnReply(access.load(agent2), chats.findById(second.getId()).orElseThrow());
        assertThat(chats.findById(second.getId()).orElseThrow().getAssignedUserId()).isEqualTo(agent2.getId());
        assertThat(crmContact(second).getOwnerId()).isEqualTo(agent2.getId());
        assertThat(deal(crmContact(second)).getOwnerId()).isEqualTo(agent2.getId());
    }

    @Test
    void closedByTheChatbotLeavesTheLeadUnowned() throws Exception {
        routing(false, agent.getId());
        String id = publish(createChatbot("Lead then say", flow(List.of(
                node("c", "add_to_crm", "Lead", Map.of()),
                node("m", "send_message", "Bye", textMessage("Thanks"))), List.of(edge("c", "next", "m")))));
        WaWebChat chat = unheld(VEENU);
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            await(() -> runRepo.findById(run.getId()).orElseThrow().getStatus() == ChatbotRunStatus.COMPLETED);
        }
        await(() -> chats.findById(chat.getId()).orElseThrow().getStatus() == ChatStatus.CLOSED);
        CrmContact lead = crmContact(chat);
        assertThat(lead.getOwnerId()).isNull();
        assertThat(deal(lead).getOwnerId()).isNull();
    }

    @Test
    void unassignDoesNotClearTheOwner() throws Exception {
        routing(false, agent.getId());
        WaWebChat chat = unheld(VEENU);
        start(leadThenHandOver(), chat);
        assertThat(crmContact(chat).getOwnerId()).isEqualTo(agent.getId());
        assignment.unassign(access.load(manager), chats.findById(chat.getId()).orElseThrow());
        assertThat(chats.findById(chat.getId()).orElseThrow().getAssignedUserId()).isNull();
        assertThat(crmContact(chat).getOwnerId()).isEqualTo(agent.getId());
        assertThat(deal(crmContact(chat)).getOwnerId()).isEqualTo(agent.getId());
    }
}
