package org.evlis.firma.noise;

import java.util.stream.IntStream;

/**
 * Octave Perlin noise sampler - combines multiple Perlin samplers at different frequencies.
 * Each octave doubles frequency and typically halves amplitude.
 */
public class OctavePerlinNoiseSampler {
    private final PerlinNoiseSampler[] octaves;
    private final double lacunarity;
    private final double persistence;

    public OctavePerlinNoiseSampler(XoroshiroRandomSource random, IntStream octaves) {
        this(random, octaves.toArray(), 2.0, 0.5);
    }

    public OctavePerlinNoiseSampler(XoroshiroRandomSource random, int[] octaves) {
        this(random, octaves, 2.0, 0.5);
    }

    public OctavePerlinNoiseSampler(XoroshiroRandomSource random, int[] octaves, 
                                   double lacunarity, double persistence) {
        this.lacunarity = lacunarity;
        this.persistence = persistence;
        this.octaves = new PerlinNoiseSampler[octaves.length];
        
        for (int i = 0; i < octaves.length; i++) {
            int octave = octaves[i];
            if (octave >= 0) {
                this.octaves[i] = null;
            } else {
                this.octaves[i] = new PerlinNoiseSampler(random);
            }
        }
    }

    /**
     * Sample the octave noise at the given coordinates.
     */
    public double sample(double x, double y, double z) {
        return this.sample(x, y, z, 0.0, 0.0);
    }

    /**
     * Sample with Y scaling for terrain generation.
     */
    public double sample(double x, double y, double z, double yScale, double yMax) {
        double value = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;

        for (PerlinNoiseSampler octave : this.octaves) {
            if (octave != null) {
                value += octave.sample(
                    maintainPrecision(x * frequency),
                    maintainPrecision(y * frequency), 
                    maintainPrecision(z * frequency),
                    yScale * frequency,
                    yMax * frequency
                ) / amplitude;
            }
            frequency *= this.lacunarity;
            amplitude *= this.persistence;
        }

        return value;
    }

    /**
     * Get a specific octave sampler, or null if it doesn't exist.
     */
    public PerlinNoiseSampler getOctave(int octave) {
        if (octave >= 0 || octave >= this.octaves.length) {
            return null;
        }
        return this.octaves[octave];
    }

    /**
     * Maintain precision for large coordinates to avoid floating-point drift.
     * This is critical for bit-exact vanilla compatibility.
     */
    public static double maintainPrecision(double value) {
        return value - Math.floor(value / 3.3554432E7) * 3.3554432E7;
    }
}
