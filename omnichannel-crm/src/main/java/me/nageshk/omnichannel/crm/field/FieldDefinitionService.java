package me.nageshk.omnichannel.crm.field;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.field.dto.FieldDefinitionRequest;
import me.nageshk.omnichannel.crm.field.dto.FieldOptionRequest;
import me.nageshk.omnichannel.crm.field.dto.ReorderRequest;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * A brand's custom field definitions (spec §6.5) and the check of a record's {@code customFields} against them.
 * {@code maxCustomFields} bounds the active definitions of each entity type.
 */
@Service
public class FieldDefinitionService {

    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9_]{1,39}$");
    private static final Pattern OPTION_KEY = Pattern.compile("^[a-z0-9][a-z0-9_]{0,39}$");
    private static final Set<TargetType> ENTITIES = Set.of(TargetType.CONTACT, TargetType.COMPANY, TargetType.DEAL);
    private static final int MAX_LABEL = 60;
    private static final int MAX_OPTIONS = 100;

    private final MongoTemplate mongo;
    private final PeopleDirectory people;
    private final ActivityLog activities;
    private final Clock clock;

    FieldDefinitionService(MongoTemplate mongo, PeopleDirectory people, ActivityLog activities, Clock clock) {
        this.mongo = mongo;
        this.people = people;
        this.activities = activities;
        this.clock = clock;
    }

    // --- values on records -------------------------------------------------------------------------------

    /**
     * The {@code customFields} to store for a record of {@code entity}: the stored values with the request's applied
     * and checked. Problems go to {@code errors} as {@code customFields.<key>}.
     */
    public Map<String, Object> values(CrmContext ctx, TargetType entity, Map<String, Object> stored,
            Map<String, Object> incoming, boolean creating, boolean enforceRequired, Map<String, String> errors) {
        if (incoming == null && !creating) return stored == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stored);
        return validator(ctx).validate(definitions(ctx.workspaceId(), entity), stored, incoming, creating,
                enforceRequired, errors);
    }

    private FieldValidator validator(CrmContext ctx) {
        return new FieldValidator(id -> people.isBrandUser(ctx.workspaceId(), id));
    }

    private List<CrmFieldDefinition> definitions(String ws, TargetType entity) {
        return mongo.find(new Query(Criteria.where("workspaceId").is(ws).and("entityType").is(entity))
                .with(Sort.by("position", "creationTime")), CrmFieldDefinition.class);
    }

    // --- definitions ---------------------------------------------------------------------------------------

    public List<CrmFieldDefinition> list(CrmContext ctx, TargetType entityType, boolean includeInactive) {
        Criteria c = Criteria.where("workspaceId").is(ctx.workspaceId()).and("entityType").is(entity(entityType));
        if (!includeInactive) c = c.and("active").is(true);
        return mongo.find(new Query(c).with(Sort.by("position", "creationTime")), CrmFieldDefinition.class);
    }

    public CrmFieldDefinition create(CrmContext ctx, FieldDefinitionRequest req) {
        ctx.require("crm:settings");
        Map<String, String> errors = new LinkedHashMap<>();
        TargetType entity = parseEntity(req.entityType(), errors);
        FieldType type = parseType(req.type(), errors);
        String key = req.key() == null ? null : req.key().trim();
        if (key == null || !KEY.matcher(key).matches()) {
            errors.put("key", "must start with a lower-case letter and use 2 to 40 letters, digits or _");
        }
        String label = label(req.label(), errors);
        List<FieldOption> options = options(type, req.options(), List.of(), errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);

        CrmFieldDefinition d = new CrmFieldDefinition();
        d.setWorkspaceId(ctx.workspaceId());
        d.setCustomerId(ctx.customerId());
        d.setEntityType(entity);
        d.setKey(key);
        d.setLabel(label);
        d.setType(type);
        d.setOptions(options);
        d.setRequired(Boolean.TRUE.equals(req.required()));
        d.setShowInList(Boolean.TRUE.equals(req.showInList()));
        d.setShowInCard(Boolean.TRUE.equals(req.showInCard()));
        d.setActive(!Boolean.FALSE.equals(req.active()));
        d.setDefaultValue(defaultValue(ctx, d, req.defaultValue()));
        d.setCreatedBy(ctx.userId());
        d.setLastModifiedBy(ctx.userId());
        if (d.isActive()) requireRoom(ctx, entity);
        d.setPosition(nextPosition(ctx.workspaceId(), entity));
        CrmFieldDefinition saved;
        try {
            saved = mongo.insert(d);
        } catch (DuplicateKeyException e) {
            throw CrmErrors.conflict("FIELD_KEY_TAKEN", "Another " + entity.name().toLowerCase() + " field uses key "
                    + key, Map.of("key", key));
        }
        changed(ctx, "Added field " + label, saved);
        return saved;
    }

    public CrmFieldDefinition update(CrmContext ctx, String id, FieldDefinitionRequest req) {
        ctx.require("crm:settings");
        CrmFieldDefinition current = load(ctx, id);
        if (req.key() != null && !req.key().trim().equals(current.getKey()))
            throw CrmErrors.badRequest("FIELD_IMMUTABLE", "A field's key cannot change");
        if (req.entityType() != null && !req.entityType().trim().equalsIgnoreCase(current.getEntityType().name()))
            throw CrmErrors.badRequest("FIELD_IMMUTABLE", "A field's record type cannot change");
        if (req.type() != null && !req.type().trim().equalsIgnoreCase(current.getType().name()))
            throw CrmErrors.badRequest("FIELD_IMMUTABLE", "A field's type cannot change");
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.version() == null) errors.put("version", "is required");
        String label = req.label() == null ? current.getLabel() : label(req.label(), errors);
        List<FieldOption> options = req.options() == null ? current.getOptions()
                : options(current.getType(), req.options(), current.getOptions(), errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        boolean active = req.active() == null ? current.isActive() : req.active();
        if (active && !current.isActive()) requireRoom(ctx, current.getEntityType());

        CrmFieldDefinition probe = new CrmFieldDefinition();
        probe.setKey(current.getKey());
        probe.setType(current.getType());
        probe.setOptions(options);
        probe.setActive(true);
        // null keeps the default; a blank string clears it
        Object def = req.defaultValue() == null ? current.getDefaultValue() : defaultValue(ctx, probe, req.defaultValue());

        Update u = new Update().set("label", label).set("options", options)
                .set("required", req.required() == null ? current.isRequired() : req.required())
                .set("showInList", req.showInList() == null ? current.isShowInList() : req.showInList())
                .set("showInCard", req.showInCard() == null ? current.isShowInCard() : req.showInCard())
                .set("active", active).set("defaultValue", def)
                .set("lastModifiedBy", ctx.userId()).set("lastUpdateTime", Instant.now(clock)).inc("version", 1);
        CrmFieldDefinition saved = guarded(ctx, id, req.version(), u);
        changed(ctx, "Changed field " + saved.getLabel(), saved);
        return saved;
    }

    public void delete(CrmContext ctx, String id) {
        ctx.require("crm:settings");
        CrmFieldDefinition current = load(ctx, id);
        if (!current.isActive()) return;
        mongo.updateFirst(byId(ctx, id), new Update().set("active", false).set("lastModifiedBy", ctx.userId())
                .set("lastUpdateTime", Instant.now(clock)).inc("version", 1), CrmFieldDefinition.class);
        changed(ctx, "Removed field " + current.getLabel(), current);
    }

    public void reorder(CrmContext ctx, ReorderRequest req) {
        ctx.require("crm:settings");
        Map<String, String> errors = new LinkedHashMap<>();
        TargetType entity = parseEntity(req.entityType(), errors);
        if (req.ids() == null || req.ids().isEmpty()) errors.put("ids", "is required");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        List<CrmFieldDefinition> all = list(ctx, entity, true);
        Set<String> known = new HashSet<>(all.stream().map(CrmFieldDefinition::getId).toList());
        if (!known.containsAll(req.ids()) || new HashSet<>(req.ids()).size() != req.ids().size())
            throw new ValidationException("ids", "must be distinct fields of this record type");
        Instant now = Instant.now(clock);
        for (int i = 0; i < req.ids().size(); i++) {
            mongo.updateFirst(byId(ctx, req.ids().get(i)), new Update().set("position", i)
                    .set("lastModifiedBy", ctx.userId()).set("lastUpdateTime", now).inc("version", 1),
                    CrmFieldDefinition.class);
        }
        // fields the request left out keep their relative order after the listed ones
        int next = req.ids().size();
        for (CrmFieldDefinition d : all) {
            if (!req.ids().contains(d.getId())) {
                mongo.updateFirst(byId(ctx, d.getId()), new Update().set("position", next++), CrmFieldDefinition.class);
            }
        }
        activities.record(ctx, ActivityType.SETTINGS_CHANGED, new CrmTarget(TargetType.SETTINGS, ctx.workspaceId()),
                List.of(), null, null, "Reordered " + entity.name().toLowerCase() + " fields",
                new LinkedHashMap<>(Map.of("entityType", entity.name())));
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private CrmFieldDefinition load(CrmContext ctx, String id) {
        CrmFieldDefinition d = mongo.findOne(byId(ctx, id), CrmFieldDefinition.class);
        if (d == null) throw new NotFoundException("Field not found");
        return d;
    }

    private static Query byId(CrmContext ctx, String id) {
        return new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId()));
    }

    private CrmFieldDefinition guarded(CrmContext ctx, String id, Long version, Update u) {
        CrmFieldDefinition saved = mongo.findAndModify(new Query(Criteria.where("_id").is(id)
                .and("workspaceId").is(ctx.workspaceId()).and("version").is(version)), u,
                FindAndModifyOptions.options().returnNew(true), CrmFieldDefinition.class);
        if (saved != null) return saved;
        if (mongo.exists(byId(ctx, id), CrmFieldDefinition.class)) {
            throw CrmErrors.conflict("VERSION_CONFLICT", "The field was changed by someone else; reload and retry",
                    Map.of());
        }
        throw new NotFoundException("Field not found");
    }

    private void requireRoom(CrmContext ctx, TargetType entity) {
        Integer max = ctx.settings().getMaxCustomFields();
        if (max == null) return;
        long active = mongo.count(new Query(Criteria.where("workspaceId").is(ctx.workspaceId())
                .and("entityType").is(entity).and("active").is(true)), CrmFieldDefinition.class);
        if (active >= max) {
            throw CrmErrors.conflict("LIMIT_REACHED", "At most " + max + " " + entity.name().toLowerCase()
                    + " fields are allowed", Map.of("limit", String.valueOf(max)));
        }
    }

    private int nextPosition(String ws, TargetType entity) {
        CrmFieldDefinition last = mongo.findOne(new Query(Criteria.where("workspaceId").is(ws).and("entityType")
                .is(entity)).with(Sort.by(Sort.Direction.DESC, "position")).limit(1), CrmFieldDefinition.class);
        return last == null ? 0 : last.getPosition() + 1;
    }

    /** The default normalised like a stored value; null when none was given. */
    private Object defaultValue(CrmContext ctx, CrmFieldDefinition d, Object raw) {
        if (FieldValidator.blank(raw)) return null;
        CrmFieldDefinition probe = new CrmFieldDefinition();
        probe.setKey(d.getKey());
        probe.setType(d.getType());
        probe.setOptions(d.getOptions());
        probe.setActive(true);
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> out = validator(ctx).validate(List.of(probe), Map.of(), Map.of(d.getKey(), raw), false,
                false, errors);
        if (!errors.isEmpty()) throw new ValidationException("defaultValue", errors.values().iterator().next());
        return out.get(d.getKey());
    }

    private void changed(CrmContext ctx, String summary, CrmFieldDefinition d) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fieldId", d.getId());
        payload.put("entityType", d.getEntityType().name());
        payload.put("key", d.getKey());
        activities.record(ctx, ActivityType.SETTINGS_CHANGED, new CrmTarget(TargetType.SETTINGS, ctx.workspaceId()),
                List.of(), null, null, summary, payload);
    }

    private static TargetType entity(TargetType t) {
        if (t == null || !ENTITIES.contains(t)) throw new ValidationException("entityType", "must be CONTACT, COMPANY or DEAL");
        return t;
    }

    private static TargetType parseEntity(String raw, Map<String, String> errors) {
        try {
            TargetType t = raw == null ? null : TargetType.valueOf(raw.trim().toUpperCase());
            if (t != null && ENTITIES.contains(t)) return t;
        } catch (IllegalArgumentException bad) {
            // reported below
        }
        errors.put("entityType", "must be CONTACT, COMPANY or DEAL");
        return null;
    }

    private static FieldType parseType(String raw, Map<String, String> errors) {
        try {
            if (raw != null) return FieldType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException bad) {
            // reported below
        }
        errors.put("type", "is not a field type");
        return null;
    }

    private static String label(String raw, Map<String, String> errors) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) errors.put("label", "is required");
        else if (s.length() > MAX_LABEL) errors.put("label", "must be at most " + MAX_LABEL + " characters");
        return s;
    }

    /** The options of a choice field in request order; saved keys may be deactivated, never dropped. */
    private static List<FieldOption> options(FieldType type, List<FieldOptionRequest> requested,
            List<FieldOption> saved, Map<String, String> errors) {
        boolean choice = type == FieldType.SELECT || type == FieldType.MULTI_SELECT;
        if (!choice) {
            if (requested != null && !requested.isEmpty()) errors.put("options", "only choice fields have options");
            return new ArrayList<>();
        }
        if (requested == null || requested.isEmpty()) {
            errors.put("options", "a choice field needs at least one option");
            return new ArrayList<>();
        }
        if (requested.size() > MAX_OPTIONS) errors.put("options", "at most " + MAX_OPTIONS + " options");
        List<FieldOption> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            FieldOptionRequest o = requested.get(i);
            String key = o == null || o.key() == null ? "" : o.key().trim();
            String label = o == null || o.label() == null ? "" : o.label().trim();
            if (!OPTION_KEY.matcher(key).matches()) errors.put("options[" + i + "].key", "use 1 to 40 a-z, 0-9 or _");
            else if (!keys.add(key)) errors.put("options[" + i + "].key", "is used twice");
            if (label.isEmpty() || label.length() > MAX_LABEL)
                errors.put("options[" + i + "].label", "must be 1 to " + MAX_LABEL + " characters");
            String color = o == null ? null : o.color();
            if (color != null && !color.matches("^#[0-9a-fA-F]{6}$"))
                errors.put("options[" + i + "].color", "must look like #1a2b3c");
            out.add(new FieldOption(key, label, color, i, o == null || !Boolean.FALSE.equals(o.active())));
        }
        for (FieldOption s : saved) {
            if (!keys.contains(s.key())) errors.put("options", "option " + s.key() + " is in use; deactivate it instead");
        }
        return out;
    }
}
