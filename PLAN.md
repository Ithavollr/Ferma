# Ferma Noise Authority — Implementation Plan

Ferma controls ALL noise for any world for which it is registered, and passes through to
vanilla where it is not. Above all else, Ferma must NOT corrupt world data: any possibility
of crossover or conflict between vanilla, Ferma, and other sources (datapacks, plugins)
fails loudly and prevents mixed or invalid generation by any means necessary, including
de-registering the world or falling back to vanilla. Priority when these conflict: never
corrupt data, then prevent mixed generation, then fail loudly, then keep the server running.
Ferma feeds the server's generators custom noise; it does not implement or control
generators. Refusals are never trippable by vanilla behavior (game rules, player actions,
seeds, vanilla presets) — only by external modification or version drift.

## Phase 1 — Graph surgery replaces the terrain rebuild ✅ (implemented 2026-08-24; core verification complete)

Status: implemented and fully verified — unit tests green, `runServerTest` green,
new-world playtest confirms vanilla generation; assertion PASS on amplified and
large_biomes worlds (occurrence counts identical across all three coupled variants,
confirming the shared graph structure); Tectonic world creation refused cleanly through
Multiverse via the primary gate.

1. Implement a replacement visitor in `FermaNoiseRouter`:
   - Obtain the canonical climate node for continents/erosion/ridges by unwrapping each
     wired router field (`HolderHolder` -> `MarkerOrMarked` -> inner node).
   - `mapAll` over the wired `depth`, `initialDensityWithoutJaggedness`, `finalDensity`,
     replacing every node structurally equal (`equals`) to a canonical inner node with the
     pack's climate function. Marker wrappers are preserved (replacement happens at the
     inner-node level).
   - Router fields temperature/vegetation/continents/erosion/ridges are replaced directly;
     the `Climate.Sampler` is rebuilt from the patched router (existing mechanism).
2. Convert `GraphSurgeryDiagnostic` into the assertion gate:
   - Assertion A: every canonical climate node is found in the terrain graphs.
   - Assertion B: every occurrence is Marker-wrapped.
   - Primary gate — before world creation: graph structure is seed-independent and
     datapacks are server-global, so the assertions run against a throwaway
     `RandomState.create(<settings from registry>, noiseRegistry, 0L)` in
     `Ferma.getDefaultWorldGenerator`. On failure, throw there — the same guard style as
     the existing unknown-pack-id check. The exception propagates out of
     `WorldCreator.createWorld()`: Bukkit aborts the world and Multiverse reports it as
     `BUKKIT_CREATION_FAILED` with our message in the stack trace. On the bukkit.yml path
     `CraftServer.getGenerator` catches it instead, and the world loads vanilla.
   - Backstop — at `WorldInitEvent`, which Aincrad fires on the main thread from both
     `MinecraftServer.loadWorld0` (bukkit.yml worlds) and `CraftServer.createWorld`: re-run
     the assertions against the world's real wired `RandomState` before patching.
   - Every world-init refusal (assertion failure, unsupported settings class, policy
     violation, patching error) takes one path: two SEVERE lines in the `Ferma.java:89-90`
     shape (what was refused and why, then the consequence); skip the router/sampler patch;
     leave the server running. Vanilla fallback comes from the delegate wrapping vanilla, or
     where no delegate is installed, from `FermaChunkGenerator.shouldGenerateNoise()`
     returning true outside VOID mode.
3. Delete the rebuild apparatus from `FermaNoiseRouter`: `rebuildTerrainInputs`,
   `rebuildInitialDensity`, `rebuildFinalDensity`, all cave rebuilders, `NoiseAccess`,
   the `WeirdScaledSampler` reflection block, `PeaksAndValleysFunction`, the `amplified`
   flag, and the legacy `PatchMode` entry point. Amplified and large-biomes variants are
   inherited from the wired graph.
4. Verify: unit tests pass; `runServerTest`; playtest `vanilla_noise` for 1.21.4 parity;
   diagnostic PASS on overworld, amplified, and large_biomes worlds, and on a
   bukkit.yml-registered `world`; a refused world generates vanilla terrain and boots.

## Phase 2 — depth becomes a configurable parameter (decoupled settings only) ✅ (implemented and verified 2026-08-25)

