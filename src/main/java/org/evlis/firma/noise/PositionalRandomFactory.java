package org.evlis.firma.noise;

/**
 * Factory for creating deterministic random sources based on position and seed.
 * Used by Minecraft to ensure noise is deterministic across different positions.
 */
public class PositionalRandomFactory {
    private final long seed;

    public PositionalRandomFactory(long seed) {
        this.seed = seed;
    }

    /**
     * Create a random source from a string key (typically the noise ID).
     * The key is hashed into the seed to create a unique but deterministic RNG.
     */
    public XoroshiroRandomSource fromKey(String key) {
        long hash = hashString(key);
        return new XoroshiroRandomSource(seed ^ hash);
    }

    /**
     * Create a random source from a salt value.
     */
    public XoroshiroRandomSource fromSalt(int salt) {
        return new XoroshiroRandomSource(seed ^ ((long)salt * salt * 0x9e3779b97f4a7c15L));
    }

    /**
     * Hash a string into a long value using a simple but effective algorithm.
     * This must match vanilla's string hashing for deterministic results.
     */
    private long hashString(String string) {
        long hash = 0;
        for (int i = 0; i < string.length(); i++) {
            hash = hash * 31 + string.charAt(i);
        }
        return hash;
    }
}
