package me.nageshk.omnichannel.crm.deal;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Lane positions (spec 5.4): appending adds {@link #GAP} after the last card, inserting takes the midpoint of the
 * neighbours, and a slot narrower than {@link #MIN_GAP} asks the caller to re-space the lane.
 */
public final class Positions {

    public static final double GAP = 1024.0;
    public static final double MIN_GAP = 1e-6;

    private Positions() {}

    /**
     * The position of a card placed after {@code before} and ahead of {@code after} (either may be null: no card on
     * that side). Empty when the slot is too narrow; the caller then re-spaces the lane and asks again. With only
     * {@code after}, the slot is {@code (0, after)}.
     */
    public static OptionalDouble between(Double before, Double after) {
        if (before == null && after == null) return OptionalDouble.of(GAP);
        if (after == null) return OptionalDouble.of(before + GAP);
        double low = before == null ? 0 : before;
        if (after - low < MIN_GAP) return OptionalDouble.empty();
        double mid = low + (after - low) / 2;
        // floating point: a midpoint that collapses onto a neighbour is no slot at all
        if (mid <= low || mid >= after) return OptionalDouble.empty();
        return OptionalDouble.of(mid);
    }

    /** {@code GAP·(i+1)} for {@code i < n}: an evenly spaced lane of {@code n} cards. */
    public static List<Double> respaced(int n) {
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(GAP * (i + 1));
        return out;
    }
}
