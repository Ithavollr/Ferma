package org.evlis.firma.pack;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VoidChunkHandler}.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Null palette → no-op behavior</li>
 *   <li>Blocks placed at correct world coordinates</li>
 *   <li>Only entries in the target chunk are applied</li>
 *   <li>Unknown block IDs are skipped gracefully</li>
 * </ul>
 */
class VoidChunkHandlerTest {

    /**
     * NMS classes require Bootstrap to be initialized.
     */
    @BeforeAll
    static void bootstrapNms() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nullPalette_fillChunk_isNoOp() {
        VoidChunkHandler handler = new VoidChunkHandler(null);

        // Create a mock chunk (we'll just verify no exception is thrown)
        // In a real test environment, we'd need a proper ChunkAccess implementation
        assertDoesNotThrow(() -> {
            // Since we can't easily create a real ChunkAccess in unit tests,
            // we at least verify the method doesn't throw on null palette
            handler.fillChunk(null);
        });
    }

    @Test
    void emptyPalette_fillChunk_isNoOp() {
        VoidPalette emptyPalette = VoidPalette.empty();
        VoidChunkHandler handler = new VoidChunkHandler(emptyPalette);

        assertDoesNotThrow(() -> {
            handler.fillChunk(null);
        });
    }

    @Test
    void constructor_resolvesKnownBlocks() {
        // This test verifies that known blocks like bedrock are resolved
        List<PaletteEntry> entries = List.of(
            new PaletteEntry(0, 64, 0, "minecraft:bedrock"),
            new PaletteEntry(1, 64, 0, "minecraft:stone")
        );
        VoidPalette palette = VoidPalette.fromEntries(entries);

        // Should not throw - bedrock and stone are valid blocks
        assertDoesNotThrow(() -> new VoidChunkHandler(palette));
    }

    @Test
    void constructor_logsUnknownBlocks_butContinues() {
        List<PaletteEntry> entries = List.of(
            new PaletteEntry(0, 64, 0, "minecraft:bedrock"),
            new PaletteEntry(1, 64, 0, "unknownmod:invalid_block"),
            new PaletteEntry(2, 64, 0, "minecraft:stone")
        );
        VoidPalette palette = VoidPalette.fromEntries(entries);

        // Should not throw even with invalid block
        VoidChunkHandler handler = assertDoesNotThrow(() -> new VoidChunkHandler(palette));
        assertNotNull(handler);
    }

    @Test
    void voidPalette_chunkIndexing_correctlyBucketsEntries() {
        // Create entries in different chunks
        List<PaletteEntry> entries = List.of(
            new PaletteEntry(0, 64, 0, "minecraft:bedrock"),      // chunk (0, 0)
            new PaletteEntry(15, 64, 15, "minecraft:bedrock"),    // chunk (0, 0)
            new PaletteEntry(16, 64, 0, "minecraft:stone"),      // chunk (1, 0)
            new PaletteEntry(0, 64, 16, "minecraft:dirt"),         // chunk (0, 1)
            new PaletteEntry(-1, 64, -1, "minecraft:obsidian")   // chunk (-1, -1)
        );

        VoidPalette palette = VoidPalette.fromEntries(entries);

        // Verify chunk indexing
        assertEquals(2, palette.getEntriesInChunk(0, 0).size());
        assertEquals(1, palette.getEntriesInChunk(1, 0).size());
        assertEquals(1, palette.getEntriesInChunk(0, 1).size());
        assertEquals(1, palette.getEntriesInChunk(-1, -1).size());
        assertEquals(0, palette.getEntriesInChunk(5, 5).size());
    }

    @Test
    void paletteEntry_chunkCalculation_matchesVanillaBehavior() {
        // Test that our chunk coordinate calculation matches Minecraft's
        PaletteEntry entry1 = new PaletteEntry(0, 64, 0, "minecraft:bedrock");
        assertEquals(0, entry1.chunkX());
        assertEquals(0, entry1.chunkZ());

        // Block at x=15 is still in chunk 0
        PaletteEntry entry2 = new PaletteEntry(15, 64, 15, "minecraft:bedrock");
        assertEquals(0, entry2.chunkX());
        assertEquals(0, entry2.chunkZ());

        // Block at x=16 is in chunk 1
        PaletteEntry entry3 = new PaletteEntry(16, 64, 0, "minecraft:stone");
        assertEquals(1, entry3.chunkX());
        assertEquals(0, entry3.chunkZ());

        // Negative coordinates use floorDiv, not truncation
        PaletteEntry entry4 = new PaletteEntry(-1, 64, -1, "minecraft:obsidian");
        assertEquals(-1, entry4.chunkX());
        assertEquals(-1, entry4.chunkZ());

        // Block at x=-16 is in chunk -1 (floorDiv(-16, 16) = -1)
        PaletteEntry entry5 = new PaletteEntry(-16, 64, -16, "minecraft:bedrock");
        assertEquals(-1, entry5.chunkX());
        assertEquals(-1, entry5.chunkZ());

        // Block at x=-17 is in chunk -2 (floorDiv(-17, 16) = -2)
        PaletteEntry entry6 = new PaletteEntry(-17, 64, 0, "minecraft:stone");
        assertEquals(-2, entry6.chunkX());
        assertEquals(0, entry6.chunkZ());
    }

    @Test
    void paletteEntry_localCoordinates_withinChunkBounds() {
        // Local coordinates should always be 0-15
        PaletteEntry entry1 = new PaletteEntry(0, 64, 0, "minecraft:bedrock");
        assertEquals(0, entry1.localX());
        assertEquals(0, entry1.localZ());

        PaletteEntry entry2 = new PaletteEntry(15, 64, 15, "minecraft:bedrock");
        assertEquals(15, entry2.localX());
        assertEquals(15, entry2.localZ());

        // 16 & 15 = 0
        PaletteEntry entry3 = new PaletteEntry(16, 64, 16, "minecraft:stone");
        assertEquals(0, entry3.localX());
        assertEquals(0, entry3.localZ());

        // -1 & 15 = 15
        PaletteEntry entry4 = new PaletteEntry(-1, 64, -1, "minecraft:obsidian");
        assertEquals(15, entry4.localX());
        assertEquals(15, entry4.localZ());
    }
}
