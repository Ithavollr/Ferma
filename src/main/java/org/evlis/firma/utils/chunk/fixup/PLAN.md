# Biome Fixup System Design

## Target

- **Minecraft 1.21.4** on **Paper**
- **Online only** — all operations happen while the server is running, using Paper's async chunk API
- **No Chunky imports** — we reference Chunky patterns but do not import its code
- **No direct file I/O** — both phases operate entirely online through Paper's async chunk API + NMS
- **Commands via Aikar ACF** — same pattern as Lunamatic's `LumaCommand`
- **YAML** for all report/mapping files (human-readable, editable)

## Problem

When migrating world generators (e.g., Terra → Ferma, or Ferma:X → Ferma:Y), existing chunk data contains biome IDs that no longer exist in the registry. The server falls back to a default biome (currently `PLAINS`), which causes incorrect behavior like rain in the End dimension or cherry grove.

## Architecture Overview

Two-phase design with human-in-the-loop approval:

1. **Scanner phase** — Walk all generated chunks online via Paper's async chunk API, read biome palettes via NMS, catalog invalid entries → write a YAML report for human review. Proposes fixes.
2. **Fixer phase** — Read a user-approved YAML mapping file, load chunks online via Paper async API, patch biome palettes via NMS `PalettedContainer`, mark dirty, let server save.

This keeps destructive writes separate from discovery and gives the operator full control.

---

## Phase 1: Scanner

### Goal

Produce a **scan report** (`plugins/Firma/fixup/<world>-scan.yml`) that lists every invalid biome string found, the chunks it appears in, and the proposed replacement.

### Chunk Iteration Strategy

Fully online — load chunks through Paper's async API (same approach Chunky uses for generation):

1. Determine world bounds (world border, or user-specified radius)
2. Iterate chunk coordinates within bounds
3. Check if chunk is generated: `world.isChunkGenerated(x, z)`
4. Load generated chunks via Paper async: `world.getChunkAtAsync(x, z)`
5. Access NMS chunk data via `((CraftChunk) chunk).getHandle(ChunkStatus.FULL)`
6. Read biome palette from each `LevelChunkSection.getBiomes()` → extract unique palette entries
7. Validate each palette entry against the live biome registry

Concurrency: Semaphore-bounded (max 50 in-flight loads), same pattern as Chunky's `GenerationTask`.

This is modelled on:
- `Chunky/common/.../GenerationTask.java:124-159` — semaphore, async load, whenComplete
- `Chunky/bukkit/.../platform/BukkitWorld.java:58-112` — isChunkGenerated + getChunkAtAsync

### Biome Palette Reading (NMS, online)

Access path on a loaded chunk (1.21.4):

```java
CraftChunk craftChunk = (CraftChunk) chunk;
LevelChunk nmsChunk = craftChunk.getHandle(ChunkStatus.FULL);
for (LevelChunkSection section : nmsChunk.getSections()) {
    PalettedContainer<Holder<Biome>> biomes = section.getBiomes();
    // Collect unique biome keys from all 4×4×4 = 64 cells
    Set<String> paletteEntries = new HashSet<>();
    for (int x = 0; x < 4; x++)
      for (int y = 0; y < 4; y++)
        for (int z = 0; z < 4; z++) {
            Holder<Biome> holder = biomes.get(x, y, z);
            holder.unwrapKey().ifPresent(k -> paletteEntries.add(k.location().toString()));
        }
    // Check each entry against the registry
}
```

If any palette entry is not in Paper's biome registry, it's invalid.

### Registry Validation

Paper 1.21.4 exposes:
```java
io.papermc.paper.registry.RegistryAccess.registryAccess()
    .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME)
    .get(NamespacedKey.fromString(biomeStr))
```

Returns `null` if the biome is not registered (including datapack biomes that are currently loaded).

### Scanner Output Format

Write to `plugins/Firma/fixup/<world>-scan.yml`:

