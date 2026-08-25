package org.evlis.firma.noise;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.evlis.firma.pack.ClimateFunctionConfig;
import org.evlis.firma.pack.ClimateFunctionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Ferma's custom climate density functions.
 *
 * <p>These tests verify mathematical correctness independent of any pack configuration:
 * <ul>
 *   <li>Constant returns configured value</li>
 *   <li>Radial gradient follows distance formula</li>
 *   <li>Double perlin respects bounds and is deterministic</li>
 * </ul>
 */
class FermaClimateFunctionTest {

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void constant_returnsConfiguredValue() {
        double value = 0.75;
        FermaClimateFunction.Constant constant = new FermaClimateFunction.Constant(value);

        // Should return same value at any coordinate
        assertEquals(value, constant.compute(0, 64, 0), 0.0001);
        assertEquals(value, constant.compute(1000, -32, -5000), 0.0001);
        assertEquals(value, constant.compute(Integer.MAX_VALUE, 320, Integer.MIN_VALUE), 0.0001);
    }

    @Test
    void constant_minMaxValue_matchesConfigured() {
        FermaClimateFunction.Constant constant = new FermaClimateFunction.Constant(0.5);

        assertEquals(0.5, constant.minValue(), 0.0001);
        assertEquals(0.5, constant.maxValue(), 0.0001);
    }

    @Test
    void constant_fillArray_optimizesCorrectly() {
        FermaClimateFunction.Constant constant = new FermaClimateFunction.Constant(0.25);
        double[] array = new double[100];

        // Fill with context provider (null is safe for Constant)
        constant.fillArray(array, null);

        // All elements should be the constant value
        for (double v : array) {
            assertEquals(0.25, v, 0.0001);
        }
    }

    @Test
    void radialGradient_returnsStartValueAtCenter() {
        // Center at (0, 0), start value 1.0, decreasing at -0.1 per block
        RadialGradientClimateFunction gradient = new RadialGradientClimateFunction(
            0.0, 0.0,      // center
            1.0,           // start value
            -0.1,          // rate: -0.1 per block
            -1.0, 1.0      // clamp bounds
        );

        // At center, should return exactly start value
        double centerValue = gradient.compute(0, 64, 0);
        assertEquals(1.0, centerValue, 0.0001);
    }

    @Test
    void radialGradient_decreasesWithDistance() {
        RadialGradientClimateFunction gradient = new RadialGradientClimateFunction(
            0.0, 0.0,      // center
            1.0,           // start value
            -0.001,        // rate: -0.001 per block (gentle falloff)
            -1.0, 1.0      // clamp bounds
        );

        double center = gradient.compute(0, 64, 0);
        double near = gradient.compute(100, 64, 0);      // 100 blocks away
        double far = gradient.compute(500, 64, 0);       // 500 blocks away

        // Values should decrease with distance
        assertTrue(center > near, "Center should be higher than near");
        assertTrue(near > far, "Near should be higher than far");
    }

    @Test
    void radialGradient_respectsClampBounds() {
        // Aggressive falloff that would go below clamp_min
        RadialGradientClimateFunction gradient = new RadialGradientClimateFunction(
            0.0, 0.0,
            1.0,
            -0.01,         // -0.01 per block
            -0.5, 0.5      // tight clamps
        );

        // At 200 blocks away: raw = 1.0 + (-0.01 * 200) = 1.0 - 2.0 = -1.0
        // Clamped to -0.5
        double farValue = gradient.compute(200, 64, 0);
        assertEquals(-0.5, farValue, 0.0001, "Should be clamped to min");

        // Center value 1.0 should be clamped to 0.5 max
        double centerValue = gradient.compute(0, 64, 0);
        assertEquals(0.5, centerValue, 0.0001, "Should be clamped to max");
    }

