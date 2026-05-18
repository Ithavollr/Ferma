package org.evlis.firma.pack;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handles block placement in void worlds based on a palette configuration.
 *
 * <p>This class owns all void-world chunk generation logic, keeping
 * {@code NMSChunkGeneratorDelegate} as a clean passthrough wrapper.
 */
public class VoidChunkHandler {

    private static final Logger LOGGER = Logger.getLogger(VoidChunkHandler.class.getName());

    private final VoidPalette palette;
    private final Map<String, BlockState> blockCache;

    /**
     * Create a new VoidChunkHandler for the given palette.
     *
     * <p>At construction, all block IDs in the palette are resolved to BlockState objects
     * and cached. Unknown block IDs are logged and skipped.
     *
     * @param palette The void palette, or null for an empty void world
     */
    public VoidChunkHandler(@Nullable VoidPalette palette) {
        this.palette = palette;

        if (palette == null) {
            this.blockCache = Collections.emptyMap();
            return;
        }

        // Resolve all block IDs to BlockState objects at construction time
        Map<String, BlockState> cache = new HashMap<>();
        for (PaletteEntry entry : palette.entries()) {
            String blockId = entry.blockId();
            if (cache.containsKey(blockId)) {
                continue; // Already resolved
            }

            BlockState state = resolveBlockState(blockId);
            if (state != null) {
                cache.put(blockId, state);
            } else {
                LOGGER.warning("Unknown block id in void palette: " + blockId);
            }
        }
        this.blockCache = Collections.unmodifiableMap(cache);
    }

    /**
     * Resolve a block ID to its default BlockState.
     *
     * @param blockId The resource location (e.g., "minecraft:bedrock")
     * @return The default BlockState, or null if not found
     */
    private @Nullable BlockState resolveBlockState(String blockId) {
        try {
            // Parse the resource location
            String[] parts = blockId.split(":");
            if (parts.length != 2) {
                return null;
            }
            String namespace = parts[0];
            String path = parts[1];

            // Build the ResourceLocation and look up the block
            net.minecraft.resources.ResourceLocation location =
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(namespace, path);
            java.util.Optional<net.minecraft.core.Holder.Reference<Block>> holder = BuiltInRegistries.BLOCK.get(location);

            return holder.map(h -> h.value().defaultBlockState()).orElse(null);
        } catch (Exception e) {
            LOGGER.warning("Failed to resolve block id: " + blockId + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Fill the given chunk with blocks from the palette.
     *
     * <p>If the palette is null, this method returns immediately (empty void world).
     * Only blocks in palette entries that belong to this chunk are placed.
     *
     * @param chunk The chunk to fill
     */
    public void fillChunk(ChunkAccess chunk) {
        if (palette == null || chunk == null) {
            return; // Empty void world or no chunk to fill
        }

        // Get chunk coordinates
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Look up entries for this chunk
        List<PaletteEntry> entries = palette.getEntriesInChunk(chunkX, chunkZ);
        if (entries.isEmpty()) {
            return; // No blocks to place in this chunk
        }

        // Place each block
        for (PaletteEntry entry : entries) {
            BlockState state = blockCache.get(entry.blockId());
            if (state == null) {
                continue; // Unknown block, skip
            }

            // Use world coordinates directly - ChunkAccess handles the mapping
            BlockPos pos = new BlockPos(entry.x(), entry.y(), entry.z());
            chunk.setBlockState(pos, state, false);
        }
    }
}

