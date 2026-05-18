<img src="https://codeberg.org/Ifiht/Firma/raw/branch/main/logo.png" width="109" height="109">

# Firma

A Minecraft Paper 1.21.4 world generation plugin that provides a minimal injection point into vanilla chunk generation.

## Purpose

Firma takes inspiration from Terra's NMS injection mechanism to intercept the world generation pipeline at the chunk generator level. Current fetures:

- Void world generation
- Vanilla world generation
- Custom world generation with tunable climate parameters including:
  - `[temperature, humidity, continentalness, erosion, weirdness]`

## Usage

To get started with Multiverse: `mv create <world_name> normal --generator Firma:void`

## Architecture

1. **Bukkit Registration** - `Firma.getDefaultWorldGenerator()` returns a `FirmaChunkGenerator` wrapper
2. **NMS Injection** - `NMSInjectListener` hooks `WorldInitEvent` to access the underlying `ServerLevel`
3. **Generator Replacement** - Uses reflection to replace the `WorldGenContext`'s `ChunkGenerator` with `NMSChunkGeneratorDelegate`
4. **Vanilla Delegation** - All methods in `NMSChunkGeneratorDelegate` pass through to the original vanilla generator

## Building

```bash
./gradlew build shadowJar
```

The plugin JAR will be in `build/libs/Firma-<version>-all.jar`

## Running

```bash
./gradlew runServer
```

## License

GPL 3.0
