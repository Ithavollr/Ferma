package org.evlis.firma.pack;

import org.evlis.firma.Firma;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            
            // Extract metadata
            String id = (String) data.getOrDefault("id", packId);
            String name = (String) data.getOrDefault("name", packId);
            String description = (String) data.getOrDefault("description", "");
            
            // Validate id matches directory
            if (!id.equals(packId)) {
                logger.warning("Pack id '" + id + "' does not match directory name '" + packId + "', using directory name");
                id = packId;
            }
            
            // Parse climate section
            Map<String, ClimateFunctionConfig> climate = new HashMap<>();
            Object climateObj = data.get("climate");
            if (climateObj instanceof Map) {
                Map<String, Object> climateMap = (Map<String, Object>) climateObj;
                for (Map.Entry<String, Object> entry : climateMap.entrySet()) {
                    String param = entry.getKey();
                    Object configObj = entry.getValue();
                    
                    if (configObj instanceof Map) {
                        try {
                            ClimateFunctionConfig config = parseClimateConfig((Map<String, Object>) configObj);
                            climate.put(param, config);
                        } catch (Exception e) {
                            logger.warning("Failed to parse climate config for '" + param + "' in pack '" + packId + "': " + e.getMessage());
                        }
                    }
                }
            }
            
            return new FirmaPack(id, name, description, climate);
        }
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
                yield new ClimateFunctionConfig.DoublePerlin(firstOctave, amplitudes, xzScale, yScale);
            }
            case "shifted_noise" -> {
                // Parse inner noise config
                Map<String, Object> innerMap = (Map<String, Object>) config.get("inner");
                ClimateFunctionConfig inner = innerMap != null ? parseClimateConfig(innerMap) : 
                    new ClimateFunctionConfig.DoublePerlin(-7, List.of(1.0, 1.0), 0.25, 0.0);
                
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
            case "y_clamped_gradient" -> {
                int minY = ((Number) config.getOrDefault("min_y", -64)).intValue();
                int maxY = ((Number) config.getOrDefault("max_y", 320)).intValue();
                yield new ClimateFunctionConfig.YClampedGradient(minY, maxY);
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
