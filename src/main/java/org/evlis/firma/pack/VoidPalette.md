# Void Palette — Implementation Plan

Adds a `palette` field to void worlds that lets pack creators place specific blocks at specific Y coordinates,
primarily for starting platforms. Inspired by Terra's palette system but much simpler — just a flat map of
Y-coordinate to block type, applied once per chunk at generation time.

## Current state

- Void worlds are configured via `bukkit.yml` generator string: `Firma:void`
- `FirmaChunkGenerator` returns empty chunks when `mode == GenerationMode.VOID`
- `NMSChunkGeneratorDelegate.fillFromNoise()` returns chunk as-is in void mode
- Void mode has NO pack association — it's a reserved keyword, not a pack id
- There is no way to customize what blocks appear in a void world

## Design

### Pack format

Void packs live alongside climate packs in `plugins/Firma/packs/<id>/pack.yml`.
They declare `type: void` to distinguish from climate packs (which are `type: pack` or have no type, defaulting to pack).

```yaml
id: void_platform
name: "Void with Platform"
description: "Void world with a bedrock-and-grass starting platform"
type: void

palette:
  319: minecraft:air       # everything above 319 is air (implicit, but explicit here)
  64: minecraft:grass_block
  63: minecraft:dirt
  62: minecraft:dirt
  61: minecraft:dirt
  0: minecraft:bedrock
  # all unspecified Y levels are air
```

The palette is a simple mapping: `Y-coordinate -> block`. Unspecified Y levels default to `minecraft:air`.
Each entry fills the **entire chunk** at that Y level (all 16×16 columns get the same block at that Y).
This is intentionally limited — it's for flat platforms, not terrain.

### How it integrates

- `GenerationMode` gets no new enum value — void palette worlds are still `VOID` mode.
- `FirmaChunkGenerator` stores an optional `VoidPalette` alongside the mode.
- When `mode == VOID` and a palette is present, `NMSChunkGeneratorDelegate.fillFromNoise()` writes the palette
  blocks into the chunk instead of returning it empty.
- The palette is applied uniformly to every chunk (the whole world is one flat layer pattern).

## Implementation steps

### Step 1: Parse palette from pack YAML

**Files:** `VoidPalette.java` (new), `PackLoader.java`

- Create `VoidPalette` record: holds a `Map<Integer, String>` of Y -> block resource location (e.g. `"minecraft:bedrock"`).
- In `PackLoader`, detect `type: void` in pack YAML. Parse the `palette` map into a `VoidPalette`.
- Validate: Y values must be in `[-64, 319]`, block ids must be valid `minecraft:*` resource locations.
- A void pack with no palette section is valid (produces a fully empty void world, same as today).
- Store the parsed `VoidPalette` on a new field in `FirmaPack` (nullable — null for non-void packs).

**Build verification:** `./gradlew build` compiles. Existing tests pass. No runtime behavior changes yet.

### Step 2: Route void palette through generator setup

**Files:** `FirmaChunkGenerator.java`, `Firma.java`, `NMSInjectListener.java`

- When `Firma.getDefaultWorldGenerator()` receives a pack id that resolves to a void-type pack, create
  `FirmaChunkGenerator` with `GenerationMode.VOID` and attach the `VoidPalette` from the pack.
- `FirmaChunkGenerator` gets a `@Nullable VoidPalette palette` field and a getter.
- `NMSInjectListener` reads the palette from `FirmaChunkGenerator` and passes it to
  `NMSChunkGeneratorDelegate` (new constructor parameter or setter).
- The bare `Firma:void` keyword (no pack) continues to work as a fully empty void world (null palette).

**Build verification:** `./gradlew build` compiles. Void worlds still generate empty. Palette is parsed and threaded through but not yet applied.

### Step 3: Apply palette in chunk generation

**Files:** `NMSChunkGeneratorDelegate.java`

- In `fillFromNoise()`, when `voidMode && palette != null`:
  - For each entry in the palette, resolve the block string to a `BlockState` (via `BuiltInRegistries.BLOCK`
    or `BlockStateParser`).
  - Set every block in the chunk at that Y level to the resolved `BlockState` (iterate x=0..15, z=0..15).
  - Block resolution should be cached (done once at construction, not per-chunk).
- When `voidMode && palette == null`: existing behavior (empty chunk).

**Build verification:** `./gradlew build` compiles. Create a void pack with a palette and verify blocks appear at the specified Y levels in-game.

### Step 4: Tests and validation

**Files:** `VoidPaletteTest.java` (new)

- Test palette parsing: valid YAML produces correct `VoidPalette`.
- Test validation: out-of-range Y values, invalid block ids, and missing palette section.
- Test that void packs without palette still produce empty worlds.
- Test that non-void packs ignore the palette field (or warn if present).

**Build verification:** `./gradlew test` passes all new tests.
