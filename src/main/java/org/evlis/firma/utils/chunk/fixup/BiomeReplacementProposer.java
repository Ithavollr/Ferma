package org.evlis.firma.utils.chunk.fixup;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class BiomeReplacementProposer {
    private final Map<String, String> explicitMappings = new HashMap<>();
    private final World.Environment dimension;
    private final Logger logger;
    
    public BiomeReplacementProposer(World.Environment dimension, Plugin plugin) {
        this.dimension = dimension;
        this.logger = plugin.getLogger();
        loadDefaultMappings(plugin);
    }

    /**
     * Load user-defined biome mappings from fixup/mappings.yml if it exists
     */
    private void loadDefaultMappings(Plugin plugin) {
        File mappingsFile = new File(plugin.getDataFolder(), "fixup/mappings.yml");
        
        if (!mappingsFile.exists()) {
            mappingsFile.getParentFile().mkdirs();
            try {
                mappingsFile.createNewFile();
                logger.info("Created new mappings file at: " + mappingsFile.getPath());
            } catch (IOException e) {
                logger.warning("Failed to create mappings file: " + e.getMessage());
            }
            logger.info("No biome mappings configured. All invalid biomes will require manual replacement.");
            return;
        }
        
        try (FileReader reader = new FileReader(mappingsFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(reader);
            
            if (data == null || data.isEmpty()) {
                logger.info("Mappings file is empty. All invalid biomes will require manual replacement.");
                return;
            }
            
            Object mappingsObj = data.get("mappings");
            if (mappingsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> mappings = (Map<String, String>) mappingsObj;
                explicitMappings.putAll(mappings);
                logger.info("Loaded " + explicitMappings.size() + " biome mappings from " + mappingsFile.getName());
            } else {
                logger.warning("Invalid mappings file format: expected 'mappings' key with map value");
            }
            
        } catch (IOException e) {
            logger.warning("Failed to load mappings file: " + e.getMessage());
            logger.info("No biome mappings configured. All invalid biomes will require manual replacement.");
        }
    }

    /**
     * Propose a replacement biome using explicit mappings, namespace heuristics, or dimension defaults
     */
    public ProposedReplacement propose(String invalidBiomeKey) {
        if (explicitMappings.containsKey(invalidBiomeKey)) {
            return new ProposedReplacement(explicitMappings.get(invalidBiomeKey), "explicit_mapping");
        }
        
        String namespace = extractNamespace(invalidBiomeKey);
        
        if ("terra".equals(namespace) || "ferma".equals(namespace)) {
            return new ProposedReplacement(getDimensionDefault(), "dimension_default");
        }
        
        return new ProposedReplacement("", "unknown_pack");
    }

    /**
     * Extract namespace from biome key (e.g., "terra" from "terra:void_ending")
     */
    private String extractNamespace(String biomeKey) {
        int colonIndex = biomeKey.indexOf(':');
        if (colonIndex > 0) {
            return biomeKey.substring(0, colonIndex);
        }
        return "";
    }

    /**
     * Return dimension-appropriate default biome (plains/nether_wastes/the_end)
     */
    private String getDimensionDefault() {
        return switch (dimension) {
            case NORMAL -> "minecraft:plains";
            case NETHER -> "minecraft:nether_wastes";
            case THE_END -> "minecraft:the_end";
            default -> "minecraft:plains";
        };
    }
    
    public static class ProposedReplacement {
        public final String biome;
        public final String reason;
        
        public ProposedReplacement(String biome, String reason) {
            this.biome = biome;
            this.reason = reason;
        }
    }
}
