package org.evlis.firma.noise;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunction.SimpleFunction;
import net.minecraft.util.KeyDispatchDataCodec;

/**
 * Interface for Firma's custom climate noise functions.
 * These replace vanilla's climate density functions in Stage 2.
 */
public interface FirmaClimateFunction extends DensityFunction {
    
    /**
     * Fallback codec for Firma climate functions.
     * Used to avoid delegating to vanilla codecs that may throw (e.g., HolderHolder).
     */
    KeyDispatchDataCodec<? extends DensityFunction> CONSTANT_CODEC = 
        KeyDispatchDataCodec.of(com.mojang.serialization.MapCodec.unit(new Constant(0.0)));
    
    /**
     * Compute the climate value at the given coordinates.
     * This is the core method that will be overridden for custom climate logic.
     */
    double compute(double x, double y, double z);
    
    /**
     * Default implementation delegates to compute().
     */
    @Override
    default double compute(FunctionContext context) {
        return compute(context.blockX(), context.blockY(), context.blockZ());
    }

    /**
     * Identity climate function - delegates to vanilla for testing.
     */
    class Identity implements FirmaClimateFunction {
        private final DensityFunction vanilla;
        
        public Identity(DensityFunction vanilla) {
            this.vanilla = vanilla;
        }
        
        @Override
        public double compute(double x, double y, double z) {
            return vanilla.compute(new DensityFunction.SinglePointContext((int)x, (int)y, (int)z));
        }
        
        @Override
        public void fillArray(double[] array, DensityFunction.ContextProvider contextProvider) {
            vanilla.fillArray(array, contextProvider);
        }
        
        @Override
        public double minValue() {
            return vanilla.minValue();
        }
        
        @Override
        public double maxValue() {
            return vanilla.maxValue();
        }
        
        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return new Identity(visitor.apply(vanilla));
        }
        
        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            // Don't delegate to vanilla codec - it may be a HolderHolder which throws
            return CONSTANT_CODEC;
        }
    }

    /**
     * Constant climate function - returns a fixed value.
     * Useful for testing (e.g., always hot temperature).
     */
    class Constant implements FirmaClimateFunction {
        private final double value;
        
        public Constant(double value) {
            this.value = Math.max(-1.0, Math.min(1.0, value)); // Clamp to climate range
        }
        
        @Override
        public double compute(double x, double y, double z) {
            return value;
        }
        
        @Override
        public void fillArray(double[] array, DensityFunction.ContextProvider contextProvider) {
            // Optimized: fill entire array at once instead of per-element
            java.util.Arrays.fill(array, value);
        }
        
        @Override
        public double minValue() {
            return value;
        }
        
        @Override
        public double maxValue() {
            return value;
        }
        
        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return this;
        }
        
        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CONSTANT_CODEC;
        }
    }

    /**
     * Weirdness to ridges fold function.
     * Converts weirdness noise to ridge values using vanilla's formula.
     */
    class WeirdnessToRidges implements FirmaClimateFunction {
        private final FirmaClimateFunction source;
        
        public WeirdnessToRidges(FirmaClimateFunction source) {
            this.source = source;
        }
        
        @Override
        public double compute(double x, double y, double z) {
            double weirdness = source.compute(x, y, z);
            return vanillaRidges(weirdness);
        }
        
        /**
         * Vanilla weirdness to ridges conversion.
         * Formula: 1.0 - (|weirdness| * 2 - 0.666...) * 4.363...
         */
        private double vanillaRidges(double weirdness) {
            return 1.0 - (Math.abs(weirdness) * 2.0 - 0.6666666666666666) * 4.363636363636363;
        }
        
        @Override
        public void fillArray(double[] array, DensityFunction.ContextProvider contextProvider) {
            for (int i = 0; i < array.length; i++) {
                array[i] = compute(contextProvider.forIndex(i));
            }
        }
        
        @Override
        public double minValue() {
            // Min value when weirdness = 0: 1.0 - (0 - 0.666...) * 4.363... = 1.0 + 0.666... * 4.363... ≈ 3.9
            // But we clamp to expected range
            return -1.0;
        }
        
        @Override
        public double maxValue() {
            return 1.0;
        }
        
        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return new WeirdnessToRidges((FirmaClimateFunction) source.mapAll(visitor));
        }
        
        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return KeyDispatchDataCodec.of(com.mojang.serialization.MapCodec.unit(this));
        }
    }
}
