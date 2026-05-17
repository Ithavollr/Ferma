package org.evlis.firma.noise;

import net.minecraft.core.Holder;
import net.minecraft.data.worldgen.TerrainProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
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
                                                   RandomState randomState,
                                                   long seed,
                                                   FirmaPack pack) {
        ClimateFunctionFactory factory = new ClimateFunctionFactory(seed, pack.id());
        
        // Only build custom functions for parameters explicitly configured in the pack.
        // For unspecified parameters, use vanilla directly (no factory overhead, no wrapper).
        DensityFunction temperature = pack.hasClimateConfig("temperature")
            ? factory.build(pack.getClimateConfig("temperature"), "temperature", vanillaRouter.temperature())
            : vanillaRouter.temperature();
        DensityFunction humidity = pack.hasClimateConfig("humidity")
            ? factory.build(pack.getClimateConfig("humidity"), "humidity", vanillaRouter.vegetation())
            : vanillaRouter.vegetation();
        DensityFunction continents = pack.hasClimateConfig("continentalness")
            ? factory.build(pack.getClimateConfig("continentalness"), "continentalness", vanillaRouter.continents())
            : vanillaRouter.continents();
        DensityFunction erosion = pack.hasClimateConfig("erosion")
            ? factory.build(pack.getClimateConfig("erosion"), "erosion", vanillaRouter.erosion())
            : vanillaRouter.erosion();
        DensityFunction weirdness = pack.hasClimateConfig("weirdness")
            ? factory.build(pack.getClimateConfig("weirdness"), "weirdness", vanillaRouter.ridges())
            : vanillaRouter.ridges();
        
        // Rebuild terrain-shape fields from patched climate functions
        TerrainInputs terrain = rebuildTerrainInputs(continents, erosion, weirdness, false, randomState);
        
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
            terrain.depth,
            weirdness,
            terrain.initialDensityWithoutJaggedness,
            terrain.finalDensity,
            vanillaRouter.veinToggle(),
            vanillaRouter.veinRidged(),
            vanillaRouter.veinGap()
        );
    }
    
    /**
     * Rebuild terrain-shape inputs (offset, factor, jaggedness, depth) from patched climate functions.
     * Uses vanilla's spline logic from TerrainProvider to preserve terrain behavior.
     * Also rebuilds initialDensityWithoutJaggedness and finalDensity to use the rebuilt terrain inputs.
     */
    private static TerrainInputs rebuildTerrainInputs(
        DensityFunction continentalness,
        DensityFunction erosion,
        DensityFunction ridges,
        boolean amplified,
        RandomState randomState
    ) {
        // Wrap climate functions in spline coordinates, matching vanilla's approach
        DensityFunctions.Spline.Coordinate continentsCoord = new DensityFunctions.Spline.Coordinate(Holder.direct(continentalness));
        DensityFunctions.Spline.Coordinate erosionCoord = new DensityFunctions.Spline.Coordinate(Holder.direct(erosion));
        DensityFunctions.Spline.Coordinate ridgesCoord = new DensityFunctions.Spline.Coordinate(Holder.direct(ridges));
        
        // Create ridges_folded from ridges (peaksAndValleys transform)
        DensityFunction ridgesFolded = peaksAndValleys(ridges);
        DensityFunctions.Spline.Coordinate ridgesFoldedCoord = new DensityFunctions.Spline.Coordinate(Holder.direct(ridgesFolded));
        
        // Build offset spline using vanilla's TerrainProvider logic
        // Vanilla wraps in splineWithBlending(add(GLOBAL_OFFSET, spline), blendOffset())
        CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> offsetSpline = 
            TerrainProvider.overworldOffset(continentsCoord, erosionCoord, ridgesFoldedCoord, amplified);
        DensityFunction offsetRaw = DensityFunctions.add(
            DensityFunctions.constant(-0.50375F), // GLOBAL_OFFSET
            DensityFunctions.spline(offsetSpline)
        );
        DensityFunction offset = splineWithBlending(offsetRaw, DensityFunctions.blendOffset());
        
        // Build factor spline using vanilla's TerrainProvider logic
        // Vanilla wraps in splineWithBlending(spline, constant(10.0))
        CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> factorSpline = 
            TerrainProvider.overworldFactor(continentsCoord, erosionCoord, ridgesCoord, ridgesFoldedCoord, amplified);
        DensityFunction factorRaw = DensityFunctions.spline(factorSpline);
        DensityFunction factor = splineWithBlending(factorRaw, DensityFunctions.constant(10.0));
        
        // Build jaggedness spline using vanilla's TerrainProvider logic
        // Vanilla wraps in splineWithBlending(spline, zero())
        CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> jaggednessSpline = 
            TerrainProvider.overworldJaggedness(continentsCoord, erosionCoord, ridgesCoord, ridgesFoldedCoord, amplified);
        DensityFunction jaggednessRaw = DensityFunctions.spline(jaggednessSpline);
        DensityFunction jaggedness = splineWithBlending(jaggednessRaw, DensityFunctions.zero());
        
        // Build depth as yClampedGradient + offset, matching vanilla
        DensityFunction depth = DensityFunctions.add(
            DensityFunctions.yClampedGradient(-64, 320, 1.5, -1.5),
            offset
        );
        
        // Rebuild initialDensityWithoutJaggedness and finalDensity using rebuilt terrain inputs
        // We need to extract jagged noise and BASE_3D_NOISE from vanilla's existing functions
        // Since we can't easily decompose vanilla's wired functions, we'll rebuild the full pipeline
        
        // Step 1: Build sloped_cheese from rebuilt terrain inputs
        // sloped_cheese = noiseGradientDensity(factor, depth + jaggedness * jaggedNoise) + BASE_3D_NOISE
        // We need to extract these from vanilla router's existing finalDensity
        
        // For now, extract the components we need by unwrapping vanilla's initialDensityWithoutJaggedness
        // Vanilla: initialDensityWithoutJaggedness = slideOverworld(amplified, add(noiseGradientDensity(...), constant(-0.703125)).clamp(-64, 64))
        // We'll rebuild this using our terrain inputs
        
        DensityFunction initialDensity = rebuildInitialDensity(factor, depth, jaggedness, amplified);
        DensityFunction finalDensity = rebuildFinalDensity(factor, depth, jaggedness, amplified, randomState);
        
        return new TerrainInputs(offset, factor, jaggedness, depth, initialDensity, finalDensity);
    }
    
    /**
     * Rebuild initialDensityWithoutJaggedness from rebuilt terrain inputs.
     * Vanilla: slideOverworld(amplified, add(noiseGradientDensity(cache2d(factor), depth), constant(-0.703125)).clamp(-64, 64))
     */
    private static DensityFunction rebuildInitialDensity(
        DensityFunction factor,
        DensityFunction depth,
        DensityFunction jaggedness,
        boolean amplified
    ) {
        // noiseGradientDensity(factor, depth) = mul(constant(4.0), mul(depth, factor).quarterNegative())
        DensityFunction factorCached = DensityFunctions.cache2d(factor);
        DensityFunction noiseGradient = noiseGradientDensity(factorCached, depth);
        
        // Add the constant offset and clamp
        DensityFunction densityWithOffset = DensityFunctions.add(noiseGradient, DensityFunctions.constant(-0.703125)).clamp(-64.0, 64.0);
        
        // Apply slide transform
        return slideOverworld(amplified, densityWithOffset);
    }
    
    /**
     * Rebuild finalDensity from rebuilt terrain inputs.
     * This is the full cave/aquifer/slide/noodle pipeline using rebuilt sloped_cheese.
     */
    private static DensityFunction rebuildFinalDensity(
        DensityFunction factor,
        DensityFunction depth,
        DensityFunction jaggedness,
        boolean amplified,
        RandomState randomState
    ) {
        // Get the wired noise functions from RandomState
        // These are already seeded and ready to compute
        NoiseRouter vanillaRouter = randomState.router();
        
        // Extract BASE_3D_NOISE_OVERWORLD and jaggedNoise from vanilla's wired router
        // We'll use the vanilla router's existing cave/aquifer/noodle functions but rebuild sloped_cheese
        
        // Build sloped_cheese from our rebuilt terrain inputs
        // sloped_cheese = noiseGradientDensity(factor, depth + jaggedness * jaggedNoise) + BASE_3D_NOISE
        
        // Access private fields from RandomState via reflection
        NoiseAccess noiseAccess = new NoiseAccess(randomState);
        
        // Create wired jagged noise matching vanilla: DensityFunctions.noise(Noises.JAGGED, 1500.0, 0.0)
        DensityFunction jaggedNoise = noiseAccess.wireNoise(
            DensityFunctions.noise(noiseAccess.noises.getOrThrow(Noises.JAGGED), 1500.0, 0.0)
        );
        
        // Create wired BASE_3D_NOISE_OVERWORLD matching vanilla's BlendedNoise seeding
        DensityFunction base3dNoise = BlendedNoise.createUnseeded(
            0.25, 0.125, 80.0, 160.0, 8.0
        ).withNewRandom(noiseAccess.terrainRandom);
        
        // Build sloped_cheese: noiseGradientDensity(factor, depth + jaggedness * jaggedNoise) + BASE_3D_NOISE
        DensityFunction factorCached = DensityFunctions.cache2d(factor);
        DensityFunction jaggedComponent = DensityFunctions.mul(jaggedness, jaggedNoise.halfNegative());
        DensityFunction depthWithJaggedness = DensityFunctions.add(depth, jaggedComponent);
        DensityFunction slopedCheeseBase = noiseGradientDensity(factorCached, depthWithJaggedness);
        DensityFunction slopedCheese = DensityFunctions.add(slopedCheeseBase, base3dNoise);
        
        // Rebuild full finalDensity pipeline with noise caves (Option B from Noise_Path.md §2.4)
        // Vanilla structure (from NoiseRouterData.overworld):
        // densityFunction7 = min(slopedCheese, mul(constant(5.0), ENTRANCES))
        // densityFunction8 = rangeChoice(slopedCheese, -1000000.0, 1.5625, densityFunction7, underground(...))
        // densityFunction9 = min(postProcess(slideOverworld(amplified, densityFunction8)), NOODLE)
        
        DensityFunction spaghettiRoughness = rebuildSpaghettiRoughness(noiseAccess);
        DensityFunction entrances = rebuildEntrances(noiseAccess, spaghettiRoughness);
        DensityFunction spaghetti2d = rebuildSpaghetti2D(noiseAccess);
        DensityFunction pillars = rebuildPillars(noiseAccess);
        DensityFunction noodle = rebuildNoodle(noiseAccess);
        
        DensityFunction underground = rebuildUnderground(
            slopedCheese, entrances, spaghetti2d, spaghettiRoughness, pillars, noiseAccess
        );
        
        // Compose full pipeline: entrance caves -> range choice -> slide -> postProcess -> noodle
        DensityFunction withEntrances = DensityFunctions.min(
            slopedCheese,
            DensityFunctions.mul(DensityFunctions.constant(5.0), entrances)
        );
        DensityFunction withCaves = DensityFunctions.rangeChoice(
            slopedCheese, -1000000.0, 1.5625, withEntrances, underground
        );
        DensityFunction slided = slideOverworld(amplified, withCaves);
        DensityFunction postProcessed = postProcess(slided);
        
        return DensityFunctions.min(postProcessed, noodle);
    }
    
    /**
     * Vanilla's noiseGradientDensity function.
     * Returns: mul(constant(4.0), mul(maxFunction, minFunction).quarterNegative())
     */
    private static DensityFunction noiseGradientDensity(DensityFunction minFunction, DensityFunction maxFunction) {
        DensityFunction multiplied = DensityFunctions.mul(maxFunction, minFunction);
        return DensityFunctions.mul(DensityFunctions.constant(4.0), multiplied.quarterNegative());
    }
    
    /**
     * Vanilla's splineWithBlending function from NoiseRouterData.
     * Returns: flatCache(cache2d(lerp(blendAlpha(), blendValue, splineValue)))
     */
    private static DensityFunction splineWithBlending(DensityFunction splineValue, DensityFunction blendValue) {
        DensityFunction lerped = DensityFunctions.lerp(DensityFunctions.blendAlpha(), blendValue, splineValue);
        return DensityFunctions.flatCache(DensityFunctions.cache2d(lerped));
    }
    
    /**
     * Vanilla's postProcess function.
     * Returns: mul(interpolated(blendDensity(densityFunction)), constant(0.64)).squeeze()
     */
    private static DensityFunction postProcess(DensityFunction densityFunction) {
        DensityFunction blended = DensityFunctions.blendDensity(densityFunction);
        DensityFunction interpolated = DensityFunctions.interpolated(blended);
        return DensityFunctions.mul(interpolated, DensityFunctions.constant(0.64)).squeeze();
    }
    
    /**
     * Vanilla's slideOverworld function.
     */
    private static DensityFunction slideOverworld(boolean amplified, DensityFunction densityFunction) {
        return slide(
            densityFunction,
            -64,  // minY
            384,  // height
            amplified ? 16 : 80,   // topStartOffset
            amplified ? 0 : 64,    // topEndOffset
            -0.078125,             // topDelta
            0,                     // bottomStartOffset
            24,                    // bottomEndOffset
            amplified ? 0.4 : 0.1171875  // bottomDelta
        );
    }
    
    /**
     * Vanilla's slide function for terrain density modification.
     * Uses DensityFunctions.lerp() exactly as vanilla does in NoiseRouterData.slide().
     */
    private static DensityFunction slide(
        DensityFunction input,
        int minY,
        int height,
        int topStartOffset,
        int topEndOffset,
        double topDelta,
        int bottomStartOffset,
        int bottomEndOffset,
        double bottomDelta
    ) {
        DensityFunction topGradient = DensityFunctions.yClampedGradient(
            minY + height - topStartOffset,
            minY + height - topEndOffset,
            1.0,
            0.0
        );
        DensityFunction afterTopSlide = DensityFunctions.lerp(topGradient, topDelta, input);
        DensityFunction bottomGradient = DensityFunctions.yClampedGradient(
            minY + bottomStartOffset,
            minY + bottomEndOffset,
            0.0,
            1.0
        );
        return DensityFunctions.lerp(bottomGradient, bottomDelta, afterTopSlide);
    }
    
    /**
     * Vanilla's peaksAndValleys transform for ridges -> ridges_folded.
     * From NoiseRouterData: -(|abs(weirdness) - 0.6666667| - 0.33333334) * 3.0
     */
    private static DensityFunction peaksAndValleys(DensityFunction weirdness) {
        return new PeaksAndValleysFunction(weirdness);
    }
    
    /**
     * Container for rebuilt terrain inputs.
     */
    private static record TerrainInputs(
        DensityFunction offset,
        DensityFunction factor,
        DensityFunction jaggedness,
        DensityFunction depth,
        DensityFunction initialDensityWithoutJaggedness,
        DensityFunction finalDensity
    ) {}
    
    /**
     * Custom DensityFunction implementing vanilla's peaksAndValleys transform.
     */
    private static class PeaksAndValleysFunction implements DensityFunction {
        private final DensityFunction input;
        
        public PeaksAndValleysFunction(DensityFunction input) {
            this.input = input;
        }
        
        @Override
        public double compute(FunctionContext context) {
            double weirdness = input.compute(context);
            return -(Math.abs(Math.abs(weirdness) - 0.6666667) - 0.33333334) * 3.0;
        }
        
        @Override
        public void fillArray(double[] array, ContextProvider contextProvider) {
            input.fillArray(array, contextProvider);
            for (int i = 0; i < array.length; i++) {
                double weirdness = array[i];
                array[i] = -(Math.abs(Math.abs(weirdness) - 0.6666667) - 0.33333334) * 3.0;
            }
        }
        
        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new PeaksAndValleysFunction(input.mapAll(visitor)));
        }
        
        @Override
        public double minValue() {
            return -3.0;
        }
        
        @Override
        public double maxValue() {
            return 0.0;
        }
        
        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return KeyDispatchDataCodec.of(
                    UnserializableMapCodec.of("PeaksAndValleysFunction", new PeaksAndValleysFunction(DensityFunctions.zero()))
            );
        }
    }
    
    /**
     * Helper providing access to RandomState's private noise fields via reflection.
     * Encapsulates all reflection access and provides noise wiring utilities.
     */
    private static class NoiseAccess {
        final net.minecraft.core.HolderGetter<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters> noises;
        final RandomSource terrainRandom;
        private final RandomState randomState;
        
        NoiseAccess(RandomState randomState) {
            this.randomState = randomState;
            try {
                java.lang.reflect.Field noisesField = RandomState.class.getDeclaredField("noises");
                noisesField.setAccessible(true);
                this.noises = (net.minecraft.core.HolderGetter<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters>)
                    noisesField.get(randomState);
                
                java.lang.reflect.Field randomField = RandomState.class.getDeclaredField("random");
                randomField.setAccessible(true);
                net.minecraft.world.level.levelgen.PositionalRandomFactory randomFactory =
                    (net.minecraft.world.level.levelgen.PositionalRandomFactory) randomField.get(randomState);
                this.terrainRandom = randomFactory.fromHashOf(ResourceLocation.withDefaultNamespace("terrain"));
            } catch (Exception e) {
                throw new RuntimeException("Failed to access RandomState private fields", e);
            }
        }
        
        /**
         * Wire an un-wired DensityFunction by resolving all NoiseHolder references
         * to their instantiated NormalNoise instances. Mirrors vanilla's NoiseWiringHelper.visitNoise().
         */
        DensityFunction wireNoise(DensityFunction unwired) {
            return unwired.mapAll(new DensityFunction.Visitor() {
                @Override
                public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noiseHolder) {
                    net.minecraft.world.level.levelgen.synth.NormalNoise noise = randomState.getOrCreateNoise(
                        noiseHolder.noiseData().unwrapKey().orElseThrow()
                    );
                    return new DensityFunction.NoiseHolder(noiseHolder.noiseData(), noise);
                }
                
                @Override
                public DensityFunction apply(DensityFunction densityFunction) {
                    return densityFunction;
                }
            });
        }
    }
    
    // ========== Cave function rebuilders (matching NoiseRouterData exactly) ==========
    
    // WeirdScaledSampler.RarityValueMapper is protected, so we access the enum constants via reflection
    private static final Object RARITY_TYPE1;
    private static final Object RARITY_TYPE2;
    private static final java.lang.reflect.Method WEIRD_SCALED_SAMPLER_METHOD;
    static {
        try {
            Class<?> rarityClass = Class.forName(
                "net.minecraft.world.level.levelgen.DensityFunctions$WeirdScaledSampler$RarityValueMapper"
            );
            RARITY_TYPE1 = Enum.valueOf((Class<Enum>) rarityClass, "TYPE1");
            RARITY_TYPE2 = Enum.valueOf((Class<Enum>) rarityClass, "TYPE2");
            WEIRD_SCALED_SAMPLER_METHOD = DensityFunctions.class.getMethod(
                "weirdScaledSampler", DensityFunction.class, Holder.class, rarityClass
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to access WeirdScaledSampler.RarityValueMapper", e);
        }
    }
    
    private static DensityFunction weirdScaledSampler(
        DensityFunction input, Holder<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters> noiseData, Object rarityType
    ) {
        try {
            return (DensityFunction) WEIRD_SCALED_SAMPLER_METHOD.invoke(null, input, noiseData, rarityType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke weirdScaledSampler", e);
        }
    }
    
    /**
     * Matches NoiseRouterData.spaghettiRoughnessFunction().
     */
    private static DensityFunction rebuildSpaghettiRoughness(NoiseAccess na) {
        DensityFunction roughness = DensityFunctions.noise(na.noises.getOrThrow(Noises.SPAGHETTI_ROUGHNESS));
        DensityFunction modulator = DensityFunctions.mappedNoise(na.noises.getOrThrow(Noises.SPAGHETTI_ROUGHNESS_MODULATOR), 0.0, -0.1);
        return na.wireNoise(
            DensityFunctions.cacheOnce(DensityFunctions.mul(modulator, DensityFunctions.add(roughness.abs(), DensityFunctions.constant(-0.4))))
        );
    }
    
    /**
     * Matches NoiseRouterData.entrances().
     */
    private static DensityFunction rebuildEntrances(NoiseAccess na, DensityFunction spaghettiRoughness) {
        DensityFunction rarity = DensityFunctions.cacheOnce(DensityFunctions.noise(na.noises.getOrThrow(Noises.SPAGHETTI_3D_RARITY), 2.0, 1.0));
        DensityFunction thickness = DensityFunctions.mappedNoise(na.noises.getOrThrow(Noises.SPAGHETTI_3D_THICKNESS), -0.065, -0.088);
        DensityFunction spaghetti3d1 = weirdScaledSampler(
            rarity, na.noises.getOrThrow(Noises.SPAGHETTI_3D_1), RARITY_TYPE1
        );
        DensityFunction spaghetti3d2 = weirdScaledSampler(
            rarity, na.noises.getOrThrow(Noises.SPAGHETTI_3D_2), RARITY_TYPE1
        );
        DensityFunction spaghetti3dCombined = DensityFunctions.add(
            DensityFunctions.max(spaghetti3d1, spaghetti3d2), thickness
        ).clamp(-1.0, 1.0);
        DensityFunction caveEntrance = DensityFunctions.noise(na.noises.getOrThrow(Noises.CAVE_ENTRANCE), 0.75, 0.5);
        DensityFunction entranceGradient = DensityFunctions.add(
            DensityFunctions.add(caveEntrance, DensityFunctions.constant(0.37)),
            DensityFunctions.yClampedGradient(-10, 30, 0.3, 0.0)
        );
        return na.wireNoise(
            DensityFunctions.cacheOnce(DensityFunctions.min(entranceGradient, DensityFunctions.add(spaghettiRoughness, spaghetti3dCombined)))
        );
    }
    
    /**
     * Matches NoiseRouterData.spaghetti2D().
     */
    private static DensityFunction rebuildSpaghetti2D(NoiseAccess na) {
        DensityFunction modulator = DensityFunctions.noise(na.noises.getOrThrow(Noises.SPAGHETTI_2D_MODULATOR), 2.0, 1.0);
        DensityFunction sampler = weirdScaledSampler(
            modulator, na.noises.getOrThrow(Noises.SPAGHETTI_2D), RARITY_TYPE2
        );
        DensityFunction elevation = DensityFunctions.mappedNoise(
            na.noises.getOrThrow(Noises.SPAGHETTI_2D_ELEVATION), 0.0, Math.floorDiv(-64, 8), 8.0
        );
        DensityFunction thicknessModulator = DensityFunctions.cacheOnce(
            DensityFunctions.mappedNoise(na.noises.getOrThrow(Noises.SPAGHETTI_2D_THICKNESS), 2.0, 1.0, -0.6, -1.3)
        );
        DensityFunction elevationGradient = DensityFunctions.add(
            elevation, DensityFunctions.yClampedGradient(-64, 320, 8.0, -40.0)
        ).abs();
        DensityFunction shaped = DensityFunctions.add(elevationGradient, thicknessModulator).cube();
        DensityFunction combined = DensityFunctions.add(
            sampler, DensityFunctions.mul(DensityFunctions.constant(0.083), thicknessModulator)
        );
        return na.wireNoise(DensityFunctions.max(combined, shaped).clamp(-1.0, 1.0));
    }
    
    /**
     * Matches NoiseRouterData.pillars().
     */
    private static DensityFunction rebuildPillars(NoiseAccess na) {
        DensityFunction pillar = DensityFunctions.noise(na.noises.getOrThrow(Noises.PILLAR), 25.0, 0.3);
        DensityFunction rareness = DensityFunctions.mappedNoise(na.noises.getOrThrow(Noises.PILLAR_RARENESS), 0.0, -2.0);
        DensityFunction thickness = DensityFunctions.mappedNoise(na.noises.getOrThrow(Noises.PILLAR_THICKNESS), 0.0, 1.1);
        DensityFunction combined = DensityFunctions.add(
            DensityFunctions.mul(pillar, DensityFunctions.constant(2.0)), rareness
        );
        return na.wireNoise(DensityFunctions.cacheOnce(DensityFunctions.mul(combined, thickness.cube())));
    }
    
    /**
     * Matches NoiseRouterData.noodle().
     * Vanilla's Y function is yClampedGradient(MIN_Y*2, MAX_Y*2, MIN_Y*2, MAX_Y*2) = yClampedGradient(-128, 768, -128, 768).
     */
    private static DensityFunction rebuildNoodle(NoiseAccess na) {
        DensityFunction yFunc = DensityFunctions.yClampedGradient(-128, 768, -128, 768);
        DensityFunction noodleNoise = yLimitedInterpolatable(
            yFunc, DensityFunctions.noise(na.noises.getOrThrow(Noises.NOODLE), 1.0, 1.0), -60, 320, -1
        );
        DensityFunction thickness = yLimitedInterpolatable(
            yFunc, DensityFunctions.mappedNoise(na.noises.getOrThrow(Noises.NOODLE_THICKNESS), 1.0, 1.0, -0.05, -0.1), -60, 320, 0
        );
        DensityFunction ridgeA = yLimitedInterpolatable(
            yFunc, DensityFunctions.noise(na.noises.getOrThrow(Noises.NOODLE_RIDGE_A), 2.6666666666666665, 2.6666666666666665), -60, 320, 0
        );
        DensityFunction ridgeB = yLimitedInterpolatable(
            yFunc, DensityFunctions.noise(na.noises.getOrThrow(Noises.NOODLE_RIDGE_B), 2.6666666666666665, 2.6666666666666665), -60, 320, 0
        );
        DensityFunction ridgeCombined = DensityFunctions.mul(
            DensityFunctions.constant(1.5), DensityFunctions.max(ridgeA.abs(), ridgeB.abs())
        );
        return na.wireNoise(DensityFunctions.rangeChoice(
            noodleNoise, -1000000.0, 0.0, DensityFunctions.constant(64.0), DensityFunctions.add(thickness, ridgeCombined)
        ));
    }
    
    /**
     * Matches NoiseRouterData.underground().
     */
    private static DensityFunction rebuildUnderground(
        DensityFunction slopedCheese,
        DensityFunction entrances,
        DensityFunction spaghetti2d,
        DensityFunction spaghettiRoughness,
        DensityFunction pillars,
        NoiseAccess na
    ) {
        DensityFunction caveLayer = na.wireNoise(DensityFunctions.noise(na.noises.getOrThrow(Noises.CAVE_LAYER), 8.0));
        DensityFunction caveCheese = na.wireNoise(DensityFunctions.noise(na.noises.getOrThrow(Noises.CAVE_CHEESE), 0.6666666666666666));
        
        DensityFunction layerSquared = DensityFunctions.mul(DensityFunctions.constant(4.0), caveLayer.square());
        DensityFunction cheeseNoise = DensityFunctions.add(
            DensityFunctions.add(DensityFunctions.constant(0.27), caveCheese).clamp(-1.0, 1.0),
            DensityFunctions.add(
                DensityFunctions.constant(1.5),
                DensityFunctions.mul(DensityFunctions.constant(-0.64), slopedCheese)
            ).clamp(0.0, 0.5)
        );
        DensityFunction cheeseCaves = DensityFunctions.add(layerSquared, cheeseNoise);
        
        DensityFunction allCaves = DensityFunctions.min(
            DensityFunctions.min(cheeseCaves, entrances),
            DensityFunctions.add(spaghetti2d, spaghettiRoughness)
        );
        
        DensityFunction pillarsRanged = DensityFunctions.rangeChoice(
            pillars, -1000000.0, 0.03, DensityFunctions.constant(-1000000.0), pillars
        );
        
        return DensityFunctions.max(allCaves, pillarsRanged);
    }
    
    /**
     * Matches NoiseRouterData.yLimitedInterpolatable().
     */
    private static DensityFunction yLimitedInterpolatable(
        DensityFunction input, DensityFunction whenInRange, int minY, int maxY, int whenOutOfRange
    ) {
        return DensityFunctions.interpolated(
            DensityFunctions.rangeChoice(input, minY, maxY + 1, whenInRange, DensityFunctions.constant(whenOutOfRange))
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
            FirmaClimateFunction temperature = createTemperatureFunction(factory, mode);
            FirmaClimateFunction humidity = createHumidityFunction(factory, mode);
            FirmaClimateFunction continents = createContinentsFunction(factory, mode);
            FirmaClimateFunction erosion = createErosionFunction(factory, mode);
            FirmaClimateFunction weirdness = createWeirdnessFunction(factory, mode);
            
            return new NoiseRouter(
                new FirmaClimateFunction.Identity(vanillaRouter.barrierNoise()),
                new FirmaClimateFunction.Identity(vanillaRouter.fluidLevelFloodednessNoise()),
                new FirmaClimateFunction.Identity(vanillaRouter.fluidLevelSpreadNoise()),
                new FirmaClimateFunction.Identity(vanillaRouter.lavaNoise()),
                temperature,
                humidity,
                continents,
                erosion,
                new FirmaClimateFunction.Identity(vanillaRouter.depth()),
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