```yaml
world: world_the_end
dimension: THE_END
scanned_at: "2026-06-01T13:56:00Z"
total_chunks: 14523
error_chunks: 891

invalid_biomes:
  "terra:void_ending":
    count: 891
    proposed_replacement: "minecraft:the_end"
    reason: dimension_default
  "ferma:oldpack:cherry_highland":
    count: 42
    proposed_replacement: ""          # empty = user must decide
    reason: unknown_pack

# Optional detailed list (can be large, toggle with --verbose flag)
chunk_details:
  - x: 10
    z: -5
    region: "r.0.-1.mca"
    invalid_entries:
      - "terra:void_ending"
```

The `proposed_replacement` is our best guess; empty string means the user must fill it in.

### Proposed Replacement Logic

1. **Explicit pre-seed mappings** — `plugins/Firma/fixup/defaults.yml` (user-editable, ships with common mappings)
2. **String reconstruction** — parse namespace, infer from dimension
3. **Dimension default** — fallback per `World.Environment`

| World Type | Default Biome | Rationale |
|------------|---------------|-----------|
| Normal     | `minecraft:plains` | Standard overworld default |
| Nether     | `minecraft:nether_wastes` | Standard nether default |
| End        | `minecraft:the_end` | No precipitation, correct for End |

### Scanner Implementation Steps

| # | Class | Description |
|---|-------|-------------|
| 1 | `ScanTask implements Runnable` | Runs on `Bukkit.getScheduler().runTaskAsynchronously()`. Owns a `Semaphore(50)`, progress counter, stop flag. Iterates chunk coords, loads via Paper async, reads palette via NMS. |
| 2 | `BiomePaletteExtractor` | Given a loaded `LevelChunk`, iterates all sections' `PalettedContainer<Holder<Biome>>` and returns the set of biome key strings. |
| 3 | `BiomeValidator` | Checks strings against `RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)`. |
| 4 | `ScanReport` | Aggregates results, writes YAML via SnakeYAML (bundled with Paper). |
| 5 | `FixupCommand` (scan subcommand) | Aikar `@Subcommand("scan")` — starts the scan, reports progress. |

### Code References (Chunky patterns to follow)

| What | Source reference |
|------|-----------------|
| Semaphore-bounded async chunk loading | `Chunky/common/.../GenerationTask.java:124-159` — acquire, getChunkAtAsync, release in whenComplete |
| Paper async chunk API | `Chunky/bukkit/.../platform/BukkitWorld.java:58-112` — isChunkGenerated + getChunkAtAsync |
| Progress reporting pattern | `Chunky/common/.../GenerationTask.java:58-115` — throttled update with rate calculation |
| NMS biome access (1.21.4) | `CraftChunk.getHandle()` → `LevelChunk.getSections()` → `LevelChunkSection.getBiomes()` → `PalettedContainer<Holder<Biome>>` |

---

## Phase 2: Fixer

### Goal

Read an approved YAML mapping file and rewrite biome palettes **online** for all affected chunks using Paper's async chunk API + NMS.

### Mapping File (input to fixer)

`plugins/Firma/fixup/<world>-fix.yml`:

```yaml
world: world_the_end
mappings:
  "terra:void_ending": "minecraft:the_end"
  "ferma:oldpack:cherry_highland": "minecraft:cherry_grove"
```

The user creates this from the scanner output (edit `proposed_replacement` where empty, save as `-fix.yml`). The `approve` subcommand can auto-generate it from a scan where all proposals are filled.

### Write Strategy (Online via Paper + NMS)

