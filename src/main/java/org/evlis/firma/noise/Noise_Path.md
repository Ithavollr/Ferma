# Noise Path — Vanilla vs. Firma

A map of every place noise/biomes flow through, what Firma overrides, and why.
All file paths are absolute; line numbers reflect current state at time of writing.

---

## 0. Terminology

- **Un-wired `DensityFunction`**: the form as decoded from data / held by `NoiseGeneratorSettings`. Its leaves are `Holder<NormalNoise.NoiseParameters>` references and `HolderHolder` wrappers — i.e. *recipes* describing which noise parameter file to use, with no actual `NormalNoise` sampler instance attached. Calling `compute()` on this form returns garbage (un-resolved holders) or throws.
- **Wired `DensityFunction`**: the same tree after `mapAll(new NoiseWiringHelper())` (run inside `RandomState`'s constructor). Each leaf has been resolved against the registry and instantiated as a real seeded `NormalNoise` (via the world's `PositionalRandomFactory`). This is the form that actually produces numbers when sampled. Interior nodes may also get wrapped in marker/cache types.
- **Codec**: a Mojang DataFixerUpper serializer/deserializer (`com.mojang.serialization.Codec` / `KeyDispatchDataCodec`). Every vanilla `DensityFunction` declares one so it can round-trip through `level.dat` and worldgen JSON. **Our custom climate functions return `MapCodec.unit(...)` which encodes as `{}`** — fine as long as the value is never asked to serialize. The moment one ends up inside something Mojang re-encodes (e.g. `NoiseBasedChunkGenerator.settings` getting saved), `level.dat` corrupts.
- **Wrapping** (in this doc): one `DensityFunction` holding another and forwarding calls to it (e.g. `FirmaClimateFunction.Identity` wraps a vanilla function). Wrapping has cost: every `compute(FunctionContext)` typically allocates a `SinglePointContext` per call, which is brutal on hot paths like `finalDensity` (called millions of times per chunk). We avoid wrapping anything we don't actively transform.

---

## 1. Vanilla flow (no plugin)

### 1.1 Server startup → world load

1. `MinecraftServer` reads `level.dat` → decodes `WorldGenSettings` (codec).
2. For each dimension, builds:
   - `BiomeSource` (usually `MultiNoiseBiomeSource`) — owns the biome → climate-parameter table.
   - `ChunkGenerator` (usually `NoiseBasedChunkGenerator`) — holds `Holder<NoiseGeneratorSettings>` (`settings` field).
3. `NoiseGeneratorSettings` (a record) holds the **un-wired** `NoiseRouter`:
   - All `DensityFunction`s reference noise via `Holder<NormalNoise.NoiseParameters>` (un-resolved) and `HolderHolder` wrappers.
   - No actual `NormalNoise` samplers exist yet.

### 1.2 `RandomState` construction (per-world, per-dimension)

`net.minecraft.world.level.levelgen.RandomState` is built from `(settings, registry, seed)`.
This is where un-wired definitions become real samplers:

```java
// RandomState constructor (paraphrased)
this.router  = settings.noiseRouter().mapAll(new NoiseWiringHelper());  // wires all noise
this.sampler = new Climate.Sampler(
    this.router.temperature().mapAll(visitor),
    this.router.vegetation().mapAll(visitor),
    this.router.continents().mapAll(visitor),
    this.router.erosion().mapAll(visitor),
    this.router.depth().mapAll(visitor),
    this.router.ridges().mapAll(visitor),
    settings.spawnTarget()
);
```

Key consequence: **`router` and `sampler` capture independent wired copies** of the climate functions at this moment. Later mutations to one do not affect the other.

### 1.3 Who reads what

| Consumer | Reads | Used for |
|---|---|---|
| `NoiseBasedChunkGenerator.fillFromNoise` | `RandomState.router` (finalDensity, fluid noises, vein noises, …) | Terrain blocks, aquifers, ore veins |
| `NoiseBasedChunkGenerator.applyCarvers` / surface | `RandomState.router` + `RandomState.surfaceSystem` | Caves, surface materials |
| `MultiNoiseBiomeSource.getNoiseBiome` | `RandomState.sampler` | Biome placement (temperature/humidity/etc. lookups) |

`NoiseGeneratorSettings` itself is **not** consulted at runtime for noise after `RandomState` is built — it only matters during construction and during save (codec round-trip).

---

## 2. Firma flow

### 2.1 Generator selection (Bukkit layer)

- `plugin.yml` registers Firma as a world-gen plugin.
- `Firma.getDefaultWorldGenerator(worldName, id)` returns a `FirmaChunkGenerator` (Bukkit-side wrapper, **not** an NMS generator).
  - `id` is parsed into `GenerationMode`:
    - `"vanilla"` / null → `VANILLA`
    - `"void"` → `VOID`
    - any registered pack id → `PACK` (pack cached on the generator)
    - anything else → warning + fallback to `VANILLA`
