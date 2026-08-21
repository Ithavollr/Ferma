package org.evlis.firma.utils.chunk.fixup;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class ScanTask implements Runnable {
    private static final int MAX_WORKING_COUNT = 50;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 5000;
    
    private final Plugin plugin;
    private final World world;
    private final CommandSender sender;
    private final int radiusChunks;
    private final boolean verbose;
    private final Logger logger;
    
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicInteger chunksProcessed = new AtomicInteger(0);
    private final AtomicLong lastProgressUpdate = new AtomicLong(0);
    private final AtomicLong startTime = new AtomicLong(0);
    
    private long totalChunks = 0;
    private float percentComplete = 0f;
    private final AtomicLong finishedChunks = new AtomicLong(0);
    
    private final BiomePaletteExtractor extractor = new BiomePaletteExtractor();
    private final BiomeValidator validator = new BiomeValidator();
    private BiomeReplacementProposer proposer;
    private ScanReport report;
    
    public ScanTask(Plugin plugin, World world, CommandSender sender, int radiusChunks, boolean verbose) {
        this.plugin = plugin;
        this.world = world;
        this.sender = sender;
        this.radiusChunks = radiusChunks;
        this.verbose = verbose;
        this.logger = plugin.getLogger();
    }

    /**
     * Main scan loop: iterate chunk coordinates, load generated chunks asynchronously, and extract biome data
     */
    @Override
    public void run() {
        logger.info("Starting biome scan for world: " + world.getName());
        sender.sendMessage("§aStarting biome scan for world: " + world.getName());
        
        proposer = new BiomeReplacementProposer(world.getEnvironment(), plugin);
        report = new ScanReport(world.getName(), world.getEnvironment(), verbose);
        
        startTime.set(System.currentTimeMillis());
        lastProgressUpdate.set(startTime.get());
        
        int centerX = 0;
        int centerZ = 0;
        
        int minX = centerX - radiusChunks;
        int maxX = centerX + radiusChunks;
        int minZ = centerZ - radiusChunks;
        int maxZ = centerZ + radiusChunks;
        
        long diameter = (long) radiusChunks * 2 + 1;
        totalChunks = diameter * diameter;
        
        logger.info(String.format("Scanning chunks from (%d, %d) to (%d, %d)", minX, minZ, maxX, maxZ));
        logger.info("Total area: " + totalChunks + " chunks");
        sender.sendMessage("§7Total area: §f" + totalChunks + " §7chunks");
        
        Semaphore working = new Semaphore(MAX_WORKING_COUNT);
        ServerLevel serverLevel = ((CraftWorld) world).getHandle();

        for (int x = minX; x <= maxX && !stopped.get(); x++) {
            for (int z = minZ; z <= maxZ && !stopped.get(); z++) {
                final int chunkX = x;
                final int chunkZ = z;
                
                try {
                    working.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stop();
                    break;
                }
                
                // Raw NBT read: same path as ChunkLoadTask, stopping before the deserialization
                // that substitutes unknown biomes with the registry default
                MoonriseRegionFileIO.loadDataAsync(serverLevel, chunkX, chunkZ,
                        MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, (chunkData, throwable) -> {
                    try {
                        if (throwable != null) {
                            logger.warning("Failed to read chunk (" + chunkX + ", " + chunkZ + "): " + throwable.getMessage());
                            update(chunkX, chunkZ, false);
                            return;
                        }

                        if (chunkData == null || stopped.get()) {
                            update(chunkX, chunkZ, false);
                            return;
                        }

                        processChunk(serverLevel, chunkData, chunkX, chunkZ);
                        update(chunkX, chunkZ, true);

                    } finally {
                        working.release();
                    }
                }, false);
            }
        }
        
        while (working.availablePermits() < MAX_WORKING_COUNT && !stopped.get()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        if (!stopped.get()) {
            finishScan();
        } else {
            sender.sendMessage("§cScan cancelled.");
            logger.info("Scan cancelled for world: " + world.getName());
        }
        stopped.set(true); // mark not-running so a completed scan doesn't block the next one
    }

    /**
     * Extract biome palette from raw chunk NBT, validate entries, and record invalid biomes in report.
     * Synchronized because Moonrise IO callbacks run concurrently and ScanReport's counters are plain ints.
     * wozniak: serializes all chunk processing behind one lock; if this becomes the bottleneck,
     * make ScanReport's counters atomic instead.
     */
    private synchronized void processChunk(ServerLevel serverLevel, CompoundTag chunkData, int x, int z) {
        try {
            CompoundTag upgraded = serverLevel.getChunkSource().chunkMap.upgradeChunkTag(chunkData, new ChunkPos(x, z));
            Set<String> biomeKeys = extractor.extractBiomeKeys(upgraded);
            Set<String> invalidBiomes = new HashSet<>();
            
            for (String biomeKey : biomeKeys) {
                if (!validator.isValid(biomeKey)) {
                    invalidBiomes.add(biomeKey);
                    
                    BiomeReplacementProposer.ProposedReplacement proposal = proposer.propose(biomeKey);
                    report.addInvalidBiome(biomeKey, proposal.biome, proposal.reason);
                }
            }
            
            report.incrementTotalChunks();
            if (!invalidBiomes.isEmpty()) {
                report.incrementErrorChunks();
                report.addChunkDetail(x, z, invalidBiomes);
            }
            
        } catch (Exception e) {
            logger.warning("Error processing chunk (" + x + ", " + z + "): " + e.getMessage());
        }
    }

    /**
     * Update progress counters and periodically log scan status with ETA
     */
    private synchronized void update(int chunkX, int chunkZ, boolean loaded) {
        if (stopped.get()) {
            return;
        }
        
        long finished = finishedChunks.incrementAndGet();
        percentComplete = 100f * finished / totalChunks;
        
        if (loaded) {
            chunksProcessed.incrementAndGet();
        }
        
        long now = System.currentTimeMillis();
        if (now - lastProgressUpdate.get() >= PROGRESS_UPDATE_INTERVAL_MS) {
            lastProgressUpdate.set(now);
            
            long elapsed = (now - startTime.get()) / 1000;
            double rate = elapsed > 0 ? (double) finished / elapsed : 0;
            long remaining = totalChunks - finished;
            long eta = rate > 0 ? (long) (remaining / rate) : 0;
            
            logger.info(String.format("Scan progress: %.1f%% (%d/%d chunks, %d loaded, %.1f chunks/s, ETA: %s)",
                percentComplete, finished, totalChunks, chunksProcessed.get(), rate, formatTime(eta)));
        }
    }

    /**
     * Format seconds into human-readable time string (e.g., "1h 23m 45s")
     */
    private String formatTime(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else {
            return String.format("%dm %ds", minutes, secs);
        }
    }

    /**
     * Write scan report to file and send completion message to user
     */
    private void finishScan() {
        long duration = (System.currentTimeMillis() - startTime.get()) / 1000;
        
        File outputFile = new File(plugin.getDataFolder(), "fixup/" + world.getName() + "-scan.yml");
        
        try {
            report.writeToFile(outputFile);
            
            sender.sendMessage("§a§lScan complete!");
            sender.sendMessage("§7Total chunks: §f" + report.getTotalChunks());
            sender.sendMessage("§7Chunks with errors: §f" + report.getErrorChunks());
            sender.sendMessage("§7Invalid biomes found: §f" + report.getInvalidBiomes().size());
            sender.sendMessage("§7Duration: §f" + formatTime(duration));
            sender.sendMessage("§7Report saved to: §f" + outputFile.getPath());
            
            logger.info("Scan complete for world: " + world.getName());
            logger.info("Total chunks: " + report.getTotalChunks());
            logger.info("Error chunks: " + report.getErrorChunks());
            logger.info("Invalid biomes: " + report.getInvalidBiomes().size());
            logger.info("Duration: " + formatTime(duration));
            
        } catch (Exception e) {
            sender.sendMessage("§cFailed to write scan report: " + e.getMessage());
            logger.severe("Failed to write scan report: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Signal the scan task to stop gracefully
     */
    public void stop() {
        stopped.set(true);
    }
    
    public boolean isStopped() {
        return stopped.get();
    }
    
    public int getChunksProcessed() {
        return chunksProcessed.get();
    }
    
    public long getTotalChunks() {
        return totalChunks;
    }
    
    public float getPercentComplete() {
        return percentComplete;
    }
}
