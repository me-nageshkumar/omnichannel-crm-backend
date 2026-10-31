package me.nageshk.omnichannel.crm.segment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.segment.SegmentFields.Known;
import me.nageshk.omnichannel.crm.segment.SegmentScope.Flag;
import org.junit.jupiter.api.Test;

class SegmentScopeTest {

    static final Known KNOWN = new Known(Set.of(), Map.of(), Set.of(), Map.of(), Set.of());
    static final Map<String, Set<String>> STAGES = Map.of("p1", Set.of("s1"), "p2", Set.of("s2"));

    static SegmentScope scope(boolean allPipelines) {
        return new SegmentScope(KNOWN, Set.of("p1", "p2"), STAGES, Set.of(), Set.of(), allPipelines);
    }

    static SegmentRule rule(String field, String operator, Object value) {
        return new SegmentRule(field, operator, value);
    }

    static SegmentRuleGroup and(SegmentRuleNode... rules) {
        return new SegmentRuleGroup("and", false, List.of(rules));
    }

    private static Flag flag(SegmentScope scope, SegmentRule r, SegmentRuleNode... siblings) {
        List<SegmentRuleNode> all = new java.util.ArrayList<>(List.of(siblings));
        all.add(r);
        return scope.flag(r, new SegmentRuleGroup("and", false, all));
    }

    @Test
    void aPipelineOrStageThatIsGoneIsMissingForSomeoneWhoSeesEveryPipeline() {
        SegmentRule pipeline = rule("deal.pipelineId", "is", "deleted");
        assertThat(flag(scope(true), pipeline)).isEqualTo(Flag.MISSING);
        assertThat(flag(scope(true), rule("deal.stageId", "is", "deleted"), rule("deal.pipelineId", "is", "p1")))
                .isEqualTo(Flag.MISSING);
    }

    @Test
    void aRestrictedCallerCannotTellGoneFromHidden() {
        assertThat(flag(scope(false), rule("deal.pipelineId", "is", "hidden"))).isEqualTo(Flag.INACCESSIBLE);
        assertThat(flag(scope(false), rule("deal.stageId", "is", "hidden"), rule("deal.pipelineId", "is", "p1")))
                .isEqualTo(Flag.INACCESSIBLE);
    }

    @Test
    void aStageIsCheckedAgainstTheGroupsPipeline() {
        SegmentRule stage = rule("deal.stageId", "is", "s2");
        assertThat(flag(scope(true), stage, rule("deal.pipelineId", "is", "p2"))).isEqualTo(Flag.NONE);
        assertThat(flag(scope(true), stage, rule("deal.pipelineId", "in", List.of("p1", "p2")))).isEqualTo(Flag.NONE);
        assertThat(flag(scope(true), stage, rule("deal.pipelineId", "is", "p1"))).isEqualTo(Flag.MISSING);
        assertThat(flag(scope(false), stage, rule("deal.pipelineId", "is", "p1"))).isEqualTo(Flag.MISSING);
    }

    @Test
    void aStageSavedWithoutAChosenPipelineIsCheckedAgainstEveryStage() {
        assertThat(flag(scope(true), rule("deal.stageId", "is", "s2"), rule("deal.pipelineId", "notIn", List.of("p1"))))
                .isEqualTo(Flag.NONE);
    }
}
