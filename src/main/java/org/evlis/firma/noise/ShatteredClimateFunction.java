package org.evlis.firma.noise;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * FROZEN — the "shattered" (porcupine) climate function. Do not modify.
 *
 * <p>This is the historical pre-fix double-perlin pipeline, extracted verbatim when the
 * default path was aligned with vanilla semantics. Its defining quirks are intentional
 * and load-bearing for the terrain it produces:
 * <ul>
 *   <li>Octave frequency starts at 1.0 (octave labels only gate which samplers exist:
 *       labels &ge; 0 are null), and contributions are divided by a halving amplitude,
 *       so the highest-frequency octave dominates — per-block decorrelated noise.</li>
 *   <li>Configured amplitude values are ignored; only the count matters.</li>
 *   <li>Fixed output factor {@code 1/6 * 10/9} regardless of octave span.</li>
 *   <li>Shift noise sampled at raw (x, y, z) — 4&times; vanilla's shift frequency and
 *       y-dependent — added after xz scaling.</li>
 * </ul>
 *
 * <p>The characterization test in {@code FermaClimateFunctionTest} pins this output
 * bit-for-bit; any change here fails it.
 */
public class ShatteredClimateFunction implements FermaClimateFunction {

    /** Historical constant: 1/6 * 10/9. */
    private static final double VALUE_FACTOR = 0.16666666666666666 * 1.1111111111111112;
    private static final double SECOND_SAMPLER_OFFSET = 1.0181268882175227;

    private final ShatteredDoublePerlin mainSampler;
    private final ShatteredDoublePerlin shiftXSampler;
    private final ShatteredDoublePerlin shiftZSampler;
    private final double xzScale;
    private final double yScale;
    private final double minValue;
    private final double maxValue;

    /**
     * @param octaveCount number of octave slots, historically {@code amplitudes.length};
     *                    the amplitude values themselves were never used.
     */
    public ShatteredClimateFunction(
            PositionalRandomFactory factory,
            int firstOctave,
            int octaveCount,
            double xzScale,
            double yScale,
            double minValue,
            double maxValue) {
        // RNG call order must match the historical DoublePerlinClimateFunction exactly.
        XoroshiroRandomSource mainRandom = factory.fromKey("shattered");
        this.mainSampler = new ShatteredDoublePerlin(mainRandom, firstOctave, octaveCount);

        XoroshiroRandomSource shiftRandomX = factory.fromKey("minecraft:shift_x");
        XoroshiroRandomSource shiftRandomZ = factory.fromKey("minecraft:shift_z");
        this.shiftXSampler = new ShatteredDoublePerlin(shiftRandomX, -3, 4);
        this.shiftZSampler = new ShatteredDoublePerlin(shiftRandomZ, -3, 4);

        this.xzScale = xzScale;
        this.yScale = yScale;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    @Override
    public double compute(double x, double y, double z) {
        double shiftX = this.shiftXSampler.sample(x, y, z) * 4.0;
        double shiftZ = this.shiftZSampler.sample(x, y, z) * 4.0;

        double raw = this.mainSampler.sample(
            x * this.xzScale + shiftX,
            y * this.yScale,
            z * this.xzScale + shiftZ
        );

        return Math.clamp(raw, this.minValue, this.maxValue);
    }

    @Override
    public void fillArray(double[] array, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(array, this);
    }

    @Override
    public double minValue() {
        return this.minValue;
    }

    @Override
    public double maxValue() {
        return this.maxValue;
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return this;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(
            UnserializableMapCodec.of("ShatteredClimateFunction", this));
    }

    /**
     * The frozen double-perlin pair: two legacy octave stacks, the second sampled with
     * the {@code 1.0181...} offset, averaged by the historical fixed factor.
     */
    private static final class ShatteredDoublePerlin {
        private final PerlinNoiseSampler[] firstOctaves;
        private final PerlinNoiseSampler[] secondOctaves;

        ShatteredDoublePerlin(XoroshiroRandomSource random, int firstOctave, int octaveCount) {
            this.firstOctaves = createOctaves(random, firstOctave, octaveCount);
            XoroshiroRandomSource random2 = new XoroshiroRandomSource(random.nextLong());
            this.secondOctaves = createOctaves(random2, firstOctave, octaveCount);
        }

        /** Samplers exist only for octave labels < 0; labels >= 0 leave null gaps. */
        private static PerlinNoiseSampler[] createOctaves(XoroshiroRandomSource random, int firstOctave, int octaveCount) {
            PerlinNoiseSampler[] octaves = new PerlinNoiseSampler[octaveCount];
            for (int i = 0; i < octaveCount; i++) {
                octaves[i] = (firstOctave + i >= 0) ? null : new PerlinNoiseSampler(random);
            }
            return octaves;
        }

        double sample(double x, double y, double z) {
            double first = sampleOctaves(this.firstOctaves, x, y, z);
            double second = sampleOctaves(this.secondOctaves,
                x * SECOND_SAMPLER_OFFSET,
                y * SECOND_SAMPLER_OFFSET,
                z * SECOND_SAMPLER_OFFSET
            );
            return (first + second) * VALUE_FACTOR;
        }

        /** The frozen legacy octave loop: frequency from 1.0, weights doubling per octave. */
        private static double sampleOctaves(PerlinNoiseSampler[] octaves, double x, double y, double z) {
            double value = 0.0;
            double amplitude = 1.0;
            double frequency = 1.0;

            for (PerlinNoiseSampler octave : octaves) {
                if (octave != null) {
                    value += octave.sample(
                        maintainPrecision(x * frequency),
                        maintainPrecision(y * frequency),
                        maintainPrecision(z * frequency),
                        0.0 * frequency,
                        0.0 * frequency
                    ) / amplitude;
                }
                frequency *= 2.0;
                amplitude *= 0.5;
            }

            return value;
        }

        private static double maintainPrecision(double value) {
            return value - Math.floor(value / 3.3554432E7) * 3.3554432E7;
        }
    }
}
