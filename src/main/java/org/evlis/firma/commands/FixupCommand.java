package org.evlis.firma.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.evlis.firma.utils.chunk.fixup.BiomeValidator;
import org.evlis.firma.utils.chunk.fixup.ScanTask;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@CommandAlias("ferma")
public class FixupCommand extends BaseCommand {
    private final Plugin plugin;
    private final AtomicReference<ScanTask> currentScanTask = new AtomicReference<>(null);
    
    public FixupCommand(Plugin plugin) {
        this.plugin = plugin;
    }
    
    @Subcommand("scan")
    @CommandPermission("ferma.command.scan")
    @CommandCompletion("@worlds")
    @Description("Scan a world's generated chunks for invalid biome palette entries")
    @Syntax("<world> [--verbose]")
    public void onScan(CommandSender sender, String worldName, @Default("") String flags) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§cWorld not found: " + worldName);
            return;
        }

        if (currentScanTask.get() != null && !currentScanTask.get().isStopped()) {
            sender.sendMessage("§cA scan is already running. Use /ferma cancel to stop it.");
            return;
        }

        boolean verbose = flags.contains("--verbose");

        ScanTask task = new ScanTask(plugin, world, sender, verbose);
        currentScanTask.set(task);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Subcommand("fix")
    @CommandPermission("ferma.command.fix")
    @CommandCompletion("@worlds")
    @Description("Apply the reviewed biome replacements from a world's scan report")
    @Syntax("<world>")
    public void onFix(CommandSender sender, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§cWorld not found: " + worldName);
            return;
        }

        if (currentScanTask.get() != null && !currentScanTask.get().isStopped()) {
            sender.sendMessage("§cA scan is already running. Use /ferma cancel to stop it.");
            return;
        }

        java.io.File reportFile = new java.io.File(plugin.getDataFolder(), "fixup/" + world.getName() + "-scan.yml");
        if (!reportFile.exists()) {
            sender.sendMessage("§cNo scan report for " + world.getName() + ". Run /ferma scan " + worldName + " first.");
            return;
        }

        Map<String, Object> data;
        try (java.io.FileReader reader = new java.io.FileReader(reportFile)) {
            data = new org.yaml.snakeyaml.Yaml().load(reader);
        } catch (Exception e) {
            sender.sendMessage("§cFailed to read scan report: " + e.getMessage());
            return;
        }

        if (!world.getName().equals(data.get("world"))) {
            sender.sendMessage("§cReport is for world '" + data.get("world") + "', not " + world.getName() + ".");
            return;
        }
        if (!Boolean.TRUE.equals(data.get("reviewed"))) {
            sender.sendMessage("§cReport is not reviewed. Inspect " + reportFile.getPath()
                + "§c, adjust proposed_replacement values if needed, then set reviewed: true.");
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> invalidBiomes = (Map<String, Map<String, Object>>) data.get("invalid_biomes");
        if (invalidBiomes == null || invalidBiomes.isEmpty()) {
            sender.sendMessage("§7Report lists no invalid biomes; nothing to fix.");
            return;
        }

        BiomeValidator biomeValidator = new BiomeValidator();
        Map<String, String> replacements = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : invalidBiomes.entrySet()) {
            String target = String.valueOf(entry.getValue().get("proposed_replacement"));
            if (!biomeValidator.isValid(target)) {
                sender.sendMessage("§cReplacement for " + entry.getKey() + " is not a registered biome: "
                    + target + "§c. Fix the report and try again. Nothing was changed.");
                return;
            }
            replacements.put(entry.getKey(), target);
        }

        sender.sendMessage("§7Applying §f" + replacements.size() + "§7 reviewed replacement(s):");
        replacements.forEach((from, to) -> sender.sendMessage("§7  §f" + from + " §7-> §f" + to));

        ScanTask task = new ScanTask(plugin, world, sender, true, replacements);
        currentScanTask.set(task);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }
    
    @Subcommand("biomelook")
    @CommandPermission("ferma.command.biomelook")
    @Description("Show the registry key of the biome at your position or at the given block coords")
    @Syntax("[x y z]")
    public void onBiomeLook(org.bukkit.entity.Player player, @Default("") String xStr, @Default("") String yStr, @Default("") String zStr) {
        int x, y, z;
        if (xStr.isEmpty() && yStr.isEmpty() && zStr.isEmpty()) {
            x = player.getLocation().getBlockX();
            y = player.getLocation().getBlockY();
            z = player.getLocation().getBlockZ();
        } else {
            try {
                x = Integer.parseInt(xStr);
                y = Integer.parseInt(yStr);
                z = Integer.parseInt(zStr);
            } catch (NumberFormatException e) {
                player.sendMessage("§cUsage: /ferma biomelook [x y z] (all three coords, whole numbers)");
                return;
            }
        }

        // refuse rather than look up: a biome query must never trigger a chunk load
        if (!player.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
            player.sendMessage(String.format("§cChunk (%d, %d) is not loaded; not looking it up. Move closer to it.", x >> 4, z >> 4));
            return;
        }

        String key = ((org.bukkit.craftbukkit.CraftWorld) player.getWorld()).getHandle()
            .getBiome(new net.minecraft.core.BlockPos(x, y, z))
            .unwrapKey()
            .map(k -> k.location().toString())
            .orElse("(unregistered inline biome)");
        player.sendMessage(String.format("§7Biome at §f%d %d %d§7: §f%s", x, y, z, key));
    }

    // TODO: /ferma biomelook's counterpart "biomeswap <from> <to> [radius?]" — targeted biome replacement (the fixer)

    @Subcommand("status")
    @CommandPermission("ferma.command.status")
    @Description("Show progress of running scan/fix task")
    public void onStatus(CommandSender sender) {
        ScanTask task = currentScanTask.get();
        if (task == null || task.isStopped()) {
            sender.sendMessage("§7No scan is currently running.");
        } else {
            sender.sendMessage("§a§lScan Status:");
            sender.sendMessage(String.format("§7Progress: §f%.1f%%", task.getPercentComplete()));
            sender.sendMessage(String.format("§7Chunks: §f%d§7/§f%d", 
                task.getChunksProcessed(), task.getTotalChunks()));
        }
    }
    
    @Subcommand("cancel")
    @CommandPermission("ferma.command.cancel")
    @Description("Cancel running scan/fix task")
    public void onCancel(CommandSender sender) {
        ScanTask task = currentScanTask.get();
        if (task == null || task.isStopped()) {
            sender.sendMessage("§7No scan is currently running.");
        } else {
            task.stop();
            sender.sendMessage("§aCancelling scan...");
        }
    }
}
