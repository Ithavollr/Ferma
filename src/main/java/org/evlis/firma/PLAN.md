# Ferma Roadmap

Four-stage roadmap for the Ferma plugin. Each stage builds on the last.

- **Stage 1:** Faithful vanilla pass-through (✅ done)
- **Stage 2:** Noise parameter override — hardcoded noise primitive library + climate function plumbing (🟡 plumbing done; primitives stubbed as constants)
- **Stage 3:** Void / static mode for empty worlds
- **Stage 4:** Pack-based configuration — load `pack.yml` files (Terra-style) that wire climate parameters to any of the hardcoded primitives or constants. **Stage 4 supersedes Stage 2's `Ferma:noise` selector entirely**: once packs ship, the only way to engage custom climate noise is via a pack, and the simplest possible pack (just an `id`, no `climate` section) reproduces the current Stage 2 identity behavior.

## Architectural Overview

Ferma sits at two layers of Minecraft's generation pipeline:

1. **Bukkit layer** — `FermaChunkGenerator` (extends `org.bukkit.generator.ChunkGenerator`)
   - Returned by `Ferma.getDefaultWorldGenerator()`
   - Serves as the **marker** that tells `NMSInjectListener` this is a Ferma world
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

**Critical Context:** This server fork uses **multithreaded world ticking** and **Moonrise concurrent chunk generation**. `WorldInitEvent` fires on the main thread, before any chunk generation, from both `MinecraftServer.loadWorld0` and `CraftServer.createWorld`. What it installs — the patched router, sampler, and delegate — is then read concurrently by Moonrise's generation workers and each world's `ServerLevelTickThread`, so all of it must be thread-safe.

**Tasks:**
- **Thread-safe injection:** `NMSInjectListener` must handle concurrent world initialization. Use `ConcurrentHashMap` for the injected-worlds tracking instead of `HashSet` + `ReentrantLock`.
- Unwrap `CustomChunkGenerator` in `NMSInjectListener` to obtain the real `NoiseBasedChunkGenerator`. Fail loudly if it isn't a `CustomChunkGenerator` — we need the real thing for Stage 2 anyway.
- `NMSChunkGeneratorDelegate` holds that real vanilla generator and delegates every method to it verbatim. The vanilla generator is already thread-safe for concurrent chunk generation.
- `FermaChunkGenerator` at the Bukkit layer:
  - **Does not override `generateNoise`** (removing the current empty override eliminates the warning loop).
  - Introduces a `GenerationMode` enum field: `VANILLA` (default), `NOISE_OVERRIDE` (Stage 2), `VOID` (Stage 3).
  - Reads its mode from the generator ID passed to `getDefaultWorldGenerator(worldName, id)` — e.g. `generator: Ferma:vanilla`, `generator: Ferma:noise`, `generator: Ferma:void`. Default is `VANILLA`.

**Verification:** Generate a world, compare terrain to a pure-vanilla world with the same seed. Must be bit-identical.

---

## Stage 2 — Noise Parameter Override (hardcoded primitive library)

**Goal:** Build the **fixed library of noise primitives and helpers** that future packs (Stage 4) will compose, plus the NMS plumbing to swap them into the vanilla `NoiseRouter`. In Stage 2, the wiring of climate parameters → primitives is **hardcoded in `FermaNoiseRouter`** for now (e.g. constants for testing, identity passthrough for vanilla parity). Stage 4 will replace those hardcoded selections with YAML-driven configuration.

**What lives here forever (the "primitive library" — never user-editable):**
- `PerlinNoiseSampler`, `OctavePerlinNoiseSampler`, `DoublePerlinNoiseSampler`
- `XoroshiroRandomSource`, `PositionalRandomFactory`
- `ShiftedNoise`, `ClimateFunctions` (weirdness→ridges, y-clamped gradient)
- `FermaClimateFunction.Constant`, `FermaClimateFunction.Identity`
- The `NoiseRouter`-patching plumbing in `FermaNoiseRouter` and `NMSInjectListener`

