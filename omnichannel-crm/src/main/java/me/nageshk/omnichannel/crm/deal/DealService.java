package me.nageshk.omnichannel.crm.deal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.FieldDiff;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.common.CrmSort;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.company.CompanyCounters;
import me.nageshk.omnichannel.crm.contact.Channel;
import me.nageshk.omnichannel.crm.contact.ChannelService;
import me.nageshk.omnichannel.crm.contact.CrmContact;
import me.nageshk.omnichannel.crm.contact.CrmContactService;
import me.nageshk.omnichannel.crm.deal.dto.CloseRequest;
import me.nageshk.omnichannel.crm.deal.dto.DealRequest;
import me.nageshk.omnichannel.crm.deal.dto.DealSearchRequest;
import me.nageshk.omnichannel.crm.deal.dto.DealView;
import me.nageshk.omnichannel.crm.deal.dto.MoveRequest;
import me.nageshk.omnichannel.crm.field.FieldDefinitionService;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmPipelineService;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import me.nageshk.omnichannel.crm.pipeline.StageType;
import me.nageshk.omnichannel.crm.platform.ContactDirectory;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformContact;
import me.nageshk.omnichannel.crm.platform.PlatformPerson;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Deals: create, edit, move between lanes, close, delete, read (spec 6.2). */
@Service
public class DealService {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;
    static final int MAX_TITLE = 200;
    static final int MAX_LINKED = 50;
    private static final Set<String> SORTS = Set.of("creationTime", "lastActivityAt", "amount", "expectedCloseDate",
            "title", "position", "closedAt", "stageEnteredAt");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final MongoTemplate mongo;
    private final CrmDealRepository repository;
    private final CrmContactService contacts;
    private final CrmPipelineService pipelines;
    private final ContactDirectory contactDirectory;
    private final PeopleDirectory people;
    private final Placement placement;
    private final ActivityLog activities;
    private final LaneLocks lanes;
    private final FieldDefinitionService fields;
    private final Clock clock;

    DealService(MongoTemplate mongo, CrmDealRepository repository, CrmContactService contacts,
            CrmPipelineService pipelines, ContactDirectory contactDirectory, PeopleDirectory people,
            Placement placement, ActivityLog activities, LaneLocks lanes, FieldDefinitionService fields, Clock clock) {
        this.fields = fields;
        this.lanes = lanes;
        this.mongo = mongo;
        this.repository = repository;
        this.contacts = contacts;
        this.pipelines = pipelines;
        this.contactDirectory = contactDirectory;
        this.people = people;
        this.placement = placement;
        this.activities = activities;
        this.clock = clock;
    }

    // --- reads -------------------------------------------------------------------------------------------

    public DealView get(CrmContext ctx, String id) {
        return view(ctx, load(ctx, id));
    }

    /** @throws NotFoundException when missing, of another brand, or in a pipeline outside the caller's access */
    public CrmDeal load(CrmContext ctx, String id) {
        CrmDeal d = repository.findByIdAndWorkspaceId(id, ctx.workspaceId()).orElse(null);
        if (d == null || !ctx.pipelines().allows(d.getPipelineId())) throw new NotFoundException("Deal not found");
        return d;
    }

