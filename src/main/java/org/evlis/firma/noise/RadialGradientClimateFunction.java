package org.evlis.firma.noise;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * A bidirectional radial climate function that returns {@code startValue} at the
 * center and linearly changes by {@code rate} per block of XZ distance from that
 * center. The result is clamped to {@code [clampMin, clampMax]}.
 *
 * <h2>Design rationale</h2>
 * <p>Instead of hardcoding a "falloff" (decreasing only, fixed endpoints), this
 * function exposes the <em>rate of change</em> (signed) and optional clamp bounds.
 * This makes the same five parameters expressive enough for:
 *
 * <h3>Use case 1: Radial falloff (original high_mountain intent)</h3>
 * <pre>
 * continentalness:
 *   type: radial_gradient
 *   start_value: 1.0          # peak mountains at spawn
 *   rate: -0.000167            # -1.0 over 6000 blocks: (1.0 - (-1.0)) / 6000
 *   clamp_min: -1.05
 *   clamp_max: 1.0
 * </pre>
 *
 * <h3>Use case 2: Radial fall-up (inverted — low center, high rim)</h3>
 * <pre>
 * temperature:
 *   type: radial_gradient
 *   start_value: -1.0         # frozen core
 *   rate: 0.00025             # warm up as you travel outward
 *   clamp_max: 1.0
 * </pre>
 * Useful for "frozen heart of the world" biomes or creating a safe temperate ring
 * around a hostile center.
 *
 * <h3>Use case 3: Plateau then drop (implicit via clamping)</h3>
 * <pre>
 * continentalness:
 *   type: radial_gradient
 *   start_value: 5.0          # way above clamp_max
 *   rate: -0.001
 *   clamp_max: 1.0            # flat plateau at 1.0 until distance > 4000
 *   clamp_min: -1.0
 * </pre>
 * The value stays pinned at {@code clamp_max} until the linear ramp drops below it,
 * producing a large central landmass with a steep coastal shelf.
 *
 * <h3>Use case 4: Bullseye band (two opposing gradients in a pack)</h3>
 * A future composer function (min/max/add) could combine two gradients:
 * <pre>
 * # Inner mountains
 * continentalness_a: { type: radial_gradient, start_value: 1.0, rate: -0.001, clamp_min: -1.0 }
 * # Outer mountains (ring)
 * continentalness_b: { type: radial_gradient, start_value: -1.0, rate: 0.001, clamp_max: 1.0 }
 * # Combined: max(continentalness_a, continentalness_b) = mountains at 0 and at ~6000
 * </pre>
 * This is not possible with a unidirectional falloff function.
 *
 * <h2>Parameter notes</h2>
 * <ul>
 *   <li>{@code centerX}, {@code centerZ}: the origin point (usually 0,0 for spawn).</li>
 *   <li>{@code startValue}: the raw output at distance 0 (before clamping).</li>
 *   <li>{@code rate}: change per block; negative = decreasing with distance.</li>
 *   <li>{@code clampMin}, {@code clampMax}: hard floor/ceiling. Use extreme values
 *       ({@code -Infinity}, {@code +Infinity}) to disable clamping entirely.</li>
 * </ul>
 *
 * <p>Y coordinate is ignored — this is a purely horizontal (XZ) function, appropriate
 * for climate parameters that drive biome placement based on surface position.
 */
public class RadialGradientClimateFunction implements FirmaClimateFunction {

    private final double centerX;
    private final double centerZ;
    private final double startValue;
    private final double rate;
    private final double clampMin;
    private final double clampMax;

    public RadialGradientClimateFunction(
            double centerX, double centerZ,
            double startValue, double rate,
            double clampMin, double clampMax) {
        if (Double.isNaN(rate)) {
            throw new IllegalArgumentException("rate must be a finite number, got NaN");
        }
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.startValue = startValue;
        this.rate = rate;
        this.clampMin = clampMin;
        this.clampMax = clampMax;
    }

    @Override
    public double compute(double x, double y, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double v = startValue + rate * dist;
        // Clamp to bounds; if bounds are infinite (default), this is a no-op.
        if (v < clampMin) return clampMin;
        if (v > clampMax) return clampMax;
        return v;
    }

    @Override
    public void fillArray(double[] array, DensityFunction.ContextProvider contextProvider) {
        for (int i = 0; i < array.length; i++) {
            DensityFunction.FunctionContext ctx = contextProvider.forIndex(i);
            array[i] = compute(ctx.blockX(), ctx.blockY(), ctx.blockZ());
        }
    }

    @Override
    public double minValue() {
        // If rate is positive, value increases with distance; min is at center.
        // If rate is negative, value decreases with distance; min approaches clampMin (or -Infinity).
        // Return the conservative lower bound we can prove.
        if (rate >= 0) {
            return Math.max(startValue, clampMin);
        } else {
            // As dist → ∞, v → -∞ unless clamped.
            return Double.isInfinite(clampMin) ? Math.min(startValue, startValue + rate * 1000000) : clampMin;
        }
    }

    @Override
    public double maxValue() {
        if (rate <= 0) {
            return Math.min(startValue, clampMax);
        } else {
            return Double.isInfinite(clampMax) ? Math.max(startValue, startValue + rate * 1000000) : clampMax;
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        // This function has no inner DensityFunction to traverse; it's a terminal.
        return this;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(
            UnserializableMapCodec.of("RadialGradientClimateFunction", this));
    }
}