**What is temporary in Stage 2 (will be removed by Stage 4):**
- The hardcoded selection inside `FermaNoiseRouter.create*Function()` methods
- The `PatchMode` enum with fixed presets like `IDENTITY` / `CONSTANT_HOT`
- The `Ferma:noise` generator ID — Stage 4 removes this entirely. Packs become the *only* user-facing way to enable custom climate noise. (`PatchMode` itself may survive as an internal test fixture, but it will no longer be reachable from `bukkit.yml`.)

Vanilla still does everything else (terrain shape, carving, surface, decoration, mobs, structures) — only the **6 climate noise functions** (temperature, humidity, continentalness, erosion, weirdness, depth) are supplied by Ferma.

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
- Add a `FermaNoiseRouter` utility that takes the vanilla `NoiseRouter` and returns a modified copy with our `DensityFunction` implementations swapped in for the 6 climate fields.
- In `NMSInjectListener`, when mode is `NOISE_OVERRIDE`:
  - Access the vanilla generator's `NoiseGeneratorSettings` via its `Holder<NoiseGeneratorSettings>`.
  - Build a replacement `NoiseGeneratorSettings` with a patched `NoiseRouter`.
  - Construct a new `NoiseBasedChunkGenerator` using the replacement settings, or patch the existing one via reflection if the fields are final.
  - Wrap that in `NMSChunkGeneratorDelegate` and inject.
