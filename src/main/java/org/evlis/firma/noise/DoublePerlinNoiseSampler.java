package org.evlis.firma.noise;

/**
 * Double Perlin noise sampler - combines two octave samplers with slight offset
 * to mask grid artifacts. This is the workhorse for Minecraft's climate noise.
 */
public class DoublePerlinNoiseSampler {
    private final OctavePerlinNoiseSampler firstSampler;
    private final OctavePerlinNoiseSampler secondSampler;
    private final double amplitude;

    /**
     * Create a DoublePerlinNoiseSampler with the given octaves, matching vanilla
     * NormalNoise normalization.
     */
    public DoublePerlinNoiseSampler(XoroshiroRandomSource random, int firstOctave, double[] amplitudes) {
        // Vanilla NormalNoise semantics: value factor = (1/6) / expectedDeviation(span),
        // span = index distance between first and last non-zero amplitude.
        this.firstSampler = new OctavePerlinNoiseSampler(random, firstOctave, amplitudes);
        XoroshiroRandomSource random2 = new XoroshiroRandomSource(random.nextLong());
        this.secondSampler = new OctavePerlinNoiseSampler(random2, firstOctave, amplitudes);

        int firstNonzero = Integer.MAX_VALUE;
        int lastNonzero = Integer.MIN_VALUE;
        for (int i = 0; i < amplitudes.length; i++) {
            if (amplitudes[i] != 0.0) {
                firstNonzero = Math.min(firstNonzero, i);
                lastNonzero = Math.max(lastNonzero, i);
            }
        }
        if (lastNonzero < firstNonzero) {
            throw new IllegalArgumentException("amplitudes must contain at least one non-zero value");
        }
        double expectedDeviation = 0.1 * (1.0 + 1.0 / (lastNonzero - firstNonzero + 1));
        this.amplitude = 0.16666666666666666 / expectedDeviation;
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
