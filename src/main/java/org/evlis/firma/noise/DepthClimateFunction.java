package org.evlis.firma.noise;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Depth climate function combining Y-clamped gradient with continentalness offset.
 * Vanilla formula: yClampedGradient(-64, 320, 1.5, -1.5) + offset
 */
public class DepthClimateFunction implements FirmaClimateFunction {
    private final FirmaClimateFunction offsetSource;
    private final int minY;
    private final int maxY;
    private final double fromValue;
    private final double toValue;
    
    public DepthClimateFunction(FirmaClimateFunction offsetSource) {
        this(offsetSource, -64, 320, 1.5, -1.5);
    }
    
    public DepthClimateFunction(
            FirmaClimateFunction offsetSource,
            int minY,
            int maxY,
            double fromValue,
            double toValue) {
        this.offsetSource = offsetSource;
        this.minY = minY;
        this.maxY = maxY;
        this.fromValue = fromValue;
        this.toValue = toValue;
    }
    
    @Override
    public double compute(double x, double y, double z) {
        // Y-clamped gradient
        double gradient;
        if (y <= this.minY) {
            gradient = this.fromValue;
        } else if (y >= this.maxY) {
            gradient = this.toValue;
        } else {
            double t = (y - this.minY) / (double)(this.maxY - this.minY);
            gradient = this.fromValue + t * (this.toValue - this.fromValue);
        }
        
        // Add offset from continents
        double offset = this.offsetSource.compute(x, y, z);
        
        return gradient + offset;
    }
    
    @Override
    public void fillArray(double[] array, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(array, this);
    }
    
    @Override
    public double minValue() {
        return -2.0; // Conservative estimate
    }
    
    @Override
    public double maxValue() {
        return 2.0; // Conservative estimate
    }
    
    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return this;
    }
    
    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(
            UnserializableMapCodec.of("DepthClimateFunction", this));
    }
}
