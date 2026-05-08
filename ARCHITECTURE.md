# Terra World Generator Architecture

This document describes how Terra registers itself as a world generator and hooks into Minecraft's chunk generation process. This analysis is intended to guide the development of a hybrid plugin that can inject custom values for the 6 critical noise functions (temperature, humidity, continentalness, erosion, weirdness, and depth) while delegating other generation to vanilla.

## Overview

Terra is a **complete world generation replacement**, not a hybrid system. It does NOT inject into vanilla noise parameters - it entirely replaces the chunk generator with its own implementation. Understanding this distinction is critical for any hybrid implementation.

---

## 1. Plugin Registration with Bukkit

### Entry Point: `TerraBukkitPlugin.java`
**File:** `/home/freyja/code/Terra/platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/TerraBukkitPlugin.java`

The plugin extends `JavaPlugin` and overrides the Bukkit world generator API method:

```java
@Override
public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, String id) {
    if(id == null || id.trim().isEmpty()) { return null; }
    return new BukkitChunkGeneratorWrapper(generatorMap.computeIfAbsent(worldName, name -> {
        ConfigPack pack = platform.getConfigRegistry().getByID(id).orElseThrow(
            () -> new IllegalArgumentException("No such config pack \"" + id + "\""));
        return pack.getGeneratorProvider().newInstance(pack);
    }), platform.getRawConfigRegistry().getByID(id).orElseThrow(), platform.getWorldHandle().air());
}
```

**Key Points:**
- Bukkit calls `getDefaultWorldGenerator(worldName, generatorID)` when a world is created with `generator: Terra:<pack_id>`
- Returns a `BukkitChunkGeneratorWrapper` that wraps Terra's internal `ChunkGenerator`
- The `generatorID` maps to a `ConfigPack` which defines all generation settings

---

## 2. Bukkit Chunk Generator Wrapper

### File: `/home/freyja/code/Terra/platforms/bukkit/common/src/main/java/com/dfsek/terra/bukkit/generator/BukkitChunkGeneratorWrapper.java`

This class adapts Terra's internal `ChunkGenerator` interface to Bukkit's `org.bukkit.generator.ChunkGenerator`:

```java
public class BukkitChunkGeneratorWrapper extends org.bukkit.generator.ChunkGenerator implements GeneratorWrapper {
    private ChunkGenerator delegate;  // Terra's internal generator
    private ConfigPack pack;          // Generation configuration
    
    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, 
                              int x, int z, @NotNull ChunkData chunkData) {
        BukkitWorldProperties properties = new BukkitWorldProperties(worldInfo);
        delegate.generateChunkData(new BukkitProtoChunk(chunkData), properties, 
                                  pack.getBiomeProvider(), x, z);
    }
    
    @Override
    public @Nullable BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new BukkitBiomeProvider(pack.getBiomeProvider());
    }
}
```

**Key Methods:**
- `generateNoise()` - Called by Bukkit to generate chunk terrain. Delegates to Terra's noise-based generator.
- `getDefaultBiomeProvider()` - Returns Terra's biome provider
- `shouldGenerateCaves()` - Returns `false` (Terra handles its own caves)
- `shouldGenerateDecorations()` - Returns `true` (delegates to vanilla)
- `shouldGenerateMobs()` - Returns `true` (delegates to vanilla)
- `shouldGenerateStructures()` - Returns `true` (delegates to vanilla)

---

## 3. NMS Injection - The Critical Hook

### File: `/home/freyja/code/Terra/platforms/bukkit/nms/v1_21_3/src/main/java/com/dfsek/terra/bukkit/nms/v1_21_3/NMSInjectListener.java`

**This is Terra's core injection mechanism.** It uses Bukkit's `WorldInitEvent` to inject into the NMS (net.minecraft.server) internals:

```java
@EventHandler
public void onWorldInit(WorldInitEvent event) {
    if(!INJECTED.contains(event.getWorld()) &&
       event.getWorld().getGenerator() instanceof BukkitChunkGeneratorWrapper wrapper) {
        
        CraftWorld craftWorld = (CraftWorld) event.getWorld();
        ServerLevel serverWorld = craftWorld.getHandle();
        
        // Get the vanilla chunk generator
        ChunkGenerator vanilla = serverWorld.getChunkSource().getGenerator();
        
        // Create Terra's biome provider wrapper
        NMSBiomeProvider provider = new NMSBiomeProvider(pack.getBiomeProvider(), craftWorld.getSeed());
        
        // Get the chunk map and its WorldGenContext
        ChunkMap chunkMap = serverWorld.getChunkSource().chunkMap;
        WorldGenContext worldGenContext = Reflection.CHUNKMAP.getWorldGenContext(chunkMap);
        
        // CRITICAL: Replace the WorldGenContext's chunk generator
        Reflection.CHUNKMAP.setWorldGenContext(chunkMap, new WorldGenContext(
            worldGenContext.level(),
            new NMSChunkGeneratorDelegate(vanilla, pack, provider, craftWorld.getSeed()),
            worldGenContext.structureManager(),
            worldGenContext.lightEngine(),
            worldGenContext.mainThreadExecutor(),
            worldGenContext.unsavedListener()
        ));
    }
}
```

