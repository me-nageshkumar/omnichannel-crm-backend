package me.nageshk.omnichannel.crm.segment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.segment.SegmentFields.FieldSpec;
import me.nageshk.omnichannel.crm.segment.SegmentFields.Known;

/**
 * What the caller may name in a rule: their active pipelines and those pipelines' stages (what the catalogue offers),
 * the brand's labels and channel accounts, and the brand's custom fields and attributes ({@link Known}). A rule outside
 * it is {@code MISSING} (the thing is gone) or {@code INACCESSIBLE} (a pipeline or stage the caller cannot see) and
 * matches nothing (spec D C4, C8, C13). {@code allPipelines} says the caller sees every pipeline, so what they do not
 * have is gone or inactive; a restricted caller cannot tell that from one they were not given.
 */
public record SegmentScope(Known known, Set<String> pipelineIds, Map<String, Set<String>> stageIdsByPipeline,
        Set<String> labelIds, Set<String> channelAccountIds, boolean allPipelines) {

    public enum Flag { NONE, MISSING, INACCESSIBLE }

    public Flag flag(SegmentRule r, SegmentRuleGroup group) {
        FieldSpec spec = SegmentFields.spec(r.field(), known).orElse(null);
        if (spec == null) return Flag.MISSING;
        List<String> values = values(r.value());
        if (values.isEmpty()) return Flag.NONE;
        return switch (r.field()) {
            case "deal.pipelineId" -> pipelineIds.containsAll(values) ? Flag.NONE : unavailable();
            case "deal.stageId" -> stageFlag(values, group);
            case "channel.label" -> labelIds.containsAll(values) ? Flag.NONE : Flag.MISSING;
            case "contact.channelAccountId" -> channelAccountIds.containsAll(values) ? Flag.NONE : Flag.MISSING;
            default -> Flag.NONE;
        };
    }

    private Flag unavailable() {
        return allPipelines ? Flag.MISSING : Flag.INACCESSIBLE;
    }

    /** Stages are read against the pipelines the group chooses; a stage of another pipeline can never match there. */
    private Flag stageFlag(List<String> stages, SegmentRuleGroup group) {
        Set<String> all = allStages();
        Set<String> chosen = new HashSet<>();
        boolean choosesPipeline = false;
        for (SegmentRuleNode n : group.rules()) {
            if (n instanceof SegmentRule rule && SegmentFields.choosesPipeline(rule)) {
                choosesPipeline = true;
                values(rule.value()).forEach(p -> chosen.addAll(stageIdsByPipeline.getOrDefault(p, Set.of())));
            }
        }
        if (choosesPipeline ? chosen.containsAll(stages) : all.containsAll(stages)) return Flag.NONE;
        return all.containsAll(stages) ? Flag.MISSING : unavailable();
    }

    private Set<String> allStages() {
        Set<String> out = new HashSet<>();
        stageIdsByPipeline.values().forEach(out::addAll);
        return out;
    }

    /** The specs of every usable rule in the tree, by field key. */
    public Map<String, FieldSpec> specs(SegmentRuleGroup g) {
        Map<String, FieldSpec> out = new HashMap<>();
        collect(g, out);
        return out;
    }

    private void collect(SegmentRuleGroup g, Map<String, FieldSpec> out) {
        for (SegmentRuleNode n : g.rules()) {
            if (n instanceof SegmentRuleGroup child) collect(child, out);
            else if (n instanceof SegmentRule r) SegmentFields.spec(r.field(), known).ifPresent(s -> out.put(r.field(), s));
        }
    }

    private static List<String> values(Object v) {
        if (v instanceof String s) return List.of(s);
        if (v instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        return List.of();
    }
}