Status: `layered_nether` generates a correct nether with altitude-stratified 3D biomes. With
a noise-altering nether datapack (PacMan's) installed it is refused instead and falls back to
vanilla — the intended behaviour, not a bug.

Policy: Ferma never replaces composite noises (functions derived from other climate
functions). `depth` on the overworld family (`add(y_clamped_gradient, <variant>/offset)`)
is the only composite sampler axis across all seven vanilla settings; everywhere else it
is a constant.

1. Add `depth` to the valid climate parameters in `PackLoader`. Unconfigured depth uses the
   wired graph's depth unchanged.
2. A pack that configures `depth` on a coupled-settings world (`minecraft:overworld`,
   `minecraft:amplified`, `minecraft:large_biomes`) is refused, citing the composite-noise
   policy; the world falls back to vanilla.
3. On decoupled settings, configured depth replaces the constant depth router field and is
   included in the sampler rebuild (no terrain occurrences exist).
4. Add a `y_gradient` climate function type (`from_y`, `to_y`, `from_value`, `to_value`),
   mirroring vanilla `yClampedGradient` semantics.
5. Verify: a coupled-settings world with a depth-configured pack is refused and generates
   vanilla; a decoupled world with configured depth loads and generates; `layered_nether`
   stratifies nether biomes by altitude; unconfigured worlds match Phase 1 output.

Note on where depth is observable: biome selection sums squared per-parameter distances
and takes the minimum (`Climate.java:416-424`), so a parameter only changes placement when
candidate biomes differ in it. All five vanilla nether biomes have depth target 0.0, so
configured depth is inert there — y-varying *temperature* (or humidity) is the lever that
stratifies a vanilla nether. Depth changes placement where biomes differ in depth: the
overworld biome set (lush caves, dripstone, deep dark) as used by caves-settings worlds,
and any datapack biomes with varied depth targets.

## Phase 3 — settings inheritance ✅ (implemented and verified 2026-08-24)

Status: unit tests green, `runServerTest` green. Live: coupled worlds (overworld,
amplified, large_biomes) pass Assertions A and B; a nether world passes the decoupled
baseline and generates — the first working decoupled Ferma world; an end world is refused
by settings class and falls back to vanilla.

1. Ferma inherits the world's noise settings from world creation (vanilla dimension
   defaults, world type, or Multiverse `-t`); Ferma never selects or overrides them. The
   settings identity is read from the registry key of `NoiseBasedChunkGenerator.settings`
   (read-only; the field is never mutated).
2. Select the assertion baseline by settings key:
   - Coupled settings (`minecraft:overworld`, `minecraft:amplified`,
     `minecraft:large_biomes`): Assertions A and B as in Phase 1.
   - Decoupled settings (`minecraft:caves`, `minecraft:floating_islands`,
     `minecraft:nether`): assert the climate router fields are constants or plain shifted
     noise AND the terrain graphs contain no climate references; the surgery is then
     router-field + sampler replacement only.
   - `minecraft:end`: Phase 4 baseline.
   - Any other settings key, or a graph not matching its settings' baseline, aborts world
     creation.
3. Verify: diagnostic PASS per settings, including a bukkit.yml-registered `world`.

## Phase 4 — end worlds

1. `minecraft:end` settings baseline: erosion router field is `cache_2d(end_islands)`; all other climate
   fields are constants; the terrain graph embeds an `EndIslandDensityFunction`.
2. Add an `end_islands` climate function type: a Ferma reimplementation of vanilla's
   end-island height function (vanilla `SimplexNoise` instantiated directly; the height
   formula reimplemented) with vanilla's constants as configurable parameters and
   defaults — `island_threshold` (-0.9), `falloff` (8.0), cell scale (8/2), size formula
   constants. `end_islands` is a self-contained leaf function and is not subject to the
   composite-noise policy.
3. A pack-configured erosion replaces the erosion router field and the sampler; end biome
   rings (highlands/midlands/barrens/small islands) follow the pack noise.
4. Terrain coupling: replace `EndIslandDensityFunction` occurrences in the terrain graphs
   by type match (`instanceof`) with the pack's erosion function.
5. The central-island override (biome source returns `the_end` within the 4096-section
   radius) is vanilla behavior and remains.
6. Verify: an `end_islands` pack with vanilla defaults is vanilla-equivalent in biome rings
   and terrain; a modified-period pack shifts biome rings and terrain together; an
   unconfigured end world is vanilla-identical.

## Limitations (accepted)

- Ferma supplies noise functions only. The end's central-island biome override (the fixed
  4096-section radius in `TheEndBiomeSource`) is generator logic and is not tunable.
- Composite noises are never replaced: `depth` on the overworld family stays derived;
  packs configuring it there fail at world init.
- Aquifer noises, ore-vein noises, surface-rule noises, and structure placement randoms
  pass through unchanged in all phases. They become configurable in a later plan without
  architecture changes.
- No density-function composition language in `pack.yml`. World settings come from world
  creation; noise comes from Ferma types.
- `minecraft:caves` and `minecraft:floating_islands` worlds exist only where world creation
  can reach them (no vanilla world preset or Multiverse type selects them). Ferma supports
  them when present but does not manufacture them.
- A Minecraft/Aincrad version change that restructures a vanilla graph fails worlds loudly
  until the baselines are re-verified against the new version.
- A world patched on one boot and refused (or unpatched) on another accumulates chunks from
  two regimes. Ferma persists nothing per world, so it cannot yet detect this; unresolved.
- PacMan ships noise-altering datapacks, so every Ferma world on a server running both is
  refused and falls back to vanilla. Scope this before the two components ship together.
