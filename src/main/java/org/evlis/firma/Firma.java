package org.evlis.firma;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.evlis.firma.NMS.NMSInjectListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public final class Firma extends JavaPlugin {
    private final Map<String, FirmaChunkGenerator> generatorMap = new HashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("Firma world generator initializing...");
        // Register the NMS injection listener
        Bukkit.getPluginManager().registerEvents(new NMSInjectListener(this), this);
        getLogger().info("Firma injection listener registered.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Firma shutting down...");
    }

    /**
     * Called by Bukkit when a world is created with generator: Firma
     */
    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        getLogger().info("Creating Firma generator for world: " + worldName + " with id: " + id);
        return generatorMap.computeIfAbsent(worldName, name -> new FirmaChunkGenerator(this));
    }

    public boolean isFirmaWorld(World world) {
        return generatorMap.containsKey(world.getName());
    }
}
