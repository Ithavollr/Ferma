package org.evlis.firma;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.evlis.firma.NMS.NMSInjectListener;
import org.evlis.firma.pack.FirmaPack;
import org.evlis.firma.pack.PackLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public final class Firma extends JavaPlugin {
    // Thread-safe map for concurrent world initialization
    private final Map<String, FirmaChunkGenerator> generatorMap = new ConcurrentHashMap<>();
    // Loaded packs (id -> pack)
    private Map<String, FirmaPack> packs = Map.of();
    // Pack loader
    private PackLoader packLoader;

    @Override
    public void onEnable() {
        getLogger().info("Firma world generator initializing...");
        
        // Load packs
        packLoader = new PackLoader(this);
        packs = packLoader.loadAll();
        getLogger().info("Loaded " + packs.size() + " pack(s)");
        
        // Register the NMS injection listener
        Bukkit.getPluginManager().registerEvents(new NMSInjectListener(this), this);
        getLogger().info("Firma injection listener registered.");
    }
    
    /**
     * Get a loaded pack by id.
     */
    public @Nullable FirmaPack getPack(String id) {
        return packs.get(id);
    }
    
    /**
     * Check if a pack id exists.
     */
    public boolean hasPack(String id) {
        return packs.containsKey(id);
    }

    @Override
    public void onDisable() {
        getLogger().info("Firma shutting down...");
    }

    /**
     * Called by Bukkit when a world is created with generator: Firma
     *
     * The id parameter can specify the generation mode:
     *   - "vanilla" or null: Faithful vanilla pass-through (Stage 1, default)
     *   - "void": Void/empty chunks (Stage 3)
     *   - Any pack id: Pack-based configuration (Stage 4)
     *
     * Legacy "noise" id is no longer supported - use a pack instead.
     *
     * Example bukkit.yml:
     *   worlds:
     *     my_world:
     *       generator: Firma:passthrough
     */
    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        getLogger().info("Creating Firma generator for world: " + worldName + " with id: " + id);
        
        // Reject legacy "noise" id completely
        if ("noise".equalsIgnoreCase(id)) {
            getLogger().severe("ERROR: 'Firma:noise' is no longer supported.");
            getLogger().severe("Use 'Firma:passthrough' for vanilla climate, 'Firma:void' for empty worlds,");
            getLogger().severe("or create a custom pack in plugins/Firma/packs/");
            throw new IllegalArgumentException("Legacy 'Firma:noise' generator is no longer supported. " +
                "Use 'Firma:passthrough' or a custom pack instead.");
        }
        
        // Check if id is a pack id
        if (id != null && !id.isEmpty() && !isReservedName(id) && !hasPack(id)) {
            getLogger().warning("Unknown pack id '" + id + "' for world: " + worldName);
            getLogger().warning("Defaulting to vanilla mode");
            id = null;
        }
        
        final String finalId = id;
        return generatorMap.computeIfAbsent(worldName, name -> new FirmaChunkGenerator(this, finalId));
    }
    
    /**
     * Check if a generator id is a reserved name.
     */
    private boolean isReservedName(String id) {
        return id.equals("vanilla") || id.equals("void");
    }

    public boolean isFirmaWorld(World world) {
        return generatorMap.containsKey(world.getName());
    }
}