- Paper wraps `FirmaChunkGenerator` inside `org.bukkit.craftbukkit.generator.CustomChunkGenerator`, which itself wraps a real vanilla `NoiseBasedChunkGenerator` as its `delegate`.

### 2.2 `WorldInitEvent` injection — `NMSInjectListener`

Triggered once per world (de-duped via `injectedWorlds` set).
Steps:

1. Confirm the world's Bukkit generator is a `FirmaChunkGenerator`. If not, bail.
2. Pull `ServerLevel` from `CraftWorld`.
3. `unwrapToVanilla(currentGenerator)` — reflects `CustomChunkGenerator.delegate` to get the real `NoiseBasedChunkGenerator`.
4. **Branch on mode**:

#### `VANILLA` mode
- No noise patching. Vanilla `RandomState` is used as-is.

#### `PACK` mode (the interesting case)
- Read `randomState = serverWorld.getChunkSource().randomState()`.
- Take the **wired** router via `randomState.router()` (NOT `settings.noiseRouter()` — that one is un-wired and would yield broken aquifer/fluid noise → "world full of water" bug).
- Build a new `NoiseRouter` via `FirmaNoiseRouter.patchClimateFunctions(wiredRouter, seed, pack)`:
  - For each of the 6 climate parameters (`temperature`, `humidity` (vanilla `vegetation`), `continentalness` (`continents`), `erosion`, `weirdness` (`ridges`), `depth`):
    - If `pack.hasClimateConfig(param)` → `ClimateFunctionFactory.build(...)` produces a custom `DensityFunction` (Constant / DoublePerlin / WeirdnessToRidges / DepthClimateFunction / etc.).
    - Otherwise → pass through the **wired vanilla function** unchanged (no wrapper, no `Identity` — avoids per-call `SinglePointContext` allocation).
  - All non-climate fields (`barrierNoise`, `fluidLevelFloodednessNoise`, `fluidLevelSpreadNoise`, `lavaNoise`, `initialDensityWithoutJaggedness`, `finalDensity`, `veinToggle`, `veinRidged`, `veinGap`) are passed through wired vanilla unchanged.
- **Hack**: reflectively set `RandomState.router` (a `final` field) to the patched router.
  - We deliberately do **not** touch `NoiseBasedChunkGenerator.settings`: replacing it with a `Holder.direct(...)` would round-trip through the codec on save and our custom `DensityFunction`s encode as `{}`, corrupting `level.dat` (`"No key dimensions in MapLike[{}]"` on reload).
  - `RandomState.router` is transient (rebuilt every server start from `settings`), so this hack is save-safe.

#### `Climate.Sampler` patch
- `RandomState.sampler` is built once from the *original* vanilla climate functions in `RandomState`'s constructor and captured independently from `router`. `MultiNoiseBiomeSource` reads from `sampler` (not `router`) for biome lookups, so patching only the router would leave biome placement using vanilla climate.
- After patching `router`, we build a new `Climate.Sampler(patchedRouter.temperature().mapAll(visitor), ..., settings.spawnTarget())` and reflect-set `RandomState.sampler`. The visitor mirrors vanilla's: unwraps `DensityFunctions.HolderHolder` → inner value, `DensityFunctions.Marker` → wrapped function; everything else (including our custom climate funcs) passes through unchanged.
- `settings.spawnTarget()` is read from the unchanged `NoiseGeneratorSettings` (we never mutate `settings`).

5. **Always** (regardless of mode): wrap the unwrapped vanilla generator in `NMSChunkGeneratorDelegate(vanilla, isVoidMode)` and reflectively replace `ChunkMap.worldGenContext.generator` with it (via `Reflection.CHUNKMAP`).
   - This is what lets us intercept chunk-gen calls (carvers, surface, decoration, mobs, `fillFromNoise`) per-mode.

### 2.3 `NMSChunkGeneratorDelegate` behavior

Wraps a vanilla `ChunkGenerator`. Each override checks `voidMode`:

| Method | `VOID` | `VANILLA` / `PACK` |
|---|---|---|
| `fillFromNoise` | return chunk unchanged (empty) | delegate to vanilla |
| `applyCarvers` | skip | delegate |
| `buildSurface` | skip | delegate |
| `applyBiomeDecoration` | skip | delegate |
| `spawnOriginalMobs` | skip | delegate |
| `getBaseHeight`, `getBaseColumn`, `getSeaLevel`, `getMinY`, `getGenDepth`, `addDebugScreenInfo` | always delegate | always delegate |
| `codec()` | `MapCodec.assumeMapUnsafe(ChunkGenerator.CODEC)` — never actually serialized; we don't replace the generator in `settings`/`level.dat` |

