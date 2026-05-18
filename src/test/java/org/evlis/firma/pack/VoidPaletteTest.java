package org.evlis.firma.pack;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VoidPalette} and {@link PaletteEntry}.
 *
 * <p>These tests cover:
 * <ul>
 *   <li>Coordinate parsing from YAML-style keys</li>
 *   <li>Chunk coordinate calculation (including negative coords)</li>
 *   <li>Chunk indexing for O(1) lookup</li>
 *   <li>Empty palette handling</li>
 * </ul>
 */
class VoidPaletteTest {

    @Test
    void paletteEntry_basicCoordinates() {
        PaletteEntry entry = new PaletteEntry(0, 64, 0, "minecraft:bedrock");

        assertEquals(0, entry.x());
        assertEquals(64, entry.y());
        assertEquals(0, entry.z());
        assertEquals("minecraft:bedrock", entry.blockId());
    }

    @Test
    void paletteEntry_chunkCoordinates_positive() {
        // Block at world (16, 64, 32) should be in chunk (1, 2)
        PaletteEntry entry = new PaletteEntry(16, 64, 32, "minecraft:stone");

        assertEquals(1, entry.chunkX());
        assertEquals(2, entry.chunkZ());
        assertEquals(0, entry.localX());  // 16 & 15 = 0
        assertEquals(0, entry.localZ());  // 32 & 15 = 0
    }

    @Test
    void paletteEntry_chunkCoordinates_negative() {
        // Block at world (-1, 64, -1) should be in chunk (-1, -1)
        // Math.floorDiv(-1, 16) = -1 (not 0)
        PaletteEntry entry = new PaletteEntry(-1, 64, -1, "minecraft:bedrock");

        assertEquals(-1, entry.chunkX());
        assertEquals(-1, entry.chunkZ());
        assertEquals(15, entry.localX());  // -1 & 15 = 15
        assertEquals(15, entry.localZ());  // -1 & 15 = 15
    }

    @Test
    void paletteEntry_chunkCoordinates_negativeSixteen() {
        // Block at world (-16, 64, -16) should be in chunk (-1, -1)
        // but local position should be (0, 0)
        PaletteEntry entry = new PaletteEntry(-16, 64, -16, "minecraft:bedrock");

        assertEquals(-1, entry.chunkX());
        assertEquals(-1, entry.chunkZ());
        assertEquals(0, entry.localX());  // -16 & 15 = 0
        assertEquals(0, entry.localZ());  // -16 & 15 = 0
    }

    @Test
    void voidPalette_fromEntries_indexesByChunk() {
        List<PaletteEntry> entries = List.of(
            new PaletteEntry(0, 64, 0, "minecraft:bedrock"),      // chunk (0, 0)
            new PaletteEntry(1, 64, 0, "minecraft:bedrock"),      // chunk (0, 0)
            new PaletteEntry(16, 64, 0, "minecraft:stone"),       // chunk (1, 0)
            new PaletteEntry(-1, 64, -1, "minecraft:obsidian")     // chunk (-1, -1)
        );

        VoidPalette palette = VoidPalette.fromEntries(entries);

        // All entries preserved
        assertEquals(4, palette.entries().size());

        // Check indexing
        long chunk00 = net.minecraft.world.level.ChunkPos.asLong(0, 0);
        long chunk10 = net.minecraft.world.level.ChunkPos.asLong(1, 0);
        long chunkNeg = net.minecraft.world.level.ChunkPos.asLong(-1, -1);

        assertEquals(2, palette.byChunk().get(chunk00).size());
        assertEquals(1, palette.byChunk().get(chunk10).size());
        assertEquals(1, palette.byChunk().get(chunkNeg).size());
    }

    @Test
    void voidPalette_getEntriesInChunk_returnsCorrectEntries() {
        List<PaletteEntry> entries = List.of(
            new PaletteEntry(0, 64, 0, "minecraft:bedrock"),
            new PaletteEntry(1, 64, 1, "minecraft:dirt"),
            new PaletteEntry(16, 64, 0, "minecraft:stone")
        );

        VoidPalette palette = VoidPalette.fromEntries(entries);

        List<PaletteEntry> chunk00 = palette.getEntriesInChunk(0, 0);
        List<PaletteEntry> chunk10 = palette.getEntriesInChunk(1, 0);
        List<PaletteEntry> chunk99 = palette.getEntriesInChunk(9, 9);

        assertEquals(2, chunk00.size());
        assertEquals(1, chunk10.size());
        assertTrue(chunk99.isEmpty());
    }

    @Test
    void voidPalette_empty_createsEmptyCollections() {
        VoidPalette palette = VoidPalette.empty();

        assertTrue(palette.entries().isEmpty());
        assertTrue(palette.byChunk().isEmpty());
        assertTrue(palette.getEntriesInChunk(0, 0).isEmpty());
    }

    @Test
    void voidPalette_fromEntries_emptyList_createsEmptyPalette() {
        VoidPalette palette = VoidPalette.fromEntries(Collections.emptyList());

        assertTrue(palette.entries().isEmpty());
        assertTrue(palette.byChunk().isEmpty());
    }

    @Test
    void voidPalette_immutable_entriesCannotBeModified() {
        List<PaletteEntry> entries = new java.util.ArrayList<>();
        entries.add(new PaletteEntry(0, 64, 0, "minecraft:bedrock"));

        VoidPalette palette = VoidPalette.fromEntries(entries);

        // Original list modification shouldn't affect palette
        entries.add(new PaletteEntry(1, 64, 0, "minecraft:stone"));
        assertEquals(1, palette.entries().size());

        // Palette entries list should be unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> {
            ((List<PaletteEntry>) palette.entries()).add(new PaletteEntry(2, 64, 0, "minecraft:dirt"));
        });
    }

    @Test
    void paletteEntry_chunkKey_matchesChunkPos() {
        PaletteEntry entry = new PaletteEntry(17, 70, 33, "minecraft:grass_block");

        long expectedKey = net.minecraft.world.level.ChunkPos.asLong(1, 2);
        assertEquals(expectedKey, entry.chunkKey());
    }
}