**How It Works:**
1. When a world initializes, checks if it's using Terra's generator
2. Gets the NMS `ServerLevel` from Bukkit's `CraftWorld`
3. Retrieves the vanilla `ChunkGenerator` (to delegate some operations)
4. Creates an `NMSChunkGeneratorDelegate` that wraps both vanilla and Terra generators
5. Uses reflection to **replace** the `WorldGenContext` in the `ChunkMap` with Terra's delegate

---

## 4. NMS Chunk Generator Delegate

### File: `/home/freyja/code/Terra/platforms/bukkit/nms/v1_21_3/src/main/java/com/dfsek/terra/bukkit/nms/v1_21_3/NMSChunkGeneratorDelegate.java`

This class extends NMS `ChunkGenerator` and overrides key methods to blend Terra generation with vanilla:

```java
public class NMSChunkGeneratorDelegate extends ChunkGenerator {
    private final com.dfsek.terra.api.world.chunk.generation.ChunkGenerator delegate; // Terra
    private final ChunkGenerator vanilla;  // Vanilla
    private final ConfigPack pack;
    
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(@NotNull Blender blender,
                                                        @NotNull RandomState noiseConfig,
                                                        @NotNull StructureManager structureAccessor, 
                                                        @NotNull ChunkAccess chunk) {
        // Delegate to vanilla for initial noise filling
        return vanilla.fillFromNoise(blender, noiseConfig, structureAccessor, chunk)
            .thenApply(c -> {
                // Then apply Terra's modifications (structure beard, etc.)
                if(compatibilityOptions.isBeard()) {
                    beard(structureAccessor, chunk, world, biomeProvider, compatibilityOptions);
                }
                return c;
            });
    }
    
    @Override
    public void applyCarvers(...) { /* no-op - Terra handles carving */ }
    
    @Override
    public void buildSurface(...) { /* no-op - Terra handles surface */ }
    
    @Override
    public void applyBiomeDecoration(...) {
        if(pack.disableStructures()) return;
        vanilla.applyBiomeDecoration(world, chunk, structureAccessor);  // Delegate to vanilla
    }
}
```

**Key Finding:** Terra does NOT modify vanilla's `NoiseBasedChunkGenerator` or its noise parameters. It either:
1. Delegates to vanilla's noise generation (`fillFromNoise`) and then modifies the result
2. Disables vanilla features entirely (`applyCarvers`, `buildSurface`) and handles them in its own generator

---

## 5. Terra's Chunk Generation Implementation

### File: `/home/freyja/code/Terra/common/addons/chunk-generator-noise-3d/src/main/java/com/dfsek/terra/addons/chunkgenerator/generation/NoiseChunkGenerator3D.java`

This is Terra's actual terrain generator:

```java
public class NoiseChunkGenerator3D implements ChunkGenerator {
    private final SamplerProvider samplerCache;  // 3D noise samplers per biome
    
    @Override
    public void generateChunkData(@NotNull ProtoChunk chunk, @NotNull WorldProperties world,
                                  @NotNull BiomeProvider biomeProvider,
                                  int chunkX, int chunkZ) {
        // Get cached noise sampler for this chunk
        Sampler3D sampler = samplerCache.getChunk(chunkX, chunkZ, world, biomeProvider);
        
        // Lazily evaluated interpolator for carver noise
        LazilyEvaluatedInterpolator carver = new LazilyEvaluatedInterpolator(...);
        
        for(int x = 0; x < 16; x++) {
            for(int z = 0; z < 16; z++) {
                int paletteLevel = 0;
                Column<Biome> biomeColumn = biomeProvider.getColumn(cx, cz, world);
                
                for(int y = world.getMaxHeight() - 1; y >= world.getMinHeight(); y--) {
                    Biome biome = biomeColumn.get(y);
                    BiomePaletteInfo paletteInfo = biome.getContext().get(paletteInfoPropertyKey);
                    
                    // Sample 3D noise for this position
                    if(sampler.sample(x, y, z) > 0) {
                        if(carver.sample(x, y, z) <= 0) {
                            // Place block from biome's palette
                            data = paletteAt(x, y, z, sampler, paletteInfo, paletteLevel)
                                .get(paletteLevel, cx, y, cz, seed);
                            chunk.setBlock(x, y, z, data);
                        }
                    } else if(y <= paletteInfo.seaLevel()) {
                        // Place ocean block
                        chunk.setBlock(x, y, z, seaPalette.get(sea - y, x + xOrig, y, z + zOrig, seed));
                    }
                }
            }
        }
    }
}
```

