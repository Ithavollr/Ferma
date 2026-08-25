package org.evlis.firma.noise;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Vertical gradient climate function: interpolates linearly from {@code fromValue} at
 * {@code fromY} to {@code toValue} at {@code toY}, clamped outside that band. This is the
 * vertical counterpart to {@link RadialGradientClimateFunction} and the way a pack
 * expresses altitude-banded climate (e.g. a depth ladder for underground biomes).
 *
 * <p>Mirrors vanilla {@code DensityFunctions.YClampedGradient} semantics and delegates the
 * arithmetic to vanilla's own {@link Mth#clampedMap} so the curve is identical. The
 * function is nonetheless a Ferma class declaring {@link UnserializableMapCodec}: every
 * Ferma noise function must fail loudly on encode rather than inherit a vanilla codec that
 * would silently serialize.
 */
public class YGradientClimateFunction implements FermaClimateFunction {

    private final double fromY;
    private final double toY;
    private final double fromValue;
    private final double toValue;

    /**
     * @throws IllegalArgumentException if {@code fromY == toY}, which would make the
     *         interpolation divide by zero and emit NaN climate values.
     */
    public YGradientClimateFunction(int fromY, int toY, double fromValue, double toValue) {
        if (fromY == toY) {
            throw new IllegalArgumentException(
                "y_gradient requires from_y != to_y (both were " + fromY + "); an empty band"
                + " would produce NaN climate values.");
        }
        this.fromY = fromY;
        this.toY = toY;
        this.fromValue = fromValue;
        this.toValue = toValue;
    }

    @Override
    public double compute(double x, double y, double z) {
        return Mth.clampedMap(y, this.fromY, this.toY, this.fromValue, this.toValue);
    }

    @Override
    public void fillArray(double[] array, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(array, this);
    }

    @Override
    public double minValue() {
        return Math.min(this.fromValue, this.toValue);
    }

    @Override
    public double maxValue() {
        return Math.max(this.fromValue, this.toValue);
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return this;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(
            UnserializableMapCodec.of("YGradientClimateFunction", this));
    }
}
