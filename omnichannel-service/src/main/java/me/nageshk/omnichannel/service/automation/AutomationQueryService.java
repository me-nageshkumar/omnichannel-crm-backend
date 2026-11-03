package me.nageshk.omnichannel.service.automation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationEventRecord;
import me.nageshk.omnichannel.db.automation.AutomationRuleRepository;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.AutomationRunRepository;
import me.nageshk.omnichannel.db.automation.RunStep;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Reads the runs of a rule and the event history of a brand. */
@Service
public class AutomationQueryService {

    private final MongoTemplate mongo;
    private final AutomationRuleRepository rules;
    private final AutomationRunRepository runs;

    public AutomationQueryService(MongoTemplate mongo, AutomationRuleRepository rules, AutomationRunRepository runs) {
        this.mongo = mongo;
        this.rules = rules;
        this.runs = runs;
    }

    /** The runs of the rule, test runs included, newest first. */
    public AutomationPage<RunView> runs(String workspaceId, String ruleId, int page) {
        rules.findByIdAndWorkspaceId(ruleId, workspaceId).orElseThrow(() -> new NotFoundException("rule not found"));
        Query filter = new Query(Criteria.where("ruleId").is(ruleId));
        long total = mongo.count(filter, AutomationRun.class);
        List<AutomationRun> found = mongo.find(paged(filter, page, Sort.by(Sort.Direction.DESC, "startedAt", "_id")),
                AutomationRun.class);
        List<RunView> items = new ArrayList<>();
        for (AutomationRun run : found) items.add(view(run));
        return new AutomationPage<>(items, total, Math.max(page, 0));
    }

    /** The brand's finished events, newest first, each with its runs. */
    public AutomationPage<EventRecordView> events(String workspaceId, AutomationEventKind kind, String ruleId,
            String contactId, Instant from, Instant to, int page) {
        Criteria c = Criteria.where("workspaceId").is(workspaceId);
        if (kind != null) c.and("kind").is(kind);
        if (ruleId != null && !ruleId.isBlank()) c.and("ruleIds").is(ruleId);
        if (contactId != null && !contactId.isBlank()) c.and("contactId").is(contactId);
        if (from != null && to != null) c.and("createdAt").gte(from).lt(to);
        else if (from != null) c.and("createdAt").gte(from);
        else if (to != null) c.and("createdAt").lt(to);
        Query filter = new Query(c);
        long total = mongo.count(filter, AutomationEventRecord.class);
        List<AutomationEventRecord> found = mongo.find(
                paged(filter, page, Sort.by(Sort.Direction.DESC, "createdAt", "_id")), AutomationEventRecord.class);

        List<String> ids = found.stream().map(AutomationEventRecord::getId).toList();
        Map<String, List<AutomationRun>> byEvent = new HashMap<>();
        for (AutomationRun run : ids.isEmpty() ? List.<AutomationRun>of() : runs.findByEventIdIn(ids)) {
            byEvent.computeIfAbsent(run.getEventId(), k -> new ArrayList<>()).add(run);
        }

        List<EventRecordView> items = new ArrayList<>();
        for (AutomationEventRecord r : found) {
            List<RunView> its = byEvent.getOrDefault(r.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(AutomationRun::getStartedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(AutomationRun::getId))
                    .map(AutomationQueryService::view).toList();
            items.add(view(r, its));
        }
        return new AutomationPage<>(items, total, Math.max(page, 0));
    }

    private static Query paged(Query q, int page, Sort sort) {
        return q.with(PageRequest.of(Math.max(page, 0), AutomationPage.SIZE, sort));
    }

    /** Each step shows the label it was recorded with; a null step (a retried send in flight) is left out. */
    private static RunView view(AutomationRun r) {
        List<StepView> steps = new ArrayList<>();
        for (RunStep s : r.getSteps() == null ? List.<RunStep>of() : r.getSteps()) {
            if (s == null) continue;
            steps.add(new StepView(s.index(), s.type(), AutomationViews.stepSummary(s), s.outcome(), s.reason(),
                    s.at()));
        }
        RunView.Contact contact = r.getContactId() == null ? null
                : new RunView.Contact(r.getContactId(), r.getContactName(), r.getContactPhone());
        RunView.Deal deal = r.getDealId() == null ? null : new RunView.Deal(r.getDealId(), r.getDealTitle());
        return new RunView(r.getId(), r.getEventId(), r.getRuleId(), r.getRuleName(), r.isTest(), r.getStatus(),
                r.getEventKind(), r.getSummary(), contact, deal, r.getStartedAt(), r.getFinishedAt(), steps);
    }

    private static EventRecordView view(AutomationEventRecord r, List<RunView> runViews) {
        var d = r.getData();
        RunView.Contact contact = null;
        if (r.getContactId() != null) {
            var cd = d == null ? null : d.contact();
            contact = new RunView.Contact(r.getContactId(), cd == null ? null : cd.name(),
                    cd == null ? null : cd.phone());
        }
        RunView.Deal deal = null;
        if (r.getDealId() != null) {
            var dd = d == null ? null : d.deal();
            deal = new RunView.Deal(r.getDealId(), dd == null ? null : dd.title());
        }
        return new EventRecordView(r.getId(), r.getKind(), r.getCreatedAt(), r.getFinishedAt(), r.getOutcome(),
                r.getError(), EventSummaries.of(r.getKind(), d), contact, deal, d == null ? null : d.number(),
                r.getTestIds() != null && !r.getTestIds().isEmpty(), runViews);
    }
}