    @Test
    void radialGradient_ignoresYCoordinate() {
        RadialGradientClimateFunction gradient = new RadialGradientClimateFunction(
            0.0, 0.0,
            1.0,
            -0.001,
            -1.0, 1.0
        );

        double atY64 = gradient.compute(100, 64, 100);
        double atY200 = gradient.compute(100, 200, 100);
        double atYNegative = gradient.compute(100, -64, 100);

        // All should be equal (Y doesn't matter)
        assertEquals(atY64, atY200, 0.0001);
        assertEquals(atY64, atYNegative, 0.0001);
    }

    @Test
    void radialGradient_positiveRate_increasesWithDistance() {
        // Inverted: starts low, gets higher as you go outward
        RadialGradientClimateFunction gradient = new RadialGradientClimateFunction(
            0.0, 0.0,
            -1.0,          // start low
            0.001,         // positive rate: increases with distance
            -1.0, 1.0
        );

        double center = gradient.compute(0, 64, 0);
        double far = gradient.compute(1000, 64, 0);

        assertTrue(far > center, "Far should be higher than center with positive rate");
    }

    @Test
    void radialGradient_centerOffset_worksCorrectly() {
        // Center at (1000, 2000) instead of (0, 0)
        RadialGradientClimateFunction gradient = new RadialGradientClimateFunction(
            1000.0, 2000.0,  // offset center
            1.0,
            -0.001,
            -1.0, 1.0
        );

        // At the offset center, should return start value
        double atOffsetCenter = gradient.compute(1000, 64, 2000);
        assertEquals(1.0, atOffsetCenter, 0.0001);

        // At (0, 0), should be decreased based on distance from offset center
        double atOrigin = gradient.compute(0, 64, 0);
        assertTrue(atOrigin < 1.0, "Should be less than start value at offset center");
    }

    @Test
    void doublePerlin_returnsValuesWithinBounds() {
        PositionalRandomFactory factory = new PositionalRandomFactory(12345L);
        DoublePerlinClimateFunction perlin = new DoublePerlinClimateFunction(
            factory,
            "test_noise",
            -7,                    // first octave
            new double[]{1.0},     // amplitudes
            0.25,                  // xz scale
            4.0,                   // y scale
            -0.5,                  // min value
            0.5                    // max value
        );

        // Sample at many locations
        for (int x = -100; x <= 100; x += 50) {
            for (int z = -100; z <= 100; z += 50) {
                double value = perlin.compute(x, 64, z);

                assertTrue(value >= -0.5, "Value " + value + " at (" + x + ", 64, " + z + ") below min");
                assertTrue(value <= 0.5, "Value " + value + " at (" + x + ", 64, " + z + ") above max");
            }
        }
    }

    @Test
    void doublePerlin_isDeterministic() {
        PositionalRandomFactory factory = new PositionalRandomFactory(99999L);
        DoublePerlinClimateFunction perlin = new DoublePerlinClimateFunction(
            factory,
            "deterministic_test",
            -5,
            new double[]{1.0, 0.5},
            0.1,
            2.0,
            -1.0,
            1.0
        );

        // Same coordinate should always produce same value
        double value1 = perlin.compute(123, 45, 678);
        double value2 = perlin.compute(123, 45, 678);
        double value3 = perlin.compute(123, 45, 678);

        assertEquals(value1, value2, 0.0001);
        assertEquals(value1, value3, 0.0001);
    }

    @Test
    void doublePerlin_differentSeeds_produceDifferentValues() {
        PositionalRandomFactory factory1 = new PositionalRandomFactory(11111L);
        PositionalRandomFactory factory2 = new PositionalRandomFactory(22222L);

        DoublePerlinClimateFunction perlin1 = new DoublePerlinClimateFunction(
            factory1, "test", -5, new double[]{1.0}, 0.25, 4.0, -1.0, 1.0
        );
        DoublePerlinClimateFunction perlin2 = new DoublePerlinClimateFunction(
            factory2, "test", -5, new double[]{1.0}, 0.25, 4.0, -1.0, 1.0
        );

        // Sample multiple points - at least some should differ
        boolean foundDifference = false;
        for (int x = -50; x <= 50; x += 25) {
            for (int z = -50; z <= 50; z += 25) {
                double v1 = perlin1.compute(x, 64, z);
                double v2 = perlin2.compute(x, 64, z);
                if (Math.abs(v1 - v2) > 0.0001) {
                    foundDifference = true;
                    break;
                }
            }
            if (foundDifference) break;
        }

        assertTrue(foundDifference, "Different seeds should produce different noise patterns");
    }