`FirmaChunkGenerator.generateNoise` (Bukkit-side) is only reached for `VOID` — and there it just returns. For other modes the Paper plumbing eventually calls our NMS delegate, which calls vanilla.

---

## 3. Summary of Firma's overrides

| Layer | What we touch | How | Why |
|---|---|---|---|
| Bukkit generator | `FirmaChunkGenerator` | `getDefaultWorldGenerator` | Marker + mode/pack carrier; Paper wraps it as `CustomChunkGenerator`. |
| NMS chunk generator | `ChunkMap.worldGenContext.generator` | Reflection (`Reflection.CHUNKMAP`) | Insert `NMSChunkGeneratorDelegate` so we can skip stages in `VOID` mode. |
| NMS noise (PACK mode) | `RandomState.router` | Reflection (`final` field set) | Replace climate density functions with pack-defined ones; safe because router is rebuilt at startup. |
| NMS climate sampler | `RandomState.sampler` | Reflection (`final` field set) | `Climate.Sampler` is captured independently in `RandomState`'s constructor; must be rebuilt from the patched router for `MultiNoiseBiomeSource` to honor pack climate. |
| NMS settings holder | `NoiseBasedChunkGenerator.settings` | **Intentionally never touched** | Mutating it corrupts `level.dat` via codec round-trip. |

---

## 4. Seed propagation

A single `long` seed is the root of every deterministic noise decision. Its path:

1. **Source**: the world's `seed` from `level.dat` (or `WorldCreator.seed(...)`). Accessible via `ServerLevel.getSeed()`.
2. **Vanilla wiring (`RandomState` constructor)**:
   - Builds a `WorldgenRandom` (Xoroshiro or legacy depending on `settings.useLegacyRandomSource()`) from the seed.
   - Derives a `PositionalRandomFactory` from it; `NoiseWiringHelper` uses this factory to seed every `NormalNoise` sampler. Each noise gets a deterministic sub-seed via `factory.fromHashOf(noiseId)`.
   - The same seed also drives `aquiferRandom`, `oreRandom`, and `surfaceSystem`.
   - Result: every wired `NormalNoise` in `RandomState.router` (and therefore `RandomState.sampler`) is keyed off the world seed.
3. **Biome placement**: `MultiNoiseBiomeSource` calls `RandomState.sampler` — deterministic in the seed because the underlying samplers are.
4. **Structure / feature placement**: separate `WorldgenRandom` instances are reseeded per-chunk from `(seed, chunkX, chunkZ, salt)` inside `ChunkGenerator` methods; not part of the climate path but seed-derived all the same.

### Firma's seed handling

- **`VANILLA` / `VOID` mode**: we do nothing with the seed; vanilla owns it end-to-end.
- **`PACK` mode**:
  - When we reflect-set `RandomState.router`, the **pass-through vanilla functions inherit the original wired samplers**, so their seed lineage is untouched.
  - For pack-configured climate parameters, `NMSInjectListener` reads `serverWorld.getSeed()` and hands it to `FirmaNoiseRouter.patchClimateFunctions(wiredRouter, seed, pack)`.
  - `FirmaNoiseRouter` constructs a `ClimateFunctionFactory(seed, pack.id())`. For each pack-configured parameter, `factory.build(...)` derives a per-parameter sub-seed:
    ```java
    new PositionalRandomFactory(worldSeed ^ (packId + ":" + parameter).hashCode())
    ```
    This factory then seeds any `DoublePerlinNoiseSampler` / `NormalNoise` instances inside that climate function (analogous to vanilla's `NoiseWiringHelper`, but locally scoped).
  - The `^ packId.hashCode()` term means two packs with the same parameter config but different ids produce different noise on the same world seed — intentional, so swapping packs doesn't trivially collide.
  - `Constant` / `Identity` configs don't consume seed; they're seed-agnostic.
- **Determinism guarantee**: for a given (world seed, pack id, pack config) tuple, generation is fully reproducible. Changing any one of those three changes the resulting noise; nothing else does.

---

## 5. Special cases

- **VOID worlds**: noise pipeline untouched at the `RandomState` level — we just short-circuit chunk-gen stages in the delegate. Vanilla `RandomState` is still built (cheap, side-effect-free) so debug commands etc. don't NPE.
- **Unknown pack id**: `Firma.getDefaultWorldGenerator` throws `IllegalArgumentException` with a list of valid ids. World creation is aborted — we never silently fall back to vanilla, because a typo would otherwise produce the wrong world. `FirmaChunkGenerator.parseMode` has the same guard as a backstop.
- **`FirmaChunkGenerator.generateNoise` warning**: if Paper ever calls this path for `VANILLA`/`PACK`, it means NMS injection didn't run — logged but non-fatal.
- **Concurrent world init**: `injectedWorlds` is a `ConcurrentHashMap.newKeySet()`; first call wins, repeats are no-ops.
