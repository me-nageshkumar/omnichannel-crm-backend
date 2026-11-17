package me.nageshk.omnichannel.common.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GstinTest {

    @Test
    void aGstinMustHaveTheShapeAndTheState() {
        assertThat(Gstin.problem("29ABCDE1234F1Z5", "29")).isEmpty();
        assertThat(Gstin.problem("29ABCDE1234F1Z5", "27")).contains("does not belong to the chosen state");
        assertThat(Gstin.problem("29abcde1234f1z5", "29")).contains("is not a GSTIN");
        assertThat(Gstin.problem("29ABCDE1234F1Z", "29")).contains("is not a GSTIN");
        assertThat(Gstin.problem("XXABCDE1234F1Z5", "29")).contains("is not a GSTIN");
        assertThat(Gstin.problem("29ABCDE1234F0Z5", "29")).contains("is not a GSTIN");
    }

    @Test
    void aGstinIsOptional() {
        assertThat(Gstin.problem(null, "29")).isEmpty();
        assertThat(Gstin.problem("  ", "29")).isEmpty();
    }

    @Test
    void theStatesAreTheGstStateCodes() {
        assertThat(IndianStates.nameOf("29")).contains("Karnataka");
        assertThat(IndianStates.nameOf("27")).contains("Maharashtra");
        assertThat(IndianStates.nameOf("07")).contains("Delhi");
        assertThat(IndianStates.nameOf("99")).isEmpty();
        assertThat(IndianStates.nameOf(null)).isEmpty();
        assertThat(IndianStates.all()).extracting(IndianStates.State::code).doesNotHaveDuplicates().hasSizeGreaterThan(35);
        assertThat(IndianStates.all()).allSatisfy(s -> assertThat(s.code()).matches("[0-9]{2}"));
    }

    @Test
    void onlySomeOrdersChangeTheTerm() {
        assertThat(OrderKind.NEW.changesTerm()).isTrue();
        assertThat(OrderKind.RENEWAL.changesTerm()).isTrue();
        assertThat(OrderKind.UPGRADE.changesTerm()).isTrue();
        assertThat(OrderKind.CHANGE.changesTerm()).isTrue();
        assertThat(OrderKind.CANCEL_PLAN.changesTerm()).isTrue();
        assertThat(OrderKind.ADDON.changesTerm()).isFalse();
        assertThat(OrderKind.CANCEL_ADDON.changesTerm()).isFalse();
        assertThat(OrderKind.ADJUST.changesTerm()).isFalse();
    }
}
