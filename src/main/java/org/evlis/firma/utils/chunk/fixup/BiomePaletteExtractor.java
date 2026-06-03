package org.evlis.firma.utils.chunk.fixup;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.bukkit.craftbukkit.CraftChunk;

import java.util.HashSet;
import java.util.Set;

public class BiomePaletteExtractor {

    /**
     * Extract all unique biome keys from a chunk's palette by reading NMS biome data from all sections
     */
    public Set<String> extractBiomeKeys(org.bukkit.Chunk chunk) {
        Set<String> biomeKeys = new HashSet<>();
        
        try {
            CraftChunk craftChunk = (CraftChunk) chunk;
            ChunkAccess nmsChunk = craftChunk.getHandle(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
            
            for (LevelChunkSection section : nmsChunk.getSections()) {
                if (section == null) {
                    continue;
                }
                
                PalettedContainerRO<Holder<Biome>> biomes = section.getBiomes();
                
                for (int x = 0; x < 4; x++) {
                    for (int y = 0; y < 4; y++) {
                        for (int z = 0; z < 4; z++) {
                            Holder<Biome> holder = biomes.get(x, y, z);
                            holder.unwrapKey().ifPresent(key -> 
                                biomeKeys.add(key.location().toString())
                            );
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract biome palette from chunk", e);
        }
        
        return biomeKeys;
    }
}
