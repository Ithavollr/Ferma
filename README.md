<img src="logo.png" width="109" height="109">

# Ferma [![Build](https://github.com/Ithavollr/Ferma/actions/workflows/build.yml/badge.svg)](https://github.com/Ithavollr/Ferma/actions/workflows/build.yml)

A Minecraft Paper 1.21.4 world generation plugin that provides a minimal injection point into vanilla chunk generation.
> [!NOTE]
> NMS reflection means this currently _ONLY_ supports 1.21.4, but more versions are planned once we're out of alpha stage.

## Purpose

Ferma was inspired from the Terra project, but takes a different approach. Instead of rebuilding world generation from scratch, it hijacts specifically the noise components, allowing customizable world generation by modifying noise parameters (for example, clamping temperature to 1.0 in order to create a desert world of only hot biomes. Or also clamp continentalness to 0.1 and you'll get Arakis, with no oceans either).  

One of the major advantages of this approach is that it allows only as much modification from vanilla as you want.

> [!IMPORTANT]
> Ferma is the sole authority for noise on any world registered to it. Datapacks that alter the noise graph (terrain, density functions, noise settings) are an incompatibility: Ferma refuses that world with a SEVERE log and it generates as plain vanilla instead. Datapacks that only add biomes, features, structures or surface rules work normally.

### Current features:

- Void world generation
- Vanilla world generation
- Custom world generation with tunable climate parameters:
  - `[temperature, humidity, continentalness, erosion, weirdness]`, plus `depth` on nether/caves/floating-islands worlds
- Noise types: `constant`, `identity`, `perlin`, `octave_perlin`, `double_perlin`, `radial_gradient`, `y_gradient`, `shattered`

## Usage

To get started with Multiverse: `mv create <world_name> normal --generator Ferma:void_template`

## Architecture

1. **Bukkit Registration** - `Ferma.getDefaultWorldGenerator()` returns a `FermaChunkGenerator` wrapper
2. **NMS Injection** - `NMSInjectListener` hooks `WorldInitEvent` to access the underlying `ServerLevel`
3. **Generator Replacement** - Uses reflection to replace the `WorldGenContext`'s `ChunkGenerator` with `NMSChunkGeneratorDelegate`
4. **NoiseRouter Patching** - For NOISE mode, patches the `RandomState.router` with custom climate functions before delegating to vanilla

## Building

```bash
./gradlew build shadowJar
```

The plugin JAR will be in `build/libs/Ferma-<version>-all.jar`

## Running

```bash
./gradlew runServer
```

## License

GPL 3.0
