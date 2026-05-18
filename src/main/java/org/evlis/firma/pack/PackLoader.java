package org.evlis.firma.pack;

import org.bukkit.plugin.Plugin;
import org.evlis.firma.Firma;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Loads Firma packs from plugins/Firma/packs/[pack_id]/pack.yml
 */
public class PackLoader {
    
    private final Firma plugin;
    private final Logger logger;
    private final Yaml yaml;
    
    public PackLoader(Firma plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.yaml = new Yaml();
    }
    
    /**
     * Load all packs from the packs directory.
     * Returns a map of pack id to FirmaPack.
     */
    public Map<String, FirmaPack> loadAll() {
        Map<String, FirmaPack> packs = new HashMap<>();
        
        File packsDir = new File(plugin.getDataFolder(), "packs");
        if (!packsDir.exists()) {
            logger.info("Packs directory does not exist, creating: " + packsDir.getPath());
            packsDir.mkdirs();
            
            // Extract default packs from jar resources
            extractDefaultPacks(packsDir);
        }
        
        if (!packsDir.isDirectory()) {
            logger.warning("Packs path is not a directory: " + packsDir.getPath());
            return packs;
        }
        
        File[] packDirs = packsDir.listFiles(File::isDirectory);
        if (packDirs == null || packDirs.length == 0) {
            logger.info("No packs found in: " + packsDir.getPath());
            return packs;
        }
        
        for (File packDir : packDirs) {
            String packId = packDir.getName();
            File packYml = new File(packDir, "pack.yml");
            
            if (!packYml.exists()) {
                logger.warning("Pack directory missing pack.yml: " + packDir.getPath());
                continue;
            }
            
            try {
                FirmaPack pack = loadPack(packId, packYml);
                if (pack != null) {
                    packs.put(packId, pack);
                    logger.info("Loaded pack: " + packId + " (" + pack.name() + ")");
                }
            } catch (Exception e) {
                logger.warning("Failed to load pack '" + packId + "': " + e.getMessage());
            }
        }
        
        return packs;
    }
    
    /**
     * Load a single pack from its pack.yml file.
     */
    @SuppressWarnings("unchecked")
    private FirmaPack loadPack(String packId, File packYml) throws IOException {
        try (FileInputStream fis = new FileInputStream(packYml)) {
            Map<String, Object> data = yaml.load(fis);
            
            if (data == null) {
                logger.warning("Empty pack.yml: " + packYml.getPath());
                return null;
            }
            
            // Validate schema version first
            Object schemaVersionObj = data.get("schema_version");
            if (schemaVersionObj == null) {
                throw new IllegalArgumentException("Pack '" + packId + "' is missing required 'schema_version' field");
            }
            if (!(schemaVersionObj instanceof Integer)) {
                throw new IllegalArgumentException("Pack '" + packId + "' has invalid schema_version (must be an integer): " + schemaVersionObj);
            }
            int schemaVersion = (Integer) schemaVersionObj;
            if (schemaVersion != 1) {
                throw new IllegalArgumentException("Pack '" + packId + "' has unsupported schema_version: " + schemaVersion + " (only version 1 is supported)");
            }
            
            // Extract metadata
            String id = (String) data.getOrDefault("id", packId);
            String name = (String) data.getOrDefault("name", packId);
            String description = (String) data.getOrDefault("description", "");
            
            // Validate id matches directory
            if (!id.equals(packId)) {
                logger.warning("Pack id '" + id + "' does not match directory name '" + packId + "', using directory name");
                id = packId;
            }
            
            // Validate required type field
            Object typeObj = data.get("type");
            if (typeObj == null) {
                throw new IllegalArgumentException("Pack '" + packId + "' is missing required 'type' field (must be 'noise' or 'void')");
            }
            String type = typeObj.toString();
            if (!"noise".equals(type) && !"void".equals(type)) {
                throw new IllegalArgumentException("Pack '" + packId + "' has unknown type: '" + type + "' (must be 'noise' or 'void')");
            }

            VoidPalette voidPalette = null;
            Map<String, ClimateFunctionConfig> climate = new HashMap<>();

            if ("void".equals(type)) {
                // Parse void palette
                Object paletteObj = data.get("palette");
                if (paletteObj instanceof Map) {
                    Map<String, Object> paletteMap = (Map<String, Object>) paletteObj;
                    voidPalette = parseVoidPalette(paletteMap, packId);
                } else if (paletteObj != null) {
                    logger.warning("Pack '" + packId + "' has type: void but palette is not a map - ignoring");
                }
                // Void packs don't have climate section
            } else {
                // Parse climate section for normal packs
                Object climateObj = data.get("climate");
                if (climateObj instanceof Map) {
                    Map<String, Object> climateMap = (Map<String, Object>) climateObj;
                    // Valid climate parameters (depth is not configurable - it's always derived)
                    Set<String> validParams = Set.of("temperature", "humidity", "continentalness", "erosion", "weirdness");

                    for (Map.Entry<String, Object> entry : climateMap.entrySet()) {
                        String param = entry.getKey();
                        Object configObj = entry.getValue();

                        // Validate parameter name
                        if (!validParams.contains(param)) {
                            throw new IllegalArgumentException("Unknown climate parameter '" + param + "' in pack '" + packId + "'. Valid parameters are: " + validParams);
                        }

                        if (configObj instanceof Map) {
                            try {
                                ClimateFunctionConfig config = parseClimateConfig((Map<String, Object>) configObj);
                                climate.put(param, config);
                            } catch (Exception e) {
                                throw new IllegalArgumentException("Failed to parse climate config for '" + param + "' in pack '" + packId + "': " + e.getMessage(), e);
                            }
                        }
                    }
                }
            }

            return new FirmaPack(schemaVersion, id, name, description, type, climate, voidPalette);
        }
    }
    
