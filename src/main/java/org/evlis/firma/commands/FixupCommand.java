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
