package me.nageshk.omnichannel.service.chatbot.report;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.ai.api.AiField;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.service.chatbot.ChatbotService;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AiNodeSettings;
import me.nageshk.omnichannel.service.chatbot.node.NodeExecutors;
import me.nageshk.omnichannel.service.chatbot.node.ValidationContext;
import me.nageshk.omnichannel.service.chatbot.report.ChatbotReport.ChatTotals;
import me.nageshk.omnichannel.service.chatbot.report.ChatbotReport.FieldCount;
import me.nageshk.omnichannel.service.chatbot.report.ChatbotReport.ProgressRow;
import me.nageshk.omnichannel.service.chatbot.report.ChatbotReport.StepRow;
import me.nageshk.omnichannel.service.chatbot.report.ChatbotReport.Totals;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/**
 * The chatbot report (lead-owner report spec §6.4): two aggregations over the chatbot's runs in a range, one counting
 * by status and where the chat went, one reading each run's steps; ordering and labels are done here.
 */
@Service
public class ChatbotReportService {

    public static final Duration DEFAULT_RANGE = Duration.ofDays(30);
    public static final Duration MAX_RANGE = Duration.ofDays(92);
    private static final int LABEL_MAX = 40;
    private static final String RUNS = "chatbotRuns";

    private final ChatbotService chatbots;
    private final FlowParser parser;
    private final NodeExecutors executors;
    private final MongoTemplate mongo;
    private final Clock clock;

    public ChatbotReportService(ChatbotService chatbots, FlowParser parser, NodeExecutors executors, MongoTemplate mongo,
            Clock clock) {
        this.chatbots = chatbots;
        this.parser = parser;
        this.executors = executors;
        this.mongo = mongo;
        this.clock = clock;
    }

