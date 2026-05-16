# Noise Path — Vanilla vs. Firma

A map of every place noise/biomes flow through, what Firma overrides, and why.
All file paths are absolute; line numbers reflect current state at time of writing.

---

## 0. Terminology

- **Un-wired `DensityFunction`**: the form as decoded from data / held by `NoiseGeneratorSettings`. Its leaves are `Holder<NormalNoise.NoiseParameters>` references and `HolderHolder` wrappers — i.e. *recipes* describing which noise parameter file to use, with no actual `NormalNoise` sampler instance attached. Calling `compute()` on this form returns garbage (un-resolved holders) or throws.
- **Wired `DensityFunction`**: the same tree after `mapAll(new NoiseWiringHelper())` (run inside `RandomState`'s constructor). Each leaf has been resolved against the registry and instantiated as a real seeded `NormalNoise` (via the world's `PositionalRandomFactory`). This is the form that actually produces numbers when sampled. Interior nodes may also get wrapped in marker/cache types.
- **Codec**: a Mojang DataFixerUpper serializer/deserializer (`com.mojang.serialization.Codec` / `KeyDispatchDataCodec`). Every vanilla `DensityFunction` declares one so it can round-trip through `level.dat` and worldgen JSON. Our custom climate functions are **never** intended to serialize — they declare an `UnserializableMapCodec` that **errors on encode** so any regression triggers a loud failure instead of silently writing `{}` and corrupting `level.dat`. See §3.1.
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
- Build a new `NoiseRouter` via `FirmaNoiseRouter.patchClimateFunctions(wiredRouter, randomState, seed, pack)`:
  - For each of the 5 configurable climate parameters (`temperature`, `humidity` (vanilla `vegetation`), `continentalness` (`continents`), `erosion`, `weirdness` (`ridges`)):
    - If `pack.hasClimateConfig(param)` → `ClimateFunctionFactory.build(...)` produces a custom `DensityFunction` (Constant / DoublePerlin / WeirdnessToRidges / RadialGradient / etc.).
    - Otherwise → pass through the **wired vanilla function** unchanged (no wrapper, no `Identity` — avoids per-call `SinglePointContext` allocation).
  - **Terrain-shape fields are rebuilt** from patched climate functions (see §2.4):
    - `depth` = `yClampedGradient(-64, 320, 1.5, -1.5) + rebuiltOffset` (derived, not pack-configurable)
    - `initialDensityWithoutJaggedness` = `slideOverworld(amplified, add(noiseGradientDensity(cache2d(rebuiltFactor), rebuiltDepth), constant(-0.703125)).clamp(-64, 64))`
    - `finalDensity` = `postProcess(slideOverworld(amplified, rebuiltSlopedCheese))` where `slopedCheese = noiseGradientDensity(rebuiltFactor, rebuiltDepth + rebuiltJaggedness * jaggedNoise) + BASE_3D_NOISE`
  - Independent fields (`barrierNoise`, `fluidLevelFloodednessNoise`, `fluidLevelSpreadNoise`, `lavaNoise`, `veinToggle`, `veinRidged`, `veinGap`) are passed through wired vanilla unchanged.
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

## 2.4 TODO: rebuild terrain-shape fields from Firma-modified climate functions

Terrain-shape dependency chain:

```text
patched continentalness
  ├─ overworld/offset
  │    └─ overworld/depth
  │         ├─ initial_density_without_jaggedness
  │         └─ overworld/sloped_cheese
  │              └─ final_density
  ├─ overworld/factor
  │    ├─ initial_density_without_jaggedness
  │    └─ overworld/sloped_cheese
  │         └─ final_density
  └─ overworld/jaggedness
       └─ overworld/sloped_cheese
            └─ final_density

patched erosion and patched ridges/weirdness also feed offset/factor/jaggedness.
```

Vanilla `overworld.json` exposes climate functions in `noise_router`, but terrain shape is not computed by dynamically asking the top-level router fields at runtime. During `RandomState` construction, Mojang wires the full density graph into callable functions. If Firma replaces only `router.continents()`, `router.erosion()`, and `router.ridges()`, the already-wired terrain graph inside `depth`, `initialDensityWithoutJaggedness`, and `finalDensity` can still reference vanilla climate functions.

Relevant vanilla source path from `NoiseRouterData.registerTerrainNoises(...)`:

```java
Coordinate continents = new DensityFunctions.Spline.Coordinate(continentalness);
Coordinate erosion = new DensityFunctions.Spline.Coordinate(erosion);
Coordinate ridges = new DensityFunctions.Spline.Coordinate(RIDGES);
Coordinate ridgesFolded = new DensityFunctions.Spline.Coordinate(RIDGES_FOLDED);

offset = spline(TerrainProvider.overworldOffset(continents, erosion, ridgesFolded, amplified));
factor = spline(TerrainProvider.overworldFactor(continents, erosion, ridges, ridgesFolded, amplified));
depth = yClampedGradient(-64, 320, 1.5, -1.5) + offset;
jaggedness = spline(TerrainProvider.overworldJaggedness(continents, erosion, ridges, ridgesFolded, amplified));
slopedCheese = noiseGradientDensity(factor, depth + jaggedness * jaggedNoise);
finalDensity = ... slopedCheese ... caves ... aquifers ...;
```

Current Firma state (terrain rebuild implementation):

- ✅ **Steps 1-3 complete**: `offset`, `factor`, `jaggedness`, and `depth` are rebuilt from patched climate functions using vanilla's spline logic.
- ✅ **Step 4 complete**: `initialDensityWithoutJaggedness` is rebuilt as `slideOverworld(amplified, add(noiseGradientDensity(cache2d(factor), depth), constant(-0.703125)).clamp(-64, 64))`.
- ⚠️ **Step 5 partial**: `finalDensity` rebuilds `slopedCheese` from patched terrain inputs using `RandomState` to access seeded `jaggedNoise` and `BASE_3D_NOISE_OVERWORLD`, then applies `slideOverworld` and `postProcess`.
- ✅ **Step 6 complete**: Only transient `RandomState.router` and `RandomState.sampler` are patched; `NoiseBasedChunkGenerator.settings` remains untouched.
- ✅ **Step 7 complete**: No `FirmaClimateFunction.Identity` wrappers in rebuilt terrain paths; direct `DensityFunction` composition.
- `depth` is no longer pack-configurable; it's always derived as `yClampedGradient + rebuiltOffset`.
- `PackLoader` validates climate parameter names and rejects unknown keys like `depth`.

### Current simplification and remaining work

**What works now:**
- Terrain shape (hills, valleys, plains, oceans) correctly responds to patched `continentalness`, `erosion`, and `weirdness`.
- Ocean packs with constant `-1.01` continentalness should generate flat, low-elevation ocean floor terrain.
- Biome placement and terrain density are now consistent.

**Current simplification in `finalDensity`:**
- Rebuilds core terrain (`slopedCheese`) using patched inputs and applies slide/postProcess.
- **Missing**: Full vanilla cave/aquifer/noodle pipeline integration.

**Remaining work: noise cave integration**

Vanilla's `finalDensity` pipeline includes noise caves (spaghetti, cheese, noodle, entrances, pillars) that are independent of climate inputs but must be composed with our rebuilt `slopedCheese`. Current simplification skips these, producing solid terrain.

**Cave function dependencies:**

| Function | Vanilla source | Climate-dependent? | Needs rebuilding? |
|---|---|---:|---|
| `ENTRANCES` | `NoiseRouterData.entrances(...)` | No | No - can use vanilla's wired function |
| `NOODLE` | `NoiseRouterData.noodle(...)` | No | No - can use vanilla's wired function |
| `SPAGHETTI_2D` | `NoiseRouterData.spaghetti2D(...)` | No | No - can use vanilla's wired function |
| `SPAGHETTI_ROUGHNESS` | `NoiseRouterData.spaghettiRoughnessFunction(...)` | No | No - can use vanilla's wired function |
| `PILLARS` | `NoiseRouterData.pillars(...)` | No | No - can use vanilla's wired function |
| `underground(...)` | `NoiseRouterData.underground(...)` | **Yes - takes `slopedCheese` as parameter** | **Yes - must rebuild with our `slopedCheese`** |

**Problem:** Vanilla's cave functions are **not exposed** in `NoiseRouter` - they're internal to the wired `finalDensity` tree. We have three options:

**Option A: Extract from vanilla's wired router**
- Traverse `vanillaRouter.finalDensity()` tree to find the cave function nodes
- Pro: Uses vanilla's exact seeded instances
- Con: Requires complex tree traversal; fragile if vanilla changes structure

