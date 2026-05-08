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
            return vanilla.codec();
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
            contextProvider.fillAllDirectly(array, this);
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
            return KeyDispatchDataCodec.of(com.mojang.serialization.MapCodec.unit(this));
        }
    }
}
