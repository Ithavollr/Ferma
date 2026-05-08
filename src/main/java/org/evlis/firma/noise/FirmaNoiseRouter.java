package org.evlis.firma.noise;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.evlis.firma.pack.ClimateFunctionFactory;
import org.evlis.firma.pack.FirmaPack;

/**
 * Utility to patch vanilla NoiseRouter with Firma's climate functions.
 * This is the core of Stage 2 - we replace the 6 climate density functions
 * while leaving everything else unchanged.
 */
public class FirmaNoiseRouter {
    
    /**
     * Create a patched NoiseRouter using a pack's climate configuration.
     * This is the Stage 4 pack-based entry point.
     */
    public static NoiseRouter patchClimateFunctions(NoiseRouter vanillaRouter,
                                                   long seed,
                                                   FirmaPack pack) {
        ClimateFunctionFactory factory = new ClimateFunctionFactory(seed, pack.id());
        
        // Build climate functions from pack configuration
        DensityFunction temperature = factory.build(
            pack.getClimateConfig("temperature"), "temperature", vanillaRouter.temperature());
        DensityFunction humidity = factory.build(
            pack.getClimateConfig("humidity"), "humidity", vanillaRouter.vegetation());
        DensityFunction continents = factory.build(
            pack.getClimateConfig("continentalness"), "continentalness", vanillaRouter.continents());
        DensityFunction erosion = factory.build(
            pack.getClimateConfig("erosion"), "erosion", vanillaRouter.erosion());
        DensityFunction weirdness = factory.build(
            pack.getClimateConfig("weirdness"), "weirdness", vanillaRouter.ridges());
        
        // Depth needs special handling - it depends on continentalness
        // For now, use the pack config or default to vanilla-style depth
        DensityFunction depth = factory.build(
            pack.getClimateConfig("depth"), "depth", vanillaRouter.depth());
        
        // Pass vanilla functions directly for non-climate fields - wrapping them in Identity
        // would cause per-call SinglePointContext allocations on hot terrain-gen paths.
        return new NoiseRouter(
            vanillaRouter.barrierNoise(),
            vanillaRouter.fluidLevelFloodednessNoise(),
            vanillaRouter.fluidLevelSpreadNoise(),
            vanillaRouter.lavaNoise(),
            temperature,
            humidity,
            continents,
            erosion,
            depth,
            weirdness,
            vanillaRouter.initialDensityWithoutJaggedness(),
            vanillaRouter.finalDensity(),
            vanillaRouter.veinToggle(),
            vanillaRouter.veinRidged(),
            vanillaRouter.veinGap()
        );
    }
    
    /**
     * Create a patched NoiseRouter with Firma's climate functions.
     * 
     * @param vanillaRouter The original vanilla NoiseRouter
     * @param randomState The random state containing noise settings
     * @param seed The world seed
     * @param mode The generation mode (identity or custom)
     * @return A new NoiseRouter with patched climate functions
     */
    public static NoiseRouter patchClimateFunctions(NoiseRouter vanillaRouter, 
                                                   RandomState randomState,
                                                   long seed,
                                                   PatchMode mode) {
        
        // Create climate functions based on mode
        if (mode == PatchMode.IDENTITY) {
            // Identity mode - delegate to vanilla for verification
            return new NoiseRouter(
                new FirmaClimateFunction.Identity(vanillaRouter.barrierNoise()),
                new FirmaClimateFunction.Identity(vanillaRouter.fluidLevelFloodednessNoise()),
                new FirmaClimateFunction.Identity(vanillaRouter.fluidLevelSpreadNoise()),
                new FirmaClimateFunction.Identity(vanillaRouter.lavaNoise()),
                new FirmaClimateFunction.Identity(vanillaRouter.temperature()),
                new FirmaClimateFunction.Identity(vanillaRouter.vegetation()),
                new FirmaClimateFunction.Identity(vanillaRouter.continents()),
                new FirmaClimateFunction.Identity(vanillaRouter.erosion()),
                new FirmaClimateFunction.Identity(vanillaRouter.depth()),
                new FirmaClimateFunction.Identity(vanillaRouter.ridges()),
                new FirmaClimateFunction.Identity(vanillaRouter.initialDensityWithoutJaggedness()),
                new FirmaClimateFunction.Identity(vanillaRouter.finalDensity()),
                new FirmaClimateFunction.Identity(vanillaRouter.veinToggle()),
                new FirmaClimateFunction.Identity(vanillaRouter.veinRidged()),
                new FirmaClimateFunction.Identity(vanillaRouter.veinGap())
            );
        } else {
            // Custom mode - implement actual climate logic
            // For now, we'll create simple implementations
            PositionalRandomFactory factory = new PositionalRandomFactory(seed);
            
            // Create custom climate functions
            // Build in dependency order: depth depends on continents
            FirmaClimateFunction temperature = createTemperatureFunction(factory, mode);
            FirmaClimateFunction humidity = createHumidityFunction(factory, mode);
            FirmaClimateFunction continents = createContinentsFunction(factory, mode);
            FirmaClimateFunction erosion = createErosionFunction(factory, mode);
            FirmaClimateFunction weirdness = createWeirdnessFunction(factory, mode);
            FirmaClimateFunction depth = createDepthFunction(factory, mode, continents);
            
            return new NoiseRouter(
                new FirmaClimateFunction.Identity(vanillaRouter.barrierNoise()),
                new FirmaClimateFunction.Identity(vanillaRouter.fluidLevelFloodednessNoise()),
                new FirmaClimateFunction.Identity(vanillaRouter.fluidLevelSpreadNoise()),
                new FirmaClimateFunction.Identity(vanillaRouter.lavaNoise()),
                temperature,
                humidity,
                continents,
                erosion,
                depth,
                weirdness,
                new FirmaClimateFunction.Identity(vanillaRouter.initialDensityWithoutJaggedness()),
                new FirmaClimateFunction.Identity(vanillaRouter.finalDensity()),
                new FirmaClimateFunction.Identity(vanillaRouter.veinToggle()),
                new FirmaClimateFunction.Identity(vanillaRouter.veinRidged()),
                new FirmaClimateFunction.Identity(vanillaRouter.veinGap())
            );
        }
    }
    
