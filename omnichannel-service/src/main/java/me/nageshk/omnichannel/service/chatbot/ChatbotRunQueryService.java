package me.nageshk.omnichannel.service.chatbot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.automation.AutomationRef;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.RunPage;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.RunView;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.TestRequest;
import me.nageshk.omnichannel.service.chatbot.node.Problem;
import me.nageshk.omnichannel.service.chatbot.node.Severity;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Runs as the Runs page sees them (spec §8.1), the test start (spec §7) and stopping a run. */
@Service
public class ChatbotRunQueryService {

    public static final String RUNNING = "CHATBOT_RUNNING";

    private final ChatbotService chatbots;
    private final ChatbotRunService runs;
    private final ChatbotRunRepository runRepo;
    private final WaWebChatRepository chats;
    private final ContactRepository contacts;
    private final PhoneNumberRepository numbers;
    private final UserRepository users;
    private final MongoTemplate mongo;

    public ChatbotRunQueryService(ChatbotService chatbots, ChatbotRunService runs, ChatbotRunRepository runRepo,
            WaWebChatRepository chats, ContactRepository contacts, PhoneNumberRepository numbers, UserRepository users,
            MongoTemplate mongo) {
        this.chatbots = chatbots;
        this.runs = runs;
        this.runRepo = runRepo;
        this.chats = chats;
        this.contacts = contacts;
        this.numbers = numbers;
        this.users = users;
        this.mongo = mongo;
    }

    /** Starts a test run on the contact's chat (spec §7); refuses when another chatbot's run is active. */
    public RunView test(AuthenticatedUser me, String workspaceId, String chatbotId, TestRequest r) {
        Chatbot bot = chatbots.require(workspaceId, chatbotId);
        Map<String, String> errors = new java.util.LinkedHashMap<>();
        for (Problem p : chatbots.problems(bot)) {
            if (p.severity() == Severity.ERROR) {
                errors.put(p.nodeId() == null ? "flow." + p.field() : "nodes." + p.nodeId() + "." + p.field(), p.message());
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        if (r == null || r.contactId() == null || r.contactId().isBlank()) throw new ValidationException("contactId", "is required");
        Contact contact = contacts.findById(r.contactId()).filter(c -> workspaceId.equals(c.getWorkspaceId()))
                .orElseThrow(() -> new ValidationException("contactId", "the contact no longer exists"));
        WaWebChat chat = null;
        if (r.phoneNumberId() != null && !r.phoneNumberId().isBlank()) {
            chat = chats.findFirstByPhoneNumberIdAndContactId(r.phoneNumberId(), contact.getId())
                    .orElseThrow(() -> new ValidationException("contactId", "no chat with this contact on the number"));
        }
        Optional<ChatbotRun> active = runs.activeForContact(workspaceId, contact.getId());
        if (active.isPresent()) {
            ChatbotRun other = active.get();
            boolean sameChatbot = chatbotId.equals(other.getChatbotId());
            if (!sameChatbot && !Boolean.TRUE.equals(r.stopRunning())) {
                throw new CodedException(HttpStatus.CONFLICT, RUNNING, null,
                        "Chatbot “" + other.getChatbotName() + "” is running on this chat", Map.of("chatbotName", other.getChatbotName()));
            }
            runs.stop(other, sameChatbot ? "replaced by a new test" : "stopped for a test of " + bot.getName(), me.userId());
        }
        ChatbotRunService.StartResult started = runs.start(workspaceId, chatbotId, contact, chat, StartedBy.test(me.userId()),
                null, IfRunning.SKIP, true);
        if (!started.started()) throw new CodedException(HttpStatus.CONFLICT, RUNNING, null, started.reason(), Map.of());
        return view(started.run());
    }

    public RunPage runs(String workspaceId, String chatbotId, ChatbotRunStatus status, Instant from, Instant to, int page, int size) {
        chatbots.require(workspaceId, chatbotId);
        Criteria c = Criteria.where("workspaceId").is(workspaceId).and("chatbotId").is(chatbotId);
        if (status != null) c = c.and("status").is(status);
        if (from != null || to != null) {
            Criteria created = Criteria.where("createdAt");
            if (from != null) created = created.gte(from);
            if (to != null) created = created.lte(to);
            c = new Criteria().andOperator(c, created);
        }
        long total = mongo.count(new Query(c), ChatbotRun.class);
        int pageSize = Math.max(1, Math.min(size, 200));
        List<ChatbotRun> items = mongo.find(new Query(c).with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip((long) Math.max(0, page) * pageSize).limit(pageSize), ChatbotRun.class);
        List<RunView> views = new ArrayList<>();
        for (ChatbotRun r : items) views.add(view(r));
        return new RunPage(views, total);
    }

    public RunView run(String workspaceId, String chatbotId, String runId) {
        return view(require(workspaceId, chatbotId, runId));
    }

    public void stop(AuthenticatedUser me, String workspaceId, String chatbotId, String runId) {
        ChatbotRun r = require(workspaceId, chatbotId, runId);
        if (!r.getStatus().active()) {
            throw new CodedException(HttpStatus.CONFLICT, "RUN_ENDED", null, "The run has already ended", Map.of());
        }
        runs.stop(r, "stopped by " + me.getUsername(), me.userId());
    }

    private ChatbotRun require(String workspaceId, String chatbotId, String runId) {
        return runRepo.findById(runId).filter(r -> workspaceId.equals(r.getWorkspaceId()) && chatbotId.equals(r.getChatbotId()))
                .orElseThrow(() -> new NotFoundException("run not found"));
    }

    public RunView view(ChatbotRun r) {
        Map<String, String> names = new HashMap<>();
        RunView.ChatRef chat = r.getChatId() == null ? null
                : chats.findById(r.getChatId()).map(c -> new RunView.ChatRef(c.getId(), c.getRemoteId())).orElse(new RunView.ChatRef(r.getChatId(), null));
        RunView.ContactRef contact = r.getContactId() == null ? null
                : contacts.findById(r.getContactId()).map(c -> new RunView.ContactRef(c.getId(), c.getName(), c.getPhone()))
                        .orElse(new RunView.ContactRef(r.getContactId(), null, null));
        RunView.NumberRef number = r.getPhoneNumberId() == null ? null
                : numbers.findById(r.getPhoneNumberId()).map(n -> new RunView.NumberRef(n.getId(), n.getLabel()))
                        .orElse(new RunView.NumberRef(r.getPhoneNumberId(), null));
        AutomationRef stoppedBy = r.getStoppedBy() == null ? null
                : new AutomationRef(r.getStoppedBy(), names.computeIfAbsent(r.getStoppedBy(), id -> users.findById(id).map(User::getName).orElse(null)));
        return new RunView(r.getId(), r.getChatbotId(), r.getChatbotName(), r.getVersionNumber(), chat, contact, number,
                r.getStatus(), r.getCurrentNodeId(), r.getCurrentNodeName(), r.getWaiting(), r.isTest(), r.getStartedBy(),
                r.getSteps() == null ? List.of() : r.getSteps(), r.getVariables(), r.getEndReason(), stoppedBy,
                r.getCreatedAt(), r.getFinishedAt());
    }
}
