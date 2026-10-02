# Texture Scaler

Client-side Minecraft mod. On resource reload it reads `GL_MAX_TEXTURE_SIZE` and downscales any
block/item texture that would not fit the block atlas, through a dynamic resource pack.
It fixes atlas stitching failures (`StitcherException` → garbled fonts or a crash) on AMD and
Intel integrated GPUs, and never overwrites files from other mods or resource packs.

中文说明：[README-zh.md](README-zh.md)

## Supported versions

| Target | Covers | Java | Forge | Fabric | NeoForge |
|--------|--------|:----:|:-----:|:------:|:--------:|
| 1.12.2 | 1.12.2 | 8 | ✅ | — | — |
| 1.16.1 | 1.16 – 1.16.1 | 8 | ✅ | ✅ | — |
| 1.16.5 | 1.16.2 – 1.16.5 | 8 | ✅ | ✅ | — |
| 1.18.2 | 1.17 – 1.18.2 | 17 | ✅ | ✅ | — |
| 1.19.2 | 1.19 – 1.19.2 | 17 | ✅ | ✅ | — |
| 1.20 | 1.19.3 – 1.20 | 17 | ✅ | ✅ | — |
| 1.20.1 | 1.20.1 – 1.20.4 | 17 | ✅ | ✅ | — |
| 1.20.6 | 1.20.5 – 1.20.6 | 21 | ✅ | ✅ | — |
| 1.21.1 | 1.21 – 1.21.1 | 21 | ✅ | ✅ | ✅ |
| 1.21.3 | 1.21.2 – 1.21.3 | 21 | — | ✅ | ✅ |
| 1.21.4 | 1.21.4 | 21 | — | ✅ | ✅ |
| 1.21.8 | 1.21.5 – 1.21.8 | 21 | — | ✅ | ✅ |
| 1.21.10 | 1.21.9 – 1.21.10 | 21 | — | ✅ | ✅ |
| 1.21.11 | 1.21.11 | 21 | — | ✅ | ✅ |
| 26.2 | 26.1 – 26.2 | 25 | — | ✅ | ✅ |

Build matrix: [versions.md](versions.md).

## Scaling rules

Textures are scaled down proportionally, by use:

| Kind | Condition | NVIDIA 32768 | AMD / iGPU 16384 | old iGPU 8192 |
|------|-----------|--------------|------------------|---------------|
| normal | longest edge ≤ 1024 | 512 | **256** | 256 |
| large | longest edge > 1024 | 4096 | **2048** | 1024 |
| strip | aspect ratio ≥ 4:1 | 8192 | **4096** | 2048 |

- Animated textures are scaled by an integer factor only, and only when
  `max(width, height) > GL_MAX_TEXTURE_SIZE`; otherwise they are left alone.
- A texture whose `.mcmeta` states an explicit `width`/`height` is never touched.
- A texture referenced by a Blockbench model with absolute-pixel UVs (`texture_size`) is never touched.

## Configuration

| Loader | File |
|--------|------|
| Forge / NeoForge | `config/texturescaler-client.toml` |
| Fabric | `config/texturescaler.json` |
| Forge 1.12.2 | `config/texturescaler.cfg` |

Options: `enabled`, `capOverride`, `capDivisor`, `capMin`, `capMax`, `diskCacheEnabled`,
`cacheDir`, `skipNamespaces`, `extraTextureDirs`, `debugLog`.

- `capOverride > 0` ignores the tiers and caps every texture at that value.
- `capDivisor` / `capMin` / `capMax` apply only when the tiered caps are disabled in code.

## Build

Each module brings its own Gradle wrapper and runs on the JDK named by `gradle_jdk` in its
`gradle.properties` (Temurin 17 / 21 / 25, from `%USERPROFILE%\.jdks` or `~/.jdks`).

```powershell
# one module
cd versions\1.20.1\forge
.\gradlew.bat build

# every module, artifacts copied to dist/
powershell -File scripts/build-all.ps1

# shared-core self test
powershell -File scripts/test-core.ps1

# clean build output
powershell -File scripts/clean.ps1
```

Artifacts are named `texturescaler-<minecraft>-<loader>-<mod version>.jar`. Drop one into `mods/`;
the server does not need it.

## Layout

```
core/                  scaling algorithm (no Minecraft dependency)
common/                vanilla-only pack adapter
versions/<mc>/<ldr>/  one Gradle build per module
gradle/mod.properties  shared mod metadata
scripts/               build-all / test-core / clean
```

## License

[MPL-2.0](LICENSE)
