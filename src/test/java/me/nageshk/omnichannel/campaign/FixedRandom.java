package me.nageshk.omnichannel.campaign;

/** A {@link PacingRandom} the tests steer: a fixed day-start offset, and either bound of every range. */
class FixedRandom extends PacingRandom {

    int offset = 0;
    boolean max = false;

    @Override
    public int dayStartOffsetMinutes() {
        return offset;
    }

    @Override
    public int seconds(int minInclusive, int maxInclusive) {
        return max ? maxInclusive : minInclusive;
    }
}
