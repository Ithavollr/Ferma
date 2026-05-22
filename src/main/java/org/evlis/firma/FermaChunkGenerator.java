package org.evlis.firma;

import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.evlis.firma.pack.FermaPack;
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
 * For Stage 2 (VOID): Overrides generateNoise with empty implementation.
 * For Stage 3 (NOISE): NMS injection handles custom noise routing.
 */
public class FermaChunkGenerator extends ChunkGenerator {

    /**
     * Generation modes for Firma worlds.
     */
    public enum GenerationMode {
        /** Faithful vanilla pass-through (Stage 1) */
        VANILLA,
        /** Void/empty chunk mode (Stage 2) */
        VOID,
        /** Pack-based configuration (Stage 3) */
        NOISE
    }

    private final Ferma plugin;
    private final GenerationMode mode;
    private final String packId;
    private FermaPack cachedPack;
    private final VoidPalette voidPalette;

    /**
     * Creates a Firma chunk generator with the specified mode.
     *
     * @param plugin The plugin instance
     * @param modeId The generation mode string ("vanilla", "void", or pack id), or null for default
     */
    public FermaChunkGenerator(Ferma plugin, @Nullable String modeId) {
        this.plugin = plugin;
        this.packId = modeId;
        this.mode = parseMode(modeId);
        this.voidPalette = loadVoidPalette(modeId);
        plugin.getLogger().info("Created Firma generator with mode: " + mode + (mode == GenerationMode.NOISE ? " (pack: " + modeId + ")" : ""));
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
        FermaPack pack = plugin.getPack(modeId);
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
        // Special pass-through case, more of a test than any functional use. Should work as a no-op
        if (modeId == null || modeId.isEmpty()) {
            return GenerationMode.VANILLA;
        }
        String id = modeId.toLowerCase();
        if (!plugin.hasPack(id)) {
            throw new IllegalArgumentException(
                "Unknown Ferma pack id: '" + id + "'. World creation aborted."
            );
        }
        FermaPack pack = plugin.getPack(id);
        if (pack == null) {
            throw new IllegalStateException(
                "Pack '" + id + "' registered but returned null. This should not happen."
            );
        }
        return switch (pack.type()) {
            case "void" -> GenerationMode.VOID;
            case "noise" -> GenerationMode.NOISE;
            default -> throw new IllegalArgumentException(
                "Pack '" + id + "' has unknown type: '" + pack.type() + "'. Expected 'void' or 'noise'."
            );
        };
    }
    
    /**
     * Get the pack for this generator (only valid in NOISE mode).
     */
    public @Nullable FermaPack getPack() {
        if (mode != GenerationMode.NOISE) {
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
     * Stage 2 (VOID): Overridden to produce empty chunks.
     *
     * Stage 3 (NOISE): NMS injection handles custom climate noise routing.
     */
    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkData chunkData) {
        if (mode == GenerationMode.VOID) {
            // Stage 2: Void mode - leave chunk empty
            // This is the intended behavior for void worlds
            return;
        }

        // For VANILLA and NOISE modes, this may be called by Bukkit API methods
        // (e.g., spawn searching, terrain preview) even though NMS injection handles
        // the actual terrain generation. Log with appropriate severity based on mode.
        String message = "Bukkit generateNoise() called with mode " + mode + " at chunk (" + x + ", " + z + ")";

        if (mode == GenerationMode.VANILLA) {
            // VANILLA mode: Unexpected if NMS injection is working correctly
            // The vanilla generator should handle everything at the NMS level
            plugin.getLogger().warning(message + " - NMS injection may have failed or Bukkit API is being used directly");
        } else if (mode == GenerationMode.NOISE) {
            // NOISE mode: May be expected for Bukkit API calls (spawn search, etc.)
            // Actual terrain generation uses patched NMS router, so this is informational
            // Identify caller from stack trace
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            String caller = stack.length > 3
                ? stack[3].getClassName().substring(stack[3].getClassName().lastIndexOf('.') + 1)
                  + "." + stack[3].getMethodName() + ":" + stack[3].getLineNumber()
                : "unknown";
            plugin.getLogger().info(message + " - caller: " + caller + " (NMS router handles actual generation)");
        } else {
            // Catch-all for unexpected mode values (defensive for alpha builds)
            plugin.getLogger().severe(message + " - UNEXPECTED MODE VALUE! This is a bug.");
        }
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
