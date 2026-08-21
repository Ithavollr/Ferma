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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ScanTask implements Runnable {
    private static final int MAX_WORKING_COUNT = 50;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 5000;
    private static final Pattern REGION_FILE = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");
    private static final int MAX_INFLIGHT_WRITES = 64;

    private final Plugin plugin;
    private final World world;
    private final CommandSender sender;
    private final boolean verbose;
    private final Logger logger;
    // null => scan only; non-null => fix mode, mapping invalid key -> reviewed replacement
    private final Map<String, String> replacements;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicInteger chunksProcessed = new AtomicInteger(0);
    private final AtomicLong lastProgressUpdate = new AtomicLong(0);
    private final AtomicLong startTime = new AtomicLong(0);

    private long totalChunks = 0;
    private float percentComplete = 0f;
    private final AtomicLong finishedChunks = new AtomicLong(0);

    // fix mode: mutated tags handed from IO callbacks to the walk thread, which alone talks
    // to the IO scheduler — the loadDataAsync callback contract forbids it (deadlock risk)
    private final ConcurrentLinkedQueue<PendingWrite> pendingWrites = new ConcurrentLinkedQueue<>();
    private final List<String> skippedLoaded = new ArrayList<>(); // walk thread only
    private int fixedChunks = 0; // walk thread only

    private final BiomePaletteExtractor extractor = new BiomePaletteExtractor();
    private final BiomeValidator validator = new BiomeValidator();
    private BiomeReplacementProposer proposer;
    private ScanReport report;

    private record PendingWrite(int x, int z, CompoundTag tag) {}

    public ScanTask(Plugin plugin, World world, CommandSender sender, boolean verbose) {
        this(plugin, world, sender, verbose, null);
    }

    public ScanTask(Plugin plugin, World world, CommandSender sender, boolean verbose, Map<String, String> replacements) {
        this.plugin = plugin;
        this.world = world;
        this.sender = sender;
        this.verbose = verbose;
        this.replacements = replacements;
        this.logger = plugin.getLogger();
    }

    private boolean fixMode() {
        return replacements != null;
    }

    private String mode() {
        return fixMode() ? "fix" : "scan";
    }

    /**
     * Main loop: enumerate generated chunks from region file names, read each chunk's raw NBT
     * asynchronously, and extract/repair biome data. Never loads or generates a chunk.
     */
    @Override
    public void run() {
        logger.info("Starting biome " + mode() + " for world: " + world.getName());
        sender.sendMessage("§aStarting biome " + mode() + " for world: " + world.getName());

        proposer = new BiomeReplacementProposer(world.getEnvironment(), plugin);
        report = new ScanReport(world.getName(), world.getEnvironment(), verbose);

        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        Path regionDir = serverLevel.levelStorageAccess.getDimensionPath(serverLevel.dimension()).resolve("region");

        List<int[]> regions = new ArrayList<>();
        try (Stream<Path> files = Files.list(regionDir)) {
            files.forEach(p -> {
                Matcher m = REGION_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    regions.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
                }
            });
        } catch (IOException e) {
            sender.sendMessage("§cFailed to list region files: " + e.getMessage());
            logger.severe("Failed to list region files in " + regionDir + ": " + e.getMessage());
            stopped.set(true);
            return;
        }

        totalChunks = regions.size() * 1024L;
        startTime.set(System.currentTimeMillis());
        lastProgressUpdate.set(startTime.get());

        logger.info("Region files: " + regions.size() + " (" + totalChunks + " chunk slots)");
        sender.sendMessage("§7Region files: §f" + regions.size() + " §7(§f" + totalChunks + " §7chunk slots)");

        Semaphore working = new Semaphore(MAX_WORKING_COUNT);

        outer:
        for (int[] region : regions) {
            int baseX = region[0] << 5;
            int baseZ = region[1] << 5;
            for (int dx = 0; dx < 32; dx++) {
                for (int dz = 0; dz < 32; dz++) {
                    if (stopped.get()) {
                        break outer;
                    }
                    final int chunkX = baseX + dx;
                    final int chunkZ = baseZ + dz;

                    try {
                        working.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        stop();
                        break outer;
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

                    drainPendingWrites(serverLevel);
                }
            }
        }

        while (working.availablePermits() < MAX_WORKING_COUNT && !stopped.get()) {
            drainPendingWrites(serverLevel);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        drainPendingWrites(serverLevel);

        if (fixMode() && !stopped.get()) {
            // block the walk thread (never an IO thread) until every scheduled save is on disk
            MoonriseRegionFileIO.flush(serverLevel, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA);
        }

        if (!stopped.get()) {
            finishScan();
        } else {
            sender.sendMessage("§c" + (fixMode() ? "Fix" : "Scan") + " cancelled.");
            logger.info(mode() + " cancelled for world: " + world.getName());
        }
        stopped.set(true); // mark not-running so a completed run doesn't block the next one
    }

    /**
     * Extract biome palette from raw chunk NBT, validate entries, and record invalid biomes in report.
     * In fix mode, additionally queue a repaired tag for the walk thread to write.
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

            // the write trigger is "a mapped key is present", not "an invalid key is present" —
            // biomeswap replaces valid-but-wrong biomes too
            if (fixMode() && biomeKeys.stream().anyMatch(replacements::containsKey)) {
                // guard against a region header pointing at the wrong chunk (mirrors ChunkStorage.write's check)
                if (upgraded.getInt("xPos") != x || upgraded.getInt("zPos") != z) {
                    logger.warning(String.format("Chunk (%d, %d) has mismatched stored position (%d, %d); not fixing it",
                        x, z, upgraded.getInt("xPos"), upgraded.getInt("zPos")));
                } else if (extractor.replaceBiomeKeys(upgraded, replacements)) {
                    pendingWrites.add(new PendingWrite(x, z, upgraded));
                }
            }

        } catch (Exception e) {
            logger.warning("Error processing chunk (" + x + ", " + z + "): " + e.getMessage());
        }
    }

    /**
     * Schedule queued fix writes. Runs only on the walk thread: scheduleSave and partialFlush
     * must never be called from an IO completion callback.
     */
    private void drainPendingWrites(ServerLevel serverLevel) {
        if (!fixMode()) {
            return;
        }
        PendingWrite w;
        while ((w = pendingWrites.poll()) != null) {
            if (world.isChunkLoaded(w.x(), w.z())) {
                // a loaded chunk's in-memory copy could later be flushed over our fix; skip and report
                skippedLoaded.add(w.x() + "," + w.z());
                continue;
            }
            MoonriseRegionFileIO.scheduleSave(serverLevel, w.x(), w.z(), w.tag(),
                MoonriseRegionFileIO.RegionFileType.CHUNK_DATA);
            fixedChunks++;
            if (fixedChunks % 256 == 0) {
                // backpressure: don't let thousands of pending tags pile up in the IO queue
                MoonriseRegionFileIO.partialFlush(serverLevel, MAX_INFLIGHT_WRITES);
            }
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
     * Write scan/fix report to file and send completion message to user
     */
    private void finishScan() {
        long duration = (System.currentTimeMillis() - startTime.get()) / 1000;

        File outputFile = new File(plugin.getDataFolder(), "fixup/" + world.getName() + (fixMode() ? "-fix.yml" : "-scan.yml"));

        try {
            if (fixMode()) {
                report.setFixResults(fixedChunks, skippedLoaded);
            }
            report.writeToFile(outputFile);

            sender.sendMessage("§a§l" + (fixMode() ? "Fix" : "Scan") + " complete!");
            sender.sendMessage("§7Total chunks: §f" + report.getTotalChunks());
            sender.sendMessage("§7Chunks with errors: §f" + report.getErrorChunks());
            sender.sendMessage("§7Invalid biomes found: §f" + report.getInvalidBiomes().size());
            if (fixMode()) {
                sender.sendMessage("§7Chunks fixed: §f" + fixedChunks);
                if (!skippedLoaded.isEmpty()) {
                    sender.sendMessage("§eSkipped §f" + skippedLoaded.size() + " §eloaded chunk(s) — restart (or unload them), then re-run fix. Coords are in the report.");
                }
            }
            sender.sendMessage("§7Duration: §f" + formatTime(duration));
            sender.sendMessage("§7Report saved to: §f" + outputFile.getPath());

            logger.info(mode() + " complete for world: " + world.getName());
            logger.info("Total chunks: " + report.getTotalChunks());
            logger.info("Error chunks: " + report.getErrorChunks());
            logger.info("Invalid biomes: " + report.getInvalidBiomes().size());
            if (fixMode()) {
                logger.info("Chunks fixed: " + fixedChunks + ", skipped loaded: " + skippedLoaded.size());
            }
            logger.info("Duration: " + formatTime(duration));

        } catch (Exception e) {
            sender.sendMessage("§cFailed to write " + mode() + " report: " + e.getMessage());
            logger.severe("Failed to write " + mode() + " report: " + e.getMessage());
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
