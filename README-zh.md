# 自动贴图缩放 (Texture Scaler)

客户端 Mod。资源重载时读取 `GL_MAX_TEXTURE_SIZE`，把放不进方块图集的方块/物品贴图通过动态资源包等比缩小，
修复 AMD / Intel 核显上「图集拼接失败（`StitcherException`）→ 字体变方块 / 崩溃」的问题。
不修改、不覆盖任何其它 Mod 或资源包的文件。

English: [README.md](README.md)

## 支持的版本

| 目标版本 | 覆盖范围 | Java | Forge | Fabric | NeoForge |
|----------|----------|:----:|:-----:|:------:|:--------:|
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

构建矩阵见 [versions.md](versions.md)。

## 缩放规则

按贴图用途分档，一律等比缩小：

| 档位 | 命中条件 | NVIDIA 32768 | AMD / 核显 16384 | 老核显 8192 |
|------|----------|--------------|------------------|-------------|
| 普通 | 最长边 ≤ 1024 | 512 | **256** | 256 |
| 大显示贴图 | 最长边 > 1024 | 4096 | **2048** | 1024 |
| 长条 | 长宽比 ≥ 4:1 | 8192 | **4096** | 2048 |

- 动画贴图只做**整数倍**缩小，且只在 `max(宽,高) > GL_MAX_TEXTURE_SIZE` 时才缩，否则整张不动。
- `.mcmeta` 里显式写了 `width`/`height` 的贴图一律不动。
- 被 Blockbench 模型按绝对像素 UV（`texture_size`）引用的贴图一律不动。

## 配置

| 加载器 | 文件 |
|--------|------|
| Forge / NeoForge | `config/texturescaler-client.toml` |
| Fabric | `config/texturescaler.json` |
| Forge 1.12.2 | `config/texturescaler.cfg` |

选项：`enabled`、`capOverride`、`capDivisor`、`capMin`、`capMax`、`diskCacheEnabled`、`cacheDir`、
`skipNamespaces`、`extraTextureDirs`、`debugLog`。

- `capOverride > 0`：忽略分档，所有贴图统一用该值。
- `capDivisor` / `capMin` / `capMax`：仅在代码里关闭分档后生效。

## 构建

每个模块自带 Gradle wrapper，运行在 `gradle.properties` 的 `gradle_jdk` 指定的 JDK 上
（Temurin 17 / 21 / 25，取自 `%USERPROFILE%\.jdks` 或 `~/.jdks`）。

```powershell
# 单个模块
cd versions\1.20.1\forge
.\gradlew.bat build

# 全部模块，产物汇总到 dist/
powershell -File scripts/build-all.ps1

# 共享核心自测
powershell -File scripts/test-core.ps1

# 清理构建产物
powershell -File scripts/clean.ps1
```

产物命名：`texturescaler-<mc版本>-<加载器>-<mod版本>.jar`。放入 `mods/` 即可，服务端不需要安装。

## 目录结构

```
core/                  缩放算法（零 Minecraft 依赖）
common/                仅依赖原版类的资源包适配层
versions/<mc>/<ldr>/   每个「版本 × 加载器」= 一个独立 Gradle 构建
gradle/mod.properties  所有模块共享的 mod 元数据
scripts/               build-all / test-core / clean
```

## 许可证

[MPL-2.0](LICENSE)
