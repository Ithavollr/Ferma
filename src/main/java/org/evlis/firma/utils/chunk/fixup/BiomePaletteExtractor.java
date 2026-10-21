package org.evlis.firma.utils.chunk.fixup;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import net.minecraft.nbt.StringTag;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BiomePaletteExtractor {

    /**
     * Extract all unique biome keys from raw chunk NBT by reading each section's biome palette strings.
     * The tag must already be upgraded to the current data version (ChunkMap#upgradeChunkTag).
     */
    public Set<String> extractBiomeKeys(CompoundTag chunkTag) {
        Set<String> biomeKeys = new HashSet<>();

        ListTag sections = chunkTag.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); i++) {
            Optional<CompoundTag> biomes = sections.getCompound(i).flatMap(s -> s.getCompound("biomes"));
            if (biomes.isEmpty()) {
                continue;
            }

            ListTag palette = biomes.get().getListOrEmpty("palette");
            for (int j = 0; j < palette.size(); j++) {
                palette.getString(j).ifPresent(biomeKeys::add);
            }
        }

        return biomeKeys;
    }

    /**
     * Replace biome palette keys per the given mapping. Returns true if anything changed.
     * Only palette strings are touched — data arrays and every other tag (light state!) stay intact,
     * so a fixed chunk still loads without triggering a relight.
     */
    public boolean replaceBiomeKeys(CompoundTag chunkTag, Map<String, String> replacements) {
        boolean changed = false;

        ListTag sections = chunkTag.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); i++) {
            Optional<CompoundTag> biomes = sections.getCompound(i).flatMap(s -> s.getCompound("biomes"));
            if (biomes.isEmpty()) {
                continue;
            }

            ListTag palette = biomes.get().getListOrEmpty("palette");
            for (int j = 0; j < palette.size(); j++) {
                String replacement = palette.getString(j).map(replacements::get).orElse(null);
                if (replacement != null) {
                    palette.set(j, StringTag.valueOf(replacement));
                    changed = true;
                }
            }
        }

        return changed;
    }
}
