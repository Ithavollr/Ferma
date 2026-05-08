package org.evlis.firma.noise;

/**
 * Xoroshiro128++ random number generator.
 * This is the RNG used by Minecraft 1.18+ for noise generation.
 * Must be bit-exact to vanilla for deterministic noise.
 */
public class XoroshiroRandomSource {
    private long s0;
    private long s1;

    public XoroshiroRandomSource(long seed) {
        this.s0 = seed;
        this.s1 = seed ^ 0x9e3779b97f4a7c15L; // Golden ratio
    }

    /**
     * Generate the next 64-bit random number.
     */
    public long nextLong() {
        long result = Long.rotateLeft(s0 + s1, 17) + s0;
        s1 ^= s0;
        s0 = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
        s1 = Long.rotateLeft(s1, 28);
        return result;
    }

    /**
     * Generate a random integer in [0, bound).
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        int r = (int) (nextLong() >>> 32);
        int m = bound - 1;
        if ((bound & m) == 0) {
            // bound is a power of 2
            r &= m;
        } else {
            for (int u = r >>> 1; u + m - (r = u % bound) < 0; u = (int) (nextLong() >>> 32)) {
                // rejection sampling
            }
        }
        return r;
    }

    /**
     * Generate a random double in [0.0, 1.0).
     */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }
}
