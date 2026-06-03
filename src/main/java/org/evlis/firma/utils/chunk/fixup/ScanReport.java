package org.evlis.firma.utils.chunk.fixup;

import org.bukkit.World;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ScanReport {
    private final String worldName;
    private final World.Environment dimension;
    private final Map<String, InvalidBiomeEntry> invalidBiomes = new ConcurrentHashMap<>();
    private final List<ChunkDetail> chunkDetails = Collections.synchronizedList(new ArrayList<>());
    private int totalChunks = 0;
    private int errorChunks = 0;
    private final boolean verbose;
    
    public ScanReport(String worldName, World.Environment dimension, boolean verbose) {
        this.worldName = worldName;
        this.dimension = dimension;
        this.verbose = verbose;
    }

    /**
     * Record an invalid biome occurrence and increment its count
     */
    public void addInvalidBiome(String biomeKey, String proposedReplacement, String reason) {
        invalidBiomes.computeIfAbsent(biomeKey, k -> 
            new InvalidBiomeEntry(proposedReplacement, reason)
        ).incrementCount();
    }

    /**
     * Add detailed chunk information if verbose mode is enabled
     */
    public void addChunkDetail(int x, int z, Set<String> invalidEntries) {
        if (verbose && !invalidEntries.isEmpty()) {
            errorChunks++;
            String region = String.format("r.%d.%d.mca", x >> 5, z >> 5);
            chunkDetails.add(new ChunkDetail(x, z, region, new ArrayList<>(invalidEntries)));
        }
    }

    /**
     * Increment total chunks scanned counter
     */
    public void incrementTotalChunks() {
        totalChunks++;
    }

    /**
     * Increment chunks with errors counter
     */
    public void incrementErrorChunks() {
        errorChunks++;
    }

    /**
     * Write scan results to YAML file with invalid biomes and optional chunk details
     */
    public void writeToFile(File outputFile) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("world", worldName);
        data.put("dimension", dimension.name());
        data.put("scanned_at", Instant.now().toString());
        data.put("total_chunks", totalChunks);
        data.put("error_chunks", errorChunks);
        
        Map<String, Map<String, Object>> invalidBiomesMap = new LinkedHashMap<>();
        for (Map.Entry<String, InvalidBiomeEntry> entry : invalidBiomes.entrySet()) {
            Map<String, Object> biomeData = new LinkedHashMap<>();
            biomeData.put("count", entry.getValue().count);
            biomeData.put("proposed_replacement", entry.getValue().proposedReplacement);
            biomeData.put("reason", entry.getValue().reason);
            invalidBiomesMap.put(entry.getKey(), biomeData);
        }
        data.put("invalid_biomes", invalidBiomesMap);
        
        if (verbose && !chunkDetails.isEmpty()) {
            List<Map<String, Object>> chunkDetailsList = new ArrayList<>();
            for (ChunkDetail detail : chunkDetails) {
                Map<String, Object> chunkData = new LinkedHashMap<>();
                chunkData.put("x", detail.x);
                chunkData.put("z", detail.z);
                chunkData.put("region", detail.region);
                chunkData.put("invalid_entries", detail.invalidEntries);
                chunkDetailsList.add(chunkData);
            }
            data.put("chunk_details", chunkDetailsList);
        }
        
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);
        
        outputFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(outputFile)) {
            yaml.dump(data, writer);
        }
    }
    
    public int getErrorChunks() {
        return errorChunks;
    }
    
    public int getTotalChunks() {
        return totalChunks;
    }
    
    public Map<String, InvalidBiomeEntry> getInvalidBiomes() {
        return invalidBiomes;
    }
    
    private static class InvalidBiomeEntry {
        String proposedReplacement;
        String reason;
        int count = 0;
        
        InvalidBiomeEntry(String proposedReplacement, String reason) {
            this.proposedReplacement = proposedReplacement;
            this.reason = reason;
        }

        /**
         * Increment occurrence count for this invalid biome
         */
        void incrementCount() {
            count++;
        }
    }
    
    private static class ChunkDetail {
        int x;
        int z;
        String region;
        List<String> invalidEntries;
        
        ChunkDetail(int x, int z, String region, List<String> invalidEntries) {
            this.x = x;
            this.z = z;
            this.region = region;
            this.invalidEntries = invalidEntries;
        }
    }
}