1. Load chunk via Paper async: `world.getChunkAtAsync(x, z, true)` (gen=true ensures it loads)
2. On the main/region thread (Paper returns the chunk on the correct thread):
   ```java
   CraftChunk craftChunk = (CraftChunk) chunk;
   LevelChunk nmsChunk = craftChunk.getHandle(ChunkStatus.FULL);
   for (LevelChunkSection section : nmsChunk.getSections()) {
       PalettedContainer<Holder<Biome>> biomes = section.getBiomes();
       // iterate all 4×4×4 = 64 cells
       for (int x = 0; x < 4; x++)
         for (int y = 0; y < 4; y++)
           for (int z = 0; z < 4; z++) {
               Holder<Biome> current = biomes.get(x, y, z);
               String key = current.unwrapKey().map(k -> k.location().toString()).orElse("");
               if (mappings.containsKey(key)) {
                   Holder<Biome> replacement = biomeRegistry.getOrThrow(
                       ResourceKey.create(Registries.BIOME, ResourceLocation.parse(mappings.get(key)))
                   );
                   biomes.set(x, y, z, replacement);
               }
           }
   }
   nmsChunk.setUnsaved(true);
   ```
3. Concurrency: use a `Semaphore(50)` to cap in-flight async chunk loads (same pattern as Chunky's `GenerationTask.java:124`)
4. Progress: report every N chunks or every T seconds to console

### Fixer Implementation Steps

| # | Class | Description |
|---|-------|-------------|
| 1 | `FixTask implements Runnable` | Async task. Loads mapping YAML, iterates chunk list from scan, loads each chunk via Paper async, applies palette fix, marks dirty. |
| 2 | `NMSBiomePatcher` | Encapsulates the NMS `PalettedContainer` manipulation above. Single method: `patchChunk(LevelChunk, Map<String,String>)` → returns count of cells changed. |
| 3 | `MappingFile` | Loads/validates `<world>-fix.yml`. Ensures all target biome strings are in the registry before starting. |
| 4 | `FixReport` | Writes `<world>-fixreport.yml` with per-chunk stats. |
| 5 | `FixupCommand` (fix subcommand) | Aikar `@Subcommand("fix")` — reads mapping, runs fixer, reports progress. |

### Code References (patterns)

| What | Source reference |
|------|-----------------|
| Async chunk load with semaphore | `Chunky/common/.../GenerationTask.java:124-159` — acquire, getChunkAtAsync, release in whenComplete |
| Paper async chunk API | `Chunky/bukkit/.../platform/BukkitWorld.java:76-112` — Paper.getChunkAtAsync pattern |
| NMS biome access (1.21.4) | `CraftChunk.getHandle()` → `LevelChunk.getSections()` → `LevelChunkSection.getBiomes()` → `PalettedContainer<Holder<Biome>>` |
| Mark dirty | `LevelChunk.setUnsaved(true)` |

---

## Commands (Aikar ACF)

Modelled on `Lunamatic/.../commands/LumaCommand.java`:

```java
@CommandAlias("ferma")
public class FixupCommand extends BaseCommand {
    private final Ferma plugin;

    public FixupCommand(Ferma plugin) { this.plugin = plugin; }

    @Subcommand("scan")
    @CommandPermission("ferma.command.scan")
    @CommandCompletion("@worlds")
    @Description("Scan a world for invalid biome palette entries")
    public void onScan(CommandSender sender, String worldName) { ... }

    @Subcommand("fix")
    @CommandPermission("ferma.command.fix")
    @CommandCompletion("@worlds")
    @Description("Apply biome fixes from an approved mapping file")
    public void onFix(CommandSender sender, String worldName) { ... }

    @Subcommand("approve")
    @CommandPermission("ferma.command.approve")
    @CommandCompletion("@worlds")
    @Description("Generate fix mapping from scan (copies proposals)")
    public void onApprove(CommandSender sender, String worldName) { ... }

    @Subcommand("status")
    @CommandPermission("ferma.command.status")
    @Description("Show progress of running scan/fix task")
    public void onStatus(CommandSender sender) { ... }

    @Subcommand("cancel")
    @CommandPermission("ferma.command.cancel")
    @Description("Cancel running scan/fix task")
    public void onCancel(CommandSender sender) { ... }
}
```

Registration in `Ferma.onEnable()`:
```java
PaperCommandManager manager = new PaperCommandManager(this);
manager.registerCommand(new FixupCommand(this));
```

---

## File Layout

```
src/main/java/org/evlis/firma/utils/chunk/
├── fixup/
│   ├── ScanTask.java                 (async chunk iteration + NMS palette reading)
│   ├── FixTask.java                  (async chunk iteration + NMS palette patching)
│   ├── BiomePaletteExtractor.java    (NMS: LevelChunk → Set<String> of biome keys)
│   ├── BiomeValidator.java           (checks against Paper registry)
│   ├── NMSBiomePatcher.java          (NMS: PalettedContainer write)
│   ├── ScanReport.java               (writes <world>-scan.yml)
│   ├── FixReport.java                (writes <world>-fixreport.yml)
│   ├── MappingFile.java              (reads/validates <world>-fix.yml)
│   └── FixupCommand.java             (Aikar BaseCommand, subcommands)
└── (future: generator/, iterator/)
```

---

## Safety Considerations

- **Read-only scanner** — Phase 1 loads chunks online via Paper async but only reads biome data, never modifies chunk state
- **Explicit approval** — User must produce/edit the mapping YAML before Phase 2 runs
- **Pre-validation** — Fixer validates all target biome strings against the live registry before touching any chunks; aborts if any are invalid
- **Semaphore-bounded** — Max 50 in-flight chunk operations (configurable via system property)
- **Mark dirty only** — We call `setUnsaved(true)` and let the server flush on its own schedule; no forced immediate saves
- **Backup warning** — `fix` subcommand prints a warning and requires `--confirm` flag to proceed
- **Error handling** — Log errors per-chunk, continue processing, summarize failures in report
- **Idempotent** — Running the fixer again on already-fixed chunks is a no-op (palette entries already valid)

---

## Decisions (resolved)

| Question | Decision |
|----------|----------|
| Write strategy | **Online** — Paper async chunk load + NMS PalettedContainer |
| NBT library | Not needed — everything is online via NMS. No NBT parsing. |
| Registry access | Paper 1.21.4 `RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)` |
| Chunk status filter | Scan all non-empty chunks; fix only `full` by default |
| Concurrency | Semaphore(50), configurable |
| Report format | **YAML** everywhere |
| Command framework | **Aikar ACF** (same as Lunamatic) |
| Progress persistence | Not for v1 — re-scan is cheap, fixer tracks last region in report |

---

## Future Work

- **Multi-threaded chunk generator** — Reuse the `utils/chunk` package to implement a Chunky-style pre-generator, consolidating generation into this plugin
- **Iterator abstractions** — Port `ChunkIterator` interface for reuse across scanner, fixer, and future generator
- **Pack-aware reconstruction** — When fixing Ferma→Ferma migrations, query the new pack's biome config to find the closest match
- **Resume support** — For very large worlds, persist fixer progress to YAML and resume on restart

---

## Migration Scenario:

**Note:** In all scenarios, the scanner examines **ALL biomes** present in the specified world. Only **invalid** biomes (those not in the current registry) are written to the YAML report. For each invalid biome, the system auto-generates a proposed replacement where it can string-match or infer from context, and falls back to dimension defaults where it cannot.

### Ferma:X -> Ferma:Y

- Scanner finds all biome IDs that are no longer valid due to migration from one generator (e.g. Ferma:random_noise -> Ferma:true_void)
- Auto-proposes new biomes based on string matching and pack configuration where possible
- Example: `ferma:<old_pack>:*` → `ferma:<new_pack>:*`, falling back to vanilla (e.g. `ferma:noise1` → `minecraft:void`)

### Terra -> Ferma

- Scanner finds all biome IDs that are no longer valid due to migration from Terra to Ferma.
- Auto-proposes dimension-appropriate vanilla biomes
- For End: `ferma:*` → `minecraft:the_end`
- For normal: `ferma:*` → `minecraft:plains`

