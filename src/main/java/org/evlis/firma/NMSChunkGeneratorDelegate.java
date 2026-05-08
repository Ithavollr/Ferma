package org.evlis.firma;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A no-op chunk generator delegate that passes ALL operations to the vanilla generator.
 * This class exists to allow us to intercept the generation pipeline for future modifications.
 * Currently, it produces completely faithful vanilla worlds.
 */
public class NMSChunkGeneratorDelegate extends ChunkGenerator {
    private final ChunkGenerator vanilla;

    public NMSChunkGeneratorDelegate(ChunkGenerator vanilla) {
        // Pass the vanilla generator's biome source
        super(getBiomeSource(vanilla));
        this.vanilla = vanilla;
    }

    private static BiomeSource getBiomeSource(ChunkGenerator generator) {
        try {
            return generator.getBiomeSource();
        } catch (Exception e) {
            // Fallback: return an empty biome source
            throw new IllegalStateException("Could not get biome source from vanilla generator", e);
        }
    }

    /**
     * Delegate everything to vanilla - no modifications.
     */
    @Override
    protected @NotNull MapCodec<? extends ChunkGenerator> codec() {
        return vanilla.codec();
    }

    /**
     * Delegate cave generation to vanilla.
     */
    @Override
    public void applyCarvers(@NotNull WorldGenRegion chunkRegion, long seed, @NotNull RandomState noiseConfig,
                             @NotNull BiomeManager world, @NotNull StructureManager structureAccessor,
                             @NotNull ChunkAccess chunk) {
        vanilla.applyCarvers(chunkRegion, seed, noiseConfig, world, structureAccessor, chunk);
    }

    /**
     * Delegate surface building to vanilla.
     */
    @Override
    public void buildSurface(@NotNull WorldGenRegion region, @NotNull StructureManager structures,
                             @NotNull RandomState noiseConfig, @NotNull ChunkAccess chunk) {
        vanilla.buildSurface(region, structures, noiseConfig, chunk);
    }

    /**
     * Delegate biome decoration to vanilla.
     */
    @Override
    public void applyBiomeDecoration(@NotNull WorldGenLevel world, @NotNull ChunkAccess chunk,
                                     @NotNull StructureManager structureAccessor) {
        vanilla.applyBiomeDecoration(world, chunk, structureAccessor);
    }

    /**
     * Delegate mob spawning to vanilla.
     */
    @Override
    public void spawnOriginalMobs(@NotNull WorldGenRegion region) {
        vanilla.spawnOriginalMobs(region);
    }

    /**
     * Delegate generation depth to vanilla.
     */
    @Override
    public int getGenDepth() {
        return vanilla.getGenDepth();
    }

    /**
     * Delegate noise filling to vanilla - THIS IS THE CORE TERRAIN GENERATION.
     * We pass through completely unmodified.
     */
    @Override
    public @NotNull CompletableFuture<ChunkAccess> fillFromNoise(@NotNull Blender blender,
                                                                  @NotNull RandomState noiseConfig,
                                                                  @NotNull StructureManager structureAccessor,
                                                                  @NotNull ChunkAccess chunk) {
        return vanilla.fillFromNoise(blender, noiseConfig, structureAccessor, chunk);
    }

    /**
     * Delegate sea level to vanilla.
     */
    @Override
    public int getSeaLevel() {
        return vanilla.getSeaLevel();
    }

    /**
     * Delegate minimum Y to vanilla.
     */
    @Override
    public int getMinY() {
        return vanilla.getMinY();
    }

    /**
     * Delegate base height calculation to vanilla.
     */
    @Override
    public int getBaseHeight(int x, int z, @NotNull Heightmap.Types heightmap,
                             @NotNull LevelHeightAccessor world, @NotNull RandomState noiseConfig) {
        return vanilla.getBaseHeight(x, z, heightmap, world, noiseConfig);
    }

    /**
     * Delegate base column generation to vanilla.
     */
    @Override
    public @NotNull NoiseColumn getBaseColumn(int x, int z, @NotNull LevelHeightAccessor world,
                                              @NotNull RandomState noiseConfig) {
        return vanilla.getBaseColumn(x, z, world, noiseConfig);
    }

    /**
     * Delegate debug info to vanilla.
     */
    @Override
    public void addDebugScreenInfo(@NotNull List<String> text, @NotNull RandomState noiseConfig, @NotNull BlockPos pos) {
        vanilla.addDebugScreenInfo(text, noiseConfig, pos);
    }

    /**
     * Delegate pack ID to vanilla.
     */
    @Override
    public @NotNull String getPackId() {
        return vanilla.getPackId();
    }

    /**
     * Check if this delegate has stronghold positions (delegate to vanilla).
     */
    @Override
    public boolean hasStrongholdPositions() {
        return vanilla.hasStrongholdPositions();
    }

    /**
     * Get the wrapped vanilla generator for direct access if needed.
     */
    public ChunkGenerator getVanillaGenerator() {
        return vanilla;
    }
}
