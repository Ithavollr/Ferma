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

    @Subcommand("biomeswap")
    @CommandPermission("ferma.command.biomeswap")
    @CommandCompletion("@worlds @biomes @biomes")
    @Description("Replace every occurrence of one biome with another across a world's generated chunks")
    @Syntax("<world> <from> <to>")
    public void onBiomeSwap(CommandSender sender, String worldName, String from, String to) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§cWorld not found: " + worldName);
            return;
        }

        if (currentScanTask.get() != null && !currentScanTask.get().isStopped()) {
            sender.sendMessage("§cA scan is already running. Use /ferma cancel to stop it.");
            return;
        }

        if (from.equals(to)) {
            sender.sendMessage("§cFrom and to are the same biome; nothing to do.");
            return;
        }
        // <from> is a plain string match and may be an unregistered key; <to> must be real
        if (!new BiomeValidator().isValid(to)) {
            sender.sendMessage("§cTarget is not a registered biome: " + to);
            return;
        }

        sender.sendMessage("§7Swapping §f" + from + " §7-> §f" + to + " §7across " + world.getName());

        ScanTask task = new ScanTask(plugin, world, sender, true, Map.of(from, to));
        currentScanTask.set(task);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Subcommand("unload")
    @CommandPermission("ferma.command.unload")
    @CommandCompletion("@worlds")
    @Description("Release a world's spawn chunks (spawn chunk radius -> 0) so fix can reach them")
    @Syntax("<world>")
    public void onUnload(CommandSender sender, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§cWorld not found: " + worldName);
            return;
        }

        Integer previous = world.getGameRuleValue(org.bukkit.GameRule.SPAWN_CHUNK_RADIUS);

        // record the original before touching anything — gamerules persist in level.dat across reboots
        try {
            Map<String, Map<String, Object>> pending = loadRestoreGamerules();
            Map<String, Object> entry = pending.computeIfAbsent(world.getName(), k -> new LinkedHashMap<>());
            // never overwrite a recorded original with an already-zeroed value (double unload)
            entry.putIfAbsent("spawnChunkRadius", previous);
            saveRestoreGamerules(pending);
        } catch (Exception e) {
            sender.sendMessage("§cCould not record the original gamerule value (" + e.getMessage() + "); aborting unload.");
            return;
        }

        world.setGameRule(org.bukkit.GameRule.SPAWN_CHUNK_RADIUS, 0);

        sender.sendMessage(String.format("§7Spawn chunk radius for §f%s§7 set to §f0§7 (was §f%s§7); spawn chunks unload over the next ticks.",
            world.getName(), previous));
        sender.sendMessage(String.format("§7Currently loaded chunks: §f%d§7. Restore afterwards with: §f/ferma restore %s",
            world.getLoadedChunks().length, world.getName()));
        if (!world.getPlayers().isEmpty()) {
            sender.sendMessage("§e" + world.getPlayers().size() + " player(s) are in this world; chunks around them will stay loaded.");
        }
    }

    @Subcommand("restore")
    @CommandPermission("ferma.command.restore")
    @CommandCompletion("@worlds")
    @Description("Restore gamerules recorded by /ferma unload")
    @Syntax("<world>")
    public void onRestore(CommandSender sender, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§cWorld not found: " + worldName);
            return;
        }

        Map<String, Map<String, Object>> pending;
        try {
            pending = loadRestoreGamerules();
        } catch (Exception e) {
            sender.sendMessage("§cCould not read " + restoreGamerulesFile().getPath() + ": " + e.getMessage());
            return;
        }

        Map<String, Object> entry = pending.remove(world.getName());
        if (entry == null) {
            sender.sendMessage("§7No recorded gamerules for " + world.getName() + ".");
            return;
        }

        Object radius = entry.get("spawnChunkRadius");
        if (radius instanceof Number n) {
            world.setGameRule(org.bukkit.GameRule.SPAWN_CHUNK_RADIUS, n.intValue());
            sender.sendMessage("§7Spawn chunk radius for §f" + world.getName() + "§7 restored to §f" + n.intValue() + "§7.");
        }

        try {
            saveRestoreGamerules(pending);
        } catch (Exception e) {
            sender.sendMessage("§cGamerule restored, but failed to update " + restoreGamerulesFile().getPath() + ": " + e.getMessage());
        }
    }

    private java.io.File restoreGamerulesFile() {
        return new java.io.File(plugin.getDataFolder(), "fixup/restore-gamerules.yml");
    }

    /**
     * Load the recorded original gamerule values: world name -> (gamerule -> value)
     */
    private Map<String, Map<String, Object>> loadRestoreGamerules() throws java.io.IOException {
        java.io.File file = restoreGamerulesFile();
        if (!file.exists()) {
            return new LinkedHashMap<>();
        }
        try (java.io.FileReader reader = new java.io.FileReader(file)) {
            Map<String, Map<String, Object>> loaded = new org.yaml.snakeyaml.Yaml().load(reader);
            return loaded != null ? new LinkedHashMap<>(loaded) : new LinkedHashMap<>();
        }
    }

    private void saveRestoreGamerules(Map<String, Map<String, Object>> pending) throws java.io.IOException {
        java.io.File file = restoreGamerulesFile();
        file.getParentFile().mkdirs();
        try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
            new org.yaml.snakeyaml.Yaml().dump(pending, writer);
        }
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
