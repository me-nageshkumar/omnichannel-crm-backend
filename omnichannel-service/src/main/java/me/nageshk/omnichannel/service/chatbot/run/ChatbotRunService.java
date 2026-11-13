package me.nageshk.omnichannel.service.chatbot.run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import java.time.Clock;

/**
 * Starts, answers and stops runs (spec §5.1, §5.3). The insert under the one-active-run-per-contact index is the
 * start's claim; an answer claims the waiting run by {@code (kind, key)}; both hand the run to the runner's pool.
 */
@Service
public class ChatbotRunService {

    /** {@code settled} completes when the run next leaves RUNNING (a wait, an end, or a retry scheduled). */
    public record StartResult(ChatbotRun run, boolean started, String reason, CompletableFuture<ChatbotRun> settled) {
    }

    private final ChatbotRepository chatbots;
    private final ChatbotRunRepository runs;
    private final ContactAttributeRepository attributes;
    private final ContactLabelRepository labels;
    private final PhoneNumberRepository numbers;
    private final RunVariables variables;
    private final RunClaims claims;
    private final ChatPointer pointer;
    private final ChatbotRunner runner;
    private final ThreadPoolTaskExecutor pool;
    private final Clock clock;

    public ChatbotRunService(ChatbotRepository chatbots, ChatbotRunRepository runs, ContactAttributeRepository attributes,
            ContactLabelRepository labels, PhoneNumberRepository numbers, RunVariables variables, RunClaims claims,
            ChatPointer pointer, ChatbotRunner runner, ThreadPoolTaskExecutor chatbotExecutor, Clock clock) {
        this.chatbots = chatbots;
        this.runs = runs;
        this.attributes = attributes;
        this.labels = labels;
        this.numbers = numbers;
        this.variables = variables;
        this.claims = claims;
        this.pointer = pointer;
        this.runner = runner;
        this.pool = chatbotExecutor;
        this.clock = clock;
    }

    /**
     * Starts the chatbot for the contact (spec §3.1, §5.1). A draft runs only when {@code test}. Returns at once;
     * the run proceeds on the pool.
     */
    public StartResult start(String workspaceId, String chatbotId, Contact contact, WaWebChat chatOrNull, StartedBy by,
            EventData event, IfRunning ifRunning, boolean test) {
        Chatbot chatbot = chatbots.findById(chatbotId).filter(c -> workspaceId.equals(c.getWorkspaceId()))
                .orElseThrow(() -> new NotFoundException("chatbot not found"));
        if (chatbot.getStatus() != ChatbotStatus.PUBLISHED && !test) {
            return new StartResult(null, false, "Chatbot “" + chatbot.getName() + "” is not published", null);
        }
        return start(chatbot, contact, chatOrNull, by, event, ifRunning, test, null, 0);
    }

