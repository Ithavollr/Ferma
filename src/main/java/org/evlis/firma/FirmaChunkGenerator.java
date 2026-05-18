package org.evlis.firma;

import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.evlis.firma.pack.FirmaPack;
import org.evlis.firma.pack.VoidPalette;
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
        /** Void/empty chunk mode (Stage 3) */
        VOID,
        /** Pack-based configuration (Stage 4) */
        PACK
    }

    private final Firma plugin;
    private final GenerationMode mode;
    private final String packId;
    private FirmaPack cachedPack;
    private final VoidPalette voidPalette;

    /**
     * Creates a Firma chunk generator with the specified mode.
     *
     * @param plugin The plugin instance
     * @param modeId The generation mode string ("vanilla", "void", or pack id), or null for default
     */
    public FirmaChunkGenerator(Firma plugin, @Nullable String modeId) {
        this.plugin = plugin;
        this.packId = modeId;
        this.mode = parseMode(modeId);
        this.voidPalette = loadVoidPalette(modeId);
        plugin.getLogger().info("Created Firma generator with mode: " + mode + (mode == GenerationMode.PACK ? " (pack: " + modeId + ")" : ""));
    }

    /**
     * Load the void palette if this is a void-type pack.
     * Returns null for vanilla, bare void keyword, or non-void packs.
     */
    private VoidPalette loadVoidPalette(@Nullable String modeId) {
        if (modeId == null || modeId.isEmpty()) {
            return null;
        }
        // Check if it's a pack with type: void
        FirmaPack pack = plugin.getPack(modeId);
        if (pack != null && pack.voidPalette() != null) {
            return pack.voidPalette();
        }
        return null;
    }

    /**
     * Parse the mode string into a GenerationMode enum.
     * Defaults to searching packs and failing if no match is found.
     */
    private GenerationMode parseMode(@Nullable String modeId) {
        if (modeId == null || modeId.isEmpty()) {
            return GenerationMode.VANILLA;
        }
        return switch (modeId.toLowerCase()) {
            case "vanilla" -> GenerationMode.VANILLA;
            case "void" -> GenerationMode.VOID;
            default -> {
                // Check if it's a pack id
                if (plugin.hasPack(modeId)) {
                    yield GenerationMode.PACK;
                }
                // Fail loudly - getDefaultWorldGenerator should have caught this already.
                // Reaching here means a stale/orphaned id slipped through.
                throw new IllegalArgumentException(
                    "Unknown Firma generation mode or pack id: '" + modeId + "'. " +
                    "World creation aborted."
                );
            }
        };
    }
    
    /**
     * Get the pack for this generator (only valid in PACK mode).
     */
    public @Nullable FirmaPack getPack() {
        if (mode != GenerationMode.PACK) {
            return null;
        }
        if (cachedPack == null && packId != null) {
            cachedPack = plugin.getPack(packId);
        }
        return cachedPack;
    }

    /**
     * Get the generation mode for this generator.
     */
    public GenerationMode getMode() {
        return mode;
    }
    
    /**
     * Get the pack id for this generator.
     */
    public @Nullable String getPackId() {
        return packId;
    }

    /**
     * Get the void palette for this generator (only valid in VOID mode with a void pack).
     */
    public @Nullable VoidPalette getVoidPalette() {
        return voidPalette;
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
     * Let vanilla handle caves (except in VOID mode).
     */
    @Override
    public boolean shouldGenerateCaves() {
        return mode != GenerationMode.VOID; // Disable in void mode
    }

    /**
     * Let vanilla handle decorations (except in VOID mode).
     */
    @Override
    public boolean shouldGenerateDecorations() {
        return mode != GenerationMode.VOID; // Disable in void mode
    }

    /**
     * Let vanilla handle mobs (except in VOID mode).
     */
    @Override
    public boolean shouldGenerateMobs() {
        return mode != GenerationMode.VOID; // Disable in void mode
    }

    /**
     * Let vanilla handle structures (except in VOID mode).
     */
    @Override
    public boolean shouldGenerateStructures() {
        return mode != GenerationMode.VOID; // Disable in void mode
    }

    /**
     * Let vanilla handle surface (except in VOID mode).
     */
    @Override
    public boolean shouldGenerateSurface() {
        return mode != GenerationMode.VOID; // Disable in void mode
    }

    /**
     * Let vanilla handle bedrock (except in VOID mode).
     */
    @Override
    public boolean shouldGenerateBedrock() {
        return mode != GenerationMode.VOID; // Disable in void mode
    }
}
