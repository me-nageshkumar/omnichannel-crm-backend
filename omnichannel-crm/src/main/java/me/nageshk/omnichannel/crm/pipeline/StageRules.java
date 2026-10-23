package me.nageshk.omnichannel.crm.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.pipeline.dto.StageRequest;

/** The rules of editing a pipeline's lanes, free of storage: the caller supplies deal counts per lane. */
public final class StageRules {

    static final double POSITION_GAP = 1024.0;
    static final int MAX_NAME = 60;

    /** The new lanes (positions 1024·n in list order) and, per removed lane that holds deals, where they go. */
    public record Plan(List<CrmStage> stages, Map<String, String> moves) {}

    private StageRules() {}

    /**
     * @param requested lanes in the wanted order
     * @param existing the stored lanes (empty when creating)
     * @param dealsInStage deals per stored lane id
     * @throws ValidationException (400) on a rule break
     * @throws me.nageshk.omnichannel.common.api.CodedException 409 STAGE_HAS_DEALS or STAGE_TYPE_HAS_DEALS
     */
    public static Plan validate(List<StageRequest> requested, List<CrmStage> existing, ToLongFunction<String> dealsInStage) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (requested == null || requested.isEmpty()) {
            throw new ValidationException("stages", "needs at least one OPEN stage");
        }
        Map<String, CrmStage> stored = new HashMap<>();
        existing.forEach(s -> stored.put(s.getId(), s));

        List<CrmStage> kept = new ArrayList<>();
        Map<String, String> removalTargets = new HashMap<>();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            StageRequest r = requested.get(i);
            String at = "stages[" + i + "]";
            if (r == null) {
                errors.put(at, "must not be null");
                continue;
            }
            boolean id = r.id() != null && !r.id().isBlank();
            if (id && !ids.add(r.id())) errors.put(at + ".id", "is duplicated");
            if (id && r.moveToStageId() != null && stored.containsKey(r.id())) {
                removalTargets.put(r.id(), r.moveToStageId());
                continue;
            }
            String name = r.name() == null ? "" : r.name().trim();
            if (name.isEmpty()) errors.put(at + ".name", "must not be blank");
            else if (name.length() > MAX_NAME) errors.put(at + ".name", "must be at most " + MAX_NAME + " characters");
            else if (!names.add(name.toLowerCase(Locale.ROOT))) errors.put(at + ".name", "is duplicated");
            if (r.type() == null) errors.put(at + ".type", "is required");
            if (r.probability() != null && (r.probability() < 0 || r.probability() > 100))
                errors.put(at + ".probability", "must be between 0 and 100");
            if (r.wipLimit() != null && r.wipLimit() < 0) errors.put(at + ".wipLimit", "must be 0 or more");
            CrmStage s = new CrmStage();
            s.setId(id ? r.id() : UUID.randomUUID().toString());
            s.setName(name);
            s.setColor(r.color());
            s.setType(r.type());
            s.setProbability(r.probability() != null ? r.probability()
                    : r.type() == StageType.WON ? 100 : 0);
            s.setWipLimit(r.wipLimit());
            s.setPosition(POSITION_GAP * (kept.size() + 1));
            kept.add(s);
        }
        if (kept.stream().noneMatch(s -> s.getType() == StageType.OPEN)) {
            errors.putIfAbsent("stages", "needs at least one OPEN stage");
        }
        Set<String> keptIds = new HashSet<>();
        kept.forEach(s -> keptIds.add(s.getId()));
        removalTargets.forEach((from, to) -> {
            if (!keptIds.contains(to) || !stored.containsKey(to))
                errors.put("stages." + from + ".moveToStageId", "must be an existing stage that is kept");
        });
        if (!errors.isEmpty()) throw new ValidationException(errors);

        Map<String, String> moves = new LinkedHashMap<>();
        for (CrmStage old : existing) {
            if (keptIds.contains(old.getId())) continue;
            long deals = dealsInStage.applyAsLong(old.getId());
            if (deals == 0) continue;
            String target = removalTargets.get(old.getId());
            if (target == null) {
                throw CrmErrors.conflict("STAGE_HAS_DEALS", "Stage '" + old.getName() + "' holds deals; give a moveToStageId",
                        Map.of("stageId", old.getId(), "count", String.valueOf(deals)));
            }
            moves.put(old.getId(), target);
        }
        for (CrmStage s : kept) {
            CrmStage old = stored.get(s.getId());
            if (old == null || old.getType() == s.getType()) continue;
            long deals = dealsInStage.applyAsLong(old.getId());
            if (deals > 0) {
                throw CrmErrors.conflict("STAGE_TYPE_HAS_DEALS",
                        "Stage '" + old.getName() + "' holds deals; move them before changing its type",
                        Map.of("stageId", old.getId(), "count", String.valueOf(deals)));
            }
        }
        return new Plan(kept, moves);
    }
}
