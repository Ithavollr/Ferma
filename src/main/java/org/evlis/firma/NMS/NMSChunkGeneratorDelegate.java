package org.evlis.firma.NMS;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import org.evlis.firma.pack.VoidChunkHandler;
import org.jetbrains.annotations.Nullable;
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
 * A chunk generator delegate that wraps the vanilla generator.
 * For VANILLA/NOISE_OVERRIDE modes: passes operations to vanilla (with possible NoiseRouter patches).
 * For VOID mode: returns empty chunks without calling vanilla noise generation.
 */
public class NMSChunkGeneratorDelegate extends ChunkGenerator {
    private final ChunkGenerator vanilla;
    private final boolean voidMode;
    private final VoidChunkHandler voidHandler;

    public NMSChunkGeneratorDelegate(ChunkGenerator vanilla) {
        this(vanilla, false, null);
    }

    public NMSChunkGeneratorDelegate(ChunkGenerator vanilla, boolean voidMode) {
        this(vanilla, voidMode, null);
    }

    public NMSChunkGeneratorDelegate(ChunkGenerator vanilla, boolean voidMode, @Nullable VoidChunkHandler voidHandler) {
        // Pass the vanilla generator's biome source
        super(getBiomeSource(vanilla));
        this.vanilla = vanilla;
        this.voidMode = voidMode;
        this.voidHandler = voidHandler != null ? voidHandler : new VoidChunkHandler(null);
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
     * Return unsafe codec - required by ChunkGenerator but we never serialize this.
     */
    @Override
    protected @NotNull MapCodec<? extends ChunkGenerator> codec() {
        return MapCodec.assumeMapUnsafe(ChunkGenerator.CODEC);
    }

    /**
     * Cave generation - skipped in VOID mode.
     */
    @Override
    public void applyCarvers(@NotNull WorldGenRegion chunkRegion, long seed, @NotNull RandomState noiseConfig,
                             @NotNull BiomeManager world, @NotNull StructureManager structureAccessor,
                             @NotNull ChunkAccess chunk) {
        if (!voidMode) {
            vanilla.applyCarvers(chunkRegion, seed, noiseConfig, world, structureAccessor, chunk);
        }
    }

    /**
     * Surface building - skipped in VOID mode.
     */
    @Override
    public void buildSurface(@NotNull WorldGenRegion region, @NotNull StructureManager structures,
                             @NotNull RandomState noiseConfig, @NotNull ChunkAccess chunk) {
        if (!voidMode) {
            vanilla.buildSurface(region, structures, noiseConfig, chunk);
        }
    }

    /**
     * Biome decoration - skipped in VOID mode.
     */
    @Override
    public void applyBiomeDecoration(@NotNull WorldGenLevel world, @NotNull ChunkAccess chunk,
                                     @NotNull StructureManager structureAccessor) {
        if (!voidMode) {
            vanilla.applyBiomeDecoration(world, chunk, structureAccessor);
        }
    }

    /**
     * Mob spawning - skipped in VOID mode.
     */
    @Override
    public void spawnOriginalMobs(@NotNull WorldGenRegion region) {
        if (!voidMode) {
            vanilla.spawnOriginalMobs(region);
        }
    }

    /**
     * Delegate generation depth to vanilla.
     */
    @Override
    public int getGenDepth() {
        return vanilla.getGenDepth();
    }

    /**
     * Core terrain generation.
     * VOID mode: Return chunk unchanged (empty).
     * Other modes: Delegate to vanilla generator.
     */
    @Override
    public @NotNull CompletableFuture<ChunkAccess> fillFromNoise(@NotNull Blender blender,
                                                                  @NotNull RandomState noiseConfig,
                                                                  @NotNull StructureManager structureAccessor,
                                                                  @NotNull ChunkAccess chunk) {
        if (voidMode) {
            // VOID mode: Apply palette blocks if present, then return
            voidHandler.fillChunk(chunk);
            return CompletableFuture.completedFuture(chunk);
        }
        // Delegate to vanilla for terrain generation
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
     * Get the wrapped vanilla generator for direct access if needed.
     */
    public ChunkGenerator getVanillaGenerator() {
        return vanilla;
    }
}
