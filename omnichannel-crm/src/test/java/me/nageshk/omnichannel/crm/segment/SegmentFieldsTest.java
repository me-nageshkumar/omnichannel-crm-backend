package me.nageshk.omnichannel.crm.segment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.field.FieldType;
import me.nageshk.omnichannel.crm.segment.SegmentFields.FieldSpec;
import me.nageshk.omnichannel.crm.segment.SegmentFields.Known;
import org.junit.jupiter.api.Test;

class SegmentFieldsTest {

    static final Known NOTHING = new Known(Set.of(), Map.of(), Set.of(), Map.of(), Set.of());

    @Test
    void everyBuiltInKeyHasATypeAndGroup() {
        Map<String, SegmentValueType> expected = Map.ofEntries(
                Map.entry("contact.lifecycleStatus", SegmentValueType.PICK),
                Map.entry("contact.ownerId", SegmentValueType.USER),
                Map.entry("contact.source", SegmentValueType.TEXT),
                Map.entry("contact.origin.kind", SegmentValueType.ENUM),
                Map.entry("contact.companyId", SegmentValueType.COMPANY),
                Map.entry("contact.channelAccountId", SegmentValueType.PICK),
                Map.entry("contact.email", SegmentValueType.TEXT),
                Map.entry("contact.jobTitle", SegmentValueType.TEXT),
                Map.entry("contact.creationTime", SegmentValueType.DATE),
                Map.entry("contact.lastActivityAt", SegmentValueType.DATE),
                Map.entry("contact.nextTaskDueAt", SegmentValueType.DATE),
                Map.entry("contact.openDealCount", SegmentValueType.NUMBER),
                Map.entry("contact.openDealAmount", SegmentValueType.MONEY),
                Map.entry("deal.pipelineId", SegmentValueType.PICK),
                Map.entry("deal.stageId", SegmentValueType.PICK),
                Map.entry("deal.status", SegmentValueType.ENUM),
                Map.entry("deal.amount", SegmentValueType.MONEY),
                Map.entry("deal.currency", SegmentValueType.TEXT),
                Map.entry("deal.ownerId", SegmentValueType.USER),
                Map.entry("deal.source", SegmentValueType.PICK),
                Map.entry("deal.lostReason", SegmentValueType.PICK),
                Map.entry("deal.daysInStage", SegmentValueType.NUMBER),
                Map.entry("deal.expectedCloseDate", SegmentValueType.DATE),
                Map.entry("deal.closedAt", SegmentValueType.DATE),
                Map.entry("deal.creationTime", SegmentValueType.DATE),
                Map.entry("deal.rotting", SegmentValueType.BOOLEAN),
                Map.entry("channel.label", SegmentValueType.LABEL),
                Map.entry("channel.lastMessageAt", SegmentValueType.DATE),
                Map.entry("channel.source", SegmentValueType.ENUM),
                Map.entry("channel.doNotContact", SegmentValueType.BOOLEAN));
        expected.forEach((key, type) -> {
            FieldSpec spec = SegmentFields.spec(key, NOTHING).orElseThrow(() -> new AssertionError(key));
            assertThat(spec.type()).as(key).isEqualTo(type);
            assertThat(spec.group()).as(key).isEqualTo(key.substring(0, key.indexOf('.')));
            assertThat(spec.custom()).as(key).isFalse();
        });
        assertThat(SegmentFields.spec("contact.phone", NOTHING)).isEmpty();
        assertThat(SegmentFields.spec("deal.title", NOTHING)).isEmpty();
        assertThat(SegmentFields.spec("nonsense", NOTHING)).isEmpty();
        assertThat(SegmentFields.BUILT_IN_KEYS).containsExactlyInAnyOrderElementsOf(expected.keySet());
    }

