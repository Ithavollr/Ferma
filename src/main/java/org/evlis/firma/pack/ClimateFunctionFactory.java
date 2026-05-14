package org.evlis.firma.pack;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.evlis.firma.noise.DepthClimateFunction;
import org.evlis.firma.noise.DoublePerlinClimateFunction;
import org.evlis.firma.noise.FirmaClimateFunction;
import org.evlis.firma.noise.PositionalRandomFactory;

/**
 * Factory for building FirmaClimateFunction instances from ClimateFunctionConfig.
 * Each parameter gets its own seeded RNG for deterministic, independent results.
 */
public class ClimateFunctionFactory {
    
    private final long worldSeed;
    private final String packId;
    public ClimateFunctionFactory(long worldSeed, String packId) {
        this.worldSeed = worldSeed;
        this.packId = packId;
    }
    
    /**
     * Build a DensityFunction from config for a specific climate parameter.
     * Returns the vanilla function directly when config is Identity to avoid wrapper overhead.
     */
    public DensityFunction build(ClimateFunctionConfig config, String parameter, DensityFunction vanilla) {
        // Create unique seed for this parameter
        PositionalRandomFactory factory = new PositionalRandomFactory(
            worldSeed ^ (packId + ":" + parameter).hashCode()
        );
        
        return buildFromConfig(config, factory, vanilla);
    }
    
    private DensityFunction buildFromConfig(ClimateFunctionConfig config, PositionalRandomFactory factory, DensityFunction vanilla) {
        return switch (config) {
            case ClimateFunctionConfig.Constant c -> new FirmaClimateFunction.Constant(c.value());
            case ClimateFunctionConfig.Identity i -> vanilla; // Pass through vanilla directly - no wrapper
            case ClimateFunctionConfig.Perlin p -> buildPerlin(p, factory);
            case ClimateFunctionConfig.OctavePerlin o -> buildOctavePerlin(o, factory);
            case ClimateFunctionConfig.DoublePerlin d -> buildDoublePerlin(d, factory);
            case ClimateFunctionConfig.ShiftedNoise s -> buildShiftedNoise(s, factory, vanilla);
            case ClimateFunctionConfig.WeirdnessToRidges w -> buildWeirdnessToRidges(w, factory, vanilla);
            case ClimateFunctionConfig.YClampedGradient y -> buildYClampedGradient(y, factory, vanilla);
        };
    }
    
    private DensityFunction buildPerlin(ClimateFunctionConfig.Perlin config, PositionalRandomFactory factory) {
        // Single octave Perlin - convert to double array
        double[] amplitudes = new double[] { 1.0 };
        return new DoublePerlinClimateFunction(
            factory,
            "perlin",
            0, // firstOctave
            amplitudes,
            config.xzScale(),
            config.yScale(),
            -1.0, // minValue
            1.0   // maxValue
        );
    }
    
    private DensityFunction buildOctavePerlin(ClimateFunctionConfig.OctavePerlin config, PositionalRandomFactory factory) {
        double[] amplitudes = config.amplitudes().stream().mapToDouble(Double::doubleValue).toArray();
        return new DoublePerlinClimateFunction(
            factory,
            "octave_perlin",
            config.firstOctave(),
            amplitudes,
            config.xzScale(),
            config.yScale(),
            -1.0, // minValue
            1.0   // maxValue
        );
    }
    
    private DensityFunction buildDoublePerlin(ClimateFunctionConfig.DoublePerlin config, PositionalRandomFactory factory) {
        double[] amplitudes = config.amplitudes().stream().mapToDouble(Double::doubleValue).toArray();
        return new DoublePerlinClimateFunction(
            factory,
            "double_perlin",
            config.firstOctave(),
            amplitudes,
            config.xzScale(),
            config.yScale(),
            -1.0, // minValue
            1.0   // maxValue
        );
    }
    
    private DensityFunction buildShiftedNoise(
            ClimateFunctionConfig.ShiftedNoise config, 
            PositionalRandomFactory factory,
            DensityFunction vanilla) {
        
        // For now, use DoublePerlinClimateFunction which has built-in shift support
        // We ignore the custom shift configs and use the standard vanilla-style shifts
        // This can be enhanced later to support custom shift configurations
        
        // Build inner config as double_perlin
        if (config.inner() instanceof ClimateFunctionConfig.DoublePerlin inner) {
            return buildDoublePerlin(inner, factory);
        }
        
        // Fallback to default temperature-style noise
        return new DoublePerlinClimateFunction(
            factory,
            "shifted_noise",
            -10,
            new double[]{1.5, 0.0, 1.0, 0.0, 0.0, 0.0},
            0.25,
            0.0,
            -1.5,
            1.5
        );
    }
    
    private DensityFunction buildWeirdnessToRidges(
            ClimateFunctionConfig.WeirdnessToRidges config,
            PositionalRandomFactory factory,
            DensityFunction vanilla) {
        
        // Build the source function
        DensityFunction source = buildFromConfig(config.source(), factory, vanilla);
        
        // Wrap with weirdness to ridges conversion - source must be a FirmaClimateFunction
        if (source instanceof FirmaClimateFunction firmaSource) {
            return new FirmaClimateFunction.WeirdnessToRidges(firmaSource);
        }
        // If source is plain vanilla, wrap it in Identity first
        return new FirmaClimateFunction.WeirdnessToRidges(new FirmaClimateFunction.Identity(source));
    }
    
    private DensityFunction buildYClampedGradient(
            ClimateFunctionConfig.YClampedGradient config,
            PositionalRandomFactory factory,
            DensityFunction vanilla) {
        
        // For depth, we need continentalness - get it from vanilla or use a default
        // This is a bit tricky since we don't have continentalness yet at this point
        // For now, use a simplified version that doesn't depend on continentalness
        // or use identity for depth if not specified
        
        // Use continentalness from vanilla router - this will be resolved at runtime
        return new DepthClimateFunction(
            new FirmaClimateFunction.Identity(vanilla), // continentalness placeholder
            config.minY(),
            config.maxY(),
            1.5,  // fromValue at minY
            -1.5  // toValue at maxY
        );
    }
}
