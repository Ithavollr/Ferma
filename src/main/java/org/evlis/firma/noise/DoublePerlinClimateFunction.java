package org.evlis.firma.noise;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import com.mojang.serialization.MapCodec;

/**
 * Climate function backed by DoublePerlinNoiseSampler.
 * Uses shifted noise for XZ position variation (vanilla-style).
 */
public class DoublePerlinClimateFunction implements FirmaClimateFunction {
    private final DoublePerlinNoiseSampler mainSampler;
    private final DoublePerlinNoiseSampler shiftXSampler;
    private final DoublePerlinNoiseSampler shiftZSampler;
    private final double xzScale;
    private final double yScale;
    private final double minValue;
    private final double maxValue;
    
    public DoublePerlinClimateFunction(
            PositionalRandomFactory factory,
            String noiseId,
            int firstOctave,
            double[] amplitudes,
            double xzScale,
            double yScale,
            double minValue,
            double maxValue) {
        // Main noise uses a unique seed derived from noiseId
        XoroshiroRandomSource mainRandom = factory.fromKey(noiseId);
        this.mainSampler = new DoublePerlinNoiseSampler(mainRandom, firstOctave, amplitudes);
        
        // Shift noises use the SHIFT noise parameters
        XoroshiroRandomSource shiftRandomX = factory.fromKey("minecraft:shift_x");
        XoroshiroRandomSource shiftRandomZ = factory.fromKey("minecraft:shift_z");
        this.shiftXSampler = new DoublePerlinNoiseSampler(shiftRandomX, -3, new double[]{1.0, 1.0, 1.0, 0.0});
        this.shiftZSampler = new DoublePerlinNoiseSampler(shiftRandomZ, -3, new double[]{1.0, 1.0, 1.0, 0.0});
        
        this.xzScale = xzScale;
        this.yScale = yScale;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }
    
    @Override
    public double compute(double x, double y, double z) {
        // Sample shift amounts at original position
        double shiftX = this.shiftXSampler.sample(x, y, z) * 4.0;
        double shiftZ = this.shiftZSampler.sample(x, y, z) * 4.0;
        
        // Sample main noise at shifted position
        return this.mainSampler.sample(
            x * this.xzScale + shiftX,
            y * this.yScale,
            z * this.xzScale + shiftZ
        );
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
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
