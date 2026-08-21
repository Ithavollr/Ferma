package org.evlis.firma.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.evlis.firma.utils.chunk.fixup.ScanTask;

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
    @Description("Scan a world for invalid biome palette entries")
    @Syntax("<world> <radius> [--verbose]")
    public void onScan(CommandSender sender, String worldName, String radiusStr, @Default("") String flags) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§cWorld not found: " + worldName);
            return;
        }
        
        int radius;
        try {
            radius = Integer.parseInt(radiusStr);
        } catch (NumberFormatException e) {
            int worldBorderRadius = (int) (world.getWorldBorder().getSize() / 2.0 / 16.0);
            sender.sendMessage("§cInvalid radius. Please specify a radius in chunks.");
            sender.sendMessage(String.format("§7World border radius: §f%d §7chunks (§f%d §7blocks)", 
                worldBorderRadius, worldBorderRadius * 16));
            sender.sendMessage("§7Example: §f/ferma scan " + worldName + " 500");
            return;
        }
        
        if (radius <= 0) {
            sender.sendMessage("§cRadius must be positive.");
            return;
        }
        
        if (currentScanTask.get() != null && !currentScanTask.get().isStopped()) {
            sender.sendMessage("§cA scan is already running. Use /ferma cancel to stop it.");
            return;
        }
        
        boolean verbose = flags.contains("--verbose");
        
        sender.sendMessage(String.format("§7Scan radius: §f%d §7chunks (§f%d §7blocks)", radius, radius * 16));
        
        ScanTask task = new ScanTask(plugin, world, sender, radius, verbose);
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
