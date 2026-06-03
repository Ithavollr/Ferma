package org.evlis.firma.utils.chunk.fixup;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;

public class BiomeValidator {

    /**
     * Check if a biome key exists in Paper's biome registry (includes vanilla + datapack biomes)
     */
    public boolean isValid(String biomeKey) {
        try {
            NamespacedKey key = NamespacedKey.fromString(biomeKey);
            if (key == null) {
                return false;
            }
            return RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.BIOME)
                    .get(key) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
