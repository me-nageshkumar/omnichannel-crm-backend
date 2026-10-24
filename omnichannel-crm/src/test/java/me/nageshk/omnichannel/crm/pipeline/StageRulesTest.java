package me.nageshk.omnichannel.crm.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.pipeline.dto.StageRequest;
import org.junit.jupiter.api.Test;

class StageRulesTest {

    private static StageRequest lane(String id, String name, StageType type) {
        return new StageRequest(id, name, null, null, type, null, null);
    }

    private static CrmStage stored(String id, String name, StageType type) {
        CrmStage s = new CrmStage();
        s.setId(id);
        s.setName(name);
        s.setType(type);
        return s;
    }

    @Test
    void needsAnOpenStage() {
        assertThatThrownBy(() -> StageRules.validate(List.of(lane(null, "Won", StageType.WON)), List.of(), id -> 0))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsKey("stages"));
    }

    @Test
    void duplicateNamesRejected() {
        assertThatThrownBy(() -> StageRules.validate(
                List.of(lane(null, "New", StageType.OPEN), lane(null, " new ", StageType.OPEN)), List.of(), id -> 0))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsKey("stages[1].name"));
    }

    @Test
    void removedStageWithDealsNeedsTarget() {
        List<CrmStage> existing = List.of(stored("a", "A", StageType.OPEN), stored("b", "B", StageType.OPEN));
        assertThatThrownBy(() -> StageRules.validate(List.of(lane("a", "A", StageType.OPEN)), existing,
                id -> id.equals("b") ? 3 : 0))
                .isInstanceOfSatisfying(CodedException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("STAGE_HAS_DEALS");
                    assertThat(e.extra()).containsEntry("stageId", "b").containsEntry("count", "3");
                });
        StageRules.Plan plan = StageRules.validate(List.of(lane("a", "A", StageType.OPEN),
                new StageRequest("b", null, null, null, null, null, "a")), existing, id -> id.equals("b") ? 3 : 0);
        assertThat(plan.moves()).isEqualTo(Map.of("b", "a"));
        assertThat(plan.stages()).extracting(CrmStage::getId).containsExactly("a");
    }

    @Test
    void retypeWithDealsRejected() {
        List<CrmStage> existing = List.of(stored("a", "A", StageType.OPEN), stored("b", "B", StageType.OPEN));
        assertThatThrownBy(() -> StageRules.validate(
                List.of(lane("a", "A", StageType.OPEN), lane("b", "B", StageType.WON)), existing,
                id -> id.equals("b") ? 2 : 0))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(e.code()).isEqualTo("STAGE_TYPE_HAS_DEALS"));
    }

    @Test
    void positionsFollowListOrder() {
        StageRules.Plan plan = StageRules.validate(List.of(lane(null, "X", StageType.OPEN),
                lane(null, "Y", StageType.WON), lane(null, "Z", StageType.LOST)), List.of(), id -> 0);
        assertThat(plan.stages()).extracting(CrmStage::getName).containsExactly("X", "Y", "Z");
        assertThat(plan.stages()).extracting(CrmStage::getPosition).containsExactly(1024.0, 2048.0, 3072.0);
        assertThat(plan.stages()).extracting(CrmStage::getProbability).containsExactly(0, 100, 0);
    }

    @Test
    void moveTargetMustBeAnExistingKeptStage() {
        List<CrmStage> existing = List.of(stored("a", "A", StageType.OPEN), stored("b", "B", StageType.OPEN));
        assertThatThrownBy(() -> StageRules.validate(List.of(lane("a", "A", StageType.OPEN),
                lane(null, "New", StageType.OPEN), new StageRequest("b", null, null, null, null, null, "zzz")),
                existing, id -> 1))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsKey("stages.b.moveToStageId"));
    }

    @Test
    void colourMustBeSixDigitHexOrAbsent() {
        StageRequest red = new StageRequest(null, "A", "red", null, StageType.OPEN, null, null);
        StageRequest shortHex = new StageRequest(null, "B", "#fff", null, StageType.OPEN, null, null);
        assertThatThrownBy(() -> StageRules.validate(List.of(red, shortHex), List.of(), id -> 0))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fields())
                        .containsKeys("stages[0].color", "stages[1].color"));
        StageRules.Plan ok = StageRules.validate(List.of(new StageRequest(null, "A", "#A0b1C2", null, StageType.OPEN,
                null, null), lane(null, "B", StageType.OPEN)), List.of(), id -> 0);
        assertThat(ok.stages()).extracting(CrmStage::getColor).containsExactly("#A0b1C2", null);
    }

    @Test
    void atMostThirtyLanes() {
        List<StageRequest> many = new java.util.ArrayList<>();
        for (int i = 0; i < 31; i++) many.add(lane(null, "L" + i, StageType.OPEN));
        assertThatThrownBy(() -> StageRules.validate(many, List.of(), id -> 0))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsKey("stages"));
        assertThat(StageRules.validate(many.subList(0, 30), List.of(), id -> 0).stages()).hasSize(30);
    }
}