    StartResult start(Chatbot chatbot, Contact contact, WaWebChat chatOrNull, StartedBy by, EventData event,
            IfRunning ifRunning, boolean test, java.util.Map<String, Object> carriedVariables, int jumps) {
        Instant now = clock.instant();
        ChatbotRun run = new ChatbotRun();
        run.setCustomerId(chatbot.getCustomerId());
        run.setWorkspaceId(chatbot.getWorkspaceId());
        run.setChatbotId(chatbot.getId());
        run.setChatbotName(chatbot.getName());
        run.setVersionNumber(chatbot.getVersionNumber());
        run.setContactId(contact.getId());
        run.setChatId(chatOrNull == null ? null : chatOrNull.getId());
        run.setPhoneNumberId(chatOrNull == null ? null : chatOrNull.getPhoneNumberId());
        run.setStatus(ChatbotRunStatus.RUNNING);
        run.setTest(test);
        run.setStartedBy(by);
        run.setJumps(jumps);
        run.setLeaseUntil(claims.lease());
        run.setCreatedAt(now);
        run.setSteps(new java.util.ArrayList<>());
        PhoneNumber number = chatOrNull == null ? null : numbers.findById(chatOrNull.getPhoneNumberId()).orElse(null);
        java.util.Map<String, Object> vars = variables.initial(contact, attributes.findByWorkspaceIdOrderByNameKey(chatbot.getWorkspaceId()),
                labels.findByWorkspaceIdOrderByNameKey(chatbot.getWorkspaceId()), chatOrNull, number, event, by, now);
        if (carriedVariables != null) {
            for (var e : carriedVariables.entrySet()) vars.putIfAbsent(e.getKey(), e.getValue());
            // the event that started the first run travels with a jump (spec §4.2); initial() put an empty branch
            if (event == null && carriedVariables.get("event") instanceof java.util.Map<?, ?> carriedEvent && !carriedEvent.isEmpty()) {
                vars.put("event", carriedEvent);
            }
        }
        run.setVariables(vars);
        ChatbotRun inserted;
        try {
            inserted = runs.insert(run);
        } catch (DuplicateKeyException e) {
            Optional<ChatbotRun> other = activeForContact(chatbot.getWorkspaceId(), contact.getId());
            String otherName = other.map(ChatbotRun::getChatbotName).orElse("another chatbot");
            if (ifRunning != IfRunning.REPLACE || other.isEmpty()) {
                return new StartResult(other.orElse(null), false, "Chatbot “" + otherName + "” is running", null);
            }
            stop(other.get(), "replaced by " + chatbot.getName(), null);
            try {
                inserted = runs.insert(run);
            } catch (DuplicateKeyException again) {
                return new StartResult(null, false, "Chatbot “" + otherName + "” is running", null);
            }
        }
        pointer.set(inserted.getChatId(), inserted);
        ChatbotRun handle = inserted;
        CompletableFuture<ChatbotRun> settled = CompletableFuture.supplyAsync(() -> runner.proceed(handle, null), pool);
        return new StartResult(inserted, true, null, settled);
    }

    /** Hands the answer to the run waiting on its (kind, key); false when nothing waits on it (spec §5.3). */
    public boolean answer(TaskAnswer answer) {
        Optional<ChatbotRun> claimed = claims.claimForAnswer(answer.kind(), answer.key());
        if (claimed.isEmpty()) return false;
        ChatbotRun run = claimed.get();
        pool.execute(() -> runner.proceed(run, answer));
        return true;
    }

    /** Like {@link #answer} but proceeds on the calling thread; for the ticker, which is already on the pool. */
    public boolean answerNow(TaskAnswer answer) {
        Optional<ChatbotRun> claimed = claims.claimForAnswer(answer.kind(), answer.key());
        if (claimed.isEmpty()) return false;
        runner.proceed(claimed.get(), answer);
        return true;
    }

    /** Stops the run if it is still active; a run already final is returned as it is. */
    public ChatbotRun stop(ChatbotRun run, String reason, String byUserId) {
        return end(run, ChatbotRunStatus.STOPPED, reason, byUserId);
    }

    public ChatbotRun handOver(ChatbotRun run, String byUserId) {
        return end(run, ChatbotRunStatus.HANDED_OVER, "an agent replied", byUserId);
    }

    /**
     * An external end is a claim, not a save of the caller's copy: the runner may be mid-node with its own copy, and
     * the claim's version bump makes that copy unsaveable (spec anti-goal: a bot and an agent talking over each other).
     */
    private ChatbotRun end(ChatbotRun run, ChatbotRunStatus status, String reason, String byUserId) {
        Optional<ChatbotRun> ended = claims.finish(run.getId(), status, reason, byUserId);
        if (ended.isEmpty()) return runs.findById(run.getId()).orElse(run);
        runner.ended(ended.get());
        return ended.get();
    }

    public Optional<ChatbotRun> activeForChat(String chatId) {
        return runs.findFirstByChatIdAndStatusIn(chatId, List.of(ChatbotRunStatus.RUNNING, ChatbotRunStatus.WAITING));
    }

    public Optional<ChatbotRun> activeForContact(String workspaceId, String contactId) {
        return runs.findFirstByWorkspaceIdAndContactIdAndStatusIn(workspaceId, contactId,
                List.of(ChatbotRunStatus.RUNNING, ChatbotRunStatus.WAITING));
    }
}
