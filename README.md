# Firma

A Minecraft Paper 1.21.4 world generation plugin that provides a minimal injection point into vanilla chunk generation.

## Purpose

Firma re-implements Terra's NMS injection mechanism to intercept the world generation pipeline at the chunk generator level. Currently, it acts as a **no-op pass-through** - all generation is delegated entirely to vanilla, producing faithful vanilla worlds.

This serves as a foundation for future modifications to the 6 critical climate noise functions (temperature, humidity, continentalness, erosion, weirdness, depth) while leaving all other generation to the vanilla server.

## Usage

Add the following to your `bukkit.yml` or Paper world configuration:

```yaml
worlds:
  your_world_name:
    generator: Firma
```

Or use the generator ID with the `/createworld` command or any world management plugin.

## Architecture

1. **Bukkit Registration** - `Firma.getDefaultWorldGenerator()` returns a `FirmaChunkGenerator` wrapper
2. **NMS Injection** - `NMSInjectListener` hooks `WorldInitEvent` to access the underlying `ServerLevel`
3. **Generator Replacement** - Uses reflection to replace the `WorldGenContext`'s `ChunkGenerator` with `NMSChunkGeneratorDelegate`
4. **Vanilla Delegation** - All methods in `NMSChunkGeneratorDelegate` pass through to the original vanilla generator

## Building

```bash
./gradlew build shadowJar
```

The plugin JAR will be in `build/libs/Firma-<version>.jar`

## Running

```bash
./gradlew runServer
```

## License

GPL 3.0