    @Test
    void doublePerlin_minMaxValue_matchesConstructorArgs() {
        PositionalRandomFactory factory = new PositionalRandomFactory(12345L);
        DoublePerlinClimateFunction perlin = new DoublePerlinClimateFunction(
            factory, "test", -5, new double[]{1.0}, 0.25, 4.0, -2.0, 3.0
        );

        assertEquals(-2.0, perlin.minValue(), 0.0001);
        assertEquals(3.0, perlin.maxValue(), 0.0001);
    }

    /**
     * The "shattered" type intentionally preserves the historical octave math whose
     * high-frequency-dominated output decorrelates between adjacent blocks (the
     * "porcupine" terrain). This guards its functional signature: neighboring samples
     * must swing wildly, which correct vanilla-style octave noise (base wavelength
     * 2^9 blocks here) never does over a 16-block span.
     */
    @Test
    void shattered_isPerBlockDecorrelated() {
        DensityFunction shattered = buildShattered(4242L);

        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int x = 0; x < 16; x++) {
            double v = shattered.compute(new DensityFunction.SinglePointContext(x, 0, 0));
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        assertTrue(max - min > 1.0,
            "Shattered noise must stay per-block decorrelated (porcupine terrain); range over 16 adjacent blocks was " + (max - min));
    }

    @Test
    void shattered_isDeterministic() {
        DensityFunction first = buildShattered(4242L);
        DensityFunction second = buildShattered(4242L);

        for (int x = 0; x < 16; x++) {
            DensityFunction.SinglePointContext pos = new DensityFunction.SinglePointContext(x, 0, 0);
            assertEquals(first.compute(pos), second.compute(pos), 0.0,
                "Same seed and config must reproduce the identical shattered field");
        }
    }

    /**
     * Characterization freeze: ShatteredClimateFunction must reproduce the historical
     * pre-fix double-perlin pipeline bit-for-bit. The reference below is an independent
     * verbatim copy of that algorithm (legacy octave loop, fixed 1/6*10/9 factor,
     * raw-coordinate y-dependent shift, clamp), including the exact RNG derivation the
     * factory used. Any change to the frozen class fails this with delta 0.0.
     */
    @Test
    void shattered_matchesFrozenReference() {
        DensityFunction shattered = buildShattered(4242L);

        // Mirror ClimateFunctionFactory.build's seed derivation for the same inputs.
        PositionalRandomFactory factory = new PositionalRandomFactory(
            4242L ^ ("shattered_test" + ":" + "continentalness").hashCode());
        LegacyDoublePerlin main = new LegacyDoublePerlin(factory.fromKey("shattered"), -9, 9);
        LegacyDoublePerlin shiftX = new LegacyDoublePerlin(factory.fromKey("minecraft:shift_x"), -3, 4);
        LegacyDoublePerlin shiftZ = new LegacyDoublePerlin(factory.fromKey("minecraft:shift_z"), -3, 4);

        int[] coords = {-3000, -7, 0, 5, 1234};
        for (int x : coords) {
            for (int z : coords) {
                for (int y : new int[]{0, 64}) {
                    double sx = shiftX.sample(x, y, z) * 4.0;
                    double sz = shiftZ.sample(x, y, z) * 4.0;
                    double expected = Math.clamp(
                        main.sample(x * 0.25 + sx, y * 0.0, z * 0.25 + sz), -1000.0, 1000.0);

                    double actual = shattered.compute(new DensityFunction.SinglePointContext(x, y, z));
                    assertEquals(expected, actual, 0.0,
                        "Frozen shattered output changed at (" + x + ", " + y + ", " + z + ")");
                }
            }
        }
    }

