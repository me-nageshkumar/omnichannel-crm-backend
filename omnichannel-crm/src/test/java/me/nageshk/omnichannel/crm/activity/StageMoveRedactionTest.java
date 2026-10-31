package me.nageshk.omnichannel.crm.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.access.PipelineAccess;
import me.nageshk.omnichannel.crm.activity.dto.ActivityView;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import org.junit.jupiter.api.Test;

class StageMoveRedactionTest {

    private static final PipelineAccess SALES = new PipelineAccess(false, Set.of("sales"));
    private static final Set<String> HIDDEN = Set.of("h1", "h2");

    private static ActivityView entry(String type, String summary, Object... kv) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) payload.put((String) kv[i], kv[i + 1]);
        return new ActivityView("a1", type, new CrmTarget(TargetType.DEAL, "d1"), summary, payload, null, "USER",
                Instant.parse("2026-10-31T10:00:00Z"));
    }

    private static ActivityView crossPipeline(String from, String to) {
        return entry("STAGE_CHANGED", "Moved from A (Hidden) to B (Sales)", "fromPipelineId", from, "toPipelineId", to,
                "fromStageId", "s-a", "toStageId", "s-b", "fromStageName", "A", "toStageName", "B", "toPipelineName",
                "Sales", "fromStageEnteredAt", "2026-10-01T00:00:00Z");
    }

    @Test
    void hiddenSourceIsRedacted() {
        ActivityView r = StageMoveRedaction.redact(crossPipeline("hidden", "sales"), SALES, HIDDEN);
        assertThat(r.payload()).doesNotContainKeys("fromPipelineId", "fromStageId", "fromStageName", "fromStageEnteredAt");
        assertThat(r.payload()).containsEntry("toStageName", "B").containsEntry("toPipelineName", "Sales")
                .containsEntry("toStageId", "s-b").containsEntry("toPipelineId", "sales");
        assertThat(r.summary()).isEqualTo("Moved into B (Sales) from another pipeline");
    }

    @Test
    void hiddenDestinationIsRedacted() {
        ActivityView r = StageMoveRedaction.redact(crossPipeline("sales", "hidden"), SALES, HIDDEN);
        assertThat(r.payload()).doesNotContainKeys("toPipelineId", "toStageId", "toStageName", "toPipelineName");
        assertThat(r.payload()).containsEntry("fromStageName", "A").containsEntry("fromPipelineId", "sales");
        assertThat(r.summary()).isEqualTo("Moved from A to another pipeline");
    }

    @Test
    void bothSidesHiddenByPipelineIds() {
        ActivityView r = StageMoveRedaction.redact(crossPipeline("hidden", "hidden2"), SALES, HIDDEN);
        assertThat(r.payload()).doesNotContainKeys("fromPipelineId", "fromStageId", "fromStageName",
                "fromStageEnteredAt", "toPipelineId", "toStageId", "toStageName", "toPipelineName");
        assertThat(r.summary()).isEqualTo("Moved within another pipeline");
    }

    @Test
    void bothSidesHiddenByStageIds() {
        ActivityView a = entry("STAGE_CHANGED", "Moved from A to B", "fromStageId", "h1", "toStageId", "h2",
                "fromStageName", "A", "toStageName", "B");
        ActivityView r = StageMoveRedaction.redact(a, SALES, HIDDEN);
        assertThat(r.payload()).isEmpty();
        assertThat(r.summary()).isEqualTo("Moved within another pipeline");
    }

    @Test
    void hiddenSourceStageWithinTheSamePipelineEntry() {
        ActivityView a = entry("STAGE_CHANGED", "Moved from A to B", "fromStageId", "h1", "toStageId", "s-b",
                "fromStageName", "A", "toStageName", "B");
        assertThat(StageMoveRedaction.redact(a, SALES, HIDDEN).summary())
                .isEqualTo("Moved into B from another pipeline");
    }

    @Test
    void visibleEntryIsUntouched() {
        ActivityView a = crossPipeline("sales", "sales");
        assertThat(StageMoveRedaction.redact(a, SALES, HIDDEN)).isSameAs(a);
    }

    @Test
    void unrestrictedCallerIsUntouched() {
        ActivityView a = crossPipeline("hidden", "hidden2");
        assertThat(StageMoveRedaction.redact(a, PipelineAccess.ALL, Set.of())).isSameAs(a);
    }

    @Test
    void otherActivityTypesAreUntouched() {
        ActivityView a = entry("DEAL_WON", "Deal won", "stageId", "h1", "fromPipelineId", "hidden");
        assertThat(StageMoveRedaction.redact(a, SALES, HIDDEN)).isSameAs(a);
    }

    @Test
    void missingNamesReadPlainly() {
        ActivityView into = entry("STAGE_CHANGED", "x", "fromPipelineId", "hidden", "toStageId", "s-b");
        assertThat(StageMoveRedaction.redact(into, SALES, HIDDEN).summary())
                .isEqualTo("Moved into a stage from another pipeline");
        ActivityView out = entry("STAGE_CHANGED", "x", "toPipelineId", "hidden", "fromStageId", "s-a");
        assertThat(StageMoveRedaction.redact(out, SALES, HIDDEN).summary())
                .isEqualTo("Moved from a stage to another pipeline");
    }

    @Test
    void storedPayloadIsNotMutated() {
        ActivityView a = crossPipeline("hidden", "sales");
        StageMoveRedaction.redact(a, SALES, HIDDEN);
        assertThat(a.payload()).containsKey("fromStageName");
        assertThat(a.summary()).startsWith("Moved from");
    }
}