    @Test
    void customKeysMapByFieldType() {
        Known known = new Known(Set.of("tier", "budget", "vip", "since", "tags", "rep", "notes"),
                Map.of("tier", FieldType.SELECT, "budget", FieldType.CURRENCY, "vip", FieldType.BOOLEAN,
                        "since", FieldType.DATETIME, "tags", FieldType.MULTI_SELECT, "rep", FieldType.USER,
                        "notes", FieldType.LONG_TEXT),
                Set.of("score"), Map.of("score", FieldType.NUMBER), Set.of());
        assertThat(SegmentFields.spec("contact.custom:tier", known).orElseThrow().type()).isEqualTo(SegmentValueType.PICK);
        assertThat(SegmentFields.spec("contact.custom:budget", known).orElseThrow().type()).isEqualTo(SegmentValueType.MONEY);
        assertThat(SegmentFields.spec("contact.custom:vip", known).orElseThrow().type()).isEqualTo(SegmentValueType.BOOLEAN);
        assertThat(SegmentFields.spec("contact.custom:since", known).orElseThrow().type()).isEqualTo(SegmentValueType.DATE);
        assertThat(SegmentFields.spec("contact.custom:tags", known).orElseThrow().type()).isEqualTo(SegmentValueType.MULTI);
        assertThat(SegmentFields.spec("contact.custom:rep", known).orElseThrow().type()).isEqualTo(SegmentValueType.USER);
        assertThat(SegmentFields.spec("contact.custom:notes", known).orElseThrow().type()).isEqualTo(SegmentValueType.TEXT);
        FieldSpec score = SegmentFields.spec("deal.custom:score", known).orElseThrow();
        assertThat(score.type()).isEqualTo(SegmentValueType.NUMBER);
        assertThat(score.group()).isEqualTo("deal");
        assertThat(score.custom()).isTrue();
        assertThat(SegmentFields.spec("deal.custom:tier", known)).as("a contact field is not a deal field").isEmpty();
        assertThat(SegmentFields.spec("contact.custom:", known)).isEmpty();
        assertThat(SegmentFields.typeOfCustom(FieldType.URL)).isEqualTo(SegmentValueType.TEXT);
        assertThat(SegmentFields.typeOfCustom(FieldType.PHONE)).isEqualTo(SegmentValueType.TEXT);
        assertThat(SegmentFields.typeOfCustom(FieldType.EMAIL)).isEqualTo(SegmentValueType.TEXT);
        assertThat(SegmentFields.typeOfCustom(FieldType.DATE)).isEqualTo(SegmentValueType.DATE);
    }

    @Test
    void attributeKeysAreText() {
        Known known = new Known(Set.of(), Map.of(), Set.of(), Map.of(), Set.of("a1"));
        FieldSpec a = SegmentFields.spec("channel.attribute:a1", known).orElseThrow();
        assertThat(a.type()).isEqualTo(SegmentValueType.TEXT);
        assertThat(a.group()).isEqualTo("channel");
        assertThat(a.custom()).isTrue();
        assertThat(SegmentFields.spec("channel.attribute:a2", known)).isEmpty();
        assertThat(SegmentFields.spec("channel.attribute:", known)).isEmpty();
    }

    @Test
    void operatorsPerType() {
        assertThat(SegmentFields.operators(SegmentValueType.TEXT))
                .containsExactlyInAnyOrder("is", "isNot", "contains", "notContains", "startsWith", "isSet", "isNotSet");
        for (SegmentValueType t : List.of(SegmentValueType.NUMBER, SegmentValueType.MONEY)) {
            assertThat(SegmentFields.operators(t)).containsExactlyInAnyOrder("eq", "neq", "lt", "lte", "gt", "gte", "between");
        }
        assertThat(SegmentFields.operators(SegmentValueType.DATE)).containsExactlyInAnyOrder("before", "after", "between",
                "withinLastDays", "moreThanDaysAgo", "isSet", "isNotSet");
        for (SegmentValueType t : List.of(SegmentValueType.PICK, SegmentValueType.USER, SegmentValueType.ENUM,
                SegmentValueType.COMPANY)) {
            assertThat(SegmentFields.operators(t)).containsExactlyInAnyOrder("is", "isNot", "in", "notIn", "isSet", "isNotSet");
        }
        assertThat(SegmentFields.operators(SegmentValueType.BOOLEAN)).containsExactly("is");
        assertThat(SegmentFields.operators(SegmentValueType.LABEL)).containsExactlyInAnyOrder("has", "hasNot", "hasAny",
                "hasAll", "hasNone", "isEmpty", "isNotEmpty");
        assertThat(SegmentFields.operators(SegmentValueType.MULTI)).containsExactlyInAnyOrder("contains", "isSet", "isNotSet");
    }

    @Test
    void negativeOperators() {
        for (String op : List.of("isNot", "notIn", "notContains", "neq", "hasNot", "hasNone", "isNotSet", "isEmpty")) {
            assertThat(SegmentFields.isNegative(op)).as(op).isTrue();
        }
        for (String op : List.of("is", "in", "contains", "eq", "has", "hasAny", "hasAll", "isSet", "isNotEmpty", "between",
                "withinLastDays", "moreThanDaysAgo", "before", "after", "lt", "gte")) {
            assertThat(SegmentFields.isNegative(op)).as(op).isFalse();
        }
    }
}