- Provide a `FermaClimateFunction` interface — implementing `DensityFunction` — where we can plug in arbitrary climate logic for each of the 6 parameters:
  ```java
  public interface FermaClimateFunction extends DensityFunction {
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

**Concept:** This is the one case where `FermaChunkGenerator`'s Bukkit-level override is actually useful and should remain. In this mode, we do **not** want vanilla noise generation at all.

**Thread-Safety Note:** Even in VOID mode, Moonrise may call `generateNoise` concurrently. The empty implementation is inherently thread-safe (no-op).

**Tasks:**
- When mode is `VOID`:
  - `FermaChunkGenerator.generateNoise` is overridden with an empty implementation (genuinely a no-op — no warnings, this is intentional).
  - `shouldGenerateSurface`, `shouldGenerateCaves`, `shouldGenerateBedrock`, `shouldGenerateDecorations`, `shouldGenerateStructures`, `shouldGenerateMobs` all return `false`.
  - `NMSInjectListener` may *skip* NMS injection for void worlds, since there's nothing to intercept — `CustomChunkGenerator` calling our empty `generateNoise` is exactly the desired behavior. Alternatively, inject an `NMSChunkGeneratorDelegate` variant whose `fillFromNoise` returns the chunk unchanged.
- Optional: detect already-generated static worlds via a marker file or config, and auto-set `VOID` mode so newly-loaded chunks at the edges are empty rather than extending with fresh vanilla terrain.

**Verification:**
- New world with `generator: Ferma:void` produces a completely empty world (fall-through to the void).
- No warnings or errors in the log.

---

## Stage 4 — Pack-Based Configuration (YAML, Terra-style)

**Goal:** Replace the hardcoded climate-function selection in `FermaNoiseRouter` with **user-authored packs**. A pack is a single `pack.yml` file in `plugins/Ferma/packs/<pack_id>/pack.yml` that declares which Stage 2 primitive (or constant) feeds each of the 6 climate parameters. The Stage 2 primitive library (Perlin/octave/double-Perlin/shifts/etc.) is fixed and not user-editable; packs only **wire** primitives together with parameters.

**Inspiration:** Terra's pack model — but radically simpler. A Ferma pack is a single YAML file, not a directory tree. No scripting, no expression language, no biome configuration (vanilla biomes still apply). Just: "for parameter X, use this primitive with these parameters."

**Pack Lifecycle:**
1. On plugin enable, scan `plugins/Ferma/packs/*/pack.yml`
2. Parse each pack into a `FermaPack` object (pack id, display name, climate-function definitions)
3. Register each pack as a selectable mode: `generator: Ferma:<pack_id>` (e.g., `Ferma:frozen_world`)
4. When a world with that generator ID initializes, `NMSInjectListener` builds the `NoiseRouter` from the pack's definitions

**Removal of `Ferma:noise`:** When Stage 4 lands, the `Ferma:noise` generator ID is **removed**. There is no longer a hardcoded path from `bukkit.yml` to custom climate noise — every custom-noise world routes through a pack. The minimal pack below replaces the old `Ferma:noise` use case 1-for-1:

```yaml
# plugins/Ferma/packs/passthrough/pack.yml
id: passthrough
name: "Passthrough"
# no climate section → all 6 parameters default to identity → bit-identical vanilla
```

Used as `generator: Ferma:passthrough`, this is the Stage 4 equivalent of the Stage 2 identity test and serves as the canonical regression check that the pack pipeline doesn't perturb vanilla output.

**Pack YAML Schema (draft):**

```yaml
# plugins/Ferma/packs/frozen_world/pack.yml
id: frozen_world
name: "Frozen World"
description: "Always-cold temperature, vanilla everything else"

climate:
  temperature:
    type: constant
    value: -1.0

  humidity:
    type: identity      # delegate to vanilla

  continentalness:
    type: double_perlin
    first_octave: -9
    amplitudes: [1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0]
    xz_scale: 0.25
    y_scale: 0.0

  erosion:
    type: identity

  weirdness:
    type: identity

  depth:
    type: y_clamped_gradient
    min_y: -64
    max_y: 320
```

**Supported `type` values (each maps to a Stage 2 primitive):**

| `type` | Backed by | Required fields |
| --- | --- | --- |
| `constant` | `FermaClimateFunction.Constant` | `value` (double, clamped to `[-1, 1]`) |
| `identity` | `FermaClimateFunction.Identity` | *(none — passes through vanilla)* |
| `perlin` | `PerlinNoiseSampler` (single octave) | `xz_scale`, `y_scale` |
| `octave_perlin` | `OctavePerlinNoiseSampler` | `first_octave`, `amplitudes` (list), `xz_scale`, `y_scale` |
| `double_perlin` | `DoublePerlinNoiseSampler` | `first_octave`, `amplitudes`, `xz_scale`, `y_scale` |
| `shifted_noise` | `ShiftedNoise` wrapping a `double_perlin` | inner config + `shift_x` / `shift_z` configs |
| `weirdness_to_ridges` | `ClimateFunctions.weirdnessToRidges` over a child noise | `source` (nested config) |
| `y_clamped_gradient` | `ClimateFunctions.yClampedGradient` | `min_y`, `max_y` |

Adding a new `type` requires writing a Java implementation in the primitive library — this is intentional. Packs *configure*, not *script*.

**Tasks:**
- New `org.evlis.firma.pack` package:
  - `FermaPack` — parsed pack record (id, name, map of parameter → `ClimateFunctionConfig`).
  - `ClimateFunctionConfig` — sealed/tagged record per `type`, holding parsed parameters.
  - `PackLoader` — scans `plugins/Ferma/packs/`, parses YAML via SnakeYAML (already pulled in by Bukkit/Paper), validates schema, returns `Map<String, FermaPack>`.
  - `ClimateFunctionFactory` — given a `ClimateFunctionConfig` + world seed + parameter name, instantiates a `FermaClimateFunction` using the Stage 2 primitives. Each parameter gets its own `PositionalRandomFactory` slot (seeded by `worldSeed ^ hash("<pack_id>:<parameter>")`) so configs are deterministic and independent.
- `Ferma.java` startup:
  - Call `PackLoader.loadAll()` and store the result.
  - In `getDefaultWorldGenerator(worldName, id)`, if `id` is not a reserved word (`vanilla`, `void`), treat it as a pack ID and look up the pack. Reject unknown pack IDs with a clear error message.
- `FermaChunkGenerator`:
  - Replace `GenerationMode.NOISE_OVERRIDE` with `GenerationMode.PACK` carrying a resolved `FermaPack`. (`VANILLA` and `VOID` remain unchanged.)
  - Reject the legacy `Ferma:noise` ID at startup with a clear migration message pointing to the `passthrough` pack example.
- `FermaNoiseRouter`:
  - Replace the `PatchMode`-based public entry point with `patchClimateFunctions(NoiseRouter, RandomState, long seed, FermaPack pack)`.
  - For each of the 6 climate parameters, call `ClimateFunctionFactory.build(pack.climate().get("temperature"), ...)` (or default to identity if the parameter is absent from the pack) to produce the `FermaClimateFunction`.
  - The internal `PatchMode` enum may be retained as a unit-test-only fixture, but is no longer reachable from production code paths.
- `NMSInjectListener`:
  - Only `PACK` mode triggers `NoiseRouter` patching. The mode-dispatch branch for `NOISE_OVERRIDE` is removed.

**Validation Rules:**
- Pack id must match folder name and be `[a-z0-9_]+`.
- Pack id must not be a reserved word: `vanilla`, `void`, `noise`.
- All unspecified parameters default to identity (vanilla).
- Unknown `type` → log error + skip pack registration (do not crash startup).
- Numeric ranges checked at parse time (e.g. `value` for `constant` must be in `[-1, 1]`).

**Verification:**
- The minimal `passthrough` pack (id only, no `climate` section) must produce bit-identical vanilla terrain — this is the Stage 4 replacement for the Stage 2 identity test.
- The `frozen_world` pack from the schema example above must produce the same effect as the current hardcoded `CONSTANT_HOT` test, but with `temperature: -1.0`.
- Two packs with different ids running in two worlds simultaneously must not interfere (per-world `NoiseRouter` isolation).
- Loading a world with the legacy `generator: Ferma:noise` must fail fast with a clear migration error pointing to the pack system.

---

## Mode Selection Syntax

```yaml
# bukkit.yml — pre-Stage-4
worlds:
  overworld_vanilla:
    generator: Ferma                          # defaults to VANILLA
  overworld_tweaked:
    generator: Ferma:noise                    # Stage 2 only: hardcoded climate overrides (REMOVED in Stage 4)
  static_world:
    generator: Ferma:void                     # Stage 3: empty chunks
```

```yaml
# bukkit.yml — Stage 4 and beyond
worlds:
  overworld_vanilla:
    generator: Ferma                          # defaults to VANILLA
  overworld_passthrough:
    generator: Ferma:passthrough              # replaces Ferma:noise — id-only pack, all parameters default to identity
  static_world:
    generator: Ferma:void                     # Stage 3: empty chunks
  frozen_world:
    generator: Ferma:frozen_world             # Stage 4: pack-driven configuration
```

`FermaChunkGenerator` parses the `id` argument of `getDefaultWorldGenerator(worldName, id)`:
- Empty or missing → `VANILLA`
- `vanilla` → `VANILLA`
- `void` → `VOID`
- Any other value → treated as a pack ID (must exist in loaded packs)

---

## Open Questions / Risks

- **`NoiseGeneratorSettings` immutability:** Fields in `NoiseRouter` may be `final`. We'll likely need reflection to patch them, or we rebuild a full new `NoiseGeneratorSettings` registry entry. Terra's `AwfulBukkitHacks` pattern (unfreeze registry → modify → refreeze) is a reference.
- **Biome source coupling:** `MultiNoiseBiomeSource` holds its own reference to climate functions in some versions. Verify whether patching the `NoiseRouter` alone is sufficient, or whether we must also patch the biome source's sampler.
- **Paperweight mappings drift:** `CustomChunkGenerator.delegate` field name may change between Paper versions. Pin to 1.21.4 and add a version check on startup.
- **Thread safety of injection:** `WorldInitEvent` fires on the main thread (a `TickThread`, not a `ServerLevelTickThread`) before any chunk generation, so the injection itself runs single-threaded and unraced. Everything it installs must still be thread-safe: the delegate and the patched density functions are read concurrently by Moonrise's generation workers and each world's `ServerLevelTickThread`.
- **Stage 4 — pack reload semantics:** Initial implementation will load packs at plugin enable only. Hot-reloading mid-server is risky and will not be enabled.
- **Stage 4 — schema versioning:** `pack.yml` should include a `schema_version: 1` field from day one so future schema changes can be detected and migrated cleanly.
- **Stage 4 — registering generator IDs dynamically:** Bukkit picks the generator at world load via the static `Ferma:<pack_id>` string, but `FermaChunkGenerator` must validate the pack exists at construction and fail loudly with a clear message if a referenced pack id is missing.