**Option B: Rebuild from noise parameters**
- Access `RandomState.noises` (private `HolderGetter<NormalNoise.NoiseParameters>`) via reflection
- Call `noisesGetter.getOrThrow(Noises.CAVE_ENTRANCE)`, etc. to get holders
- Reconstruct each cave function following vanilla's exact logic from `NoiseRouterData`
- Pro: Explicit, maintainable, matches vanilla's construction exactly
- Con: Requires implementing 5+ helper functions; more code

**Option C: Use vanilla's `finalDensity` as-is (current approach)**
- Skip cave integration entirely; rely on datapack to disable legacy carvers
- Pro: Simplest; terrain shape works correctly
- Con: No underground features (no caves, no aquifers below y=0)

**Decision needed:** Which option to implement?

**If Option B (recommended):**
- Already have reflection access to `RandomState.noises` (used for `jaggedNoise`)
- Need to implement:
  1. `rebuildEntrances(RandomState, noisesGetter)` - spaghetti 3D caves
  2. `rebuildNoodle(RandomState, noisesGetter)` - noodle caves
  3. `rebuildSpaghetti2D(noisesGetter)` - 2D spaghetti caves
  4. `rebuildSpaghettiRoughness(noisesGetter)` - roughness modifier
  5. `rebuildPillars(noisesGetter)` - pillar caves
  6. `rebuildUnderground(slopedCheese, ...)` - compose cheese + spaghetti + pillars
- Then compose: `rangeChoice(slopedCheese, -1000000, 1.5625, min(slopedCheese, mul(5.0, entrances)), underground)` → `slideOverworld` → `postProcess` → `min(..., noodle)`

**Additional considerations:**
- **Aquifer consistency**: Vanilla `Aquifer` reads `noiseRouter.erosion()` and `noiseRouter.depth()` directly. Our rebuilt `depth` is now in the router, so aquifers should see consistent values.
- **Legacy carvers**: User will disable via datapack (separate from noise caves).
- **Y-limited interpolation**: Some cave functions use `yLimitedInterpolatable` which requires access to the `Y` density function from the registry.

### Pass-through audit

`FirmaNoiseRouter.patchClimateFunctions(...)` currently passes these vanilla router fields through unchanged:

| Pass-through field | Vanilla role | Depends on patched climate inputs? | Status |
|---|---|---:|---|
| `barrierNoise` | Aquifer barrier noise from `Noises.AQUIFER_BARRIER` | No | ✅ Pass-through safe; independent aquifer noise. |
| `fluidLevelFloodednessNoise` | Aquifer fluid-level floodedness from `Noises.AQUIFER_FLUID_LEVEL_FLOODEDNESS` | No | ✅ Pass-through safe; independent aquifer noise. |
| `fluidLevelSpreadNoise` | Aquifer fluid-level spread from `Noises.AQUIFER_FLUID_LEVEL_SPREAD` | No | ✅ Pass-through safe; independent aquifer noise. |
| `lavaNoise` | Aquifer lava selector from `Noises.AQUIFER_LAVA` | No | ✅ Pass-through safe; independent aquifer noise. |
| `veinToggle` | Ore vein vertical/noise selector using `Noises.ORE_VEININESS` | No | ✅ Pass-through safe; independent ore-vein path. |
| `veinRidged` | Ore vein ridge strength using `Noises.ORE_VEIN_A/B` and Y range | No | ✅ Pass-through safe; independent ore-vein path. |
| `veinGap` | Ore vein gap noise using `Noises.ORE_GAP` | No | ✅ Pass-through safe; independent ore-vein path. |

**Rebuilt fields (no longer pass-through):**

| Rebuilt field | Implementation | Status |
|---|---|---|
| `depth` | `yClampedGradient(-64, 320, 1.5, -1.5) + rebuiltOffset` | ✅ Rebuilt from patched climate; derived, not configurable. |
| `initialDensityWithoutJaggedness` | `slideOverworld(amplified, add(noiseGradientDensity(cache2d(rebuiltFactor), rebuiltDepth), constant(-0.703125)).clamp(-64, 64))` | ✅ Rebuilt from patched terrain inputs. |
| `finalDensity` | `postProcess(slideOverworld(amplified, rebuiltSlopedCheese))` where `slopedCheese = noiseGradientDensity(rebuiltFactor, rebuiltDepth + rebuiltJaggedness * jaggedNoise) + BASE_3D_NOISE` | ⚠️ Partial; missing cave/noodle integration (see remaining TODO above). |

