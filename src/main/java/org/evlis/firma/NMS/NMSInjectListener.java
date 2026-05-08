package org.evlis.firma.NMS;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.evlis.firma.Firma;
import org.evlis.firma.FirmaChunkGenerator;
import org.evlis.firma.FirmaChunkGenerator.GenerationMode;
import org.evlis.firma.Reflection;
import org.evlis.firma.noise.FirmaNoiseRouter;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for WorldInitEvent and injects our NMSChunkGeneratorDelegate into the world's chunk generation pipeline.
 * This is the core injection mechanism that allows us to intercept and potentially modify vanilla generation.
 */
public class NMSInjectListener implements Listener {
    private final Firma plugin;
    // Thread-safe set for tracking injected worlds (concurrent world initialization)
    private final Set<World> injectedWorlds = ConcurrentHashMap.newKeySet();

    public NMSInjectListener(Firma plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        World world = event.getWorld();

        // Check if this world is using our generator
        if (!(world.getGenerator() instanceof FirmaChunkGenerator firmaGenerator)) {
            return; // Not a Firma world, skip
        }

        // Prevent duplicate injection (thread-safe)
        if (!injectedWorlds.add(world)) {
            return; // Already injected
        }

        try {
            plugin.getLogger().info("Injecting Firma into world: " + world.getName());

            // Get the NMS ServerLevel from CraftWorld
            CraftWorld craftWorld = (CraftWorld) world;
            ServerLevel serverWorld = craftWorld.getHandle();

            // Get the current chunk generator (may be CustomChunkGenerator wrapper)
            ChunkGenerator currentGenerator = serverWorld.getChunkSource().getGenerator();
            plugin.getLogger().info("Captured generator: " + currentGenerator.getClass().getName());

            // If it's CustomChunkGenerator, unwrap to get the real vanilla generator
            ChunkGenerator vanillaGenerator = unwrapToVanilla(currentGenerator);
            plugin.getLogger().info("Unwrapped to vanilla: " + vanillaGenerator.getClass().getName());
            
            // Check generation mode
            GenerationMode mode = firmaGenerator.getMode();
            plugin.getLogger().info("Generation mode: " + mode);
            
            // For NOISE_OVERRIDE mode, patch the NoiseRouter
            if (mode == GenerationMode.NOISE_OVERRIDE) {
                if (!(vanillaGenerator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
                    throw new IllegalStateException("NOISE_OVERRIDE mode requires NoiseBasedChunkGenerator, got: " + vanillaGenerator.getClass().getName());
                }
                
                // Patch the NoiseRouter in the generator's settings
                try {
                    // Get the settings from the generator
                    var settings = noiseGenerator.settings.value();
                    
                    // Get the vanilla router from settings
                    NoiseRouter vanillaRouter = settings.noiseRouter();
                    
                    // Patch the climate functions
                    NoiseRouter patchedRouter = FirmaNoiseRouter.patchClimateFunctions(
                        vanillaRouter, null, serverWorld.getSeed(), 
                        FirmaNoiseRouter.PatchMode.VANILLA_NOISE // Use actual noise implementations
                    );
                    
                    // Create new settings with patched router (NoiseGeneratorSettings is a record - immutable)
                    var newSettings = new net.minecraft.world.level.levelgen.NoiseGeneratorSettings(
                        settings.noiseSettings(),
                        settings.defaultBlock(),
                        settings.defaultFluid(),
                        patchedRouter,
                        settings.surfaceRule(),
                        settings.spawnTarget(),
                        settings.seaLevel(),
                        settings.disableMobGeneration(),
                        settings.isAquifersEnabled(),
                        settings.oreVeinsEnabled(),
                        settings.useLegacyRandomSource()
                    );
                    
                    // Replace the settings in the generator
                    java.lang.reflect.Field settingsField = net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.class.getDeclaredField("settings");
                    settingsField.setAccessible(true);
                    settingsField.set(noiseGenerator, net.minecraft.core.Holder.direct(newSettings));
                    
                    plugin.getLogger().info("Patched NoiseRouter in NoiseGeneratorSettings");
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to patch NoiseRouter via reflection: " + e.getMessage());
                    e.printStackTrace();
                    // Continue with vanilla generation if patching fails
                }
            }

            // Get the ChunkMap and its WorldGenContext
            ChunkMap chunkMap = serverWorld.getChunkSource().chunkMap;
            WorldGenContext worldGenContext = Reflection.CHUNKMAP.getWorldGenContext(chunkMap);

            // Create our delegate that wraps the (possibly patched) generator
            // For VOID mode, pass true to create empty chunks
            boolean isVoidMode = (mode == GenerationMode.VOID);
            NMSChunkGeneratorDelegate delegate = new NMSChunkGeneratorDelegate(vanillaGenerator, isVoidMode);
            
            if (isVoidMode) {
                plugin.getLogger().info("VOID mode - will generate empty chunks for world: " + world.getName());
            }

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
        }
    }

    /**
     * Unwrap CustomChunkGenerator to get the actual vanilla NMS generator.
     * CustomChunkGenerator is Paper's wrapper that bridges Bukkit API to NMS.
     * We need the real vanilla generator inside it.
     *
     * Stage 1 Requirement: Must successfully unwrap to get NoiseBasedChunkGenerator
     * for faithful vanilla generation. If unwrapping fails, we cannot proceed.
     */
    private ChunkGenerator unwrapToVanilla(ChunkGenerator generator) throws IllegalStateException {
        // If it's not CustomChunkGenerator, this is unexpected - fail loudly
        if (!generator.getClass().getName().equals("org.bukkit.craftbukkit.generator.CustomChunkGenerator")) {
            throw new IllegalStateException(
                "Expected CustomChunkGenerator but got: " + generator.getClass().getName() +
                ". This indicates an unexpected server configuration."
            );
        }

        try {
            // CustomChunkGenerator has a 'delegate' field holding the real vanilla generator
            Field delegateField = generator.getClass().getDeclaredField("delegate");
            delegateField.setAccessible(true);
            ChunkGenerator delegate = (ChunkGenerator) delegateField.get(generator);

            if (delegate != null) {
                plugin.getLogger().info("Extracted delegate: " + delegate.getClass().getName());
                return delegate;
            } else {
                throw new IllegalStateException("CustomChunkGenerator delegate field is null");
            }
        } catch (NoSuchFieldException e) {
            // Try alternative field names
            try {
                Field[] fields = generator.getClass().getDeclaredFields();
                for (Field field : fields) {
                    if (ChunkGenerator.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        ChunkGenerator value = (ChunkGenerator) field.get(generator);
                        if (value != null && value != generator) {
                            plugin.getLogger().info("Found ChunkGenerator field '" + field.getName() + "': " + value.getClass().getName());
                            return value;
                        }
                    }
                }
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("Could not access ChunkGenerator fields", ex);
            }
            throw new IllegalStateException("Could not find delegate field in CustomChunkGenerator", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not access delegate field", e);
        }
    }
}
