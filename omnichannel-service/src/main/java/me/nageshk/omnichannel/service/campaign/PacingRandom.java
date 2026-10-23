package me.nageshk.omnichannel.service.campaign;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** The only source of randomness in campaign pacing; tests replace it. */
@Component
public class PacingRandom {

    /** Uniform 0 to 60. */
    public int dayStartOffsetMinutes() {
        return ThreadLocalRandom.current().nextInt(0, 61);
    }

    public int seconds(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }
}
