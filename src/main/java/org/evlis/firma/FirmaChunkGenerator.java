package org.evlis.firma;

import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;

/**
 * Bukkit ChunkGenerator wrapper for Firma.
 * This class serves as a marker to identify worlds using our NMS injection
 * and carries the generation mode configuration per-world.
 *
 * For Stage 1 (VANILLA): Does not override generateNoise - let Paper/CustomChunkGenerator fall through.
 * For Stage 3 (VOID): Will override generateNoise with empty implementation.
 */
public class FirmaChunkGenerator extends ChunkGenerator {

    /**
     * Generation modes for Firma worlds.
     */
    public enum GenerationMode {
        /** Faithful vanilla pass-through (Stage 1) */
        VANILLA,
        /** Noise parameter override mode (Stage 2) */
        NOISE_OVERRIDE,
        /** Void/empty chunk mode (Stage 3) */
        VOID
    }

    private final Firma plugin;
    private final GenerationMode mode;

    /**
     * Creates a Firma chunk generator with the specified mode.
     *
     * @param plugin The plugin instance
     * @param modeId The generation mode string ("vanilla", "noise", "void"), or null for default
     */
    public FirmaChunkGenerator(Firma plugin, @Nullable String modeId) {
        this.plugin = plugin;
        this.mode = parseMode(modeId);
        plugin.getLogger().info("Created Firma generator with mode: " + mode);
    }

    /**
     * Parse the mode string into a GenerationMode enum.
     * Defaults to VANILLA if null or unrecognized.
     */
    private GenerationMode parseMode(@Nullable String modeId) {
        if (modeId == null || modeId.isEmpty()) {
            return GenerationMode.VANILLA;
        }
        return switch (modeId.toLowerCase()) {
            case "vanilla" -> GenerationMode.VANILLA;
            case "noise", "noise_override" -> GenerationMode.NOISE_OVERRIDE;
            case "void" -> GenerationMode.VOID;
            default -> {
                plugin.getLogger().warning("Unknown generation mode '" + modeId + "', defaulting to VANILLA");
                yield GenerationMode.VANILLA;
            }
        };
    }

    /**
     * Get the generation mode for this generator.
     */
    public GenerationMode getMode() {
        return mode;
    }

    /**
     * Generate noise for the chunk.
     *
     * Stage 1 (VANILLA): Not overridden - falls through to Paper's CustomChunkGenerator,
     * which delegates to the real vanilla NoiseBasedChunkGenerator (after NMS injection).
     *
     * Stage 3 (VOID): Will be overridden to produce empty chunks.
     */
    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkData chunkData) {
        if (mode == GenerationMode.VOID) {
            // Stage 3: Void mode - leave chunk empty
            // This is the intended behavior for void worlds
            return;
        }
        // Stage 1 & 2: Should not reach here - NMS injection handles generation
        // If it does, log a warning but don't break (could be race condition or unexpected state)
        plugin.getLogger().warning("generateNoise called on FirmaChunkGenerator with mode " + mode +
                                   " at (" + x + ", " + z + ") - this may indicate an injection issue");
    }

    /**
     * Return null to use vanilla biome provider.
     */
    @Override
    public @Nullable BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return null; // Use vanilla biome provider
    }

    /**
     * Return empty list - vanilla will handle populators.
     */
    @Override
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of(); // Vanilla handles all populators
    }

    /**
     * Let vanilla handle caves.
     */
    @Override
    public boolean shouldGenerateCaves() {
        return true; // Delegate to vanilla
    }

    /**
     * Let vanilla handle decorations.
     */
    @Override
    public boolean shouldGenerateDecorations() {
        return true; // Delegate to vanilla
    }

    /**
     * Let vanilla handle mobs.
     */
    @Override
    public boolean shouldGenerateMobs() {
        return true; // Delegate to vanilla
    }

    /**
     * Let vanilla handle structures.
     */
    @Override
    public boolean shouldGenerateStructures() {
        return true; // Delegate to vanilla
    }

    /**
     * Let vanilla handle surface.
     */
    @Override
    public boolean shouldGenerateSurface() {
        return true; // Delegate to vanilla
    }

    /**
     * Let vanilla handle bedrock.
     */
    @Override
    public boolean shouldGenerateBedrock() {
        return true; // Delegate to vanilla
    }
}