    /**
     * The runs the report counts: this chatbot's, created in the range (default the last 30 days, at most 92), not
     * test runs, and on the number when one is given. The runs list behind each count uses the same criteria.
     */
    public Criteria criteria(String workspaceId, String chatbotId, Instant from, Instant to, String phoneNumberId) {
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? end.minus(DEFAULT_RANGE) : from;
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new ValidationException("from", "The range can be at most 92 days");
        }
        Criteria c = Criteria.where("workspaceId").is(workspaceId).and("chatbotId").is(chatbotId).and("test").ne(true);
        List<Criteria> all = new ArrayList<>();
        all.add(c);
        all.add(Criteria.where("createdAt").gte(Date.from(start)).lte(Date.from(end)));
        if (phoneNumberId != null && !phoneNumberId.isBlank()) all.add(Criteria.where("phoneNumberId").is(phoneNumberId));
        return new Criteria().andOperator(all);
    }

    public ChatbotReport report(String workspaceId, String chatbotId, Instant from, Instant to, String phoneNumberId) {
        Chatbot bot = chatbots.require(workspaceId, chatbotId);
        Criteria criteria = criteria(workspaceId, chatbotId, from, to, phoneNumberId);
        Totals totals = totals(criteria);
        List<Document> runs = mongo.aggregate(Aggregation.newAggregation(Aggregation.match(criteria),
                op(new Document("$sort", new Document("createdAt", 1))), op(new Document("$project", new Document()
                        .append("status", 1)
                        .append("steps", new Document("$map", new Document("input", new Document("$ifNull", List.of("$steps", List.of())))
                                .append("as", "s").append("in", new Document("i", "$$s.nodeId").append("n", "$$s.nodeName"))))
                        .append("lastKeys", new Document("$let", new Document("vars", new Document("last",
                                new Document("$arrayElemAt", List.of(new Document("$ifNull", List.of("$steps", List.of())), -1))))
                                .append("in", new Document("$map", new Document("input", new Document("$objectToArray",
                                        new Document("$ifNull", List.of("$$last.progress", new Document()))))
                                        .append("in", "$$this.k")))))))),
                RUNS, Document.class).getMappedResults();
        return build(bot, totals, runs);
    }

    private Totals totals(Criteria criteria) {
        List<Document> groups = mongo.aggregate(Aggregation.newAggregation(Aggregation.match(criteria), op(new Document("$group",
                new Document("_id", new Document("status", "$status").append("chat", "$ending.chat")
                        .append("agent", new Document("$gt", java.util.Arrays.asList("$ending.agentUserId", null))))
                        .append("n", new Document("$sum", 1))))), RUNS, Document.class).getMappedResults();
        long started = 0, running = 0, completed = 0, silent = 0, stopped = 0, failed = 0, handed = 0, jumped = 0;
        long toAgent = 0, toQueue = 0, closed = 0, left = 0, notRecorded = 0;
        for (Document g : groups) {
            Document id = g.get("_id", Document.class);
            long n = ((Number) g.get("n")).longValue();
            ChatbotRunStatus status = ChatbotRunStatus.valueOf(id.getString("status"));
            started += n;
            switch (status) {
                case RUNNING, WAITING -> running += n;
                case COMPLETED -> completed += n;
                case ENDED -> silent += n;
                case STOPPED -> stopped += n;
                case FAILED -> failed += n;
                case HANDED_OVER -> handed += n;
                case JUMPED -> jumped += n;
            }
            if (status.active()) continue;
            String chat = id.getString("chat");
            if (chat == null) notRecorded += n;
            else if (Boolean.TRUE.equals(id.getBoolean("agent"))) toAgent += n;
            else switch (chat) {
                case "HANDED_OVER", "ROUTED" -> toQueue += n;
                case "CLOSED" -> closed += n;
                default -> left += n;
            }
        }
        return new Totals(started, running, completed, silent, stopped, failed, handed, jumped,
                new ChatTotals(toAgent, toQueue, closed, left, notRecorded));
    }

    private ChatbotReport build(Chatbot bot, Totals totals, List<Document> runs) {
        Flow flow = parseOrEmpty(bot);
        List<FlowNode> ordered = order(flow, bot.getId(), bot.getWorkspaceId());
        Map<String, Long> reached = new HashMap<>();
        Map<String, Map<String, Long>> endedHere = new HashMap<>();
        Map<String, String> lastName = new LinkedHashMap<>();
        Map<String, List<List<String>>> endedKeys = new HashMap<>();
        for (Document r : runs) {
            List<Document> steps = r.getList("steps", Document.class, List.of());
            Set<String> seen = new HashSet<>();
            String last = null;
            for (Document s : steps) {
                String nodeId = s.getString("i");
                if (nodeId == null) continue;
                last = nodeId;
                if (s.getString("n") != null) lastName.put(nodeId, s.getString("n"));
                else lastName.putIfAbsent(nodeId, nodeId);
                if (seen.add(nodeId)) reached.merge(nodeId, 1L, Long::sum);
            }
            ChatbotRunStatus status = ChatbotRunStatus.valueOf(r.getString("status"));
            if (last != null && !status.active()) {
                endedHere.computeIfAbsent(last, k -> new LinkedHashMap<>()).merge(status.name(), 1L, Long::sum);
                endedKeys.computeIfAbsent(last, k -> new ArrayList<>()).add(r.getList("lastKeys", String.class, List.of()));
            }
        }
        List<StepRow> rows = new ArrayList<>();
        Set<String> current = new HashSet<>();
        for (FlowNode n : ordered) {
            current.add(n.id());
            rows.add(new StepRow(n.id(), n.name() == null || n.name().isBlank() ? n.id() : n.name(), n.type().code(), false,
                    reached.getOrDefault(n.id(), 0L), endedHere.getOrDefault(n.id(), Map.of())));
        }
        Set<String> gone = new LinkedHashSet<>(lastName.keySet());
        for (String id : gone) {
            if (current.contains(id)) continue;
            rows.add(new StepRow(id, lastName.get(id), null, true, reached.getOrDefault(id, 0L),
                    endedHere.getOrDefault(id, Map.of())));
        }
        List<ProgressRow> progress = new ArrayList<>();
        for (FlowNode n : ordered) {
            if (n.type() != NodeType.AI) continue;
            AiNodeSettings s;
            try {
                s = parser.settings(n, AiNodeSettings.class);
            } catch (RuntimeException e) {
                continue;
            }
            if (s.modeOrDefault() != AiNodeSettings.Mode.CONVERSATION || s.collectOrEmpty().isEmpty()) continue;
            progress.add(progressRow(n.id(), s.collectOrEmpty(), endedKeys.getOrDefault(n.id(), List.of())));
        }
        return new ChatbotReport(totals, rows, progress);
    }

    private static ProgressRow progressRow(String nodeId, List<AiField> collect, List<List<String>> runs) {
        List<String> keys = collect.stream().map(AiField::key).toList();
        long[] given = new long[keys.size()];
        long[] byCount = new long[keys.size() + 1];
        for (List<String> have : runs) {
            int k = 0;
            for (int i = 0; i < keys.size(); i++) {
                if (have.contains(keys.get(i))) {
                    given[i]++;
                    k++;
                }
            }
            byCount[k]++;
        }
        List<FieldCount> fields = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            AiField f = collect.get(i);
            String d = f.description();
            String label = d == null || d.isBlank() ? f.key() : d.length() > LABEL_MAX ? d.substring(0, LABEL_MAX) : d;
            fields.add(new FieldCount(f.key(), label, given[i]));
        }
        List<Long> counts = new ArrayList<>();
        for (long c : byCount) counts.add(c);
        return new ProgressRow(nodeId, fields, counts);
    }

    private Flow parseOrEmpty(Chatbot bot) {
        try {
            return parser.parse(bot.getFlow());
        } catch (RuntimeException e) {
            return new Flow(List.of(), List.of());
        }
    }

    /** Breadth-first from the entry along each node's outputs in their own order; nodes not reached follow in canvas order. */
    private List<FlowNode> order(Flow flow, String chatbotId, String workspaceId) {
        List<FlowNode> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String entry : flow.entryCandidates()) {
            queue.add(entry);
            while (!queue.isEmpty()) {
                String id = queue.poll();
                if (!seen.add(id)) continue;
                FlowNode node = flow.node(id).orElse(null);
                if (node == null) continue;
                out.add(node);
                List<String> outputs;
                try {
                    outputs = executors.of(node.type()).outputs(node, new ValidationContext(workspaceId, chatbotId, false));
                } catch (RuntimeException e) {
                    outputs = flow.from(id).stream().map(e2 -> e2.output()).toList();
                }
                for (String o : outputs) flow.target(id, o).ifPresent(queue::add);
                for (var e : flow.from(id)) if (!outputs.contains(e.output())) queue.add(e.target());
            }
        }
        for (FlowNode n : flow.nodes()) if (seen.add(n.id())) out.add(n);
        return out;
    }

    private static AggregationOperation op(Document stage) {
        return context -> stage;
    }
}
