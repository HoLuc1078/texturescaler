# Version matrix

One module per Minecraft / loader pair at `versions/<minecraft>/<loader>`.

| Target | Covers | Java | Forge | Fabric | NeoForge |
|--------|--------|:----:|:-----:|:------:|:--------:|
| 1.12.2 | 1.12.2 | 8 | 1.12.2 | — | — |
| 1.16.1 | 1.16 – 1.16.1 | 8 | 1.16.1 | 1.16.1 | — |
| 1.16.5 | 1.16.2 – 1.16.5 | 8 | 1.16.5 | 1.16.5 | — |
| 1.18.2 | 1.17 – 1.18.2 | 17 | 1.18.2 | 1.18.2 | — |
| 1.19.2 | 1.19 – 1.19.2 | 17 | 1.19.2 | 1.19.2 | — |
| 1.20 | 1.19.3 – 1.20 | 17 | 1.20 | 1.20 | — |
| 1.20.1 | 1.20.1 – 1.20.4 | 17 | 1.20.1 | 1.20.1 | — |
| 1.20.6 | 1.20.5 – 1.20.6 | 21 | 1.20.6 | 1.20.6 | — |
| 1.21.1 | 1.21 – 1.21.1 | 21 | 1.21.1 | 1.21.1 | 1.21.1 |
| 1.21.3 | 1.21.2 – 1.21.3 | 21 | — | 1.21.3 | 1.21.3 |
| 1.21.4 | 1.21.4 | 21 | — | 1.21.4 | 1.21.4 |
| 1.21.8 | 1.21.5 – 1.21.8 | 21 | — | 1.21.8 | 1.21.8 |
| 1.21.10 | 1.21.9 – 1.21.10 | 21 | — | 1.21.10 | 1.21.10 |
| 1.21.11 | 1.21.11 | 21 | — | 1.21.11 | 1.21.11 |
| 26.2 | 26.1 – 26.2 | 25 | — | 26.2 | 26.2 |

## Loom plugin ids

| Minecraft | Plugin | Mappings |
|-----------|--------|----------|
| 1.21.11 and older | `fabric-loom-remap` | official Mojang mappings |
| 26.1 and newer | `net.fabricmc.fabric-loom` | none (unobfuscated) |

## Adding a version

1. Copy the closest module directory, including `gradlew` and `gradle/wrapper/`.
2. In `gradle.properties` set `minecraft_version`, `minecraft_version_range`, the loader
   versions and `gradle_jdk`.
3. Update the pack format passed to the overlay pack.
4. Adjust the loader glue if the API changed; leave `core/` alone.
