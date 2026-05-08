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
 * This class exists solely to identify worlds that should use our NMS injection.
 * All actual generation is delegated to vanilla by NMSChunkGeneratorDelegate.
 */
public class FirmaChunkGenerator extends ChunkGenerator {
    private final Firma plugin;

    public FirmaChunkGenerator(Firma plugin) {
        this.plugin = plugin;
    }

    /**
     * We don't generate noise here - vanilla handles it.
     * This method should never be called because we inject at the NMS level.
     */
    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull ChunkData chunkData) {
        plugin.getLogger().warning("generateNoise called on FirmaChunkGenerator - this should not happen!");
        // Leave empty - vanilla NMS will handle generation
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