    /** Verbatim copy of the historical (pre-vanilla-fix) double perlin pipeline. */
    private static final class LegacyDoublePerlin {
        private final PerlinNoiseSampler[] first;
        private final PerlinNoiseSampler[] second;

        LegacyDoublePerlin(XoroshiroRandomSource random, int firstOctave, int octaveCount) {
            this.first = createOctaves(random, firstOctave, octaveCount);
            this.second = createOctaves(new XoroshiroRandomSource(random.nextLong()), firstOctave, octaveCount);
        }

        private static PerlinNoiseSampler[] createOctaves(XoroshiroRandomSource random, int firstOctave, int octaveCount) {
            PerlinNoiseSampler[] octaves = new PerlinNoiseSampler[octaveCount];
            for (int i = 0; i < octaveCount; i++) {
                octaves[i] = (firstOctave + i >= 0) ? null : new PerlinNoiseSampler(random);
            }
            return octaves;
        }

        double sample(double x, double y, double z) {
            double offset = 1.0181268882175227;
            return (sampleOctaves(first, x, y, z) + sampleOctaves(second, x * offset, y * offset, z * offset))
                * (0.16666666666666666 * 1.1111111111111112);
        }

        private static double sampleOctaves(PerlinNoiseSampler[] octaves, double x, double y, double z) {
            double value = 0.0, amplitude = 1.0, frequency = 1.0;
            for (PerlinNoiseSampler octave : octaves) {
                if (octave != null) {
                    value += octave.sample(
                        wrap(x * frequency), wrap(y * frequency), wrap(z * frequency),
                        0.0, 0.0) / amplitude;
                }
                frequency *= 2.0;
                amplitude *= 0.5;
            }
            return value;
        }

        private static double wrap(double value) {
            return value - Math.floor(value / 3.3554432E7) * 3.3554432E7;
        }
    }

    /** Builds a continentalness-like shattered function through the full config->factory path. */
    private static DensityFunction buildShattered(long seed) {
        ClimateFunctionConfig config = new ClimateFunctionConfig.Shattered(
            -9,
            List.of(1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0),
            0.25,
            0.0,
            -1000.0,  // wide bounds: observe the raw field, not the clamp
            1000.0
        );
        return new ClimateFunctionFactory(seed, "shattered_test").build(config, "continentalness", null);
    }

    /**
     * With vanilla octave semantics, a first_octave -9 noise has a base wavelength of
     * 2^9 blocks and halving weights, so neighboring blocks must be strongly correlated —
     * the opposite of the shattered/porcupine field. The pre-fix math (frequency from 1.0,
     * doubling weights) produced per-block swings in the tens and fails this immediately.
     */
    @Test
    void doublePerlin_isSmoothAtVanillaScale() {
        PositionalRandomFactory factory = new PositionalRandomFactory(2024L);
        DoublePerlinClimateFunction perlin = new DoublePerlinClimateFunction(
            factory, "vanilla_smoothness", -9,
            new double[]{1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0},
            0.25, 0.0,
            -10.0, 10.0   // wide bounds: observe the raw field, not the clamp
        );

        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int x = 0; x < 16; x++) {
            double v = perlin.compute(x, 0, 0);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertTrue(max - min < 0.5,
            "Adjacent blocks must be correlated at wavelength 512; range over 16 blocks was " + (max - min));

        // Guard against a degenerate flat/zero field: widely spaced samples must differ.
        double farMin = Double.POSITIVE_INFINITY, farMax = Double.NEGATIVE_INFINITY;
        for (int x = 0; x <= 12288; x += 4096) {
            double v = perlin.compute(x, 0, 0);
            farMin = Math.min(farMin, v);
            farMax = Math.max(farMax, v);
        }
        assertTrue(farMax - farMin > 1.0e-6, "Noise must vary across base-wavelength distances");
    }

