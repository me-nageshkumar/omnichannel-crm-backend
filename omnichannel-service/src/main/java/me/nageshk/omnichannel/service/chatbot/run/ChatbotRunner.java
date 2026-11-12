package me.nageshk.omnichannel.service.chatbot.run;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.chatbot.ChatbotStep;
import me.nageshk.omnichannel.db.chatbot.ChatbotVersionRepository;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.node.NodeExecutor;
import me.nageshk.omnichannel.service.chatbot.node.NodeExecutors;
import me.nageshk.omnichannel.service.chatbot.node.RunContext;
import me.nageshk.omnichannel.service.chatbot.node.StepResult;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Walks a run through its flow (spec §5.2): node after node until one waits, ends, jumps or fails. The caller holds
 * the claim (a fresh insert, or {@link RunClaims}); every write renews the lease. A node that throws fails the run; a
 * send that never reached the extension is retried after a short wait.
 */
@Component
public class ChatbotRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatbotRunner.class);

    private final ChatbotRepository chatbots;
    private final ChatbotVersionRepository versions;
    private final ChatbotRunRepository runs;
    private final WaWebChatRepository chats;
    private final ContactRepository contacts;
    private final FlowParser parser;
    private final NodeExecutors executors;
    private final ChatbotSender sender;
    private final ChatPointer pointer;
    private final RunClaims claims;
    private final ChatbotRunService service;
    private final ThreadPoolTaskExecutor pool;
    private final ApplicationEventPublisher publisher;
    private final OmnichannelProperties props;
    private final Clock clock;

    public ChatbotRunner(ChatbotRepository chatbots, ChatbotVersionRepository versions, ChatbotRunRepository runs,
            WaWebChatRepository chats, ContactRepository contacts, FlowParser parser, NodeExecutors executors,
            ChatbotSender sender, ChatPointer pointer, RunClaims claims, @Lazy ChatbotRunService service,
            @Lazy ThreadPoolTaskExecutor chatbotExecutor, ApplicationEventPublisher publisher,
            OmnichannelProperties props, Clock clock) {
        this.chatbots = chatbots;
        this.versions = versions;
        this.runs = runs;
        this.chats = chats;
        this.contacts = contacts;
        this.parser = parser;
        this.executors = executors;
        this.sender = sender;
        this.pointer = pointer;
        this.claims = claims;
        this.service = service;
        this.pool = chatbotExecutor;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
    }

    /** Runs the claimed run until it waits, ends or must retry; {@code answer} is delivered to the current node first. */
    public ChatbotRun proceed(ChatbotRun run, TaskAnswer answer) {
        try {
            return walk(run, answer);
        } catch (RuntimeException e) {
            log.warn("chatbot run {} failed unexpectedly", run.getId(), e);
            return finish(run, ChatbotRunStatus.FAILED, "unexpected error: " + e.getMessage());
        }
    }

    private ChatbotRun walk(ChatbotRun run, TaskAnswer answer) {
        Chatbot chatbot = chatbots.findById(run.getChatbotId()).orElse(null);
        if (chatbot == null) return finish(run, ChatbotRunStatus.FAILED, "the chatbot no longer exists");
        Map<String, Object> flowMap = versions.findByChatbotIdAndVersionNumber(chatbot.getId(), run.getVersionNumber())
                .map(v -> v.getFlow()).orElse(chatbot.getFlow());
        Flow flow = parser.parse(flowMap);
        ChatbotSettings settings = chatbot.getSettings() == null ? ChatbotSettings.defaults() : chatbot.getSettings();
        WaWebChat chat = run.getChatId() == null ? null : chats.findById(run.getChatId()).orElse(null);
        Contact contact = run.getContactId() == null ? null : contacts.findById(run.getContactId()).orElse(null);
        RunContext ctx = new RunContext(run, chatbot, flow, settings, chat, contact, clock.instant());
        ctx.sender(sender);
        if (run.getVariables() == null) run.setVariables(new LinkedHashMap<>());
        if (run.getSteps() == null) run.setSteps(new ArrayList<>());
        boolean answered = answer != null && answer.kind() != me.nageshk.omnichannel.db.chatbot.TaskKind.TIMER;

        while (true) {
            FlowNode node;
            if (run.getCurrentNodeId() == null) {
                Optional<String> entry = flow.entryCandidates().stream().findFirst();
                if (entry.isEmpty()) return finish(run, ChatbotRunStatus.FAILED, "the flow has no entry node");
                node = flow.node(entry.get()).orElseThrow();
                run.setCurrentNodeId(node.id());
                run.setCurrentNodeName(node.name());
            } else {
                Optional<FlowNode> current = flow.node(run.getCurrentNodeId());
                if (current.isEmpty()) return finish(run, ChatbotRunStatus.FAILED, "the node " + run.getCurrentNodeName() + " no longer exists");
                node = current.get();
            }
            NodeExecutor executor = executors.of(node.type());
            Instant startedAt = clock.instant();
            StepResult result;
            try {
                result = answer != null ? executor.onAnswer(ctx, node, answer) : executor.run(ctx, node);
            } catch (RetryLater e) {
                return scheduleRetry(run, e.getMessage());
            } catch (StopRun e) {
                return finish(run, ChatbotRunStatus.STOPPED, e.getMessage());
            } catch (NodeFailed e) {
                recordFailed(run, node, startedAt, e.getMessage());
                return finish(run, ChatbotRunStatus.FAILED, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("node {} of run {} threw", node.name(), run.getId(), e);
                recordFailed(run, node, startedAt, e.getMessage());
                return finish(run, ChatbotRunStatus.FAILED, node.name() + ": " + e.getMessage());
            }
            answer = null;
            run.setSendAttempts(0);
            switch (result.kind()) {
                case WAIT -> {
                    recordWaiting(run, node, startedAt, result);
                    run.setWaiting(result.waiting());
                    run.setStatus(ChatbotRunStatus.WAITING);
                    run.setLeaseUntil(null);
                    if (answered || run.getEndAt() == null || result.waiting().kind() == me.nageshk.omnichannel.db.chatbot.TaskKind.TIMER
                            || run.getRemindAt() == null && !run.isReminderSent()) {
                        ChatbotTimers.onWait(run, settings, clock.instant());
                    }
                    ChatbotRun saved = save(run);
                    pointer.set(saved.getChatId(), saved);
                    publisher.publishEvent(new InboxEvents.ChatbotRunChanged(saved));
                    return saved;
                }
                case NEXT -> {
                    completeStep(run, node, startedAt, result);
                    run.setInvalidReplies(0);
                    if (result.result() != null && node.name() != null) run.getVariables().put(node.name(), result.result());
                    if (run.getSteps().size() >= props.chatbot().maxSteps()) return finish(run, ChatbotRunStatus.FAILED, "too many steps");
                    Optional<String> target = flow.target(node.id(), result.output());
                    if (target.isEmpty()) return finish(run, ChatbotRunStatus.COMPLETED, node.name() + ": no edge from " + result.output());
                    FlowNode next = flow.node(target.get()).orElseThrow();
                    run.setCurrentNodeId(next.id());
                    run.setCurrentNodeName(next.name());
                    run.setWaiting(null);
                    run.setLeaseUntil(claims.lease());
                    run = save(run);
                }
                case END -> {
                    completeStep(run, node, startedAt, result);
                    return finish(run, ChatbotRunStatus.COMPLETED, result.summary());
                }
                case JUMP -> {
                    completeStep(run, node, startedAt, result);
                    return jump(run, result.jumpToChatbotId(), ctx);
                }
                case FAIL -> {
                    recordFailed(run, node, startedAt, result.error());
                    return finish(run, ChatbotRunStatus.FAILED, result.error());
                }
            }
        }
    }

    private ChatbotRun jump(ChatbotRun run, String chatbotId, RunContext ctx) {
        ChatbotRun ended = finish(run, ChatbotRunStatus.JUMPED, "jumped to another chatbot");
        if (run.getJumps() + 1 > props.chatbot().maxJumps()) {
            return finish(ended, ChatbotRunStatus.FAILED, "too many jumps");
        }
        Chatbot target = chatbots.findById(chatbotId).filter(c -> run.getWorkspaceId().equals(c.getWorkspaceId())).orElse(null);
        if (target == null) return finish(ended, ChatbotRunStatus.FAILED, "the chatbot to jump to no longer exists");
        if (target.getStatus() != me.nageshk.omnichannel.db.chatbot.ChatbotStatus.PUBLISHED && !run.isTest()) {
            return finish(ended, ChatbotRunStatus.FAILED, "the chatbot “" + target.getName() + "” is not published");
        }
        Contact contact = ctx.contact();
        if (contact == null) return finish(ended, ChatbotRunStatus.FAILED, "the contact no longer exists");
        ChatbotRunService.StartResult next = service.start(target, contact, ctx.chat(), run.getStartedBy(), null,
                me.nageshk.omnichannel.db.chatbot.IfRunning.SKIP, run.isTest(), run.getVariables(), run.getJumps() + 1);
        if (!next.started()) {
            log.warn("jump from run {} to {} did not start: {}", run.getId(), chatbotId, next.reason());
        }
        return ended;
    }

    private ChatbotRun scheduleRetry(ChatbotRun run, String why) {
        int attempts = run.getSendAttempts() + 1;
        if (attempts > props.chatbot().sendAttempts()) {
            return finish(run, ChatbotRunStatus.FAILED, "could not send: " + why);
        }
        run.setSendAttempts(attempts);
        run.setRetryAt(clock.instant().plus(props.chatbot().sendRetryDelay()));
        run.setLeaseUntil(claims.lease().plus(props.chatbot().sendRetryDelay()));
        run.setEndReason(null);
        return save(run);
    }

    /** Retries every run whose retry is due; the future completes when all of them have proceeded. */
    public CompletableFuture<Void> retryDue() {
        List<CompletableFuture<Void>> all = new ArrayList<>();
        for (ChatbotRun due : claims.retriesDue()) {
            claims.claimForRetry(due.getId()).ifPresent(r -> all.add(CompletableFuture.runAsync(() -> proceed(r, null), pool)));
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]));
    }

    /** Ends the run with a final status, clears the chat pointer and announces the change. */
    public ChatbotRun finish(ChatbotRun run, ChatbotRunStatus status, String reason) {
        run.setStatus(status);
        run.setEndReason(reason);
        run.setWaiting(null);
        run.setRetryAt(null);
        run.setLeaseUntil(null);
        run.setFinishedAt(clock.instant());
        ChatbotRun saved = save(run);
        pointer.clear(saved.getChatId(), saved.getId());
        publisher.publishEvent(new InboxEvents.ChatbotRunChanged(saved));
        return saved;
    }

    private ChatbotRun save(ChatbotRun run) {
        return runs.save(run);
    }

    // ---- steps ----

    private static void recordWaiting(ChatbotRun run, FlowNode node, Instant startedAt, StepResult r) {
        List<ChatbotStep> steps = run.getSteps();
        int last = steps.size() - 1;
        if (last >= 0 && steps.get(last).outcome() == ChatbotStep.Outcome.WAITING && steps.get(last).nodeId().equals(node.id())) {
            // a re-ask: the same wait goes on
            ChatbotStep s = steps.get(last);
            steps.set(last, new ChatbotStep(s.index(), s.nodeId(), s.nodeName(), s.type(), s.startedAt(), null, s.outcome(), null,
                    r.summary() != null ? r.summary() : s.summary(), null, null, r.waiting()));
            return;
        }
        steps.add(new ChatbotStep(steps.size(), node.id(), node.name(), node.type().code(), startedAt, null,
                ChatbotStep.Outcome.WAITING, null, r.summary(), null, null, r.waiting()));
    }

    private void completeStep(ChatbotRun run, FlowNode node, Instant startedAt, StepResult r) {
        List<ChatbotStep> steps = run.getSteps();
        int last = steps.size() - 1;
        Instant now = clock.instant();
        if (last >= 0 && steps.get(last).outcome() == ChatbotStep.Outcome.WAITING && steps.get(last).nodeId().equals(node.id())) {
            ChatbotStep s = steps.get(last);
            steps.set(last, new ChatbotStep(s.index(), s.nodeId(), s.nodeName(), s.type(), s.startedAt(), now,
                    ChatbotStep.Outcome.DONE, r.output(), r.summary() != null ? r.summary() : s.summary(), null, r.result(), s.waitedFor()));
            return;
        }
        steps.add(new ChatbotStep(steps.size(), node.id(), node.name(), node.type().code(), startedAt, now,
                ChatbotStep.Outcome.DONE, r.output(), r.summary(), null, r.result(), null));
    }

    private void recordFailed(ChatbotRun run, FlowNode node, Instant startedAt, String error) {
        List<ChatbotStep> steps = run.getSteps();
        int last = steps.size() - 1;
        Instant now = clock.instant();
        if (last >= 0 && steps.get(last).outcome() == ChatbotStep.Outcome.WAITING && steps.get(last).nodeId().equals(node.id())) {
            ChatbotStep s = steps.get(last);
            steps.set(last, new ChatbotStep(s.index(), s.nodeId(), s.nodeName(), s.type(), s.startedAt(), now,
                    ChatbotStep.Outcome.FAILED, null, s.summary(), error, null, s.waitedFor()));
            return;
        }
        steps.add(new ChatbotStep(steps.size(), node.id(), node.name(), node.type().code(), startedAt, now,
                ChatbotStep.Outcome.FAILED, null, null, error, null, null));
    }
}
