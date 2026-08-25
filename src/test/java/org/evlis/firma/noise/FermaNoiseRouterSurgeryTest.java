package org.evlis.firma.noise;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.evlis.firma.NMS.GraphSurgeryDiagnostic;
import org.evlis.firma.pack.ClimateFunctionConfig;
import org.evlis.firma.pack.FermaPack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mechanics tests for the graph surgery on a synthetic wired-style router: canonical
 * unwrapping, equals-based replacement inside terrain graphs, Marker preservation, and
 * the assertion gate's pass/fail behavior. The wiring-dedup premise itself (that the
 * canonical node is shared into real terrain graphs) is asserted live by the
 * primary/backstop gates; these tests cover the machinery around it.
 */
class FermaNoiseRouterSurgeryTest {

    private static final Logger LOG = Logger.getLogger("FermaNoiseRouterSurgeryTest");

    // Distinctive canonical stand-ins for the three coupled climate functions.
    // NOT DensityFunctions.constant(...): vanilla's add()/mul() absorb Constant arguments
    // into a MulOrAdd scalar, deleting the node from the graph. A yClampedGradient with
    // equal endpoints computes the same fixed value but survives as a structural node.
    // Assigned in @BeforeAll, never in static initializers: touching DensityFunctions at
    // class-load time runs before Bootstrap.bootStrap() and poisons the class (and with
    // it every NMS-touching test in the shared JVM).
    private static DensityFunction CONTINENTS_INNER;
    private static DensityFunction EROSION_INNER;
    private static DensityFunction RIDGES_INNER;

    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CONTINENTS_INNER = DensityFunctions.yClampedGradient(-64, 320, 0.111213, 0.111213);
        EROSION_INNER = DensityFunctions.yClampedGradient(-64, 320, 0.212223, 0.212223);
        RIDGES_INNER = DensityFunctions.yClampedGradient(-64, 320, 0.313233, 0.313233);
    }

    /** Router field shape: HolderHolder(flat_cache(inner)) — as vanilla wires references. */
    private static DensityFunction routerField(DensityFunction inner) {
        return new DensityFunctions.HolderHolder(Holder.direct(DensityFunctions.flatCache(inner)));
    }

    /**
     * Synthetic router whose terrain fields embed the canonical nodes inside Markers,
     * mirroring the coupled-settings structure.
     */
    private static NoiseRouter syntheticCoupledRouter() {
        DensityFunction zero = DensityFunctions.zero();
        DensityFunction depth = DensityFunctions.add(
            DensityFunctions.flatCache(CONTINENTS_INNER), DensityFunctions.constant(1.0));
        DensityFunction initial = DensityFunctions.add(
            DensityFunctions.flatCache(EROSION_INNER), DensityFunctions.flatCache(RIDGES_INNER));
        DensityFunction finalDensity = DensityFunctions.add(
            DensityFunctions.add(
                DensityFunctions.flatCache(CONTINENTS_INNER), DensityFunctions.flatCache(EROSION_INNER)),
            DensityFunctions.flatCache(RIDGES_INNER));
        return new NoiseRouter(
            zero, zero, zero, zero,
            zero,                            // temperature
            zero,                            // vegetation
            routerField(CONTINENTS_INNER),   // continents
            routerField(EROSION_INNER),      // erosion
            depth,
            routerField(RIDGES_INNER),       // ridges
            initial,
            finalDensity,
            zero, zero, zero
        );
    }

    private static double compute(DensityFunction df) {
        return df.compute(new DensityFunction.SinglePointContext(0, 0, 0));
    }

    @Test
    void unwrapCanonical_peelsHolderAndMarker() {
        assertSame(CONTINENTS_INNER, FermaNoiseRouter.unwrapCanonical(routerField(CONTINENTS_INNER)));
        // A bare field (no HolderHolder, no Marker) unwraps to itself.
        assertSame(CONTINENTS_INNER, FermaNoiseRouter.unwrapCanonical(CONTINENTS_INNER));
    }

    @Test
    void surgery_replacesCanonicalNodesInsideMarkers() {
        FermaPack pack = new FermaPack(1, "surgery_test", "t", "", "noise",
            Map.of("continentalness", new ClimateFunctionConfig.Constant(0.5)), null);

        NoiseRouter patched = FermaNoiseRouter.patchClimateFunctions(syntheticCoupledRouter(), 42L, pack, true);

        // Router field replaced directly with the pack function.
        assertEquals(0.5, compute(patched.continents()), 0.0);
        // Terrain rewritten: depth = flat_cache(pack fn) + 1 -> 1.5 (was 1.111213).
        assertEquals(1.5, compute(patched.depth()), 1e-9);
        // Unconfigured params untouched: initial = erosion + ridges inners, unchanged.
        assertEquals(0.212223 + 0.313233, compute(patched.initialDensityWithoutJaggedness()), 1e-9);
        // finalDensity: only the continents occurrence replaced.
        assertEquals(0.5 + 0.212223 + 0.313233, compute(patched.finalDensity()), 1e-9);
    }

    @Test
    void surgery_withoutCoupledParams_keepsWiredTerrainInstances() {
        FermaPack pack = new FermaPack(1, "temp_only", "t", "", "noise",
            Map.of("temperature", new ClimateFunctionConfig.Constant(-1.0)), null);

        NoiseRouter wired = syntheticCoupledRouter();
        NoiseRouter patched = FermaNoiseRouter.patchClimateFunctions(wired, 42L, pack, true);

        assertEquals(-1.0, compute(patched.temperature()), 0.0);
        // Terrain fields are the identical wired objects - no rewrite ran.
        assertSame(wired.depth(), patched.depth());
        assertSame(wired.finalDensity(), patched.finalDensity());
    }

    @Test
    void assertionGate_passesOnCoupledStructure() {
        assertDoesNotThrow(() ->
            GraphSurgeryDiagnostic.assertCoupledBaseline(syntheticCoupledRouter(), "synthetic", LOG));
    }

    @Test
    void assertionGate_failsWhenCanonicalMissingFromTerrain() {
        // Terrain graphs reference something else entirely - the Tectonic situation.
        DensityFunction zero = DensityFunctions.zero();
        DensityFunction foreign = DensityFunctions.flatCache(DensityFunctions.constant(0.999));
        NoiseRouter broken = new NoiseRouter(
            zero, zero, zero, zero,
            zero, zero,
            routerField(CONTINENTS_INNER),
            routerField(EROSION_INNER),
            foreign,
            routerField(RIDGES_INNER),
            foreign,
            foreign,
            zero, zero, zero
        );
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
            GraphSurgeryDiagnostic.assertCoupledBaseline(broken, "synthetic-broken", LOG));
        assertTrue(e.getMessage().contains("continents"), "error should name the failing parameter");
    }

    /**
     * Synthetic decoupled router (nether/caves shape): constant climate fields, real
     * temp/veg noises, terrain built from y-gradients and constants — including a
     * constant EQUAL to the climate fields' 0.0, which the surgery must never touch.
     */
    private static NoiseRouter syntheticDecoupledRouter() {
        DensityFunction zero = DensityFunctions.zero();
        // max(), not add(): ADD/MUL absorb a Constant argument into a MulOrAdd scalar,
        // deleting the node. max() keeps a real constant(0.0) node in the terrain graph —
        // equal to the climate fields' constants, and exactly what a terrain rewrite would
        // wrongly match here.
        DensityFunction terrain = DensityFunctions.max(
            DensityFunctions.yClampedGradient(0, 128, -1.0, 1.0),
            DensityFunctions.constant(0.0)
        );
        return new NoiseRouter(
            zero, zero, zero, zero,
            CONTINENTS_INNER,                 // temperature (bare noise stand-in, no wrapper)
            EROSION_INNER,                    // vegetation
            DensityFunctions.constant(0.0),   // continents
            DensityFunctions.constant(0.0),   // erosion
            DensityFunctions.constant(0.0),   // depth
            DensityFunctions.constant(0.0),   // ridges
            terrain,
            terrain,
            zero, zero, zero
        );
    }

    @Test
    void decoupledBaseline_passesOnDecoupledStructure() {
        assertDoesNotThrow(() ->
            GraphSurgeryDiagnostic.assertDecoupledBaseline(syntheticDecoupledRouter(), "synthetic-decoupled", LOG));
    }

    @Test
    void decoupledBaseline_failsOnNonConstantClimateField() {
        DensityFunction zero = DensityFunctions.zero();
        NoiseRouter broken = new NoiseRouter(
            zero, zero, zero, zero,
            CONTINENTS_INNER, EROSION_INNER,
            DensityFunctions.yClampedGradient(0, 64, -1.0, 1.0), // continents: not constant-valued
            DensityFunctions.constant(0.0),
            DensityFunctions.constant(0.0),
            DensityFunctions.constant(0.0),
            zero, zero,
            zero, zero, zero
        );
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
            GraphSurgeryDiagnostic.assertDecoupledBaseline(broken, "synthetic-decoupled-broken", LOG));
        assertTrue(e.getMessage().contains("continents"));
    }

    @Test
    void decoupledBaseline_failsWhenClimateNoiseReachesTerrain() {
        DensityFunction zero = DensityFunctions.zero();
        DensityFunction terrainUsingTemperature = DensityFunctions.add(
            CONTINENTS_INNER, DensityFunctions.yClampedGradient(0, 128, -1.0, 1.0));
        NoiseRouter broken = new NoiseRouter(
            zero, zero, zero, zero,
            CONTINENTS_INNER,                 // temperature — also appears in terrain below
            EROSION_INNER,
            DensityFunctions.constant(0.0),
            DensityFunctions.constant(0.0),
            DensityFunctions.constant(0.0),
            DensityFunctions.constant(0.0),
            terrainUsingTemperature,
            terrainUsingTemperature,
            zero, zero, zero
        );
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
            GraphSurgeryDiagnostic.assertDecoupledBaseline(broken, "synthetic-decoupled-coupledtemp", LOG));
        assertTrue(e.getMessage().contains("temperature"));
    }

    @Test
    void decoupledSurgery_replacesFieldsOnly_terrainUntouched() {
        FermaPack pack = new FermaPack(1, "decoupled_test", "t", "", "noise",
            Map.of("temperature", new ClimateFunctionConfig.Constant(-1.0),
                   "continentalness", new ClimateFunctionConfig.Constant(0.5)), null);

        NoiseRouter wired = syntheticDecoupledRouter();
        NoiseRouter patched = FermaNoiseRouter.patchClimateFunctions(wired, 42L, pack, false);

        assertEquals(-1.0, compute(patched.temperature()), 0.0);
        assertEquals(0.5, compute(patched.continents()), 0.0);
        // Terrain graphs are the identical wired objects — the constant(0.0) inside them
        // (equal to the climate constants) was never a replacement target.
        assertSame(wired.depth(), patched.depth());
        assertSame(wired.initialDensityWithoutJaggedness(), patched.initialDensityWithoutJaggedness());
        assertSame(wired.finalDensity(), patched.finalDensity());
    }

    @Test
    void depthConfigured_onDecoupled_replacesDepthField() {
        FermaPack pack = new FermaPack(1, "depth_test", "t", "", "noise",
            Map.of("depth", new ClimateFunctionConfig.YGradient(0, 128, 1.0, -1.0)), null);

        NoiseRouter wired = syntheticDecoupledRouter();
        NoiseRouter patched = FermaNoiseRouter.patchClimateFunctions(wired, 42L, pack, false);

        // Depth now varies with height instead of being the wired constant 0.0.
        assertEquals(1.0, patched.depth().compute(new DensityFunction.SinglePointContext(0, 0, 0)), 1e-9);
        assertEquals(-1.0, patched.depth().compute(new DensityFunction.SinglePointContext(0, 128, 0)), 1e-9);
        // Terrain is still untouched on decoupled settings.
        assertSame(wired.finalDensity(), patched.finalDensity());
    }

    @Test
    void depthConfigured_onCoupled_isRefusedByCompositePolicy() {
        FermaPack pack = new FermaPack(1, "depth_coupled", "t", "", "noise",
            Map.of("depth", new ClimateFunctionConfig.YGradient(0, 128, 1.0, -1.0)), null);

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
            FermaNoiseRouter.patchClimateFunctions(syntheticCoupledRouter(), 42L, pack, true));
        assertTrue(e.getMessage().contains("composite"),
            "refusal should cite the composite-noise policy, was: " + e.getMessage());
    }

    @Test
    void classify_mapsVanillaKeysToClasses() {
        assertEquals(GraphSurgeryDiagnostic.SettingsClass.COUPLED,
            GraphSurgeryDiagnostic.classify(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD));
        assertEquals(GraphSurgeryDiagnostic.SettingsClass.COUPLED,
            GraphSurgeryDiagnostic.classify(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.LARGE_BIOMES));
        assertEquals(GraphSurgeryDiagnostic.SettingsClass.DECOUPLED,
            GraphSurgeryDiagnostic.classify(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.NETHER));
        assertEquals(GraphSurgeryDiagnostic.SettingsClass.DECOUPLED,
            GraphSurgeryDiagnostic.classify(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.CAVES));
        assertEquals(GraphSurgeryDiagnostic.SettingsClass.END,
            GraphSurgeryDiagnostic.classify(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.END));
        assertEquals(GraphSurgeryDiagnostic.SettingsClass.UNKNOWN,
            GraphSurgeryDiagnostic.classify(null));
    }

    @Test
    void assertionGate_failsOnUnwrappedOccurrence() {
        // Canonical node present in terrain but NOT marker-wrapped: Assertion B must trip.
        DensityFunction zero = DensityFunctions.zero();
        DensityFunction bareUse = DensityFunctions.add(CONTINENTS_INNER, DensityFunctions.constant(1.0));
        DensityFunction wrappedErosion = DensityFunctions.flatCache(EROSION_INNER);
        DensityFunction wrappedRidges = DensityFunctions.flatCache(RIDGES_INNER);
        NoiseRouter broken = new NoiseRouter(
            zero, zero, zero, zero,
            zero, zero,
            routerField(CONTINENTS_INNER),
            routerField(EROSION_INNER),
            bareUse,
            routerField(RIDGES_INNER),
            DensityFunctions.add(wrappedErosion, wrappedRidges),
            DensityFunctions.add(wrappedErosion, wrappedRidges),
            zero, zero, zero
        );
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
            GraphSurgeryDiagnostic.assertCoupledBaseline(broken, "synthetic-unwrapped", LOG));
        assertTrue(e.getMessage().contains("Marker"), "error should name the marker violation");
    }
}