    /**
     * Create temperature function - uses shifted DoublePerlin noise.
     * Vanilla parameters: firstOctave=-10, amplitudes=[1.5, 0.0, 1.0, 0.0, 0.0, 0.0], xz_scale=0.25
     */
    private static FirmaClimateFunction createTemperatureFunction(PositionalRandomFactory factory, PatchMode mode) {
        if (mode == PatchMode.CONSTANT_HOT) {
            return new FirmaClimateFunction.Constant(1.0);
        }
        if (mode == PatchMode.FROZEN) {
            return new FirmaClimateFunction.Constant(-1.0);
        }
        // Standard overworld temperature noise (used by VANILLA_NOISE and CUSTOM)
        return new DoublePerlinClimateFunction(
            factory,
            "minecraft:temperature",
            -10, // firstOctave
            new double[]{1.5, 0.0, 1.0, 0.0, 0.0, 0.0}, // amplitudes
            0.25, // xz_scale
            0.0,  // y_scale (2D noise)
            -1.5, // minValue
            1.5   // maxValue
        );
    }
    
    /**
     * Create humidity function - uses shifted DoublePerlin noise.
     * Vanilla parameters: firstOctave=-8, amplitudes=[1.0, 1.0, 0.0, 0.0, 0.0, 0.0], xz_scale=0.25
     */
    private static FirmaClimateFunction createHumidityFunction(PositionalRandomFactory factory, PatchMode mode) {
        // Standard overworld vegetation/humidity noise (used by all non-constant modes)
        return new DoublePerlinClimateFunction(
            factory,
            "minecraft:vegetation",
            -8, // firstOctave
            new double[]{1.0, 1.0, 0.0, 0.0, 0.0, 0.0}, // amplitudes
            0.25, // xz_scale
            0.0,  // y_scale (2D noise)
            -1.0, // minValue
            1.0   // maxValue
        );
    }
    
    /**
     * Create continentalness function - uses shifted DoublePerlin noise.
     * Vanilla parameters: firstOctave=-9, amplitudes=[1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0], xz_scale=0.25
     */
    private static FirmaClimateFunction createContinentsFunction(PositionalRandomFactory factory, PatchMode mode) {
        // Standard overworld continentalness noise
        return new DoublePerlinClimateFunction(
            factory,
            "minecraft:continentalness",
            -9, // firstOctave
            new double[]{1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0}, // amplitudes
            0.25, // xz_scale
            0.0,  // y_scale (2D noise)
            -2.0, // minValue (amplified by 2.0 amplitudes)
            2.0   // maxValue
        );
    }
    
    /**
     * Create erosion function - uses shifted DoublePerlin noise.
     * Vanilla parameters: firstOctave=-9, amplitudes=[1.0, 1.0, 0.0, 1.0, 1.0], xz_scale=0.25
     */
    private static FirmaClimateFunction createErosionFunction(PositionalRandomFactory factory, PatchMode mode) {
        // Standard overworld erosion noise
        return new DoublePerlinClimateFunction(
            factory,
            "minecraft:erosion",
            -9, // firstOctave
            new double[]{1.0, 1.0, 0.0, 1.0, 1.0}, // amplitudes
            0.25, // xz_scale
            0.0,  // y_scale (2D noise)
            -1.0, // minValue
            1.0   // maxValue
        );
    }
    
    /**
     * Create weirdness (ridges) function - uses shifted DoublePerlin noise.
     * Vanilla parameters: firstOctave=-7, amplitudes=[1.0, 2.0, 1.0, 0.0, 0.0, 0.0], xz_scale=0.25
     */
    private static FirmaClimateFunction createWeirdnessFunction(PositionalRandomFactory factory, PatchMode mode) {
        // Standard overworld ridges/weirdness noise
        DoublePerlinClimateFunction weirdness = new DoublePerlinClimateFunction(
            factory,
            "minecraft:ridge",
            -7, // firstOctave
            new double[]{1.0, 2.0, 1.0, 0.0, 0.0, 0.0}, // amplitudes
            0.25, // xz_scale
            0.0,  // y_scale (2D noise)
            -1.5, // minValue
            1.5   // maxValue
        );
        
        // Return as-is (ridges fold is applied by vanilla, not by us)
        return weirdness;
    }
    
    /**
     * Create depth function - Y-clamped gradient + continentalness offset.
     * Vanilla: yClampedGradient(-64, 320, 1.5, -1.5) + offset
     */
    private static FirmaClimateFunction createDepthFunction(PositionalRandomFactory factory, 
                                                           PatchMode mode,
                                                           FirmaClimateFunction continents) {
        // Depth is gradient + continentalness offset
        return new DepthClimateFunction(continents, -64, 320, 1.5, -1.5);
    }
    
    /**
     * Patch mode for testing different configurations.
     */
    public enum PatchMode {
        /** Identity mode - delegates to vanilla for verification */
        IDENTITY,
        /** Constant hot mode - for testing climate override */
        CONSTANT_HOT,
        /** Frozen mode - for testing cold climate override */
        FROZEN,
        /** Vanilla noise mode - uses our noise implementations with vanilla parameters */
        VANILLA_NOISE,
        /** Full custom mode - for Stage 4 pack-based configuration */
        CUSTOM
    }
}
