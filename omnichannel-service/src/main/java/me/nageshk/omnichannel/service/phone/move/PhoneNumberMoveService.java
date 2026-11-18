package me.nageshk.omnichannel.service.phone.move;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRuleRepository;
import me.nageshk.omnichannel.db.automation.ConditionNode;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.enablement.Disabled;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.security.phone.DeviceAccessChanged;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportService;
import me.nageshk.omnichannel.service.phone.PhoneNumberService;
import me.nageshk.omnichannel.service.phone.move.PhoneNumberMove.Counts;
import me.nageshk.omnichannel.service.phone.move.PhoneNumberMove.Step;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Moves a number to another brand of its customer (spec §7.2). The old brand keeps what the number had on an archive
 * {@code PhoneNumber}; a brand that held an archive of the number gets its records back on the number. One unfinished
 * move per number; each step is safe to run again and is recorded once done, so {@link #retry} finishes a move that
 * stopped half way. Until then the number stays {@code movingSince}, which its device sees as "try again".
 */
@Service
public class PhoneNumberMoveService {

    private static final Logger log = LoggerFactory.getLogger(PhoneNumberMoveService.class);

    public static final String STOP_REASON = "number moved";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final List<String> ACTIVE_RUNS = List.of(ChatbotRunStatus.RUNNING.name(),
            ChatbotRunStatus.WAITING.name());
    private static final List<String> ACTIVE_IMPORTS = List.of(HistoryImportStatus.QUEUED.name(),
            HistoryImportStatus.RUNNING.name(), HistoryImportStatus.PAUSED.name());

    private final PhoneNumberService phoneNumbers;
    private final PhoneNumberRepository numbers;
    private final WorkspaceRepository workspaces;
    private final TeamRepository teams;
    private final AutomationRuleRepository rules;
    private final PhoneNumberMoveRepository moves;
    private final NumberRecords records;
    private final ChatbotRunService runs;
    private final HistoryImportService historyImports;
    private final MongoTemplate mongo;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PhoneNumberMoveService(PhoneNumberService phoneNumbers, PhoneNumberRepository numbers,
            WorkspaceRepository workspaces, TeamRepository teams, AutomationRuleRepository rules,
            PhoneNumberMoveRepository moves, NumberRecords records, ChatbotRunService runs,
            HistoryImportService historyImports, MongoTemplate mongo, ApplicationEventPublisher events, Clock clock) {
        this.phoneNumbers = phoneNumbers;
        this.numbers = numbers;
        this.workspaces = workspaces;
        this.teams = teams;
        this.rules = rules;
        this.moves = moves;
        this.records = records;
        this.runs = runs;
        this.historyImports = historyImports;
        this.mongo = mongo;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Starts and runs the move. A step that fails leaves the record unfinished with its {@code error} (returned, not
     * thrown) and the number still moving, for {@link #retry}.
     *
     * @throws CodedException 409 {@code number_archived} for an archive, 409 {@code number_move_unfinished} while
     *     another move of the number is unfinished, 400 {@code number_move_other_customer} or
     *     {@code number_move_same_brand} for a wrong target
     */
    public PhoneNumberMove move(String phoneNumberId, String toWorkspaceId, AuthenticatedUser me) {
        PhoneNumber n = phoneNumbers.require(phoneNumberId);
        refuseArchive(n);
        if (moves.findByUnfinishedOf(n.getId()).isPresent()) throw unfinished();
        target(n, toWorkspaceId);
        PhoneNumberMove m = new PhoneNumberMove();
        m.setCustomerId(n.getCustomerId());
        m.setPhoneNumberId(n.getId());
        m.setUnfinishedOf(n.getId());
        m.setFromWorkspaceId(n.getWorkspaceId());
        m.setToWorkspaceId(toWorkspaceId);
        m.setStep(Step.STARTED);
        m.setCounts(new Counts(0, 0, 0));
        m.setStartedAt(clock.instant());
        m.setByUserId(me.userId());
        try {
            m = moves.insert(m);
        } catch (DuplicateKeyException e) {
            throw unfinished();
        }
        log.info("[{}] moving number from brand {} to {} (move {})", n.getId(), m.getFromWorkspaceId(),
                toWorkspaceId, m.getId());
        return run(m);
    }

    /**
     * Runs the steps the number's unfinished move has not done yet.
     *
     * @throws CodedException 409 {@code number_move_nothing_to_retry} when no move of the number is unfinished
     */
    public PhoneNumberMove retry(String phoneNumberId, AuthenticatedUser me) {
        PhoneNumberMove m = moves.findByUnfinishedOf(phoneNumberId).orElseThrow(
                () -> new CodedException(HttpStatus.CONFLICT, "number_move_nothing_to_retry", null,
                        "This number has no unfinished move."));
        log.info("[{}] retrying move {} after {} (asked by {})", phoneNumberId, m.getId(), m.getStep(), me.userId());
        m.setError(null);
        return run(moves.save(m));
    }

    /** The number's newest move, finished or not. */
    public Optional<PhoneNumberMove> last(String phoneNumberId) {
        return moves.findFirstByPhoneNumberIdOrderByIdDesc(phoneNumberId);
    }

    /** What moving the number to the brand would do; refused as {@link #move} refuses a wrong target. */
    public MovePreview preview(String phoneNumberId, String toWorkspaceId) {
        PhoneNumber n = phoneNumbers.require(phoneNumberId);
        refuseArchive(n);
        Workspace target = target(n, toWorkspaceId);
        String fromName = workspaces.findById(n.getWorkspaceId()).map(Workspace::getName).orElse(null);
        String teamName = n.getTeamId() == null ? null : teams.findById(n.getTeamId()).map(Team::getName).orElse(null);
        Optional<PhoneNumber> back = archiveIn(n.getId(), target.getId());
        long returning = back.map(a -> mongo.count(on(a.getId()), WaWebChat.class)).orElse(0L);
        List<String> naming = rules.findByWorkspaceId(n.getWorkspaceId()).stream()
                .filter(r -> names(r.getConditions(), n.getId())).map(AutomationRule::getName).toList();
        return new MovePreview(fromName, target.getName(), mongo.count(on(n.getId()), WaWebChat.class),
                mongo.count(activeRuns(n.getId()), ChatbotRun.class), teamName, archiveLabel(n, clock.instant()),
                back.isPresent(), returning, naming);
    }

    // --- the steps ---------------------------------------------------------------------------------------

    private PhoneNumberMove run(PhoneNumberMove m) {
        try {
            if (m.getStep() == Step.STARTED) m = offline(m);
            if (m.getStep() == Step.OFFLINE) m = endRuns(m);
            if (m.getStep() == Step.RUNS_ENDED) m = archive(m);
            if (m.getStep() == Step.ARCHIVED) m = merge(m);
            if (m.getStep() == Step.MERGED) m = finish(m);
            return m;
        } catch (RuntimeException e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // by update, not a save of this copy, which may be stale; never on a move another attempt finished
            mongo.updateFirst(new Query(Criteria.where("_id").is(m.getId()).and("step").ne(Step.DONE.name())),
                    new Update().set("error", error), PhoneNumberMove.class);
            PhoneNumberMove stopped = moves.findById(m.getId()).orElseThrow();
            log.warn("[{}] move {} stopped after {}: {}", m.getPhoneNumberId(), m.getId(), stopped.getStep(), error, e);
            return stopped;
        }
    }

    /** Step 1: the number is moving, which its device is refused for, and the live session is closed. */
    private PhoneNumberMove offline(PhoneNumberMove m) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(m.getPhoneNumberId()).and("movingSince").is(null)),
                new Update().set("movingSince", clock.instant()).inc("version", 1), PhoneNumber.class);
        events.publishEvent(new DeviceAccessChanged(m.getPhoneNumberId(), m.getCustomerId(), m.getFromWorkspaceId(),
                DeviceAccessChanged.Reason.MOVED));
        return advance(m, Step.OFFLINE);
    }

    /** Step 2: chatbot runs end, chats a chatbot still holds open for people, history imports stop. */
    private PhoneNumberMove endRuns(PhoneNumberMove m) {
        String id = m.getPhoneNumberId();
        for (ChatbotRun run : mongo.find(activeRuns(id), ChatbotRun.class)) {
            runs.stop(run, STOP_REASON, m.getByUserId());
        }
        mongo.updateMulti(new Query(Criteria.where("phoneNumberId").is(id).and("status").is(ChatStatus.BOT.name())),
                new Update().set("status", ChatStatus.OPEN.name()).unset("assignedUserId").unset("assignedTeamId")
                        .unset("assignedAt").unset("chatbot").unset("routingPendingSince").inc("version", 1),
                WaWebChat.class);
        for (WaWebHistoryImport h : mongo.find(new Query(Criteria.where("phoneNumberId").is(id).and("status")
                .in(ACTIVE_IMPORTS)), WaWebHistoryImport.class)) {
            try {
                historyImports.cancel(h.getWorkspaceId(), h.getId());
            } catch (CodedException finishedMeanwhile) {
                log.info("[{}] history import {} was already over", id, h.getId());
            }
        }
        long ended = mongo.count(new Query(Criteria.where("phoneNumberId").is(id).and("endReason").is(STOP_REASON)
                .and("finishedAt").gte(m.getStartedAt())), ChatbotRun.class);
        m.setCounts(new Counts(0, 0, ended));
        return advance(m, Step.RUNS_ENDED);
    }

    /** Step 3: the archive in the source brand, created once, and the number's records moved onto it. */
    private PhoneNumberMove archive(PhoneNumberMove m) {
        if (m.getSourceArchiveId() == null) {
            m.setSourceArchiveId(new ObjectId().toHexString());
            m = moves.save(m);
        }
        String archiveId = m.getSourceArchiveId();
        if (!numbers.existsById(archiveId)) {
            PhoneNumber n = numbers.findById(m.getPhoneNumberId()).orElseThrow();
            Instant now = clock.instant();
            PhoneNumber a = new PhoneNumber();
            a.setId(archiveId);
            a.setCustomerId(n.getCustomerId());
            a.setWorkspaceId(m.getFromWorkspaceId());
            a.setLabel(archiveLabel(n, m.getStartedAt()));
            a.setTeamId(n.getTeamId());
            a.setConnectionType(n.getConnectionType());
            a.setArchiveOf(n.getId());
            a.setArchivedNumber(n.getNumber());
            a.setArchivedAt(now);
            a.setDisabled(new Disabled("Archived", EnableLevel.PLATFORM, now));
            a.setCreatedBy(n.getCreatedBy());
            numbers.insert(a);
        }
        records.repoint(m.getPhoneNumberId(), archiveId);
        long runsEnded = m.getCounts() == null ? 0 : m.getCounts().runsEnded();
        m.setCounts(new Counts(mongo.count(on(archiveId), WaWebChat.class),
                mongo.count(on(archiveId), WaWebMessage.class), runsEnded));
        return advance(m, Step.ARCHIVED);
    }

    /** Step 4: an archive of this number in the target brand gives its records back to the number and goes. */
    private PhoneNumberMove merge(PhoneNumberMove m) {
        String archiveId = m.getMergedArchiveId();
        if (archiveId == null) {
            archiveId = archiveIn(m.getPhoneNumberId(), m.getToWorkspaceId()).map(PhoneNumber::getId).orElse(null);
            if (archiveId != null) {
                m.setMergedArchiveId(archiveId);
                m = moves.save(m);
            }
        }
        if (archiveId != null) {
            records.repoint(archiveId, m.getPhoneNumberId());
            numbers.deleteById(archiveId);
        }
        return advance(m, Step.MERGED);
    }

    /** Step 5: the number is the target brand's, with no team yet, and its device may connect again. */
    private PhoneNumberMove finish(PhoneNumberMove m) {
        Instant now = clock.instant();
        mongo.updateFirst(new Query(Criteria.where("_id").is(m.getPhoneNumberId()).and("movingSince").ne(null)),
                new Update().set("workspaceId", m.getToWorkspaceId()).unset("teamId").set("movedInAt", now)
                        .unset("movingSince").inc("version", 1), PhoneNumber.class);
        m.setFinishedAt(now);
        m.setUnfinishedOf(null);
        m.setError(null);
        m = advance(m, Step.DONE);
        log.info("[{}] moved to brand {} (move {})", m.getPhoneNumberId(), m.getToWorkspaceId(), m.getId());
        return m;
    }

    private PhoneNumberMove advance(PhoneNumberMove m, Step done) {
        m.setStep(done);
        return moves.save(m);
    }

    // --- helpers -----------------------------------------------------------------------------------------

    /** {@code "<label> (until <d MMM>)"}, the date in India; a number without a label shows its digits. */
    private static String archiveLabel(PhoneNumber n, Instant until) {
        String label = n.getLabel() == null || n.getLabel().isBlank() ? n.shownNumber() : n.getLabel();
        return label + " (until " + DAY.format(until.atZone(IST)) + ")";
    }

    private Optional<PhoneNumber> archiveIn(String phoneNumberId, String workspaceId) {
        return numbers.findByArchiveOf(phoneNumberId).stream()
                .filter(a -> Objects.equals(a.getWorkspaceId(), workspaceId)).findFirst();
    }

    private Workspace target(PhoneNumber n, String toWorkspaceId) {
        if (toWorkspaceId == null || toWorkspaceId.isBlank()) throw new ValidationException("workspaceId", "is required");
        Workspace target = workspaces.findById(toWorkspaceId).orElseThrow(() -> new NotFoundException("brand not found"));
        if (!Objects.equals(target.getCustomerId(), n.getCustomerId())) {
            throw new CodedException(HttpStatus.BAD_REQUEST, "number_move_other_customer", "workspaceId",
                    "A number moves only between brands of the same customer.");
        }
        if (target.getId().equals(n.getWorkspaceId())) {
            throw new CodedException(HttpStatus.BAD_REQUEST, "number_move_same_brand", "workspaceId",
                    "The number is already in this brand.");
        }
        return target;
    }

    private static void refuseArchive(PhoneNumber n) {
        if (n.isArchive()) {
            throw new CodedException(HttpStatus.CONFLICT, "number_archived", null,
                    "This number moved to another brand; this archive only keeps the old chats.");
        }
    }

    private static CodedException unfinished() {
        return new CodedException(HttpStatus.CONFLICT, "number_move_unfinished", null,
                "This number's last move did not finish: retry it first.");
    }

    private static Query on(String phoneNumberId) {
        return new Query(Criteria.where("phoneNumberId").is(phoneNumberId));
    }

    private static Query activeRuns(String phoneNumberId) {
        return new Query(Criteria.where("phoneNumberId").is(phoneNumberId).and("status").in(ACTIVE_RUNS));
    }

    /** Whether a leaf of the condition tree names the number (its value, or a list holding it). */
    private static boolean names(ConditionNode node, String phoneNumberId) {
        if (node == null) return false;
        if (node.rules() != null && node.rules().stream().anyMatch(r -> names(r, phoneNumberId))) return true;
        return node.value() != null && String.valueOf(node.value()).contains(phoneNumberId);
    }
}
