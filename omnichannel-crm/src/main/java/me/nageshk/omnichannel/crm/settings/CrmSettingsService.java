package me.nageshk.omnichannel.crm.settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.access.PipelineIds;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.platform.BrandDirectory;
import me.nageshk.omnichannel.crm.platform.EnablementAudit;
import me.nageshk.omnichannel.crm.platform.PlatformBrand;
import me.nageshk.omnichannel.crm.settings.dto.EnablementRequest;
import me.nageshk.omnichannel.crm.settings.dto.SettingsRequest;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class CrmSettingsService {

    private final CrmSettingsRepository repository;
    private final BrandDirectory brands;
    private final EnablementAudit audit;
    private final MongoTemplate mongo;
    private final PipelineIds pipelineIds;

    CrmSettingsService(CrmSettingsRepository repository, BrandDirectory brands, EnablementAudit audit,
            MongoTemplate mongo, PipelineIds pipelineIds) {
        this.repository = repository;
        this.brands = brands;
        this.audit = audit;
        this.mongo = mongo;
        this.pipelineIds = pipelineIds;
    }

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

    public Optional<CrmSettings> find(String workspaceId) {
        return repository.findByWorkspaceId(workspaceId);
    }

    /** The brand's settings, created with the reference defaults on first use by {@code byUserId} ("system" for non-user callers); safe against concurrent creation. */
    public CrmSettings getOrCreate(String workspaceId, String customerId, String byUserId) {
        return repository.findByWorkspaceId(workspaceId).orElseGet(() -> {
            try {
                CrmSettings fresh = CrmDefaults.newSettings(workspaceId, customerId);
                fresh.setCreatedBy(byUserId);
                fresh.setLastModifiedBy(byUserId);
                return repository.insert(fresh);
            } catch (DuplicateKeyException e) {
                return repository.findByWorkspaceId(workspaceId).orElseThrow(() -> e);
            }
        });
    }

    /** Points the brand's default pipeline at {@code pipelineId} (null = none) without a version check. */
    public void setDefaultPipeline(String workspaceId, String pipelineId, String byUserId) {
        mongo.updateFirst(new Query(Criteria.where("workspaceId").is(workspaceId)),
                new Update().set("defaultPipelineId", pipelineId).set("lastModifiedBy", byUserId)
                        .set("lastUpdateTime", Instant.now()).inc("version", 1),
                CrmSettings.class);
    }

    /** The brand, which must belong to the customer. */
    public PlatformBrand requireBrand(String customerId, String workspaceId) {
        return brands.find(workspaceId).filter(b -> b.customerId().equals(customerId))
                .orElseThrow(() -> new NotFoundException("Workspace not found"));
    }

    public CrmSettings setSwitch(AuthenticatedUser by, String customerId, String workspaceId, EnablementRequest req) {
        requireBrand(customerId, workspaceId);
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.enabled() == null) errors.put("enabled", "is required");
        if (req.maxPipelines() != null && req.maxPipelines() < 1) errors.put("maxPipelines", "must be at least 1");
        if (req.maxCustomFields() != null && req.maxCustomFields() < 1)
            errors.put("maxCustomFields", "must be at least 1");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        CrmSettings s = getOrCreate(workspaceId, customerId, by.userId());
        boolean before = s.isEnabled();
        s.setEnabled(req.enabled());
        s.setMaxPipelines(req.maxPipelines());
        s.setMaxCustomFields(req.maxCustomFields());
        s.setLastModifiedBy(by.userId());
        s = repository.save(s);
        if (before != s.isEnabled()) audit.recordCrmSwitch(by, workspaceId, before, s.isEnabled());
        return s;
    }

    /**
     * Replaces the editable settings. 400 VALIDATION on a rule break; 404 if the settings are gone; 409
     * VERSION_CONFLICT when {@code version} is not the stored one.
     */
    public CrmSettings update(CrmContext ctx, SettingsRequest req) {
        CrmSettings current = ctx.settings();
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.version() == null) errors.put("version", "is required");
        if (req.defaultCurrency() == null || !validCurrency(req.defaultCurrency()))
            errors.put("defaultCurrency", "must be an ISO 4217 currency code");
        if (req.dealRottingDays() != null && (req.dealRottingDays() < 1 || req.dealRottingDays() > 365))
            errors.put("dealRottingDays", "must be between 1 and 365");
        if (req.allowChannelMove() == null) errors.put("allowChannelMove", "is required");
        if (req.timelineShowsWhatsappMessages() == null) errors.put("timelineShowsWhatsappMessages", "is required");
        if (req.defaultPipelineId() != null
                && !pipelineIds.active(ctx.workspaceId()).contains(req.defaultPipelineId()))
            errors.put("defaultPipelineId", "must be an active pipeline");
        checkList(errors, "lifecycleStatuses", req.lifecycleStatuses(), current.getLifecycleStatuses());
        checkList(errors, "dealSources", req.dealSources(), current.getDealSources());
        checkList(errors, "taskTypes", req.taskTypes(), current.getTaskTypes());
        checkList(errors, "lostReasons", req.lostReasons(), current.getLostReasons());
        checkList(errors, "industries", req.industries(), current.getIndustries());
        if (!errors.isEmpty()) throw new ValidationException(errors);

        Update update = new Update()
                .set("defaultCurrency", req.defaultCurrency())
                .set("dealRottingDays", req.dealRottingDays())
                .set("allowChannelMove", req.allowChannelMove())
                .set("timelineShowsWhatsappMessages", req.timelineShowsWhatsappMessages())
                .set("lifecycleStatuses", req.lifecycleStatuses())
                .set("dealSources", req.dealSources())
                .set("taskTypes", req.taskTypes())
                .set("lostReasons", req.lostReasons())
                .set("industries", req.industries())
                .set("lastModifiedBy", ctx.userId())
                .set("lastUpdateTime", Instant.now())
                .inc("version", 1);
        CrmSettings saved = mongo.findAndModify(
                new Query(Criteria.where("_id").is(current.getId()).and("workspaceId").is(ctx.workspaceId())
                        .and("version").is(req.version())),
                update, FindAndModifyOptions.options().returnNew(true), CrmSettings.class);
        if (saved != null) return saved;
        if (repository.findById(current.getId()).isEmpty()) throw new NotFoundException("CRM settings not found");
        throw CrmErrors.conflict("VERSION_CONFLICT", "The settings were changed by someone else; reload and retry",
                Map.of());
    }

    private static boolean validCurrency(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void checkList(Map<String, String> errors, String name, List<PicklistItem> items,
            List<PicklistItem> stored) {
        if (items == null) {
            errors.put(name, "is required");
            return;
        }
        Set<String> seen = new HashSet<>();
        int defaults = 0;
        for (int i = 0; i < items.size(); i++) {
            PicklistItem it = items.get(i);
            String at = name + "[" + i + "]";
            if (it == null) {
                errors.put(at, "must not be null");
                continue;
            }
            if (it.key() == null || !KEY.matcher(it.key()).matches()) {
                errors.put(at + ".key", "must match ^[a-z][a-z0-9_]{0,39}$");
            } else if (!seen.add(it.key())) {
                errors.put(at + ".key", "is duplicated");
            }
            if (it.label() == null || it.label().isBlank()) errors.put(at + ".label", "must not be blank");
            if (it.isDefault()) defaults++;
        }
        if (defaults > 1) errors.put(name, "has more than one default");
        for (PicklistItem old : stored) {
            if (!seen.contains(old.key())) {
                errors.putIfAbsent(name, "may not drop '" + old.key() + "'; deactivate it instead");
            }
        }
    }
}
