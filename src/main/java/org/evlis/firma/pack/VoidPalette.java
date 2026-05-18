package org.evlis.firma.pack;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A parsed void palette containing specific block placements at world coordinates.
 *
 * <p>The palette is indexed by chunk for efficient lookup during chunk generation.
 * Each entry maps a world coordinate [x, y, z] to a block resource location.
 *
 * @param entries  The raw list of palette entries (unmodifiable)
 * @param byChunk  Map from chunk key (ChunkPos.asLong) to entries in that chunk (unmodifiable)
 */
public record VoidPalette(
    List<PaletteEntry> entries,
    Map<Long, List<PaletteEntry>> byChunk
) {

    /**
     * Create a VoidPalette from a list of entries, automatically building the chunk index.
     *
     * @param entries The palette entries (will be copied and made unmodifiable)
     * @return A new VoidPalette with entries indexed by chunk
     */
    public static VoidPalette fromEntries(List<PaletteEntry> entries) {
        // Build the chunk-indexed map
        Map<Long, List<PaletteEntry>> byChunk = new java.util.HashMap<>();
        for (PaletteEntry entry : entries) {
            byChunk.computeIfAbsent(entry.chunkKey(), k -> new java.util.ArrayList<>()).add(entry);
        }

        // Make immutable copies
        Map<Long, List<PaletteEntry>> immutableByChunk = new java.util.HashMap<>();
        for (Map.Entry<Long, List<PaletteEntry>> e : byChunk.entrySet()) {
            immutableByChunk.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }

        return new VoidPalette(
            Collections.unmodifiableList(new java.util.ArrayList<>(entries)),
            Collections.unmodifiableMap(immutableByChunk)
        );
    }

    /**
     * Create an empty palette (no entries).
     */
    public static VoidPalette empty() {
        return new VoidPalette(
            Collections.emptyList(),
            Collections.emptyMap()
        );
    }

    /**
     * Get all palette entries for a specific chunk.
     *
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return List of entries in that chunk, or empty list if none
     */
    public List<PaletteEntry> getEntriesInChunk(int chunkX, int chunkZ) {
        long key = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);
        return byChunk.getOrDefault(key, Collections.emptyList());
    }
}