**Key Points:**
- Uses 3D noise samplers per biome (not Minecraft's native noise)
- Each biome defines its own "palette" (block types at different depths)
- Handles carving with a separate noise interpolator
- Sea level is per-biome configurable

---

## 6. Reflection Utilities

### File: `/home/freyja/code/Terra/platforms/bukkit/nms/v1_21_3/src/main/java/com/dfsek/terra/bukkit/nms/v1_21_3/Reflection.java`

Terra uses `reflection-remapper` to access obfuscated NMS fields/methods:

```java
public class Reflection {
    @Proxies(ChunkMap.class)
    public interface ChunkMapProxy {
        @FieldGetter("worldGenContext")
        WorldGenContext getWorldGenContext(ChunkMap instance);
        
        @FieldSetter("worldGenContext")
        void setWorldGenContext(ChunkMap instance, WorldGenContext worldGenContext);
    }
    
    @Proxies(MappedRegistry.class)
    public interface MappedRegistryProxy {
        @FieldSetter("frozen")
        void setFrozen(MappedRegistry<?> instance, boolean frozen);
    }
}
```

---

## 7. Critical Implications for Hybrid Implementation

### What Terra Does NOT Do:

1. **Does NOT inject into vanilla noise functions** - Terra completely replaces the chunk generator with its own noise-based system
3. **Does NOT modify the 6 climate parameters** (temperature, humidity, continentalness, erosion, weirdness, depth) - It ignores them entirely

### What a Hybrid Plugin Would Need to Do:

To create a plugin that only injects the 6 critical noise values while keeping vanilla generation:

1. **Create a custom `NoiseBasedChunkGenerator` subclass** that overrides:
   - `createState()` to provide custom `RandomState` with modified noise
   - The climate/noise functions that feed into biome selection

2. **Use the same injection mechanism** as Terra:
   - Listen to `WorldInitEvent`
   - Access `ServerLevel` via `CraftWorld`
   - Replace the `ChunkMap`'s `WorldGenContext` chunk generator

3. **Key target for injection:**
   ```java
   // In vanilla NoiseBasedChunkGenerator, access the noise router
   NoiseRouter router = randomState.noiseRouter();
   // Override the specific density functions for the 6 parameters
   ```

4. **Alternative approach - Mixin/Transform:**
   - Modify `NoiseRouterData` or `DensityFunction` classes
   - Intercept calls to the 6 specific noise functions
   - Return custom values while preserving the rest of the generation pipeline

### Minimal Hybrid Architecture:

```java
// Conceptual hybrid implementation
public class HybridChunkGenerator extends NoiseBasedChunkGenerator {
    private final CustomNoiseInjector noiseInjector;
    
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(...) {
        // Inject custom noise values into the NoiseRouter
        RandomState modifiedState = noiseInjector.inject(randomState);
        // Continue with vanilla generation using modified state
        return super.fillFromNoise(blender, modifiedState, structureAccessor, chunk);
    }
}
```

---

## Summary

Terra's architecture is **complete replacement**, not injection:
- Registers via Bukkit's `getDefaultWorldGenerator()`
- Wraps Bukkit's `ChunkGenerator` with `BukkitChunkGeneratorWrapper`
- Uses NMS injection via `WorldInitEvent` to replace the `WorldGenContext` generator
- Terra's `NMSChunkGeneratorDelegate` selectively delegates to vanilla or handles features itself
- Biomes are Terra-defined, mapped to NMS biomes via registry manipulation
- Terrain generation uses Terra's own 3D noise system, not Minecraft's

**For a hybrid plugin:** The key is to inject at the `NoiseRouter` or `DensityFunction` level within vanilla's `NoiseBasedChunkGenerator`, rather than replacing the entire generator as Terra does.
