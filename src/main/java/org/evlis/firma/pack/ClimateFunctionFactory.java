package org.evlis.firma.pack;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.evlis.firma.noise.DoublePerlinClimateFunction;
import org.evlis.firma.noise.FermaClimateFunction;
import org.evlis.firma.noise.PositionalRandomFactory;
import org.evlis.firma.noise.RadialGradientClimateFunction;
import org.evlis.firma.noise.ShatteredClimateFunction;

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
            case ClimateFunctionConfig.Constant c -> new FermaClimateFunction.Constant(c.value());
            case ClimateFunctionConfig.Identity i -> vanilla; // Pass through vanilla directly - no wrapper
            case ClimateFunctionConfig.Perlin p -> buildPerlin(p, factory);
            case ClimateFunctionConfig.OctavePerlin o -> buildOctavePerlin(o, factory);
            case ClimateFunctionConfig.DoublePerlin d -> buildDoublePerlin(d, factory);
            case ClimateFunctionConfig.Shattered s -> buildShattered(s, factory);
            case ClimateFunctionConfig.ShiftedNoise s -> buildShiftedNoise(s, factory, vanilla);
            case ClimateFunctionConfig.WeirdnessToRidges w -> buildWeirdnessToRidges(w, factory, vanilla);
            case ClimateFunctionConfig.RadialGradient g -> buildRadialGradient(g);
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
            config.minValue(),
            config.maxValue()
        );
    }
    
    private DensityFunction buildShattered(ClimateFunctionConfig.Shattered config, PositionalRandomFactory factory) {
        // The frozen porcupine pipeline; amplitude values were historically ignored, only the count matters.
        return new ShatteredClimateFunction(
            factory,
            config.firstOctave(),
            config.amplitudes().size(),
            config.xzScale(),
            config.yScale(),
            config.minValue(),
            config.maxValue()
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
        if (source instanceof FermaClimateFunction firmaSource) {
            return new FermaClimateFunction.WeirdnessToRidges(firmaSource);
        }
        // If source is plain vanilla, wrap it in Identity first
        return new FermaClimateFunction.WeirdnessToRidges(new FermaClimateFunction.Identity(source));
    }
    
    private DensityFunction buildRadialGradient(ClimateFunctionConfig.RadialGradient config) {
        // Bidirectional radial gradient: startValue + rate * dist, clamped.
        // Supports falloff, fall-up, plateau-then-drop, and bullseye bands.
        return new RadialGradientClimateFunction(
            config.centerX(),
            config.centerZ(),
            config.startValue(),
            config.rate(),
            config.clampMin(),
            config.clampMax()
        );
    }
}
