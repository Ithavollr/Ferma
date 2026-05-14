package org.evlis.firma.noise;

import java.util.stream.IntStream;

/**
 * Double Perlin noise sampler - combines two octave samplers with slight offset
 * to mask grid artifacts. This is the workhorse for Minecraft's climate noise.
 */
public class DoublePerlinNoiseSampler {
    private final OctavePerlinNoiseSampler firstSampler;
    private final OctavePerlinNoiseSampler secondSampler;
    private final double amplitude;

    /**
     * Create a DoublePerlinNoiseSampler with the given octaves.
     * The amplitude factor normalizes output back to [-1, 1] range.
     */
    public DoublePerlinNoiseSampler(XoroshiroRandomSource random, int firstOctave, double[] amplitudes) {
        this.firstSampler = new OctavePerlinNoiseSampler(random, 
            IntStream.rangeClosed(firstOctave, firstOctave + amplitudes.length - 1).toArray());
        
        // Second sampler uses a different random instance with offset
        XoroshiroRandomSource random2 = new XoroshiroRandomSource(random.nextLong());
        this.secondSampler = new OctavePerlinNoiseSampler(random2,
            IntStream.rangeClosed(firstOctave, firstOctave + amplitudes.length - 1).toArray());
        
        // Amplitude factor from vanilla: 1/6 * (10/9)
        this.amplitude = 0.16666666666666666 * 1.1111111111111112; // ~0.18518518518518517
    }

    /**
     * Sample the double Perlin noise at the given coordinates.
     */
    public double sample(double x, double y, double z) {
        // Second sampler is offset by 1.0181268882175227 to mask grid artifacts
        double first = this.firstSampler.sample(x, y, z);
        double second = this.secondSampler.sample(
            x * 1.0181268882175227, 
            y * 1.0181268882175227, 
            z * 1.0181268882175227
        );
        
        // Average and apply amplitude factor
        return (first + second) * this.amplitude;
    }

    /**
     * Sample with Y scaling for terrain generation.
     */
    public double sample(double x, double y, double z, double yScale, double yMax) {
        double first = this.firstSampler.sample(x, y, z, yScale, yMax);
        double second = this.secondSampler.sample(
            x * 1.0181268882175227,
            y * 1.0181268882175227,
            z * 1.0181268882175227,
            yScale, yMax
        );
        
        return (first + second) * this.amplitude;
    }
}
