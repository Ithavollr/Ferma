package org.evlis.firma.noise;

/**
 * Octave Perlin noise sampler mirroring vanilla
 * {@code net.minecraft.world.level.levelgen.synth.PerlinNoise}: frequency starts at
 * {@code 2^firstOctave} and doubles per octave, contribution weight starts at
 * {@code 2^(n-1)/(2^n - 1)} and halves per octave, each scaled by its configured
 * amplitude (zero-amplitude octaves are skipped). Octaves above 0 are rejected,
 * as in vanilla.
 *
 * <p>The historical pre-fix octave math lives on, frozen, in
 * {@link ShatteredClimateFunction}.
 */
public class OctavePerlinNoiseSampler {
    private final PerlinNoiseSampler[] octaves;
    private final double[] amplitudes;
    private final double lowestFreqInputFactor;
    private final double lowestFreqValueFactor;

    public OctavePerlinNoiseSampler(XoroshiroRandomSource random, int firstOctave, double[] amplitudes) {
        int highestOctave = firstOctave + amplitudes.length - 1;
        if (highestOctave > 0) {
            throw new IllegalArgumentException(
                "Positive octaves are not supported (first_octave " + firstOctave + " with "
                + amplitudes.length + " amplitudes reaches octave " + highestOctave + ")");
        }
        this.amplitudes = amplitudes.clone();
        this.octaves = new PerlinNoiseSampler[amplitudes.length];
        for (int i = 0; i < amplitudes.length; i++) {
            this.octaves[i] = amplitudes[i] != 0.0 ? new PerlinNoiseSampler(random) : null;
        }
        this.lowestFreqInputFactor = Math.pow(2.0, firstOctave);
        this.lowestFreqValueFactor = Math.pow(2.0, amplitudes.length - 1) / (Math.pow(2.0, amplitudes.length) - 1.0);
    }

    /**
     * Sample the octave noise at the given coordinates.
     */
    public double sample(double x, double y, double z) {
        return this.sample(x, y, z, 0.0, 0.0);
    }

    /**
     * Sample with Y scaling for terrain generation. Vanilla PerlinNoise.getValue semantics.
     */
    public double sample(double x, double y, double z, double yScale, double yMax) {
        double value = 0.0;
        double frequency = this.lowestFreqInputFactor;
        double weight = this.lowestFreqValueFactor;

        for (int i = 0; i < this.octaves.length; i++) {
            PerlinNoiseSampler octave = this.octaves[i];
            if (octave != null) {
                value += this.amplitudes[i] * weight * octave.sample(
                    maintainPrecision(x * frequency),
                    maintainPrecision(y * frequency),
                    maintainPrecision(z * frequency),
                    yScale * frequency,
                    yMax * frequency
                );
            }
            frequency *= 2.0;
            weight /= 2.0;
        }

        return value;
    }

    /**
     * Maintain precision for large coordinates to avoid floating-point drift.
     * This is critical for bit-exact vanilla compatibility.
     */
    public static double maintainPrecision(double value) {
        return value - Math.floor(value / 3.3554432E7) * 3.3554432E7;
    }
}