---

## 3. Summary of Firma's overrides

| Layer | What we touch | How | Why |
|---|---|---|---|
| Bukkit generator | `FirmaChunkGenerator` | `getDefaultWorldGenerator` | Marker + mode/pack carrier; Paper wraps it as `CustomChunkGenerator`. |
| NMS chunk generator | `ChunkMap.worldGenContext.generator` | Reflection (`Reflection.CHUNKMAP`) | Insert `NMSChunkGeneratorDelegate` so we can skip stages in `VOID` mode. |
| NMS noise (PACK mode) | `RandomState.router` | Reflection (`final` field set) | Replace climate density functions with pack-defined ones; safe because router is rebuilt at startup. |
| NMS climate sampler | `RandomState.sampler` | Reflection (`final` field set) | `Climate.Sampler` is captured independently in `RandomState`'s constructor; must be rebuilt from the patched router for `MultiNoiseBiomeSource` to honor pack climate. |
| NMS settings holder | `NoiseBasedChunkGenerator.settings` | **Intentionally never touched** | Mutating it corrupts `level.dat` via codec round-trip. |

### 3.1 Defending against `level.dat` corruption

The corruption hazard: if any of our custom `DensityFunction`s ever ends up inside a Mojang codec encode path (the canonical example: replacing `NoiseBasedChunkGenerator.settings` with `Holder.direct(newSettings)` so the saved `level.dat` tries to inline the noise router), Mojang's `resultOrPartial(...)` salvage logic would happily write `{}` for our nodes. The next world load then dies with `"No key dimensions in MapLike[{}]"` and the world is unrecoverable.

We close this hazard with three layers:

1. **No mutation of saved fields.** `NoiseBasedChunkGenerator.settings` is never touched. All Firma noise patches go into `RandomState` (transient — rebuilt every server start from the unchanged `settings`). This is the primary defense.
2. **Fail-loud codecs.** Every Firma `DensityFunction` (`Constant`, `Identity`, `WeirdnessToRidges`, `DoublePerlinClimateFunction`, `PeaksAndValleysFunction`) declares its `codec()` via `UnserializableMapCodec.of(name, recoveryDefault)`. That codec returns `DataResult.error(...)` on **encode**, so any future regression that re-introduces a serialization path errors out at the first attempted write. The error propagates through Mojang's `RecordBuilder` and `resultOrPartial(...)` cannot salvage it into `{}` — the save aborts loudly with a stack trace naming the offending Firma class. `level.dat` is left untouched.
3. **Permissive decode.** The same codec returns a safe sentinel (e.g. `Constant(0.0)`) on decode. This is purely a recovery affordance: if a `level.dat` from a buggy past version somehow contains our nodes, the world still loads. Decode is never expected to fire in normal operation since these classes are never registered in `BuiltInRegistries.DENSITY_FUNCTION_TYPE`.

Together, layers (1) + (2) make silent corruption unreachable: either we never serialize (layer 1), or we error out before producing partial output (layer 2). Layer (3) is the parachute.

#### Regression tests

`./gradlew test` runs three guards that fail the build if any layer above is broken:

- `UnserializableMapCodecTest` (pure DFU): asserts `UnserializableMapCodec.of(...)` errors on encode and recovers on decode.
- `FirmaDensityFunctionCodecGuardTest` (real NMS classes, no mocking): instantiates each Firma `DensityFunction` (`Constant`, `Identity`, `WeirdnessToRidges`, `DoublePerlinClimateFunction`, `PeaksAndValleysFunction`) and asserts `df.codec().codec().codec().encodeStart(JsonOps.INSTANCE, df).isError()`. If anyone replaces the failing codec with `MapCodec.unit(...)`, this test breaks.
- `NMSInjectListenerSafetyTest` (source-level static analysis): scans `NMSInjectListener.java` for the historical corruption patterns:
  - reflective access to `NoiseBasedChunkGenerator.class.getDeclaredField("settings")`
  - `Holder.direct(...)` paired with `NoiseGeneratorSettings`
  - direct assignment to a generator's `settings` field
  - also asserts router patching is still present (positive sanity check).

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

---
