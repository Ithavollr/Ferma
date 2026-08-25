package org.evlis.firma.noise;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.evlis.firma.pack.ClimateFunctionFactory;
import org.evlis.firma.pack.FermaPack;

import java.util.ArrayList;
import java.util.List;

/**
 * Patches a wired vanilla {@link NoiseRouter} with a pack's climate functions via in-place
 * graph surgery.
 *
 * <p>Climate router fields are replaced directly. Terrain graphs ({@code depth},
 * {@code initialDensityWithoutJaggedness}, {@code finalDensity}) are rewritten with
 * {@code mapAll}: every node structurally equal to a configured parameter's canonical
 * climate node (the inner node of its wired router field) is replaced by the pack's
 * function. Marker wrappers (flat_cache/cache_2d/interpolated) are preserved because
 * replacement happens at the inner-node level, so NoiseChunk caching is unaffected.
 *
 * <p>The wiring dedup that makes equals-matching sound is asserted by
 * {@code GraphSurgeryDiagnostic} before this runs; do not call this on a router that has
 * not passed the assertion gate.
 */
public class FermaNoiseRouter {

    /**
     * Create a patched NoiseRouter using a pack's climate configuration.
     *
     * @param rewriteTerrain true for coupled settings (overworld family), where terrain
     *                       splines consume the climate fields and must be rewritten.
     *                       MUST be false for decoupled settings: their climate fields
     *                       are constants, and an equals-matching terrain rewrite would
     *                       replace every unrelated constant of the same value.
     */
    public static NoiseRouter patchClimateFunctions(NoiseRouter wiredRouter, long seed, FermaPack pack, boolean rewriteTerrain) {
        ClimateFunctionFactory factory = new ClimateFunctionFactory(seed, pack.id());

        DensityFunction temperature = pack.hasClimateConfig("temperature")
            ? factory.build(pack.getClimateConfig("temperature"), "temperature", wiredRouter.temperature())
            : wiredRouter.temperature();
        DensityFunction humidity = pack.hasClimateConfig("humidity")
            ? factory.build(pack.getClimateConfig("humidity"), "humidity", wiredRouter.vegetation())
            : wiredRouter.vegetation();
        DensityFunction continents = pack.hasClimateConfig("continentalness")
            ? factory.build(pack.getClimateConfig("continentalness"), "continentalness", wiredRouter.continents())
            : wiredRouter.continents();
        DensityFunction erosion = pack.hasClimateConfig("erosion")
            ? factory.build(pack.getClimateConfig("erosion"), "erosion", wiredRouter.erosion())
            : wiredRouter.erosion();
        DensityFunction weirdness = pack.hasClimateConfig("weirdness")
            ? factory.build(pack.getClimateConfig("weirdness"), "weirdness", wiredRouter.ridges())
            : wiredRouter.ridges();

        // Composite-noise policy: on coupled settings, depth is add(y_gradient, offset
        // splines) — derived from the other climate functions and from terrain shape.
        // Ferma never replaces composite noises, so a pack configuring depth there is
        // refused. Checked here, at the point of danger, so the function cannot be called
        // incorrectly regardless of caller.
        if (pack.hasClimateConfig("depth") && rewriteTerrain) {
            throw new IllegalStateException(
                "Pack '" + pack.id() + "' configures 'depth', which is not permitted on these noise"
                + " settings: depth there is a composite of the offset splines (derived from"
                + " continentalness/erosion/weirdness), and Ferma never replaces composite noises."
                + " Configure depth only on caves, floating_islands, or nether settings.");
        }
        DensityFunction packDepth = pack.hasClimateConfig("depth")
            ? factory.build(pack.getClimateConfig("depth"), "depth", wiredRouter.depth())
            : null;

        // Terrain surgery: replace canonical climate nodes for the configured
        // terrain-coupled parameters inside the wired terrain graphs.
        List<DensityFunction> canonical = new ArrayList<>(3);
        List<DensityFunction> replacement = new ArrayList<>(3);
        if (rewriteTerrain && pack.hasClimateConfig("continentalness")) {
            canonical.add(unwrapCanonical(wiredRouter.continents()));
            replacement.add(continents);
        }
        if (rewriteTerrain && pack.hasClimateConfig("erosion")) {
            canonical.add(unwrapCanonical(wiredRouter.erosion()));
            replacement.add(erosion);
        }
        if (rewriteTerrain && pack.hasClimateConfig("weirdness")) {
            canonical.add(unwrapCanonical(wiredRouter.ridges()));
            replacement.add(weirdness);
        }

        // packDepth and rewriteTerrain are mutually exclusive (guarded above), so a
        // pack-configured depth is never also a terrain-rewrite target.
        DensityFunction depth = packDepth != null ? packDepth : wiredRouter.depth();
        DensityFunction initialDensity = wiredRouter.initialDensityWithoutJaggedness();
        DensityFunction finalDensity = wiredRouter.finalDensity();
        if (!canonical.isEmpty()) {
            DensityFunction.Visitor replacer = new DensityFunction.Visitor() {
                @Override
                public DensityFunction apply(DensityFunction df) {
                    for (int i = 0; i < canonical.size(); i++) {
                        if (df.equals(canonical.get(i))) {
                            return replacement.get(i);
                        }
                    }
                    return df;
                }
            };
            depth = depth.mapAll(replacer);
            initialDensity = initialDensity.mapAll(replacer);
            finalDensity = finalDensity.mapAll(replacer);
        }

        // Non-climate fields pass through the wired vanilla functions unchanged.
        return new NoiseRouter(
            wiredRouter.barrierNoise(),
            wiredRouter.fluidLevelFloodednessNoise(),
            wiredRouter.fluidLevelSpreadNoise(),
            wiredRouter.lavaNoise(),
            temperature,
            humidity,
            continents,
            erosion,
            depth,
            weirdness,
            initialDensity,
            finalDensity,
            wiredRouter.veinToggle(),
            wiredRouter.veinRidged(),
            wiredRouter.veinGap()
        );
    }

    /**
     * Unwrap a wired climate router field to its canonical inner node:
     * {@code HolderHolder -> MarkerOrMarked -> inner}. This is the node the wiring cache
     * deduplicated into the terrain spline coordinates, and the level at which
     * replacement preserves Marker wrappers.
     */
    public static DensityFunction unwrapCanonical(DensityFunction field) {
        DensityFunction node = field;
        if (node instanceof DensityFunctions.HolderHolder holderHolder) {
            node = holderHolder.function().value();
        }
        if (node instanceof DensityFunctions.MarkerOrMarked marker) {
            node = marker.wrapped();
        }
        return node;
    }
}
