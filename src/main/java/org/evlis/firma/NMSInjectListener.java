package org.evlis.firma;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Listens for WorldInitEvent and injects our NMSChunkGeneratorDelegate into the world's chunk generation pipeline.
 * This is the core injection mechanism that allows us to intercept and potentially modify vanilla generation.
 */
public class NMSInjectListener implements Listener {
    private final Firma plugin;
    private final Set<World> injectedWorlds = new HashSet<>();
    private final ReentrantLock injectLock = new ReentrantLock();

    public NMSInjectListener(Firma plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        World world = event.getWorld();
        
        // Check if this world is using our generator
        if (!(world.getGenerator() instanceof FirmaChunkGenerator)) {
            return; // Not a Firma world, skip
        }

        // Prevent duplicate injection
        if (injectedWorlds.contains(world)) {
            return;
        }

        injectLock.lock();
        try {
            if (injectedWorlds.contains(world)) {
                return; // Double-check after acquiring lock
            }
            injectedWorlds.add(world);

            plugin.getLogger().info("Injecting Firma into world: " + world.getName());

            // Get the NMS ServerLevel from CraftWorld
            CraftWorld craftWorld = (CraftWorld) world;
            ServerLevel serverWorld = craftWorld.getHandle();

            // Get the current vanilla chunk generator
            ChunkGenerator vanillaGenerator = serverWorld.getChunkSource().getGenerator();
            plugin.getLogger().info("Captured vanilla generator: " + vanillaGenerator.getClass().getName());

            // Get the ChunkMap and its WorldGenContext
            ChunkMap chunkMap = serverWorld.getChunkSource().chunkMap;
            WorldGenContext worldGenContext = Reflection.CHUNKMAP.getWorldGenContext(chunkMap);

            // Create our no-op delegate that wraps the vanilla generator
            NMSChunkGeneratorDelegate delegate = new NMSChunkGeneratorDelegate(vanillaGenerator);

            // Replace the WorldGenContext's generator with our delegate
            WorldGenContext newContext = new WorldGenContext(
                    worldGenContext.level(),
                    delegate,
                    worldGenContext.structureManager(),
                    worldGenContext.lightEngine(),
                    worldGenContext.mainThreadExecutor(),
                    worldGenContext.unsavedListener()
            );

            Reflection.CHUNKMAP.setWorldGenContext(chunkMap, newContext);

            plugin.getLogger().info("Successfully injected Firma into world: " + world.getName());

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to inject Firma into world: " + world.getName());
            e.printStackTrace();
        } finally {
            injectLock.unlock();
        }
    }
}