    public CrmPage<DealView> search(CrmContext ctx, DealSearchRequest req) {
        int page = req.page() == null ? 0 : req.page();
        int size = req.size() == null ? DEFAULT_SIZE : req.size();
        Map<String, String> errors = new LinkedHashMap<>();
        if (page < 0) errors.put("page", "must not be negative");
        if (size < 1) errors.put("size", "must be at least 1");
        CrmSort sorting = CrmSort.parse(req.sort(), req.dir(), SORTS, "creationTime",
                f -> f.equals("position") || f.equals("title") ? "asc" : "desc", errors);
        DealStatus status = null;
        if (present(req.status())) {
            try {
                status = DealStatus.valueOf(req.status().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.put("status", "must be OPEN, WON or LOST");
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        size = Math.min(size, MAX_SIZE);

        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("workspaceId").is(ctx.workspaceId()));
        if (!ctx.pipelines().all()) all.add(ctx.pipelines().criteria("pipelineId"));
        if (present(req.pipelineId())) all.add(Criteria.where("pipelineId").is(req.pipelineId()));
        if (present(req.stageId())) all.add(Criteria.where("stageId").is(req.stageId()));
        if (status != null) all.add(Criteria.where("status").is(status));
        if (present(req.ownerId())) all.add(Criteria.where("ownerId").is(req.ownerId()));
        if (present(req.companyId())) all.add(Criteria.where("companyId").is(req.companyId()));
        if (present(req.crmContactId())) all.add(Criteria.where("crmContactIds").is(req.crmContactId()));
        Criteria text = textCriteria(req.q());
        if (text != null) all.add(text);
        Criteria filter = new Criteria().andOperator(all);
        long total = mongo.count(new Query(filter), CrmDeal.class);
        String sort = sorting.field();
        // a lane's load-more must continue the board's order exactly: position, then id ascending
        Sort order = Sort.by(sorting.direction(), sort).and(Sort.by(sort.equals("position") ? Sort.Direction.ASC
                : Sort.Direction.DESC, "_id"));
        List<CrmDeal> found = mongo.find(new Query(filter).with(order).skip((long) page * size).limit(size),
                CrmDeal.class);
        return new CrmPage<>(views(ctx, found), total, page);
    }

    /** Title, contact name or phone containing {@code q} (case-insensitive, literal); null when blank. */
    static Criteria textCriteria(String q) {
        if (!present(q)) return null;
        Pattern p = Pattern.compile(Pattern.quote(q.trim()), Pattern.CASE_INSENSITIVE);
        return new Criteria().orOperator(Criteria.where("title").regex(p), Criteria.where("contactSnapshot.name").regex(p),
                Criteria.where("contactSnapshot.phone").regex(p));
    }

    /** The company's OPEN deals the caller may see, newest first (at most {@value #MAX_SIZE}). */
    public List<DealView> openDealsOfCompany(CrmContext ctx, String companyId) {
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("workspaceId").is(ctx.workspaceId()));
        all.add(Criteria.where("companyId").is(companyId));
        all.add(Criteria.where("status").is(DealStatus.OPEN));
        if (!ctx.pipelines().all()) all.add(ctx.pipelines().criteria("pipelineId"));
        return views(ctx, mongo.find(new Query(new Criteria().andOperator(all))
                .with(Sort.by(Sort.Direction.DESC, "creationTime").and(Sort.by(Sort.Direction.DESC, "_id")))
                .limit(MAX_SIZE), CrmDeal.class));
    }

    // --- capture -----------------------------------------------------------------------------------------

    /**
     * The contact's OPEN deal (as primary) in the pipeline, else a new one in its first OPEN stage, appended. The
     * caller has checked the pipeline is accessible and active. Two concurrent captures both insert, then the deal
     * with the lowest id wins and the other is removed, so both answer the same deal.
     */
    public CaptureDeal createForCapture(CrmContext ctx, CrmContact contact, String pipelineId, String title) {
        CrmDeal existing = firstOpen(ctx.workspaceId(), contact.getId(), pipelineId);
        if (existing != null) return new CaptureDeal(view(ctx, existing), false);
        CrmPipeline pipeline = mongo.findOne(new Query(Criteria.where("_id").is(pipelineId)
                .and("workspaceId").is(ctx.workspaceId())), CrmPipeline.class);
        if (pipeline == null) throw new NotFoundException("Pipeline not found");
        CrmStage stage = firstOpenStage(pipeline);
        String name = displayName(ctx, contact);
        String t = present(title) ? title.trim() : name + " deal";
        if (t.length() > MAX_TITLE) t = t.substring(0, MAX_TITLE);
        CrmDeal d = newDeal(ctx, contact, name, pipeline, stage);
        d.setTitle(t);
        // capture has no one to fill required fields; defaults still apply
        d.setCustomFields(fields.values(ctx, TargetType.DEAL, Map.of(), null, true, false, new LinkedHashMap<>()));
        d.setOwnerId(contact.getOwnerId() != null ? contact.getOwnerId() : ctx.userId());
        d.setTeamId(contact.getTeamId());
        CrmDeal saved = appended(ctx, d);
        CrmDeal winner = firstOpen(ctx.workspaceId(), contact.getId(), pipelineId);
        if (winner != null && !winner.getId().equals(saved.getId())) {
            mongo.remove(new Query(Criteria.where("_id").is(saved.getId()).and("workspaceId").is(ctx.workspaceId())),
                    CrmDeal.class);
            return new CaptureDeal(view(ctx, winner), false);
        }
        afterCreate(ctx, saved, "Deal created from a capture");
        return new CaptureDeal(view(ctx, reload(ctx, saved.getId())), true);
    }

    private CrmDeal firstOpen(String ws, String crmContactId, String pipelineId) {
        return mongo.findOne(new Query(Criteria.where("workspaceId").is(ws).and("primaryCrmContactId").is(crmContactId)
                .and("pipelineId").is(pipelineId).and("status").is(DealStatus.OPEN))
                .with(Sort.by(Sort.Direction.ASC, "_id")), CrmDeal.class);
    }

    // --- create / update ---------------------------------------------------------------------------------

    public DealView create(CrmContext ctx, DealRequest req) {
        ctx.require("crm:edit");
        Map<String, String> errors = new LinkedHashMap<>();
        String title = text(req.title(), null, "title", MAX_TITLE, errors);
        if (title == null) errors.putIfAbsent("title", "is required");
        if (!present(req.primaryCrmContactId())) errors.put("primaryCrmContactId", "is required");
        BigDecimal amount = amount(req.amount(), null, errors);
        String currency = currency(req.currency(), ctx.settings().getDefaultCurrency(), errors);
        Integer probability = probability(req.probability(), errors);
        String closeDate = date(req.expectedCloseDate(), null, errors);
        String companyId = company(ctx, text(req.companyId(), null, "companyId", 64, errors), null, errors);
        String ownerId = owner(ctx, text(req.ownerId(), null, "ownerId", 64, errors), null, errors);
        String teamId = text(req.teamId(), null, "teamId", 64, errors);
        String source = pick(text(req.source(), null, "source", 64, errors), null, "source",
                ctx.settings().getDealSources(), errors);
        Map<String, Object> custom = fields.values(ctx, TargetType.DEAL, Map.of(), req.customFields(), true, true, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);

        CrmContact primary = contacts.load(ctx, req.primaryCrmContactId().trim());
        String pipelineId = present(req.pipelineId()) ? req.pipelineId().trim() : ctx.settings().getDefaultPipelineId();
        if (pipelineId == null) throw new NotFoundException("Pipeline not found");
        CrmPipeline pipeline = pipelines.get(ctx, pipelineId);
        requireActive(pipeline);
        CrmStage stage;
        if (present(req.stageId())) {
            stage = Placement.stage(pipeline, req.stageId().trim())
                    .orElseThrow(() -> new ValidationException("stageId", "is not a stage of the pipeline"));
        } else {
            stage = firstOpenStage(pipeline);
        }
        List<String> linked = linked(ctx, primary, req.crmContactIds(), Set.of());

        CrmDeal d = newDeal(ctx, primary, displayName(ctx, primary), pipeline, stage);
        d.setTitle(title);
        d.setAmount(amount);
        d.setCurrency(currency);
        if (probability != null) {
            d.setProbability(probability);
            d.setProbabilityOverridden(true);
        }
        d.setExpectedCloseDate(closeDate);
        d.setCompanyId(companyId);
        d.setCrmContactIds(new ArrayList<>(linked));
        d.setOwnerId(ownerId != null ? ownerId : ctx.userId());
        d.setTeamId(teamId);
        if (source != null) d.setSource(source);
        d.setLabelIds(labels(req.labelIds(), List.of()));
        d.setCustomFields(custom);
        CrmDeal saved = appended(ctx, d);
        afterCreate(ctx, saved, "Deal created");
        return view(ctx, reload(ctx, saved.getId()));
    }

    /** A deal of the contact in {@code stage}, on its channel, owned by the caller, with the settings' defaults. */
    private CrmDeal newDeal(CrmContext ctx, CrmContact contact, String contactName, CrmPipeline pipeline,
            CrmStage stage) {
        Instant now = Instant.now(clock);
        CrmDeal d = new CrmDeal();
        d.setWorkspaceId(ctx.workspaceId());
        d.setCustomerId(ctx.customerId());
        d.setChannel(contact.getChannel());
        d.setPipelineId(pipeline.getId());
        d.setStageId(stage.getId());
        d.setStatus(Placement.statusOf(stage));
        d.setStageEnteredAt(now);
        d.setLastActivityAt(now);
        if (d.getStatus() != DealStatus.OPEN) d.setClosedAt(now);
        d.setProbability(stage.getProbability());
        d.setProbabilityOverridden(false);
        d.setCurrency(ctx.settings().getDefaultCurrency());
        d.setSource(defaultKey(ctx.settings().getDealSources()));
        d.setPrimaryCrmContactId(contact.getId());
        d.setContactSnapshot(new ContactSnapshot(contactName, contact.getPhone()));
        d.setCrmContactIds(new ArrayList<>(List.of(contact.getId())));
        d.setCreatedBy(ctx.userId());
        d.setLastModifiedBy(ctx.userId());
        return d;
    }

    /** Inserts the new deal after the last card of its lane, under the lane's lock. */
    private CrmDeal appended(CrmContext ctx, CrmDeal d) {
        return lanes.withLane(ctx.workspaceId(), d.getPipelineId(), d.getStageId(), () -> {
            d.setPosition(placement.appendPosition(ctx.workspaceId(), d.getPipelineId(), d.getStageId(), null));
            return mongo.insert(d);
        });
    }

    /** {@code RECORD_CREATED}, plus {@code DEAL_WON}/{@code DEAL_LOST} for a deal created closed; counters. */
    private void afterCreate(CrmContext ctx, CrmDeal saved, String summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stageId", saved.getStageId());
        payload.put("amount", Placement.plain(saved.getAmount()));
        payload.put("currency", saved.getCurrency());
        activities.record(ctx, ActivityType.RECORD_CREATED, Placement.target(saved), Placement.related(saved),
                saved.getPipelineId(), Placement.phoneOf(saved), summary, payload);
        placement.recordOutcome(ctx, saved);
        placement.recompute(ctx.workspaceId(), Placement.contactsOf(saved),
                saved.getCompanyId() == null ? List.of() : List.of(saved.getCompanyId()));
    }

    public DealView update(CrmContext ctx, String id, DealRequest req) {
        ctx.require("crm:edit");
        CrmDeal current = load(ctx, id);
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.version() == null) errors.put("version", "is required");
        String title = text(req.title(), current.getTitle(), "title", MAX_TITLE, errors);
        if (title == null) errors.putIfAbsent("title", "must not be blank");
        BigDecimal amount = amount(req.amount(), current.getAmount(), errors);
        String currency = req.currency() == null ? current.getCurrency()
                : currency(req.currency(), null, errors);
        Integer probability = probability(req.probability(), errors);
        String closeDate = date(req.expectedCloseDate(), current.getExpectedCloseDate(), errors);
        String companyId = company(ctx, text(req.companyId(), current.getCompanyId(), "companyId", 64, errors),
                current.getCompanyId(), errors);
        String ownerId = owner(ctx, text(req.ownerId(), current.getOwnerId(), "ownerId", 64, errors),
                current.getOwnerId(), errors);
        String teamId = text(req.teamId(), current.getTeamId(), "teamId", 64, errors);
        String source = pick(text(req.source(), current.getSource(), "source", 64, errors), current.getSource(),
                "source", ctx.settings().getDealSources(), errors);
        boolean pipelineChange = present(req.pipelineId()) && !req.pipelineId().trim().equals(current.getPipelineId());
        if (!pipelineChange && present(req.stageId()) && !req.stageId().trim().equals(current.getStageId()))
            errors.put("stageId", "cannot change here; move the deal instead");
        if (req.primaryCrmContactId() != null && !present(req.primaryCrmContactId()))
            errors.put("primaryCrmContactId", "must not be blank");
        Map<String, Object> custom = fields.values(ctx, TargetType.DEAL, current.getCustomFields(), req.customFields(),
                false, true, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);

        CrmPipeline from = pipelines.get(ctx, current.getPipelineId());
        CrmPipeline to = from;
        CrmStage target = null;
        if (pipelineChange) {
            to = pipelines.get(ctx, req.pipelineId().trim());
            requireActive(to);
            target = firstOpenStage(to);
        }
        CrmContact primary = null;
        String primaryId = current.getPrimaryCrmContactId();
        if (present(req.primaryCrmContactId()) && !req.primaryCrmContactId().trim().equals(primaryId)) {
            primary = contacts.load(ctx, req.primaryCrmContactId().trim());
            if (current.getChannel() != null && !sameChannel(primary.getChannel(), current.getChannel())) {
                throw mismatch(current.getChannel().phoneNumberId());
            }
            primaryId = primary.getId();
        }
        List<String> linked;
        Set<String> before = Placement.contactsOf(current);
        if (req.crmContactIds() != null || primary != null) {
            CrmContact head = primary != null ? primary : primaryOf(ctx, current);
            // a new primary heads the list; the former one stays linked (reference rule)
            List<String> requested = req.crmContactIds() != null ? req.crmContactIds() : current.getCrmContactIds();
            linked = linked(ctx, head, requested, before);
        } else {
            linked = new ArrayList<>(current.getCrmContactIds());
        }
        boolean overridden = current.isProbabilityOverridden();
        Integer prob = current.getProbability();
        if (probability != null) {
            prob = probability;
            overridden = true;
        } else if (Boolean.FALSE.equals(req.probabilityOverridden())) {
            overridden = false;
            CrmStage stageNow = Placement.stage(from, current.getStageId()).orElse(null);
            if (stageNow != null) prob = stageNow.getProbability();
        }

        Map<String, Object> was = audited(current.getTitle(), current.getAmount(), current.getCurrency(),
                current.getProbability(), current.isProbabilityOverridden(), current.getExpectedCloseDate(),
                current.getCompanyId(), current.getPrimaryCrmContactId(), current.getCrmContactIds(),
                current.getOwnerId(), current.getTeamId(), current.getSource(), current.getLabelIds(),
                current.getCustomFields());
        Map<String, Object> now = audited(title, amount, currency, prob, overridden, closeDate, companyId, primaryId,
                linked, ownerId, teamId, source, labels(req.labelIds(), current.getLabelIds()),
                custom);
        List<FieldDiff> diffs = ActivityLog.diff(was, now);
        if (diffs.isEmpty() && !pipelineChange) {
            if (!req.version().equals(current.getVersion())) throw versionConflict();
            return view(ctx, current);
        }
        Instant at = Instant.now(clock);
        Update u = new Update().set("lastModifiedBy", ctx.userId()).set("lastUpdateTime", at).inc("version", 1);
        diffs.forEach(d -> u.set(d.field(), d.to() instanceof BigDecimal b ? new Decimal128(b) : d.to()));
        if (primary != null) {
            u.set("contactSnapshot", new ContactSnapshot(displayName(ctx, primary), primary.getPhone()));
        }
        Query guarded = new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())
                .and("version").is(req.version()));
        CrmDeal saved;
        if (pipelineChange) {
            CrmStage into = target;
            CrmPipeline toPipeline = to;
            boolean keepProbability = overridden;
            // leaving one lane for another: both lanes' positions are settled under their locks
            saved = lanes.withLanes(ctx.workspaceId(), List.of(new LaneLocks.Lane(current.getPipelineId(),
                    current.getStageId()), new LaneLocks.Lane(to.getId(), target.getId())), () -> {
                        Placement.enter(u, current, toPipeline.getId(), into, placement.appendPosition(
                                ctx.workspaceId(), toPipeline.getId(), into.getId(), id), keepProbability, at);
                        return mongo.findAndModify(guarded, u, FindAndModifyOptions.options().returnNew(true),
                                CrmDeal.class);
                    });
        } else {
            saved = mongo.findAndModify(guarded, u, FindAndModifyOptions.options().returnNew(true), CrmDeal.class);
        }
        if (saved == null) {
            if (mongo.exists(new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())),
                    CrmDeal.class)) throw versionConflict();
            throw new NotFoundException("Deal not found");
        }
        if (!diffs.isEmpty()) {
            List<Map<String, Object>> changes = diffs.stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field", d.field());
                m.put("from", Placement.plain(d.from()));
                m.put("to", Placement.plain(d.to()));
                return m;
            }).toList();
            activities.record(ctx, ActivityType.FIELD_CHANGED, Placement.target(saved), Placement.related(saved),
                    saved.getPipelineId(), Placement.phoneOf(saved),
                    "Changed " + String.join(", ", diffs.stream().map(FieldDiff::field).toList()),
                    new LinkedHashMap<>(Map.of("changes", changes)));
        }
        if (pipelineChange) {
            placement.recordMove(ctx, current, saved, from, to, null);
            placement.retag(ctx.workspaceId(), List.of(id), saved.getPipelineId());
        }
        Set<String> touched = new LinkedHashSet<>(before);
        touched.addAll(Placement.contactsOf(saved));
        List<String> companies = new ArrayList<>();
        companies.add(current.getCompanyId());
        companies.add(saved.getCompanyId());
        placement.recompute(ctx.workspaceId(), touched, companies);
        return view(ctx, reload(ctx, id));
    }

    private static Map<String, Object> audited(String title, BigDecimal amount, String currency, Integer probability,
            boolean overridden, String closeDate, String companyId, String primaryId, List<String> linked,
            String ownerId, String teamId, String source, List<String> labelIds, Map<String, Object> custom) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("amount", amount);
        m.put("currency", currency);
        m.put("probability", probability);
        m.put("probabilityOverridden", overridden);
        m.put("expectedCloseDate", closeDate);
        m.put("companyId", companyId);
        m.put("primaryCrmContactId", primaryId);
        m.put("crmContactIds", linked == null ? List.of() : List.copyOf(linked));
        m.put("ownerId", ownerId);
        m.put("teamId", teamId);
        m.put("source", source);
        m.put("labelIds", labelIds == null ? List.of() : List.copyOf(labelIds));
        m.put("customFields", custom == null ? new LinkedHashMap<>() : custom);
        return m;
    }

    /**
     * The primary first, then the requested contacts (no blanks or repeats, at most {@value #MAX_LINKED}). A contact
     * not linked before must exist and be visible to the caller (400) and every linked contact must share the
     * primary's channel (409 CHANNEL_MISMATCH).
     */
    private List<String> linked(CrmContext ctx, CrmContact primary, List<String> requested, Set<String> before) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(primary.getId());
        if (requested != null) requested.stream().filter(DealService::present).map(String::trim).forEach(ids::add);
        if (ids.size() > MAX_LINKED) throw new ValidationException("crmContactIds", "at most " + MAX_LINKED + " contacts");
        List<String> others = ids.stream().filter(x -> !x.equals(primary.getId())).toList();
        if (others.isEmpty()) return new ArrayList<>(ids);
        Map<String, CrmContact> found = new HashMap<>();
        mongo.find(new Query(Criteria.where("workspaceId").is(ctx.workspaceId()).and("_id").in(others)),
                CrmContact.class).forEach(c -> found.put(c.getId(), c));
        for (String id : others) {
            CrmContact c = found.get(id);
            boolean visible = c != null && (ctx.pipelines().all() || before.contains(id)
                    || c.getPipelineIds().stream().anyMatch(ctx.pipelines()::allows));
            if (!visible) throw new ValidationException("crmContactIds", "unknown contact " + id);
            if (!sameChannel(c.getChannel(), primary.getChannel())) {
                throw mismatch(primary.getChannel() == null ? null : primary.getChannel().phoneNumberId());
            }
        }
        return new ArrayList<>(ids);
    }

    private static boolean sameChannel(Channel a, Channel b) {
        return a != null && b != null && Objects.equals(a.phoneNumberId(), b.phoneNumberId());
    }

    private CrmContact primaryOf(CrmContext ctx, CrmDeal d) {
        CrmContact c = mongo.findOne(new Query(Criteria.where("_id").is(d.getPrimaryCrmContactId())
                .and("workspaceId").is(ctx.workspaceId())), CrmContact.class);
        if (c != null) return c;
        // the primary is gone: keep the deal's own channel as the reference
        CrmContact stand = new CrmContact();
        stand.setId(d.getPrimaryCrmContactId());
        stand.setChannel(d.getChannel());
        return stand;
    }

    private static RuntimeException mismatch(String phoneNumberId) {
        return CrmErrors.conflict("CHANNEL_MISMATCH", "Linked contacts must be on the primary contact's number",
                phoneNumberId == null ? Map.of() : Map.of("phoneNumberId", phoneNumberId));
    }

    static RuntimeException versionConflict() {
        return CrmErrors.conflict("VERSION_CONFLICT", "The deal was changed by someone else; reload and retry",
                Map.of());
    }

    // --- move / close / delete ---------------------------------------------------------------------------

    public DealView move(CrmContext ctx, String id, MoveRequest req) {
        ctx.require("crm:edit");
        if (req == null || !present(req.stageId())) throw new ValidationException("stageId", "is required");
        CrmDeal deal = load(ctx, id);
        CrmPipeline pipeline = pipelines.get(ctx, deal.getPipelineId());
        CrmStage target = Placement.stage(pipeline, req.stageId().trim())
                .orElseThrow(() -> new ValidationException("stageId", "is not a stage of the deal's pipeline"));
        // slot, write and settle under the locks of the lane it leaves and the lane it enters
        CrmDeal saved = lanes.withLanes(ctx.workspaceId(), List.of(new LaneLocks.Lane(deal.getPipelineId(),
                deal.getStageId()), new LaneLocks.Lane(pipeline.getId(), target.getId())), () -> {
                    String before = neighbour(ctx, deal, target, req.beforeDealId(), "beforeDealId");
                    String after = neighbour(ctx, deal, target, req.afterDealId(), "afterDealId");
                    double position = placement.slot(ctx.workspaceId(), pipeline.getId(), target.getId(), id,
                            before, after);
                    return placement.place(ctx, deal, pipeline, pipeline, target, position, null, null, null);
                });
        placement.recompute(ctx.workspaceId(), Placement.contactsOf(saved),
                saved.getCompanyId() == null ? List.of() : List.of(saved.getCompanyId()));
        return view(ctx, reload(ctx, id));
    }

    /** A move neighbour: a deal of the target lane other than the moved one, else 400. */
    private String neighbour(CrmContext ctx, CrmDeal deal, CrmStage target, String neighbourId, String field) {
        if (!present(neighbourId)) return null;
        String nid = neighbourId.trim();
        if (nid.equals(deal.getId())) throw new ValidationException(field, "cannot be the deal itself");
        if (!mongo.exists(new Query(Placement.lane(ctx.workspaceId(), deal.getPipelineId(), target.getId())
                .and("_id").is(nid)), CrmDeal.class)) {
            throw new ValidationException(field, "is not a deal of the target stage");
        }
        return nid;
    }

    public DealView close(CrmContext ctx, String id, CloseRequest req) {
        ctx.require("crm:edit");
        DealStatus outcome = null;
        if (req != null && present(req.outcome())) {
            try {
                outcome = DealStatus.valueOf(req.outcome().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // reported below
            }
        }
        if (outcome == null || outcome == DealStatus.OPEN) throw new ValidationException("outcome", "must be WON or LOST");
        CrmDeal deal = load(ctx, id);
        String lostReason = null;
        if (outcome == DealStatus.LOST && present(req.lostReason())) {
            lostReason = req.lostReason().trim();
            Map<String, String> errors = new LinkedHashMap<>();
            pick(lostReason, deal.getLostReason(), "lostReason", ctx.settings().getLostReasons(), errors);
            if (!errors.isEmpty()) throw new ValidationException(errors);
        }
        CrmPipeline pipeline = pipelines.get(ctx, deal.getPipelineId());
        StageType type = outcome == DealStatus.WON ? StageType.WON : StageType.LOST;
        CrmStage target = Placement.first(pipeline, type)
                .orElseThrow(() -> new ValidationException("outcome", "the pipeline has no " + type + " stage"));
        DealStatus closing = outcome;
        String reason = lostReason;
        CrmDeal saved = lanes.withLanes(ctx.workspaceId(), List.of(new LaneLocks.Lane(deal.getPipelineId(),
                deal.getStageId()), new LaneLocks.Lane(pipeline.getId(), target.getId())), () -> {
                    double position = target.getId().equals(deal.getStageId()) ? deal.getPosition()
                            : placement.appendPosition(ctx.workspaceId(), pipeline.getId(), target.getId(), id);
                    return placement.place(ctx, deal, pipeline, pipeline, target, position, closing, reason, null);
                });
        placement.recompute(ctx.workspaceId(), Placement.contactsOf(saved),
                saved.getCompanyId() == null ? List.of() : List.of(saved.getCompanyId()));
        return view(ctx, reload(ctx, id));
    }

    public void delete(CrmContext ctx, String id) {
        ctx.require("crm:delete");
        ctx.require("crm:edit");
        CrmDeal deal = load(ctx, id);
        long removed = mongo.remove(new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())),
                CrmDeal.class).getDeletedCount();
        if (removed == 0) throw new NotFoundException("Deal not found");
        // its tasks and notes go with it (Ruling B4: by collection name)
        Query followUps = new Query(Criteria.where("workspaceId").is(ctx.workspaceId())
                .and("target.type").is(TargetType.DEAL.name()).and("target.id").is(id));
        for (String collection : ChannelService.FOLLOW_UPS) mongo.remove(followUps, collection);
        placement.recompute(ctx.workspaceId(), Placement.contactsOf(deal),
                deal.getCompanyId() == null ? List.of() : List.of(deal.getCompanyId()));
    }

    // --- views -------------------------------------------------------------------------------------------

    public DealView view(CrmContext ctx, CrmDeal d) {
        return views(ctx, List.of(d)).get(0);
    }

    public List<DealView> views(CrmContext ctx, List<CrmDeal> list) {
        if (list.isEmpty()) return List.of();
        String ws = ctx.workspaceId();
        Set<String> userIds = new HashSet<>();
        Set<String> companyIds = new HashSet<>();
        Set<String> primaryIds = new HashSet<>();
        for (CrmDeal d : list) {
            if (d.getOwnerId() != null) userIds.add(d.getOwnerId());
            if (d.getCompanyId() != null) companyIds.add(d.getCompanyId());
            if (d.getPrimaryCrmContactId() != null) primaryIds.add(d.getPrimaryCrmContactId());
        }
        Map<String, PlatformPerson> persons = userIds.isEmpty() ? Map.of() : people.people(userIds);
        Map<String, String> companies = names(ws, companyIds);
        Map<String, CrmContact> primaries = new HashMap<>();
        if (!primaryIds.isEmpty()) {
            mongo.find(new Query(Criteria.where("workspaceId").is(ws).and("_id").in(primaryIds)), CrmContact.class)
                    .forEach(c -> primaries.put(c.getId(), c));
        }
        Map<String, PlatformContact> live = primaries.isEmpty() ? Map.of()
                : contactDirectory.findAll(ws, primaries.values().stream().map(CrmContact::getContactId)
                        .filter(Objects::nonNull).toList());
        Instant now = Instant.now(clock);
        List<DealView> out = new ArrayList<>();
        for (CrmDeal d : list) {
            out.add(new DealView(d.getId(), d.getTitle(), d.getPipelineId(), d.getStageId(), d.getPosition(),
                    d.getStatus(), d.getAmount(), d.getCurrency(), d.getProbability(), d.isProbabilityOverridden(),
                    d.getExpectedCloseDate(), d.getClosedAt(), d.getLostReason(),
                    d.getCompanyId() == null ? null : new Ref(d.getCompanyId(), companies.get(d.getCompanyId())),
                    primaryContact(d, primaries.get(d.getPrimaryCrmContactId()), live),
                    d.getCrmContactIds() == null ? List.of() : d.getCrmContactIds(), ref(persons, d.getOwnerId()),
                    d.getSource(), d.getLabelIds() == null ? List.of() : d.getLabelIds(),
                    d.getCustomFields() == null ? Map.of() : d.getCustomFields(), d.getChannel(),
                    d.getStageEnteredAt(), d.isRotting(),
                    d.getNextTaskDueAt() != null && d.getNextTaskDueAt().isBefore(now), d.getLastActivityAt(),
                    d.getNextTaskDueAt(), d.getCreationTime(), d.getVersion()));
        }
        return out;
    }

    private static DealView.PrimaryContact primaryContact(CrmDeal d, CrmContact c, Map<String, PlatformContact> live) {
        ContactSnapshot snap = d.getContactSnapshot();
        if (c == null) {
            return new DealView.PrimaryContact(d.getPrimaryCrmContactId(), snap == null ? null : snap.name(),
                    snap == null ? null : snap.phone());
        }
        PlatformContact p = c.getContactId() == null ? null : live.get(c.getContactId());
        String phone = p != null && p.phone() != null ? p.phone() : c.getPhone();
        return new DealView.PrimaryContact(c.getId(), CrmContactService.name(c, p), phone);
    }

    private Map<String, String> names(String ws, Set<String> companyIds) {
        if (companyIds.isEmpty()) return Map.of();
        Query q = new Query(Criteria.where("workspaceId").is(ws).and("_id").in(companyIds));
        q.fields().include("name");
        Map<String, String> out = new HashMap<>();
        for (org.bson.Document doc : mongo.find(q, org.bson.Document.class, CompanyCounters.COMPANIES)) {
            out.put(doc.get("_id").toString(), doc.getString("name"));
        }
        return out;
    }

    private static Ref ref(Map<String, PlatformPerson> persons, String userId) {
        if (userId == null) return null;
        PlatformPerson p = persons.get(userId);
        return new Ref(userId, p == null ? null : p.name() != null && !p.name().isBlank() ? p.name() : p.username());
    }

    private String displayName(CrmContext ctx, CrmContact c) {
        PlatformContact live = c.getContactId() == null ? null
                : contactDirectory.find(ctx.workspaceId(), c.getContactId()).orElse(null);
        return CrmContactService.name(c, live);
    }

    private CrmDeal reload(CrmContext ctx, String id) {
        return repository.findByIdAndWorkspaceId(id, ctx.workspaceId())
                .orElseThrow(() -> new NotFoundException("Deal not found"));
    }

    // --- validation helpers ------------------------------------------------------------------------------

    private static void requireActive(CrmPipeline p) {
        if (!p.isActive()) throw CrmErrors.badRequest("PIPELINE_INACTIVE", "The pipeline is not active");
    }

    private static CrmStage firstOpenStage(CrmPipeline p) {
        return Placement.first(p, StageType.OPEN)
                .orElseThrow(() -> new ValidationException("pipelineId", "the pipeline has no OPEN stage"));
    }

    /** Absent = unchanged, blank = cleared, else trimmed; over-long values are reported. */
    private static String text(String given, String current, String field, int max, Map<String, String> errors) {
        if (given == null) return current;
        String t = given.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) errors.put(field, "must be at most " + max + " characters");
        return t;
    }

    /** A non-empty picklist restricts a changed value to its active keys; the stored value always passes. */
    private static String pick(String value, String current, String field, List<PicklistItem> items,
            Map<String, String> errors) {
        if (value == null || value.equals(current) || items == null || items.isEmpty()) return value;
        if (items.stream().noneMatch(i -> i.active() && i.key().equals(value))) errors.put(field, "is not an active option");
        return value;
    }

    private static BigDecimal amount(BigDecimal given, BigDecimal current, Map<String, String> errors) {
        if (given == null) return current;
        if (given.signum() < 0) errors.put("amount", "must not be negative");
        return given;
    }

    private static String currency(String given, String fallback, Map<String, String> errors) {
        if (given == null || given.isBlank()) return fallback;
        String c = given.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(c).matches()) errors.put("currency", "must be a three-letter ISO 4217 code");
        return c;
    }

    private static Integer probability(Integer given, Map<String, String> errors) {
        if (given != null && (given < 0 || given > 100)) errors.put("probability", "must be between 0 and 100");
        return given;
    }

    private static String date(String given, String current, Map<String, String> errors) {
        if (given == null) return current;
        if (given.isBlank()) return null;
        try {
            return LocalDate.parse(given.trim()).toString();
        } catch (DateTimeParseException e) {
            errors.put("expectedCloseDate", "must be a date as yyyy-MM-dd");
            return given.trim();
        }
    }

    private String company(CrmContext ctx, String companyId, String current, Map<String, String> errors) {
        if (companyId != null && !companyId.equals(current) && !mongo.exists(new Query(Criteria.where("_id")
                .is(companyId).and("workspaceId").is(ctx.workspaceId())), CompanyCounters.COMPANIES)) {
            errors.put("companyId", "must be a company of this brand");
        }
        return companyId;
    }

    private String owner(CrmContext ctx, String ownerId, String current, Map<String, String> errors) {
        if (ownerId != null && !ownerId.equals(current) && !people.isBrandUser(ctx.workspaceId(), ownerId))
            errors.put("ownerId", "must be a user of this brand");
        return ownerId;
    }

    private static List<String> labels(List<String> given, List<String> current) {
        if (given == null) return current == null ? new ArrayList<>() : new ArrayList<>(current);
        return new ArrayList<>(given.stream().filter(DealService::present).map(String::trim).distinct().toList());
    }

    private static String defaultKey(List<PicklistItem> items) {
        if (items == null) return null;
        return items.stream().filter(i -> i.active() && i.isDefault()).map(PicklistItem::key).findFirst().orElse(null);
    }

    static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
