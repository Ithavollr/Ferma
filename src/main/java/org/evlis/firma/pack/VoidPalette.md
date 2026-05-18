# Void Palette — Implementation Plan

Adds a `palette` field to void packs that lets pack creators place specific blocks at specific **world
coordinates**, primarily for starting platforms. Mirrors Terra's palette format: each entry is a
`[x, y, z]: block_id` mapping. Only those exact positions are set; everything else stays air.

## Current state

- Void worlds are configured via `bukkit.yml` generator string: `Firma:void`
- `FirmaChunkGenerator` returns empty chunks when `mode == GenerationMode.VOID`
- `NMSChunkGeneratorDelegate.fillFromNoise()` returns chunk as-is in void mode
- Void mode has NO pack association — it's a reserved keyword, not a pack id
- There is no way to customize what blocks appear in a void world

## Design

### Pack format

Void packs live alongside climate packs in `plugins/Firma/packs/<id>/pack.yml`.
They declare `type: void` to distinguish from climate packs (which are `type: pack` or have no type).

```yaml
id: void_platform
name: "Void with Platform"
description: "Void world with a 5x5 bedrock platform at 0,64,0"
type: void

palette:
  "[0, 64, 0]": minecraft:bedrock
  "[1, 64, 0]": minecraft:bedrock
  "[0, 64, 1]": minecraft:bedrock
  "[-1, 64, 0]": minecraft:bedrock
  "[0, 64, -1]": minecraft:bedrock
```

Each entry places **exactly one block** at `[x, y, z]` in world coordinates. All other positions remain
air. Coordinates can be any valid world position (x/z unbounded, y in `[-64, 320]`).

### Architecture: `VoidChunkHandler`

Void-specific logic is pulled out of `NMSChunkGeneratorDelegate` into a dedicated
`VoidChunkHandler` class (`org.evlis.firma.pack.VoidChunkHandler`). This keeps the delegate
as a pure passthrough wrapper with a single `if (voidMode)` guard, while `VoidChunkHandler`
owns all void-world behavior:

- **Holds** the `VoidPalette` (nullable) and the resolved `Map<String, BlockState>` cache.
- **Provides** `fillChunk(ChunkAccess chunk)` — looks up palette entries for the chunk and
  sets blocks. No-ops if palette is null (bare `Firma:void`).
- **Extensible** — future void features (spawn platform rules, void-world gamerules, etc.)
  live here, not scattered across the delegate.

`NMSChunkGeneratorDelegate.fillFromNoise()` stays clean:
```java
if (voidMode) {
    voidHandler.fillChunk(chunk);
    return CompletableFuture.completedFuture(chunk);
}
return vanilla.fillFromNoise(...);
```

### How it integrates

- `GenerationMode` gets no new enum value — void palette worlds are still `VOID` mode.
- The `Firma:void` bare keyword continues to work as a fully empty void world (no palette).
- Void packs declare `type: void` and are associated with a pack id (e.g. `Firma:void_platform`).
- `NMSInjectListener` creates a `VoidChunkHandler` (with or without palette) and passes it
  to `NMSChunkGeneratorDelegate`.

### Coordinate → chunk lookup

For a given chunk at `(chunkX, chunkZ)`, a palette entry at `(x, y, z)` belongs to this chunk iff:
```
Math.floorDiv(x, 16) == chunkX && Math.floorDiv(z, 16) == chunkZ
```
The local position within the chunk is `(x & 15, y, z & 15)` (using bitwise AND, works for negative coords too).

The palette is indexed at load time into a `Map<Long, List<PaletteEntry>>` keyed by `chunkKey(chunkX, chunkZ)`
(same encoding as `ChunkPos.asLong`) so per-chunk lookup is O(1).

## Implementation steps

### Step 1: Parse palette from pack YAML

**Files:** `VoidPalette.java` (new), `PackLoader.java`, `FirmaPack.java`

- `VoidPalette` is a record holding:
  - `List<PaletteEntry> entries` — raw list of `(x, y, z, blockId)` tuples.
  - `Map<Long, List<PaletteEntry>> byChunk` — pre-indexed by `ChunkPos.asLong(chunkX, chunkZ)`.
- `PaletteEntry` is a record: `(int x, int y, int z, String blockId)`.
- In `PackLoader`, detect `type: void`. Parse the `palette` map where keys are `"[x, y, z]"` strings and
  values are block resource locations (e.g. `"minecraft:bedrock"`).
- Validate: y in `[-64, 320]`, block ids must match `[a-z0-9_]+:[a-z0-9_/]+`.
- A void pack with no `palette` section is valid (fully empty void world).
- `FirmaPack` gets a `@Nullable VoidPalette voidPalette` field (null for non-void packs).

**Build verification:** `./gradlew build` compiles. Existing tests pass. No runtime behavior changes yet.

### Step 2: Create `VoidChunkHandler`

**Files:** `VoidChunkHandler.java` (new, in `org.evlis.firma.pack`)

- Constructor: `VoidChunkHandler(@Nullable VoidPalette palette)`.
- At construction, resolve all `blockId` strings in the palette to `BlockState` objects
  (via `BuiltInRegistries.BLOCK`). Cache in a `Map<String, BlockState>`. Log and skip unknowns.
- `void fillChunk(ChunkAccess chunk)`:
  - If palette is null, return immediately (empty void world).
  - Look up `palette.byChunk.get(ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z))`.
  - If null (no entries in this chunk), return.
  - For each `PaletteEntry`, call `chunk.setBlockState(new BlockPos(x, y, z), resolvedState, false)`.

**Build verification:** `./gradlew build` compiles. Class exists but is not wired yet.

### Step 3: Route through generator setup

**Files:** `FirmaChunkGenerator.java`, `Firma.java`, `NMSInjectListener.java`, `NMSChunkGeneratorDelegate.java`

- `Firma.getDefaultWorldGenerator()` with a `type: void` pack id creates `FirmaChunkGenerator` with
  `GenerationMode.VOID` and stores the `VoidPalette` from the pack.
- `FirmaChunkGenerator` gets a `@Nullable VoidPalette palette` field and getter.
- `NMSInjectListener` creates `VoidChunkHandler(palette)` and passes it to
  `NMSChunkGeneratorDelegate` (new constructor parameter: `@Nullable VoidChunkHandler`).
- `NMSChunkGeneratorDelegate.fillFromNoise()` calls `voidHandler.fillChunk(chunk)` when in void mode.
- The bare `Firma:void` keyword produces a `VoidChunkHandler(null)` (empty world, no change from today).

**Build verification:** `./gradlew build` compiles. Create a void pack with a palette and verify only
the specified coordinates receive blocks in-game.

### Step 4: Tests and validation

**Files:** `VoidPaletteTest.java` (new), `VoidChunkHandlerTest.java` (new, in `org.evlis.firma.pack`)

- **VoidPaletteTest:**
  - YAML parsing: `"[0, 64, 0]": minecraft:bedrock` → `PaletteEntry(0, 64, 0, "minecraft:bedrock")`.
  - Negative coordinates: `"[-16, 64, -16]"` maps to chunk `(-1, -1)`.
  - Chunk indexing: entries are correctly bucketed into `byChunk`.
  - Validation: y out of range, malformed key, invalid block id.
  - Empty palette section → empty void world.
  - Non-void packs → `voidPalette` is null.
- **VoidChunkHandlerTest:**
  - Null palette → `fillChunk` is a no-op.
  - Palette with entries → correct blocks placed at correct chunk-local positions.
  - Entries in different chunks → only matching entries applied per chunk.

**Build verification:** `./gradlew test` passes all new tests.