    /**
     * Parse a void palette from YAML map.
     * Keys are "[x, y, z]" strings, values are block resource locations.
     */
    private VoidPalette parseVoidPalette(Map<String, Object> paletteMap, String packId) {
        List<PaletteEntry> entries = new ArrayList<>();

        for (Map.Entry<String, Object> entry : paletteMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Parse the key as [x, y, z]
            int[] coords = parseCoordinateKey(key, packId);
            if (coords == null) {
                logger.warning("Pack '" + packId + "' has invalid palette key: '" + key + "' - skipping");
                continue;
            }

            int x = coords[0];
            int y = coords[1];
            int z = coords[2];

            // Validate y coordinate
            if (y < -64 || y > 320) {
                logger.warning("Pack '" + packId + "' has palette entry with y=" + y + " outside valid range [-64, 320] - skipping");
                continue;
            }

            // Parse the block ID
            String blockId = value instanceof String ? (String) value : null;
            if (blockId == null || !isValidBlockId(blockId)) {
                logger.warning("Pack '" + packId + "' has invalid block id: '" + value + "' for key '" + key + "' - skipping");
                continue;
            }

            entries.add(new PaletteEntry(x, y, z, blockId));
        }

        return VoidPalette.fromEntries(entries);
    }

    /**
     * Parse a coordinate key in the format "[x, y, z]".
     * Returns int[3] with {x, y, z} or null if invalid.
     */
    private int[] parseCoordinateKey(String key, String packId) {
        // Remove brackets and whitespace
        String trimmed = key.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return null;
        }

        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        String[] parts = inner.split(",");

        if (parts.length != 3) {
            return null;
        }

        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            return new int[]{x, y, z};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Validate a block ID matches the expected resource location format.
     * Format: [a-z0-9_]+:[a-z0-9_/]+
     */
    private boolean isValidBlockId(String blockId) {
        return blockId != null && blockId.matches("^[a-z0-9_]+:[a-z0-9_/]+$");
    }

