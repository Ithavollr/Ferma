# Firma Roadmap

Three-stage roadmap for the Firma plugin. Each stage builds on the last.

## Architectural Overview

Firma sits at two layers of Minecraft's generation pipeline:

1. **Bukkit layer** — `FirmaChunkGenerator` (extends `org.bukkit.generator.ChunkGenerator`)
   - Returned by `Firma.getDefaultWorldGenerator()`
   - Serves as the **marker** that tells `NMSInjectListener` this is a Firma world
   - Can carry **per-world mode configuration** (vanilla / noise-override / void)
   - In some modes (Stage 3), it directly produces chunk data
2. **NMS layer** — `NMSChunkGeneratorDelegate` (extends `net.minecraft.world.level.chunk.ChunkGenerator`)
   - Injected at `WorldInitEvent`, replaces the `WorldGenContext`'s generator
   - Holds a reference to the real vanilla `NoiseBasedChunkGenerator` (unwrapped from Paper's `CustomChunkGenerator`)
   - Controls what happens at the NMS level — the only layer that can meaningfully intercept noise router density functions

Both layers are kept because each stage uses them differently.

---

## Stage 1 — Faithful Vanilla Pass-Through *(current target)*

**Goal:** Plugin is installed, worlds generate identically to vanilla. Verifies the injection plumbing.

**Critical Context:** This server fork uses **multithreaded world ticking** and **Moonrise concurrent chunk generation**. `WorldInitEvent` fires on the world's dedicated `tickExecutor` (not the main thread), and chunk generation begins immediately afterward in parallel. The delegate must be thread-safe.

**Tasks:**
- **Thread-safe injection:** `NMSInjectListener` must handle concurrent world initialization. Use `ConcurrentHashMap` for the injected-worlds tracking instead of `HashSet` + `ReentrantLock`.
- Unwrap `CustomChunkGenerator` in `NMSInjectListener` to obtain the real `NoiseBasedChunkGenerator`. Fail loudly if it isn't a `CustomChunkGenerator` — we need the real thing for Stage 2 anyway.
- `NMSChunkGeneratorDelegate` holds that real vanilla generator and delegates every method to it verbatim. The vanilla generator is already thread-safe for concurrent chunk generation.
- `FirmaChunkGenerator` at the Bukkit layer:
  - **Does not override `generateNoise`** (removing the current empty override eliminates the warning loop).
  - Introduces a `GenerationMode` enum field: `VANILLA` (default), `NOISE_OVERRIDE` (Stage 2), `VOID` (Stage 3).
  - Reads its mode from the generator ID passed to `getDefaultWorldGenerator(worldName, id)` — e.g. `generator: Firma:vanilla`, `generator: Firma:noise`, `generator: Firma:void`. Default is `VANILLA`.

**Verification:** Generate a world, compare terrain to a pure-vanilla world with the same seed. Must be bit-identical.

---

## Stage 2 — Noise Parameter Override

**Goal:** Vanilla does everything (terrain shape, carving, surface, decoration, mobs, structures) except the **6 climate noise functions** (temperature, humidity, continentalness, erosion, weirdness, depth), which are supplied by Firma.

**Concept:** Minecraft's `NoiseRouter` contains `DensityFunction` fields for each of these parameters:
- `continents` → `minecraft:overworld/continents`
- `depth` → `minecraft:overworld/depth`
- `erosion` → `minecraft:overworld/erosion`
- `temperature` → `minecraft:overworld/temperature` (shifted_noise)
- `humidity` → `minecraft:overworld/humidity` (vanilla density function)
- `weirdness` → `minecraft:overworld/ridges` (weirdness uses the ridges parameter)

Vanilla samples these during biome selection and terrain shaping. If we replace those `DensityFunction`s with our own thread-safe implementations, vanilla's entire downstream pipeline will use *our* values without further modification.

**Thread-Safety Requirements:**
- Moonrise calls `fillFromNoise()` concurrently from multiple chunk generation threads
- Our custom `DensityFunction` implementations must be **stateless and thread-safe** (no mutable fields, no static shared state)
- If we need per-thread state, use `ThreadLocal` or pass context via the `DensityFunction.FunctionContext`

**Tasks:**
- Add a `FirmaNoiseRouter` utility that takes the vanilla `NoiseRouter` and returns a modified copy with our `DensityFunction` implementations swapped in for the 6 climate fields.
- In `NMSInjectListener`, when mode is `NOISE_OVERRIDE`:
  - Access the vanilla generator's `NoiseGeneratorSettings` via its `Holder<NoiseGeneratorSettings>`.
  - Build a replacement `NoiseGeneratorSettings` with a patched `NoiseRouter`.
  - Construct a new `NoiseBasedChunkGenerator` using the replacement settings, or patch the existing one via reflection if the fields are final.
  - Wrap that in `NMSChunkGeneratorDelegate` and inject.
- Provide a `FirmaClimateFunction` interface — implementing `DensityFunction` — where we can plug in arbitrary climate logic for each of the 6 parameters:
  ```java
  public interface FirmaClimateFunction extends DensityFunction {
      double compute(double x, double y, double z, long seed);
  }
  ```
  - Start with identity functions that delegate to the original vanilla density function for verification
  - Then allow custom logic (e.g. temperature maps from external data sources)
- Ensure `MultiNoiseBiomeSource` uses the patched router's climate functions (it typically samples via the router, but verify no caching of old references occurs).

**Why we need the unwrapped vanilla generator:** `CustomChunkGenerator` does not expose `NoiseGeneratorSettings` or `NoiseRouter`. Only `NoiseBasedChunkGenerator` does.

**Verification:**
- With identity climate functions: terrain must be bit-identical to vanilla (same as Stage 1).
- With a trivial override (e.g. temperature always = 1.0): world should be entirely hot biomes, confirming the override took effect.

---

## Stage 2 Implementation: Noise Function Inventory

To replicate vanilla 1.21.4 climate sampling without using Mojang's `DensityFunction` tree, we need to hand-implement the underlying noise primitives in pure Java math. Reference implementations exist in `NMS-1.17.1/util/math/noise/` (the noise primitives are largely unchanged between 1.17 and 1.21 — only the *composition* moved from hardcoded Java to JSON-driven density function trees).

### Tier 1: Core Noise Primitives

These are the foundational building blocks. Everything else composes from these.

1. **`PerlinNoiseSampler`** — Classic 3D improved Perlin noise (Ken Perlin's 2002 algorithm).
   - 256-byte permutation table seeded from RNG
   - Uses 16 standard gradient vectors (`SimplexNoiseSampler.GRADIENTS`)
   - Trilinear interpolation with quintic fade curve `f(t) = 6t^5 - 15t^4 + 10t^3`
   - Reference: `PerlinNoiseSampler.java` (1.17)
   - **Stateless after construction → thread-safe ✓**

2. **`SimplexNoiseSampler`** — Simplex noise (used for End islands and biome layer mutation).
   - Same 256-byte permutation table approach as Perlin
   - 12 gradient vectors for 3D, skew/unskew constants `F2 = 0.5 * (sqrt(3) - 1)`, `G2 = (3 - sqrt(3)) / 6`
   - Returns values normalized to roughly `[-1, 1]`
   - Reference: `SimplexNoiseSampler.java` (1.17)
   - **Stateless after construction → thread-safe ✓**
   - *May not be needed if we don't target End/Nether — defer until proven necessary.*

### Tier 2: Octave Compositions

These layer multiple Perlin samplers at different frequencies and amplitudes.

3. **`OctavePerlinNoiseSampler`** — Multiple `PerlinNoiseSampler`s combined for fractal noise.
   - Configurable octave list (e.g. `firstOctave = -7`, amplitudes `[1.0, 1.0]`)
   - Each octave doubles frequency and halves amplitude (typical fBm)
   - Includes `maintainPrecision()` helper to avoid floating-point drift at large coordinates: `value - floor(value / 3.3554432E7) * 3.3554432E7`
   - Reference: `OctavePerlinNoiseSampler.java` (1.17)

4. **`DoublePerlinNoiseSampler`** — Two `OctavePerlinNoiseSampler`s combined with a `1/6` offset to mask grid artifacts.
   - This is the workhorse for climate noise in 1.18+
   - `sample(x, y, z) = (firstSampler.sample(x, y, z) + secondSampler.sample(x*1.0181268882175227, y*1.0181268882175227, z*1.0181268882175227)) * (1/6 * (10/9))`
   - The amplitude factor normalizes output back to `[-1, 1]` range
   - Reference: `DoublePerlinNoiseSampler.java` (1.17)
   - **This is the type used for all 6 climate parameters in `MultiNoiseBiomeSource`**

5. **`InterpolatedNoiseSampler`** — The "main" terrain noise (sloped cheese / blob noise).
   - Uses 3 `OctavePerlinNoiseSampler`s: lower, upper, interpolation
   - The interpolation noise blends between lower and upper at each point
   - Octaves: lower/upper use `[-15, 0]` (16 octaves), interpolation uses `[-7, 0]` (8 octaves)
   - Reference: `InterpolatedNoiseSampler.java` (1.17)
   - **Used for `final_density` / terrain shape — only needed if Stage 2 expands beyond climate to terrain shape itself.** For climate-only override, skip this.

### Tier 3: Climate Parameter Sources

In 1.18+, all 6 climate parameters use **`DoublePerlinNoiseSampler`** with specific octave/amplitude configurations defined in `worldgen/noise/`. The configurations from `worldgen/noise_settings/overworld.json` are:

| Parameter         | Vanilla noise ID                  | Type                       | Notes                                    |
| ----------------- | --------------------------------- | -------------------------- | ---------------------------------------- |
| temperature       | `minecraft:temperature`           | `DoublePerlinNoiseSampler` | Wrapped in `shifted_noise` (XZ shifts)   |
| humidity          | `minecraft:vegetation`            | `DoublePerlinNoiseSampler` | Wrapped in `shifted_noise`               |
| continentalness   | `minecraft:continentalness`       | `DoublePerlinNoiseSampler` | Used directly                            |
| erosion           | `minecraft:erosion`               | `DoublePerlinNoiseSampler` | Used directly                            |
| weirdness         | `minecraft:ridge`                 | `DoublePerlinNoiseSampler` | Folded via `weirdness → ridges` formula  |
| depth             | *(synthesized)*                   | Y-clamped gradient + offset | `1.0 - y/128.0` clamped, plus terrain    |

We will need to **fetch the exact octave/amplitude parameters** from the vanilla noise registry at runtime (or hardcode them from the JSON). They are NOT all `DEFAULT_NOISE_PARAMETERS` — each parameter has its own octave configuration.

### Tier 4: Helper Functions

6. **`shifted_noise` wrapper** — Used by temperature and humidity in 1.18+.
   - Samples `shift_x` and `shift_z` noises (themselves `DoublePerlinNoiseSampler`s with `minecraft:offset` config) at the input position
   - Adds those shifts (multiplied by 4) to the input coordinates
   - Then samples the base noise at the shifted position
   - Formula: `base.sample(x*xz_scale + shift_x.sample(x,y,z)*4, y*y_scale, z*xz_scale + shift_z.sample(x,y,z)*4)`

7. **`weirdness → ridges` fold** — Converts raw weirdness into the "PV" (peaks/valleys) curve.
   - `ridges = -3 * (|weirdness| - 2/3)` *(the standard "ridge" fold)*
   - Used by `MultiNoiseBiomeSource` for biome selection, NOT by `final_density`

8. **`y_clamped_gradient` for depth** — Linear gradient over Y for depth parameter.
   - `depth(y) = clamp(1.0 - y/128.0, -1.0, 1.0)` (approximately — verify exact formula)
   - Then optionally offset by terrain shape

### Tier 5: RNG (Required for Determinism)

9. **`XoroshiroRandomSource` / `LegacyRandomSource`** — Vanilla seeded RNG.
   - 1.18+ uses Xoroshiro128++ for new noise sources
   - We must replicate this exactly to get bit-identical output to vanilla
   - Used to generate the permutation tables for each `PerlinNoiseSampler`
   - Reference: Mojang's `RandomSupport` and `XoroshiroRandomSource`

10. **`PositionalRandomFactory`** — Per-position deterministic randoms.
    - Used to seed sub-noises from the world seed + a string identifier (the noise ID)
    - Hashes the noise ID into a seed offset for reproducibility

### Implementation Order (Dependencies)

```
LegacyRandomSource ──┐
XoroshiroRandomSource ┴── PositionalRandomFactory ──┐
                                                    ├── PerlinNoiseSampler ──┬── OctavePerlinNoiseSampler ── DoublePerlinNoiseSampler ──┬── 6 climate noises
                                                    └── SimplexNoiseSampler                                                              └── shifted_noise wrapper
```

### Verification Strategy

For each noise function:
1. Implement in pure Java
2. Sample at known coordinates with a fixed seed
3. Compare bit-for-bit to vanilla NMS output (instrument vanilla via reflection in a test world, or capture with Mixin)
4. Only proceed to the next tier when current tier matches vanilla exactly

**Critical:** We don't need to replicate the full noise tree — just the climate sources. Vanilla's `final_density`, `vein_*`, `aquifer_*`, etc. continue to be computed by vanilla. We only swap out the 6 climate density functions.

---

## Stage 3 — Void / Static Mode

**Goal:** Support worlds that should produce **completely empty chunks**, e.g. for void worlds or pre-generated static worlds that shouldn't extend further.

**Concept:** This is the one case where `FirmaChunkGenerator`'s Bukkit-level override is actually useful and should remain. In this mode, we do **not** want vanilla noise generation at all.

**Thread-Safety Note:** Even in VOID mode, Moonrise may call `generateNoise` concurrently. The empty implementation is inherently thread-safe (no-op).

**Tasks:**
- When mode is `VOID`:
  - `FirmaChunkGenerator.generateNoise` is overridden with an empty implementation (genuinely a no-op — no warnings, this is intentional).
  - `shouldGenerateSurface`, `shouldGenerateCaves`, `shouldGenerateBedrock`, `shouldGenerateDecorations`, `shouldGenerateStructures`, `shouldGenerateMobs` all return `false`.
  - `NMSInjectListener` may *skip* NMS injection for void worlds, since there's nothing to intercept — `CustomChunkGenerator` calling our empty `generateNoise` is exactly the desired behavior. Alternatively, inject an `NMSChunkGeneratorDelegate` variant whose `fillFromNoise` returns the chunk unchanged.
- Optional: detect already-generated static worlds via a marker file or config, and auto-set `VOID` mode so newly-loaded chunks at the edges are empty rather than extending with fresh vanilla terrain.

**Verification:**
- New world with `generator: Firma:void` produces a completely empty world (fall-through to the void).
- No warnings or errors in the log.

---

## Mode Selection Syntax

```yaml
# bukkit.yml
worlds:
  overworld_vanilla:
    generator: Firma            # defaults to VANILLA
  overworld_tweaked:
    generator: Firma:noise      # Stage 2: climate overrides active
  static_world:
    generator: Firma:void       # Stage 3: empty chunks
```

`FirmaChunkGenerator` parses the `id` argument of `getDefaultWorldGenerator(worldName, id)` to pick its mode.

---

## Open Questions / Risks

- **`NoiseGeneratorSettings` immutability:** Fields in `NoiseRouter` may be `final`. We'll likely need reflection to patch them, or we rebuild a full new `NoiseGeneratorSettings` registry entry. Terra's `AwfulBukkitHacks` pattern (unfreeze registry → modify → refreeze) is a reference.
- **Biome source coupling:** `MultiNoiseBiomeSource` holds its own reference to climate functions in some versions. Verify whether patching the `NoiseRouter` alone is sufficient, or whether we must also patch the biome source's sampler.
- **Paperweight mappings drift:** `CustomChunkGenerator.delegate` field name may change between Paper versions. Pin to 1.21.4 and add a version check on startup.
- **Thread safety of injection:** `WorldInitEvent` is called from `ServerLevel.tick()` on each world's dedicated `tickExecutor` (not the main thread), and chunk generation immediately follows via Moonrise's concurrent system. All injection logic and the delegate itself must be thread-safe. The delegate is shared across multiple chunk generation threads.
