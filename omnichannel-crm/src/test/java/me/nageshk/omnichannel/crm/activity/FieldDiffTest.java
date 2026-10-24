package me.nageshk.omnichannel.crm.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldDiffTest {

    @Test
    void onlyChangedKeysInAfter() {
        Map<String, Object> before = Map.of("name", "A", "city", "X", "gone", 1);
        Map<String, Object> after = Map.of("name", "B", "city", "X");
        assertThat(ActivityLog.diff(before, after)).containsExactly(new FieldDiff("name", "A", "B"));
    }

    @Test
    void bigDecimalScaleIgnored() {
        assertThat(ActivityLog.diff(Map.of("amount", new BigDecimal("1.0")),
                Map.of("amount", new BigDecimal("1.00")))).isEmpty();
    }

    @Test
    void nullToValueIsADiff() {
        Map<String, Object> before = new HashMap<>();
        before.put("phone", null);
        List<FieldDiff> diff = ActivityLog.diff(before, Map.of("phone", "123"));
        assertThat(diff).containsExactly(new FieldDiff("phone", null, "123"));
        assertThat(ActivityLog.diff(Map.of(), Map.of("phone", "1"))).containsExactly(new FieldDiff("phone", null, "1"));
    }
}