    /**
     * fillArray must produce the same values as compute() at the same coordinates —
     * historically it carried a second, divergent (and unclamped) sampling formula.
     */
    @Test
    void doublePerlin_fillArray_matchesCompute() {
        PositionalRandomFactory factory = new PositionalRandomFactory(31337L);
        DoublePerlinClimateFunction perlin = new DoublePerlinClimateFunction(
            factory, "fill_array_test", -7, new double[]{1.0, 1.0}, 0.25, 0.0, -0.5, 0.5
        );

        int n = 16;
        DensityFunction.ContextProvider provider = new DensityFunction.ContextProvider() {
            @Override
            public DensityFunction.FunctionContext forIndex(int i) {
                return new DensityFunction.SinglePointContext(i * 3, 64, i * 7);
            }

            @Override
            public void fillAllDirectly(double[] values, DensityFunction function) {
                for (int i = 0; i < values.length; i++) {
                    values[i] = function.compute(forIndex(i));
                }
            }
        };

        double[] filled = new double[n];
        perlin.fillArray(filled, provider);

        for (int i = 0; i < n; i++) {
            assertEquals(perlin.compute(provider.forIndex(i)), filled[i], 0.0,
                "fillArray diverged from compute() at index " + i);
        }
    }

    @Test
    void yGradient_interpolatesAcrossBandAndClampsOutside() {
        // Vanilla depth-style ramp: 1.5 at the bottom falling to -1.5 at the top.
        YGradientClimateFunction gradient = new YGradientClimateFunction(-64, 320, 1.5, -1.5);

        assertEquals(1.5, gradient.compute(0, -64, 0), 1e-9, "value at from_y");
        assertEquals(-1.5, gradient.compute(0, 320, 0), 1e-9, "value at to_y");
        assertEquals(0.0, gradient.compute(0, 128, 0), 1e-9, "midpoint interpolates linearly");

        // Outside the band the value is clamped, not extrapolated.
        assertEquals(1.5, gradient.compute(0, -200, 0), 1e-9, "below from_y clamps");
        assertEquals(-1.5, gradient.compute(0, 1000, 0), 1e-9, "above to_y clamps");

        // Monotone across the band, and independent of X/Z.
        double lower = gradient.compute(0, 0, 0);
        double upper = gradient.compute(0, 200, 0);
        assertTrue(lower > upper, "descending gradient must decrease with height");
        assertEquals(lower, gradient.compute(9999, 0, -9999), 0.0, "must not vary with X/Z");
    }

    @Test
    void yGradient_invertedBand_stillInterpolates() {
        // Ascending gradient (from_value < to_value) must work symmetrically.
        YGradientClimateFunction gradient = new YGradientClimateFunction(0, 100, -1.0, 1.0);

        assertEquals(-1.0, gradient.compute(0, 0, 0), 1e-9);
        assertEquals(0.0, gradient.compute(0, 50, 0), 1e-9);
        assertEquals(1.0, gradient.compute(0, 100, 0), 1e-9);
        assertEquals(-1.0, gradient.minValue(), 0.0);
        assertEquals(1.0, gradient.maxValue(), 0.0);
    }

    @Test
    void yGradient_emptyBand_isRejected() {
        // from_y == to_y would divide by zero and emit NaN climate values.
        assertThrows(IllegalArgumentException.class,
            () -> new YGradientClimateFunction(64, 64, 1.0, -1.0));
    }

    @Test
    void identity_delegatesToWrapped() {
        FermaClimateFunction.Constant wrapped = new FermaClimateFunction.Constant(0.42);
        FermaClimateFunction.Identity identity = new FermaClimateFunction.Identity(wrapped);

        // Identity should return what the wrapped function returns
        assertEquals(0.42, identity.compute(0, 64, 0), 0.0001);
        assertEquals(0.42, identity.compute(999, -32, -888), 0.0001);
    }

    @Test
    void identity_minMaxValue_delegatesToWrapped() {
        FermaClimateFunction.Constant wrapped = new FermaClimateFunction.Constant(0.75);
        FermaClimateFunction.Identity identity = new FermaClimateFunction.Identity(wrapped);

        assertEquals(wrapped.minValue(), identity.minValue(), 0.0001);
        assertEquals(wrapped.maxValue(), identity.maxValue(), 0.0001);
    }
}
