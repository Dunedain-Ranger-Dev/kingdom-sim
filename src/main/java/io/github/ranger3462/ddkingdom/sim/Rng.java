package io.github.ranger3462.ddkingdom.sim;

/**
 * The simulator's random numbers (mulberry32), so a seed plays the same game here as in the browser simulator.
 */
public final class Rng {
    private int state;

    public Rng(int seed) {
        state = seed;
    }

    /** A number in [0, 1). */
    public double next() {
        state += 0x6D2B79F5;
        int t = (state ^ (state >>> 15)) * (1 | state);
        t = (t + (t ^ (t >>> 7)) * (61 | t)) ^ t;
        return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
    }
}
