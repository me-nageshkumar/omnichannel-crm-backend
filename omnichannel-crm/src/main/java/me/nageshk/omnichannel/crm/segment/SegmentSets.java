package me.nageshk.omnichannel.crm.segment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Set algebra over CRM contact ids (spec D C5, C16). Contact rules with a negative operator run as Mongo negations
 * inside the universe query, and deal negatives apply to the deal itself (C22); negative channel-contact rules
 * complement within the universe inside {@code SegmentConditionQueries.channelRule}. So the resolver itself needs
 * the universe only for NOT groups.
 */
public final class SegmentSets {

    private SegmentSets() {}

    /** Intersects starting from the smallest set, telling {@code visited} the order (for tests and logs). */
    public static Set<String> intersectAll(List<Set<String>> sets, Consumer<Set<String>> visited) {
        if (sets.isEmpty()) return Set.of();
        List<Set<String>> ordered = new ArrayList<>(sets);
        ordered.sort((a, b) -> Integer.compare(a.size(), b.size()));
        Set<String> out = new HashSet<>(ordered.get(0));
        visited.accept(ordered.get(0));
        for (int i = 1; i < ordered.size() && !out.isEmpty(); i++) {
            visited.accept(ordered.get(i));
            out.retainAll(ordered.get(i));
        }
        return out;
    }

    public static Set<String> intersectAll(List<Set<String>> sets) {
        return intersectAll(sets, s -> {});
    }

    public static Set<String> unionAll(List<Set<String>> sets) {
        Set<String> out = new HashSet<>();
        sets.forEach(out::addAll);
        return out;
    }

    public static Set<String> complement(Set<String> universe, Set<String> s) {
        Set<String> out = new HashSet<>(universe);
        out.removeAll(s);
        return out;
    }
}
