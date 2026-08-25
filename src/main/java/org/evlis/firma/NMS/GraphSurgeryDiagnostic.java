package org.evlis.firma.NMS;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.evlis.firma.noise.FermaNoiseRouter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The assertion gate for Ferma's noise-graph surgery. Every world's settings fall into a
 * {@link SettingsClass} with its own structural baseline, verified against a WIRED router.
 *
 * <p><b>Coupled baseline</b> (overworld, amplified, large_biomes) — terrain splines consume
 * the climate fields, so the surgery rewrites terrain and must verify:
 * <ul>
 *   <li><b>Assertion A</b>: the canonical wired climate node (the inner node of each of the
 *       continents/erosion/ridges router fields) is found, by structural equality, embedded
 *       in the wired terrain graphs.</li>
 *   <li><b>Assertion B</b>: every such occurrence sits directly inside a Marker wrapper
 *       (flat_cache/cache_2d), so inner-node replacement preserves NoiseChunk caching.</li>
 * </ul>
 *
 * <p><b>Decoupled baseline</b> (caves, floating_islands, nether) — the opposite premise:
 * continents/erosion/ridges/depth are constant-valued and the terrain graphs contain no
 * climate noise. The surgery replaces router fields only; a terrain rewrite is never run
 * there, because equals-matching a constant would replace every unrelated constant of the
 * same value.
 *
 * <p>Two call sites:
 * <ul>
 *   <li>{@link #validateVanillaStructure(Logger)} — the primary gate, run before a pack
 *       world's generator is handed to Bukkit. Graph structure is seed-independent and
 *       datapacks are server-global, so every supported vanilla settings graph is validated
 *       once against throwaway {@code RandomState}s built from the registry. Throws on
 *       failure; the exception propagates out of {@code WorldCreator.createWorld()},
 *       which Bukkit and Multiverse handle as a clean creation failure.</li>
 *   <li>{@link #assertCoupledBaseline} / {@link #assertDecoupledBaseline} — the backstop,
 *       run against the world's real wired router at WorldInitEvent before patching, using
 *       the baseline its own settings key selects.</li>
 * </ul>
 *
 * <p>Read-only: traverses via {@code mapAll} (which builds discarded copies) and mutates
 * nothing.
 */
public final class GraphSurgeryDiagnostic {

    private static volatile boolean vanillaStructureValidated = false;

    /**
     * Structural class of a world's noise settings, selecting the assertion baseline and
     * the surgery mode.
     */
    public enum SettingsClass {
        /** Terrain splines consume the climate fields; surgery rewrites terrain. */
        COUPLED,
        /** Climate fields are constants; terrain is climate-free; surgery replaces router fields only. */
        DECOUPLED,
        /** minecraft:end — not yet supported for Ferma packs (Phase 4). */
        END,
        /** Not a vanilla settings key (or no key at all) — refused. */
        UNKNOWN;
    }

    public static SettingsClass classify(ResourceKey<NoiseGeneratorSettings> key) {
        if (key == null) {
            return SettingsClass.UNKNOWN;
        }
        if (coupledSettings().contains(key)) {
            return SettingsClass.COUPLED;
        }
        if (decoupledSettings().contains(key)) {
            return SettingsClass.DECOUPLED;
        }
        if (NoiseGeneratorSettings.END.equals(key)) {
            return SettingsClass.END;
        }
        return SettingsClass.UNKNOWN;
    }

    /*
     * Settings lists are built per-call, never in static initializers:
     * NoiseGeneratorSettings' class init reaches BuiltInRegistries via its codecs, and
     * any static NMS reference here would make this class unloadable before
     * Bootstrap.bootStrap() (a test-JVM poison; the live server is always bootstrapped
     * before plugin classes load).
     */
    private static List<ResourceKey<NoiseGeneratorSettings>> coupledSettings() {
        return List.of(
            NoiseGeneratorSettings.OVERWORLD,
            NoiseGeneratorSettings.AMPLIFIED,
            NoiseGeneratorSettings.LARGE_BIOMES
        );
    }

    private static List<ResourceKey<NoiseGeneratorSettings>> decoupledSettings() {
        return List.of(
            NoiseGeneratorSettings.CAVES,
            NoiseGeneratorSettings.FLOATING_ISLANDS,
            NoiseGeneratorSettings.NETHER
        );
    }

    private GraphSurgeryDiagnostic() {}

    /**
     * Primary gate: validate every supported vanilla settings graph from the registry,
     * each against its own baseline. Idempotent; the first successful validation is cached
     * for the server's lifetime. The cache is safe even if that assumption were ever
     * broken upstream, because the backstop re-asserts on each world's own wired router.
     *
     * @throws IllegalStateException naming the settings, parameter, and divergence.
     */
    public static void validateVanillaStructure(Logger logger) {
        if (vanillaStructureValidated) {
            return;
        }
        RegistryAccess registries = MinecraftServer.getServer().registryAccess();
        for (ResourceKey<NoiseGeneratorSettings> key : coupledSettings()) {
            RandomState throwaway = RandomState.create(registries, key, 0L);
            assertCoupledBaseline(throwaway.router(), "settings '" + key.location() + "'", logger);
        }
        for (ResourceKey<NoiseGeneratorSettings> key : decoupledSettings()) {
            RandomState throwaway = RandomState.create(registries, key, 0L);
            assertDecoupledBaseline(throwaway.router(), "settings '" + key.location() + "'", logger);
        }
        // minecraft:end is not validated: end worlds are refused for Ferma packs until
        // the end baseline lands (Phase 4).
        vanillaStructureValidated = true;
    }

    /**
     * Baseline for decoupled settings (caves, floating_islands, nether): the
     * continents/erosion/ridges/depth router fields are constant-valued, and the terrain
     * graphs contain no occurrence of the temperature/vegetation climate noise. Under
     * this baseline the surgery replaces router fields only — a terrain rewrite would
     * equals-match unrelated constants and is never run.
     *
     * @throws IllegalStateException naming the context, field, and divergence.
     */
    public static void assertDecoupledBaseline(NoiseRouter wiredRouter, String context, Logger logger) {
        Map<String, DensityFunction> constantFields = new LinkedHashMap<>();
        constantFields.put("continents", wiredRouter.continents());
        constantFields.put("erosion", wiredRouter.erosion());
        constantFields.put("ridges", wiredRouter.ridges());
        constantFields.put("depth", wiredRouter.depth());

        for (Map.Entry<String, DensityFunction> field : constantFields.entrySet()) {
            DensityFunction df = field.getValue();
            if (df.minValue() != df.maxValue()) {
                throw new IllegalStateException(
                    "Ferma noise-graph assertion FAILED for " + context + ": decoupled baseline expects a"
                    + " constant-valued '" + field.getKey() + "' router field, found range ["
                    + df.minValue() + ", " + df.maxValue() + "]. The noise graph has been altered by an"
                    + " external source (worldgen datapack or mod), which is incompatible with Ferma."
                    + " World creation aborted.");
            }
        }

        Map<String, DensityFunction> terrainFields = new LinkedHashMap<>();
        terrainFields.put("depth", wiredRouter.depth());
        terrainFields.put("initialDensityWithoutJaggedness", wiredRouter.initialDensityWithoutJaggedness());
        terrainFields.put("finalDensity", wiredRouter.finalDensity());

        Map<String, DensityFunction> climateNoises = new LinkedHashMap<>();
        climateNoises.put("temperature", FermaNoiseRouter.unwrapCanonical(wiredRouter.temperature()));
        climateNoises.put("vegetation", FermaNoiseRouter.unwrapCanonical(wiredRouter.vegetation()));

        for (Map.Entry<String, DensityFunction> climate : climateNoises.entrySet()) {
            int total = 0;
            for (DensityFunction terrain : terrainFields.values()) {
                total += countOccurrences(terrain, climate.getValue())[0];
            }
            if (total != 0) {
                throw new IllegalStateException(
                    "Ferma noise-graph assertion FAILED for " + context + ": decoupled baseline expects"
                    + " climate-free terrain, but the '" + climate.getKey() + "' climate noise occurs "
                    + total + " time(s) in the terrain graphs. The noise graph has been altered by an"
                    + " external source, which is incompatible with Ferma. World creation aborted.");
            }
        }
        logger.info("[GraphSurgery] " + context + ": decoupled baseline PASS"
            + " (constant climate fields, climate-free terrain)");
    }

    /**
     * Assert both structural assumptions against a wired router. Logs the evidence,
     * then throws on any failure.
     *
     * @throws IllegalStateException naming the context, parameter, and divergence.
     */
    public static void assertCoupledBaseline(NoiseRouter wiredRouter, String context, Logger logger) {
        Map<String, DensityFunction> climateFields = new LinkedHashMap<>();
        climateFields.put("continents", wiredRouter.continents());
        climateFields.put("erosion", wiredRouter.erosion());
        climateFields.put("ridges", wiredRouter.ridges());

        Map<String, DensityFunction> terrainFields = new LinkedHashMap<>();
        terrainFields.put("depth", wiredRouter.depth());
        terrainFields.put("initialDensityWithoutJaggedness", wiredRouter.initialDensityWithoutJaggedness());
        terrainFields.put("finalDensity", wiredRouter.finalDensity());

        for (Map.Entry<String, DensityFunction> climate : climateFields.entrySet()) {
            String param = climate.getKey();
            DensityFunction inner = FermaNoiseRouter.unwrapCanonical(climate.getValue());

            int totalMatches = 0;
            int totalMarkerWrapped = 0;
            StringBuilder perField = new StringBuilder();
            for (Map.Entry<String, DensityFunction> terrain : terrainFields.entrySet()) {
                int[] counts = countOccurrences(terrain.getValue(), inner);
                totalMatches += counts[0];
                totalMarkerWrapped += counts[1];
                perField.append(terrain.getKey()).append("=").append(counts[0])
                        .append("(marker-wrapped ").append(counts[1]).append(") ");
            }

            logger.info("[GraphSurgery] " + context + " " + param + ": occurrences " + perField.toString().trim());

            if (totalMatches == 0) {
                throw new IllegalStateException(
                    "Ferma noise-graph assertion FAILED for " + context + ": the canonical '" + param
                    + "' climate node was not found in the terrain graphs. The noise graph has been"
                    + " altered by an external source (worldgen datapack or mod), which is incompatible"
                    + " with Ferma. World creation aborted.");
            }
            if (totalMatches != totalMarkerWrapped) {
                throw new IllegalStateException(
                    "Ferma noise-graph assertion FAILED for " + context + ": " + (totalMatches - totalMarkerWrapped)
                    + " occurrence(s) of the '" + param + "' climate node are not Marker-wrapped."
                    + " Replacement would degrade chunk generation caching. The noise graph has been"
                    + " altered by an external source, which is incompatible with Ferma. World creation aborted.");
            }
        }
        logger.info("[GraphSurgery] " + context + ": Assertions A and B PASS");
    }

    /**
     * Traverses {@code root} and returns {matches, markerWrappedMatches} for nodes
     * structurally equal to {@code canonical}. mapAll rebuilds copies bottom-up, so the
     * visitor compares by equals (rebuilt copies of the deduplicated canonical node are
     * structurally equal to it); the rebuilt graph is discarded.
     */
    private static int[] countOccurrences(DensityFunction root, DensityFunction canonical) {
        int[] counts = new int[2];
        root.mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction df) {
                if (df.equals(canonical)) {
                    counts[0]++;
                }
                if (df instanceof DensityFunctions.MarkerOrMarked marker && marker.wrapped().equals(canonical)) {
                    counts[1]++;
                }
                return df;
            }
        });
        return counts;
    }
}
