package me.nageshk.omnichannel.crm.segment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SegmentSetsTest {

    @Test
    void intersectStartsFromTheSmallest() {
        Set<String> big = new LinkedHashSet<>(List.of("a", "b", "c", "d", "e"));
        Set<String> small = Set.of("c", "e", "z");
        Set<String> mid = Set.of("a", "c", "e", "q");
        List<Set<String>> order = new ArrayList<>();
        Set<String> out = SegmentSets.intersectAll(List.of(big, small, mid), order::add);
        assertThat(out).containsExactlyInAnyOrder("c", "e");
        assertThat(order.get(0)).isSameAs(small);
        assertThat(SegmentSets.intersectAll(List.of(), order::add)).isEmpty();
        assertThat(SegmentSets.intersectAll(List.of(big), order::add)).containsExactlyInAnyOrderElementsOf(big);
    }

    @Test
    void unionAndComplement() {
        assertThat(SegmentSets.unionAll(List.of(Set.of("a"), Set.of("b", "a"), Set.of()))).containsExactlyInAnyOrder("a", "b");
        assertThat(SegmentSets.complement(Set.of("a", "b", "c"), Set.of("b", "x"))).containsExactlyInAnyOrder("a", "c");
    }
}