    /**
     * Parse a climate function configuration from YAML map.
     */
    @SuppressWarnings("unchecked")
    private ClimateFunctionConfig parseClimateConfig(Map<String, Object> config) {
        String type = (String) config.get("type");
        if (type == null) {
            throw new IllegalArgumentException("Missing 'type' field");
        }
        
        return switch (type) {
            case "constant" -> {
                double value = ((Number) config.getOrDefault("value", 0.0)).doubleValue();
                // Clamp to [-1, 1]
                value = Math.max(-1.0, Math.min(1.0, value));
                yield new ClimateFunctionConfig.Constant(value);
            }
            case "identity" -> new ClimateFunctionConfig.Identity();
            case "perlin" -> {
                double xzScale = ((Number) config.getOrDefault("xz_scale", 1.0)).doubleValue();
                double yScale = ((Number) config.getOrDefault("y_scale", 1.0)).doubleValue();
                yield new ClimateFunctionConfig.Perlin(xzScale, yScale);
            }
            case "octave_perlin" -> {
                int firstOctave = ((Number) config.getOrDefault("first_octave", 0)).intValue();
                List<Double> amplitudes = parseAmplitudes(config.get("amplitudes"));
                double xzScale = ((Number) config.getOrDefault("xz_scale", 1.0)).doubleValue();
                double yScale = ((Number) config.getOrDefault("y_scale", 1.0)).doubleValue();
                yield new ClimateFunctionConfig.OctavePerlin(firstOctave, amplitudes, xzScale, yScale);
            }
            case "double_perlin" -> {
                int firstOctave = ((Number) config.getOrDefault("first_octave", 0)).intValue();
                List<Double> amplitudes = parseAmplitudes(config.get("amplitudes"));
                double xzScale = ((Number) config.getOrDefault("xz_scale", 1.0)).doubleValue();
                double yScale = ((Number) config.getOrDefault("y_scale", 1.0)).doubleValue();
                double minValue = ((Number) config.getOrDefault("min_value", -1.0)).doubleValue();
                double maxValue = ((Number) config.getOrDefault("max_value", 1.0)).doubleValue();
                yield new ClimateFunctionConfig.DoublePerlin(firstOctave, amplitudes, xzScale, yScale, minValue, maxValue);
            }
            case "shifted_noise" -> {
                // Parse inner noise config
                Map<String, Object> innerMap = (Map<String, Object>) config.get("inner");
                ClimateFunctionConfig inner = innerMap != null ? parseClimateConfig(innerMap) : 
                    new ClimateFunctionConfig.DoublePerlin(-7, List.of(1.0, 1.0), 0.25, 0.0, -1.0, 1.0);
                
                // Parse shift configs
                Map<String, Object> shiftXMap = (Map<String, Object>) config.get("shift_x");
                ClimateFunctionConfig.ShiftedNoise.ShiftConfig shiftX = parseShiftConfig(shiftXMap, "shift_x");
                
                Map<String, Object> shiftZMap = (Map<String, Object>) config.get("shift_z");
                ClimateFunctionConfig.ShiftedNoise.ShiftConfig shiftZ = parseShiftConfig(shiftZMap, "shift_z");
                
                yield new ClimateFunctionConfig.ShiftedNoise(inner, shiftX, shiftZ);
            }
            case "weirdness_to_ridges" -> {
                Map<String, Object> sourceMap = (Map<String, Object>) config.get("source");
                ClimateFunctionConfig source = sourceMap != null ? parseClimateConfig(sourceMap) :
                    new ClimateFunctionConfig.Identity();
                yield new ClimateFunctionConfig.WeirdnessToRidges(source);
            }
            case "radial_gradient" -> {
                // Bidirectional radial function: startValue + rate * distance, clamped.
                // See RadialGradientClimateFunction for detailed examples (falloff, fall-up, plateau, bullseye).
                double centerX   = ((Number) config.getOrDefault("center_x",   0.0)).doubleValue();
                double centerZ   = ((Number) config.getOrDefault("center_z",   0.0)).doubleValue();
                double startValue = ((Number) config.getOrDefault("start_value", 0.0)).doubleValue();
                double rate      = ((Number) config.getOrDefault("rate",       0.0)).doubleValue();
                // Use extreme defaults so clamping is opt-in; users can set clamp_min/max to -1/1 etc.
                double clampMin  = ((Number) config.getOrDefault("clamp_min", Double.NEGATIVE_INFINITY)).doubleValue();
                double clampMax  = ((Number) config.getOrDefault("clamp_max", Double.POSITIVE_INFINITY)).doubleValue();
                yield new ClimateFunctionConfig.RadialGradient(centerX, centerZ, startValue, rate, clampMin, clampMax);
            }
            default -> throw new IllegalArgumentException("Unknown climate type: " + type);
        };
    }
    
    /**
     * Parse amplitudes list from config.
     */
    @SuppressWarnings("unchecked")
    private List<Double> parseAmplitudes(Object amplitudesObj) {
        if (amplitudesObj == null) {
            return List.of(1.0);
        }
        if (amplitudesObj instanceof List) {
            List<?> list = (List<?>) amplitudesObj;
            return list.stream()
                .map(obj -> ((Number) obj).doubleValue())
                .toList();
        }
        return List.of(1.0);
    }
    
    /**
     * Parse shift config for shifted noise.
     */
    @SuppressWarnings("unchecked")
    private ClimateFunctionConfig.ShiftedNoise.ShiftConfig parseShiftConfig(Map<String, Object> config, String defaultName) {
        if (config == null) {
            // Default shift config
            return new ClimateFunctionConfig.ShiftedNoise.ShiftConfig(defaultName, -3, List.of(1.0, 1.0, 1.0, 0.0));
        }
        
        String type = (String) config.getOrDefault("type", defaultName);
        int firstOctave = ((Number) config.getOrDefault("first_octave", -3)).intValue();
        List<Double> amplitudes = parseAmplitudes(config.get("amplitudes"));
        
        return new ClimateFunctionConfig.ShiftedNoise.ShiftConfig(type, firstOctave, amplitudes);
    }
    
    /**
     * Extract default packs from jar resources to the packs directory.
     */
    private void extractDefaultPacks(File packsDir) {
        String[] defaultPacks = {"passthrough", "frozen_world", "fully_frozen", "custom_noise", "high_mountain"};
        
        for (String packId : defaultPacks) {
            try {
                extractPackFromResources(packId, packsDir);
            } catch (Exception e) {
                logger.warning("Failed to extract pack '" + packId + "' from resources: " + e.getMessage());
            }
        }
    }
    
    /**
     * Extract a single pack from jar resources.
     */
    private void extractPackFromResources(String packId, File packsDir) throws IOException {
        String resourcePath = "/packs/" + packId + "/pack.yml";
        java.io.InputStream is = getClass().getResourceAsStream(resourcePath);
        
        if (is == null) {
            logger.warning("Pack resource not found: " + resourcePath);
            return;
        }
        
        File packDir = new File(packsDir, packId);
        packDir.mkdirs();
        
        File packYml = new File(packDir, "pack.yml");
        java.nio.file.Files.copy(is, packYml.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        is.close();
        
        logger.info("Extracted default pack: " + packId);
    }
}
