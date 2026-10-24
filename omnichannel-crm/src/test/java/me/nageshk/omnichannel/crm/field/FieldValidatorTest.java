package me.nageshk.omnichannel.crm.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.common.TargetType;
import org.junit.jupiter.api.Test;

class FieldValidatorTest {

    private final FieldValidator validator = new FieldValidator(id -> id.equals("u1"));

    private static CrmFieldDefinition def(String key, FieldType type) {
        CrmFieldDefinition d = new CrmFieldDefinition();
        d.setEntityType(TargetType.DEAL);
        d.setKey(key);
        d.setLabel(key);
        d.setType(type);
        d.setActive(true);
        d.setOptions(new ArrayList<>());
        return d;
    }

    private static CrmFieldDefinition select(String key, FieldType type) {
        CrmFieldDefinition d = def(key, type);
        d.setOptions(new ArrayList<>(List.of(new FieldOption("gold", "Gold", null, 0, true),
                new FieldOption("silver", "Silver", null, 1, true), new FieldOption("bronze", "Bronze", null, 2, false))));
        return d;
    }

    private static Map<String, Object> in(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    private Map<String, Object> ok(CrmFieldDefinition d, Object value) {
        return validator.validate(List.of(d), Map.of(), in(d.getKey(), value), false, true);
    }

    private void bad(CrmFieldDefinition d, Object value) {
        assertThatThrownBy(() -> ok(d, value)).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.fields()).containsKey("customFields." + d.getKey()));
    }

    @Test
    void textTooLong() {
        CrmFieldDefinition d = def("note", FieldType.TEXT);
        assertThat(ok(d, "  hello  ")).containsEntry("note", "hello");
        bad(d, "x".repeat(1001));
        CrmFieldDefinition longText = def("story", FieldType.LONG_TEXT);
        assertThat(ok(longText, "x".repeat(10_000))).containsKey("story");
        bad(longText, "x".repeat(10_001));
    }

    @Test
    void numberMustBeFinite() {
        CrmFieldDefinition d = def("count", FieldType.NUMBER);
        assertThat(ok(d, 3)).containsEntry("count", 3L);
        assertThat(ok(d, "2.5")).containsEntry("count", 2.5d);
        bad(d, "many");
        bad(d, Double.POSITIVE_INFINITY);
    }

    @Test
    void currencyNegative() {
        CrmFieldDefinition d = def("budget", FieldType.CURRENCY);
        assertThat(ok(d, "1250.50")).containsEntry("budget", 1250.5d);
        assertThat(ok(d, 0)).containsEntry("budget", 0L);
        bad(d, -1);
    }

    @Test
    void booleanMustBeTrueOrFalse() {
        CrmFieldDefinition d = def("vip", FieldType.BOOLEAN);
        assertThat(ok(d, true)).containsEntry("vip", true);
        assertThat(ok(d, "false")).containsEntry("vip", false);
        bad(d, "yes");
    }

    @Test
    void dateWrongFormat() {
        CrmFieldDefinition d = def("due", FieldType.DATE);
        assertThat(ok(d, "2026-10-24")).containsEntry("due", "2026-10-24");
        bad(d, "24/10/2026");
        bad(d, "2026-02-30");
    }

    @Test
    void datetimeParsesIso() {
        CrmFieldDefinition d = def("at", FieldType.DATETIME);
        assertThat(ok(d, "2026-10-24T10:15:30+05:30")).containsEntry("at", "2026-10-24T04:45:30Z");
        bad(d, "tomorrow");
    }

    @Test
    void selectInactiveOptionRejected() {
        CrmFieldDefinition d = select("tier", FieldType.SELECT);
        assertThat(ok(d, "gold")).containsEntry("tier", "gold");
        bad(d, "bronze");
        bad(d, "platinum");
    }

    @Test
    void inactiveOptionKeptWhenUnchanged() {
        CrmFieldDefinition d = select("tier", FieldType.SELECT);
        assertThat(validator.validate(List.of(d), Map.of("tier", "bronze"), in("tier", "bronze"), false, true))
                .containsEntry("tier", "bronze");
    }

    @Test
    void multiSelectDuplicatesRejected() {
        CrmFieldDefinition d = select("tags", FieldType.MULTI_SELECT);
        assertThat(ok(d, List.of("gold", "silver"))).containsEntry("tags", List.of("gold", "silver"));
        bad(d, List.of("gold", "gold"));
        bad(d, List.of("gold", "bronze"));
        bad(d, "gold");
    }

    @Test
    void urlMustBeHttp() {
        CrmFieldDefinition d = def("site", FieldType.URL);
        assertThat(ok(d, "https://example.com/a")).containsEntry("site", "https://example.com/a");
        bad(d, "ftp://example.com");
        bad(d, "example.com");
        bad(d, "javascript:alert(1)");
    }

    @Test
    void emailMustLookLikeAnAddress() {
        CrmFieldDefinition d = def("mail", FieldType.EMAIL);
        assertThat(ok(d, "a@b.co")).containsEntry("mail", "a@b.co");
        bad(d, "a@b");
        bad(d, "a b@c.de");
    }

    @Test
    void phoneStripsSpacesAndDashes() {
        CrmFieldDefinition d = def("alt", FieldType.PHONE);
        assertThat(ok(d, "+91 98765-43210")).containsEntry("alt", "+919876543210");
        bad(d, "12345");
        bad(d, "phone");
    }

    @Test
    void userMustBeBrandUser() {
        CrmFieldDefinition d = def("rep", FieldType.USER);
        assertThat(ok(d, "u1")).containsEntry("rep", "u1");
        bad(d, "u2");
    }

    @Test
    void blankClears() {
        CrmFieldDefinition d = def("note", FieldType.TEXT);
        Map<String, Object> out = validator.validate(List.of(d), Map.of("note", "old"), in("note", "  "), false, true);
        assertThat(out).doesNotContainKey("note");
        out = validator.validate(List.of(d), Map.of("note", "old"), in("note", null), false, true);
        assertThat(out).doesNotContainKey("note");
    }

    @Test
    void absentKeyKeepsStoredValue() {
        CrmFieldDefinition a = def("a", FieldType.TEXT);
        CrmFieldDefinition b = def("b", FieldType.TEXT);
        Map<String, Object> out = validator.validate(List.of(a, b), Map.of("a", "kept"), in("b", "new"), false, true);
        assertThat(out).containsEntry("a", "kept").containsEntry("b", "new");
    }

    @Test
    void unknownKeyRejected() {
        assertThatThrownBy(() -> validator.validate(List.of(def("a", FieldType.TEXT)), Map.of(), in("nope", "x"),
                false, true)).isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsKey("customFields.nope"));
    }

    @Test
    void inactiveFieldValueKeptOnUnrelatedSave() {
        CrmFieldDefinition gone = def("old", FieldType.TEXT);
        gone.setActive(false);
        CrmFieldDefinition live = def("live", FieldType.TEXT);
        Map<String, Object> stored = new LinkedHashMap<>(Map.of("old", "keep me"));
        Map<String, Object> incoming = new HashMap<>(Map.of("old", "keep me", "live", "x"));
        assertThat(validator.validate(List.of(gone, live), stored, incoming, false, true))
                .containsEntry("old", "keep me").containsEntry("live", "x");
        assertThat(validator.validate(List.of(gone, live), stored, in("live", "y"), false, true))
                .containsEntry("old", "keep me");
        assertThatThrownBy(() -> validator.validate(List.of(gone, live), stored, in("old", "changed"), false, true))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsKey("customFields.old"));
    }

    @Test
    void requiredOnCreate() {
        CrmFieldDefinition d = def("tier", FieldType.TEXT);
        d.setRequired(true);
        assertThatThrownBy(() -> validator.validate(List.of(d), Map.of(), Map.of(), true, true))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsKey("customFields.tier"));
        // on update only when the key is sent
        assertThat(validator.validate(List.of(d), Map.of(), Map.of(), false, true)).isEmpty();
        assertThatThrownBy(() -> validator.validate(List.of(d), Map.of("tier", "a"), in("tier", ""), false, true))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void requiredSkippedWhenNotEnforced() {
        CrmFieldDefinition d = def("tier", FieldType.TEXT);
        d.setRequired(true);
        assertThat(validator.validate(List.of(d), Map.of(), Map.of(), true, false)).isEmpty();
    }

    @Test
    void defaultAppliedOnCreate() {
        CrmFieldDefinition d = select("tier", FieldType.SELECT);
        d.setDefaultValue("silver");
        assertThat(validator.validate(List.of(d), Map.of(), Map.of(), true, true)).containsEntry("tier", "silver");
        assertThat(validator.validate(List.of(d), Map.of(), in("tier", "gold"), true, true))
                .containsEntry("tier", "gold");
        assertThat(validator.validate(List.of(d), Map.of(), Map.of(), false, true)).isEmpty();
    }

    @Test
    void defaultOnSwitchedOffOptionIsSkipped() {
        CrmFieldDefinition d = select("tier", FieldType.SELECT);
        d.setDefaultValue("bronze");
        // the default went stale when its option was switched off: a create that leaves the field alone still works
        assertThat(validator.validate(List.of(d), Map.of(), Map.of(), true, true)).isEmpty();
        d.setRequired(true);
        assertThatThrownBy(() -> validator.validate(List.of(d), Map.of(), Map.of(), true, true))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsEntry("customFields.tier", "is required"));
    }

    @Test
    void nullIncomingKeepsStored() {
        CrmFieldDefinition d = def("a", FieldType.TEXT);
        assertThat(validator.validate(List.of(d), Map.of("a", "kept"), null, false, true)).containsEntry("a", "kept");
    }
}
