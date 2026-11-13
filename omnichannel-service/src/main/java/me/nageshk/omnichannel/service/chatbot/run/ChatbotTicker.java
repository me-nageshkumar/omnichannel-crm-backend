package me.nageshk.omnichannel.service.chatbot.run;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.chatbot.Waiting;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.service.chatbot.node.RunContext;
import me.nageshk.omnichannel.service.chatbot.node.SendMessageExecutor;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * The timers (spec §5.6): reminders, inactivity ends, Wait timers (it is the TIMER answer source), send retries and
 * stale runs. Each part runs in its own try/catch; tests call {@link #tick()} by hand.
 */
@Component
public class ChatbotTicker implements TaskAnswerSource {

    private static final Logger log = LoggerFactory.getLogger(ChatbotTicker.class);

    private final MongoTemplate mongo;
    private final ChatbotRepository chatbots;
    private final WaWebChatRepository chats;
    private final ContactRepository contacts;
    private final ChatbotRunService runs;
    private final ChatbotRunner runner;
    private final ChatbotSender sender;
    private final RunClaims claims;
    private final ThreadPoolTaskExecutor pool;
    private final Clock clock;

    public ChatbotTicker(MongoTemplate mongo, ChatbotRepository chatbots, WaWebChatRepository chats,
            ContactRepository contacts, @Lazy ChatbotRunService runs, @Lazy ChatbotRunner runner, ChatbotSender sender,
            RunClaims claims, ThreadPoolTaskExecutor chatbotExecutor, Clock clock) {
        this.mongo = mongo;
        this.chatbots = chatbots;
        this.chats = chats;
        this.contacts = contacts;
        this.runs = runs;
        this.runner = runner;
        this.sender = sender;
        this.claims = claims;
        this.pool = chatbotExecutor;
        this.clock = clock;
    }

    @Override
    public TaskKind kind() {
        return TaskKind.TIMER;
    }

    /** One pass over every timer; the future completes when the work it started has finished. */
    public CompletableFuture<Void> tick() {
        Instant now = clock.instant();
        List<CompletableFuture<Void>> work = new ArrayList<>();
        safely("reminders", () -> reminders(now));
        safely("ends", () -> ends(now));
        safely("timers", () -> work.add(timers(now)));
        safely("retries", () -> work.add(runner.retryDue()));
        safely("stale", () -> stale(now));
        return CompletableFuture.allOf(work.toArray(new CompletableFuture[0]));
    }

    private void reminders(Instant now) {
        Query due = new Query(Criteria.where("status").is(ChatbotRunStatus.WAITING.name()).and("reminderSent").is(false)
                .and("remindAt").lte(now));
        for (ChatbotRun run : mongo.find(due, ChatbotRun.class)) {
            // claim the reminder so two ticks never send it twice
            ChatbotRun claimed = mongo.findAndModify(new Query(Criteria.where("_id").is(run.getId()).and("reminderSent").is(false)),
                    new Update().set("reminderSent", true).unset("remindAt"), FindAndModifyOptions.options().returnNew(true), ChatbotRun.class);
            if (claimed == null) continue;
            RunContext ctx = contextFor(claimed);
            if (ctx == null || ctx.settings().reminderMessage() == null) continue;
            try {
                sender.send(ctx, SendMessageExecutor.textMessage(ctx.render(ctx.settings().reminderMessage())));
            } catch (RuntimeException e) {
                log.warn("reminder for run {} not sent: {}", claimed.getId(), e.getMessage());
            }
        }
    }

    private void ends(Instant now) {
        Query due = new Query(Criteria.where("status").is(ChatbotRunStatus.WAITING.name()).and("endAt").lte(now));
        for (ChatbotRun run : mongo.find(due, ChatbotRun.class)) {
            ChatbotRun claimed = mongo.findAndModify(new Query(Criteria.where("_id").is(run.getId()).and("status").is(ChatbotRunStatus.WAITING.name())),
                    new Update().set("status", ChatbotRunStatus.RUNNING.name()).set("leaseUntil", claims.lease()),
                    FindAndModifyOptions.options().returnNew(true), ChatbotRun.class);
            if (claimed == null) continue;
            RunContext ctx = contextFor(claimed);
            if (ctx != null && ctx.settings().endMessage() != null && !ctx.settings().endMessage().isBlank()) {
                try {
                    sender.send(ctx, SendMessageExecutor.textMessage(ctx.render(ctx.settings().endMessage())));
                } catch (RuntimeException e) {
                    log.warn("end message for run {} not sent: {}", claimed.getId(), e.getMessage());
                }
            }
            int minutes = ctx == null ? 0 : ctx.settings().endAfterMinutes();
            runner.finish(claimed, ChatbotRunStatus.ENDED, "no reply for " + minutes + " minutes");
        }
    }

    private CompletableFuture<Void> timers(Instant now) {
        Query due = new Query(Criteria.where("status").is(ChatbotRunStatus.WAITING.name())
                .and("waiting.kind").is(TaskKind.TIMER.name()).and("waiting.key").lte(Waiting.timerKey(now)));
        List<CompletableFuture<Void>> all = new ArrayList<>();
        for (ChatbotRun run : mongo.find(due, ChatbotRun.class)) {
            String key = run.getWaiting().key();
            all.add(CompletableFuture.runAsync(() -> runs.answerNow(TaskAnswer.timer(key)), pool));
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]));
    }

    private void stale(Instant now) {
        for (ChatbotRun run : claims.stale(now)) {
            runner.finish(run, ChatbotRunStatus.FAILED, "interrupted");
        }
    }

    private RunContext contextFor(ChatbotRun run) {
        Chatbot chatbot = chatbots.findById(run.getChatbotId()).orElse(null);
        ChatbotSettings settings = chatbot == null || chatbot.getSettings() == null ? ChatbotSettings.defaults() : chatbot.getSettings();
        if (chatbot == null) return null;
        RunContext ctx = new RunContext(run, chatbot, null, settings,
                run.getChatId() == null ? null : chats.findById(run.getChatId()).orElse(null),
                run.getContactId() == null ? null : contacts.findById(run.getContactId()).orElse(null), clock.instant());
        ctx.sender(sender);
        return ctx;
    }

    private void safely(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("chatbot tick: {} failed", what, e);
        }
    }
}
