package org.evlis.firma.utils.chunk.fixup;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashSet;
import java.util.Set;

public class BiomePaletteExtractor {

    /**
     * Extract all unique biome keys from raw chunk NBT by reading each section's biome palette strings.
     * The tag must already be upgraded to the current data version (ChunkMap#upgradeChunkTag).
     */
    public Set<String> extractBiomeKeys(CompoundTag chunkTag) {
        Set<String> biomeKeys = new HashSet<>();

        ListTag sections = chunkTag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (!section.contains("biomes", Tag.TAG_COMPOUND)) {
                continue;
            }

            ListTag palette = section.getCompound("biomes").getList("palette", Tag.TAG_STRING);
            for (int j = 0; j < palette.size(); j++) {
                biomeKeys.add(palette.getString(j));
            }
        }

        return biomeKeys;
    }
}
