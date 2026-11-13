package me.nageshk.omnichannel.service.automation;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.campaign.RecipientSkips;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import org.springframework.stereotype.Component;

/**
 * Starts a chatbot for the event's contact (chatbot spec §3.1): the event's chat is the run's WhatsApp context when it
 * has one. Waits at most the automation send timeout for the run's first wait, never holds the event lease hostage,
 * and starts a run once per (event, rule): a re-claimed event finds the run it already started.
 */
@Component
public class StartChatbotHandler implements ActionHandler {

    private final ChatbotRepository chatbots;
    private final ChatbotRunRepository runs;
    private final ChatbotRunService runService;
    private final WaWebChatRepository chats;
    private final ContactRepository contacts;
    private final RecipientSkips skips;
    private final OmnichannelProperties props;

    public StartChatbotHandler(ChatbotRepository chatbots, ChatbotRunRepository runs, ChatbotRunService runService,
            WaWebChatRepository chats, ContactRepository contacts, RecipientSkips skips, OmnichannelProperties props) {
        this.chatbots = chatbots;
        this.runs = runs;
        this.runService = runService;
        this.chats = chats;
        this.contacts = contacts;
        this.skips = skips;
        this.props = props;
    }

    @Override
    public AutomationActionType type() {
        return AutomationActionType.START_CHATBOT;
    }

    @Override
    public ActionResult run(ActionContext ctx, AutomationAction action) {
        String contactId = ctx.data() == null || ctx.data().contact() == null ? null : ctx.data().contact().id();
        Contact contact = contactId == null ? null : contacts.findById(contactId).orElse(null);
        if (contact == null) return ActionResult.failed("No contact for this event");
        String chatId = ctx.data().chat() == null ? null : ctx.data().chat().id();
        WaWebChat chat = chatId == null ? null : chats.findById(chatId).orElse(null);
        Chatbot chatbot = action.chatbotId() == null ? null
                : chatbots.findById(action.chatbotId()).filter(c -> ctx.workspaceId().equals(c.getWorkspaceId())).orElse(null);
        if (chatbot == null) return ActionResult.failed("The chatbot no longer exists");
        if (!ctx.test()) {
            Optional<ChatbotRun> already = runs.findFirstByStartedByEventIdAndStartedByRuleIdAndTestFalse(ctx.eventId(), ctx.ruleId());
            if (already.isPresent()) return report(chatbot, already.get());
        }
        if (contact.getDoNotContact() != null || (contact.getPhone() != null && skips.isDoNotContact(ctx.workspaceId(), contact.getPhone()))) {
            return ActionResult.skipped("Contact is marked do-not-contact");
        }
        if (chatbot.getStatus() != ChatbotStatus.PUBLISHED) {
            return ActionResult.failed("Chatbot “" + chatbot.getName() + "” is not published");
        }
        IfRunning ifRunning = action.ifRunning() == null ? IfRunning.SKIP : action.ifRunning();
        StartedBy by = new StartedBy(ctx.ruleId(), ctx.ruleName(), ctx.test() ? null : ctx.eventId(), null);
        ChatbotRunService.StartResult r = runService.start(ctx.workspaceId(), chatbot.getId(), contact, chat, by,
                ctx.data(), ifRunning, false);
        if (!r.started()) return ActionResult.skipped(r.reason());
        ChatbotRun settled;
        try {
            settled = r.settled().get(props.automation().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return ActionResult.done("Started “" + chatbot.getName() + "”: still running");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.interrupted("The app was stopping; the chatbot may have started");
        } catch (ExecutionException e) {
            return ActionResult.failed("The chatbot could not start: " + (e.getCause() == null ? e : e.getCause()).getMessage());
        }
        return report(chatbot, settled);
    }

    private static ActionResult report(Chatbot chatbot, ChatbotRun run) {
        String prefix = "Started “" + chatbot.getName() + "”: ";
        ChatbotRunStatus status = run.getStatus();
        if (status == null) return ActionResult.done(prefix + "still running");
        return switch (status) {
            case RUNNING -> ActionResult.done(prefix + "still running");
            case WAITING -> ActionResult.done(prefix + "waiting at " + run.getCurrentNodeName());
            case COMPLETED -> ActionResult.done(prefix + "completed");
            case JUMPED -> ActionResult.done(prefix + "jumped to another chatbot");
            case FAILED -> ActionResult.failed(run.getEndReason() == null ? "The chatbot failed" : run.getEndReason());
            default -> ActionResult.done(prefix + status.name().toLowerCase().replace('_', ' '));
        };
    }
}
