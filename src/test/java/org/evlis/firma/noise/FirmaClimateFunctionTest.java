package org.evlis.firma.noise;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Firma's custom climate density functions.
 *
 * <p>These tests verify mathematical correctness independent of any pack configuration:
 * <ul>
 *   <li>Constant returns configured value</li>
 *   <li>Radial gradient follows distance formula</li>
 *   <li>Double perlin respects bounds and is deterministic</li>
 * </ul>
 */
class FirmaClimateFunctionTest {

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void constant_returnsConfiguredValue() {
        double value = 0.75;
        FirmaClimateFunction.Constant constant = new FirmaClimateFunction.Constant(value);

        // Should return same value at any coordinate
        assertEquals(value, constant.compute(0, 64, 0), 0.0001);
        assertEquals(value, constant.compute(1000, -32, -5000), 0.0001);
        assertEquals(value, constant.compute(Integer.MAX_VALUE, 320, Integer.MIN_VALUE), 0.0001);
    }

    @Test
    void constant_minMaxValue_matchesConfigured() {
        FirmaClimateFunction.Constant constant = new FirmaClimateFunction.Constant(0.5);

        assertEquals(0.5, constant.minValue(), 0.0001);
        assertEquals(0.5, constant.maxValue(), 0.0001);
    }

    @Test
    void constant_fillArray_optimizesCorrectly() {
        FirmaClimateFunction.Constant constant = new FirmaClimateFunction.Constant(0.25);
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

    @Test
    void identity_delegatesToWrapped() {
        FirmaClimateFunction.Constant wrapped = new FirmaClimateFunction.Constant(0.42);
        FirmaClimateFunction.Identity identity = new FirmaClimateFunction.Identity(wrapped);

        // Identity should return what the wrapped function returns
        assertEquals(0.42, identity.compute(0, 64, 0), 0.0001);
        assertEquals(0.42, identity.compute(999, -32, -888), 0.0001);
    }

    @Test
    void identity_minMaxValue_delegatesToWrapped() {
        FirmaClimateFunction.Constant wrapped = new FirmaClimateFunction.Constant(0.75);
        FirmaClimateFunction.Identity identity = new FirmaClimateFunction.Identity(wrapped);

        assertEquals(wrapped.minValue(), identity.minValue(), 0.0001);
        assertEquals(wrapped.maxValue(), identity.maxValue(), 0.0001);
    }
}
