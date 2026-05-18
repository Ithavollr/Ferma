package org.evlis.firma.pack;

/**
 * A single entry in a void palette: a block at a specific world coordinate.
 *
 * @param x       World X coordinate
 * @param y       World Y coordinate (must be in [-64, 320])
 * @param z       World Z coordinate
 * @param blockId Resource location of the block (e.g., "minecraft:bedrock")
 */
public record PaletteEntry(int x, int y, int z, String blockId) {

    /**
     * Compute the chunk X coordinate that contains this entry.
     */
    public int chunkX() {
        return Math.floorDiv(x, 16);
    }

    /**
     * Compute the chunk Z coordinate that contains this entry.
     */
    public int chunkZ() {
        return Math.floorDiv(z, 16);
    }

    /**
     * Compute the chunk-local X position (0-15).
     */
    public int localX() {
        return x & 15;
    }

    /**
     * Compute the chunk-local Z position (0-15).
     */
    public int localZ() {
        return z & 15;
    }

    /**
     * Compute the chunk key (as used by ChunkPos.asLong) for indexing.
     */
    public long chunkKey() {
        return net.minecraft.world.level.ChunkPos.asLong(chunkX(), chunkZ());
    }
}
