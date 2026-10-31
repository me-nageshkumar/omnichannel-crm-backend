package me.nageshk.omnichannel.crm.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.task.dto.ChecklistItemRequest;
import org.junit.jupiter.api.Test;

class ChecklistsTest {

    private static ChecklistItem item(String id, String text, boolean done) {
        ChecklistItem i = new ChecklistItem();
        i.setId(id);
        i.setText(text);
        i.setDone(done);
        if (done) {
            i.setDoneAt(Instant.parse("2026-10-30T10:00:00Z"));
            i.setDoneBy("u1");
        }
        return i;
    }

    private static final String A = "6723f0a1b2c3d4e5f6a7b8c9";
    private static final String B = "6723f0a1b2c3d4e5f6a7b8ca";

    @Test
    void keepsTicksOfKnownIdsInSentOrder() {
        Map<String, String> errors = new LinkedHashMap<>();
        List<ChecklistItem> merged = Checklists.merge(List.of(item(A, "Send", true), item(B, "Collect", false)),
                List.of(new ChecklistItemRequest(B, "Collect GST"), new ChecklistItemRequest(A, " Send list ")), errors);
        assertThat(errors).isEmpty();
        assertThat(merged).extracting(ChecklistItem::getId).containsExactly(B, A);
        assertThat(merged.get(0).getText()).isEqualTo("Collect GST");
        assertThat(merged.get(0).isDone()).isFalse();
        assertThat(merged.get(1).getText()).isEqualTo("Send list");
        assertThat(merged.get(1).isDone()).isTrue();
        assertThat(merged.get(1).getDoneBy()).isEqualTo("u1");
        assertThat(merged.get(1).getDoneAt()).isEqualTo(Instant.parse("2026-10-30T10:00:00Z"));
    }

    @Test
    void unknownOrRepeatedIdsBecomeNewItems() {
        Map<String, String> errors = new LinkedHashMap<>();
        List<ChecklistItem> merged = Checklists.merge(List.of(item(A, "Send", true)),
                List.of(new ChecklistItemRequest(A, "Send"), new ChecklistItemRequest(A, "Again"),
                        new ChecklistItemRequest("nope", "New"), new ChecklistItemRequest(null, "Fresh")), errors);
        assertThat(errors).isEmpty();
        assertThat(merged).hasSize(4);
        assertThat(merged.get(0).getId()).isEqualTo(A);
        assertThat(merged.get(0).isDone()).isTrue();
        for (int i = 1; i < 4; i++) {
            assertThat(merged.get(i).getId()).hasSize(24).isNotEqualTo(A);
            assertThat(merged.get(i).isDone()).isFalse();
        }
        assertThat(merged.stream().map(ChecklistItem::getId).distinct()).hasSize(4);
    }

    @Test
    void missingItemsAreDropped() {
        List<ChecklistItem> merged = Checklists.merge(List.of(item(A, "Send", true), item(B, "Collect", false)),
                List.of(new ChecklistItemRequest(B, "Collect")), new LinkedHashMap<>());
        assertThat(merged).extracting(ChecklistItem::getId).containsExactly(B);
    }

    @Test
    void nullMeansNoSteps() {
        assertThat(Checklists.merge(List.of(item(A, "Send", true)), null, new LinkedHashMap<>())).isEmpty();
    }

    @Test
    void rejectsOver30() {
        Map<String, String> errors = new LinkedHashMap<>();
        List<ChecklistItemRequest> many = java.util.stream.IntStream.range(0, 31)
                .mapToObj(i -> new ChecklistItemRequest(null, "Step " + i)).toList();
        List<ChecklistItem> current = List.of(item(A, "Send", true));
        assertThat(Checklists.merge(current, many, errors)).isSameAs(current);
        assertThat(errors).containsEntry("checklist", "at most 30 items");
    }

    @Test
    void rejectsBlankAndLongText() {
        Map<String, String> errors = new LinkedHashMap<>();
        Checklists.merge(List.of(), List.of(new ChecklistItemRequest(null, "ok"), new ChecklistItemRequest(null, "  "),
                new ChecklistItemRequest(null, "x".repeat(201))), errors);
        assertThat(errors).containsOnlyKeys("checklist[1].text", "checklist[2].text");
        assertThat(errors.get("checklist[1].text")).isEqualTo("must be 1 to 200 characters");
    }

    @Test
    void copyUndoneResetsTicksWithFreshIds() {
        List<ChecklistItem> copy = Checklists.copyUndone(List.of(item(A, "Send", true), item(B, "Collect", false)));
        assertThat(copy).extracting(ChecklistItem::getText).containsExactly("Send", "Collect");
        assertThat(copy).allSatisfy(i -> {
            assertThat(i.isDone()).isFalse();
            assertThat(i.getDoneAt()).isNull();
            assertThat(i.getDoneBy()).isNull();
            assertThat(i.getId()).hasSize(24).isNotIn(A, B);
        });
        assertThat(Checklists.copyUndone(null)).isNull();
    }
}
