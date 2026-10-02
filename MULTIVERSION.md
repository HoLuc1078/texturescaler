# Texture Scaler 多版本 / 多加载器架构

## 1. 目标版本矩阵

| Minecraft | Forge | Fabric | NeoForge |
|-----------|:-----:|:------:|:--------:|
| 1.12.2 | ✅ | — | — |
| 1.16.5 | ✅ | ✅ | — |
| 1.18.2 | ✅ | ✅ | — |
| 1.19.2 | ✅ | ✅ | — |
| 1.20.1 | ✅ | ✅ | — |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.4+ | — | ✅ | ✅ |

- 1.12.2 无 Fabric（Fabric 自 1.14 起）；NeoForge 自 1.20.2 起存在。
- 1.21.4 起只提供 Fabric / NeoForge。

## 2. 工程结构

```
texturescaler/
├── core/                     # ★ 共享算法（纯 Java 8，零 Minecraft 依赖）
│   └── src/main/java/com/evernight/texturescaler/core/
│       ├── ScalerEngine.java      # 缩放引擎
│       ├── ScalerPlatform.java    # 平台 SPI（各加载器实现）
│       ├── TextureHandle.java     # 平台无关的贴图句柄
│       ├── ScalerConfig.java      # 平台无关的配置快照
│       ├── ScalerLog.java         # 可插拔日志
│       ├── DiskCache.java         # 磁盘缓存 + sizes.json 尺寸清单
│       ├── ModelScanner.java      # 模型 texture_size（Blockbench UV）扫描
│       ├── PngInfo.java           # PNG 头读取（免解码）
│       ├── AnimationInfo.java     # .mcmeta 动画帧格识别 + 安全整数缩放因子
│       └── ImageDownscaler.java   # 纯 JDK（ImageIO/AWT）缩图
├── common/                   # 仅依赖原版类的适配层（面向 1.20.1 的 PackResources API）
├── versions/<mc>/<loader>/   # 每个「版本 × 加载器」= 一个独立 Gradle 构建
├── versions/<mc>/common/     # （仅 1.21.1 / 1.21.4）该 MC 版本的 pack 变体
├── gradle/mod.properties     # 所有模块共享的 mod 元数据（单一事实来源）
├── scripts/                  # build-all / test-core / clean
└── docs/
```

仓库根目录不是 Gradle 工程（没有根 `gradlew` / `settings.gradle`）：每个 `versions/<mc>/<loader>/` 模块自带 wrapper，进入对应目录构建，或用 `scripts/build-all.*` 统一调度。

不同 MC 代际的工具链互不兼容（ForgeGradle 需要 Gradle 7、Fabric Loom 需要 Gradle 9、ModDevGradle 面向 1.21+），一个 Gradle 版本无法驱动全部，因此每个模块自带 wrapper。

### common/ 的适用范围

`common/TextureScalingPack` 只适配 **1.20.1** 的 `PackResources` API：

| 版本 | 处理 |
|------|------|
| 1.20.1 | 直接复用 `common/TextureScalingPack` |
| 1.21.1 / 1.21.4 | 在 `versions/<mc>/common/` 放同名版本变体，模块用 `srcDir file('../common/src/main/java')` |
| 1.18.2 / 1.19.2 | 源码集 `exclude` 掉 `common/TextureScalingPack.java`，模块内自带 `OverlayPack` |
| 1.16.5 | 完全不使用 `common/`，模块内自带 pack（MC 命名与 official 命名不一致） |

`common/OriginalReadGuard` 在所有版本通用。

## 3. 共享核心

- 方块图集通过**目录列举**取图，因此必须能重新 emit 一份「缩小后的合并清单」；`getResource` 保留给非列举式查询（≤1.19.2 走的就是这条路）。
- 先读 **PNG 头** 跳过小图，并写入持久化尺寸清单（`cache/sizes.json`）；查磁盘缓存优先于解码原图。
- Blockbench 绝对像素 UV（`texture_size`）会否决缩放。
- 包列表与 cap 均未变时复用上一轮扫描，只增量检查新出现的贴图。
- 用 `javax.imageio` 替代 `NativeImage`：其 API 在 1.12.2→1.21.4 间多次变动，而 `ImageIO` 在 Java 8–21 上稳定。缩放采用「逐级减半 + 最终双线性」，比单次双线性采样在大幅缩小时混叠更少。
- 核心只用 Gson 2.8.0 就有的**实例 API**（`new JsonParser().parse(...)`），因为 MC 1.12.2 / 1.16.5 自带 Gson 2.8.0。

`ScalerPlatform` 需要各加载器实现：`log()`、`config()`、`gameDirectory()`、`gpuMaxTextureSize()`、`listAllTextures()`、`listModels()`、`readOriginal()`、`claimedNamespaces()`、`packFingerprint()`。

### 核心自测

```powershell
powershell -NoProfile -File scripts/test-core.ps1     # Windows
bash scripts/test-core.sh                             # Linux/macOS
```

## 4. 各模块工具链

| 模块 | 构建工具 | Gradle | 运行 JDK | 字节码 | Loader 版本 | pack_format |
|------|----------|--------|----------|-----------|-------------|-------------|
| 1.16.5/forge | ForgeGradle 5.1.79 | 7.6 | 17 | 8 | Forge 36.2.39 | 5 |
| 1.16.5/fabric | Fabric Loom 1.14.10 | 9.3.0 | 21 | 8 | loader 0.15.11 / API 0.42.0+1.16 | 5 |
| 1.18.2/forge | ForgeGradle 5.1.+ | 7.6 | 17 | 17 | Forge 40.2.10 | 8 |
| 1.18.2/fabric | Fabric Loom 1.14.10 | 9.3.0 | 21 | 17 | loader 0.15.11 / API 0.77.0+1.18.2 | 8 |
| 1.19.2/forge | ForgeGradle 6.0 | 8.8 | 17 | 17 | Forge 43.3.13 | 9 |
| 1.19.2/fabric | Fabric Loom 1.14.10 | 9.3.0 | 21 | 17 | loader 0.15.11 / API 0.77.0+1.19.2 | 9 |
| 1.20.1/forge | ForgeGradle 6.0 | 8.8 | 17 | 17 | Forge 47.4.10 | 15 |
| 1.20.1/fabric | Fabric Loom 1.14.10 | 9.3.0 | 21 | 17 | loader 0.15.11 / API 0.92.5+1.20.1 | 15 |
| 1.21.1/forge | ForgeGradle 6.0.54 | 8.8 | 21 | 21 | Forge 52.1.16 | 34 |
| 1.21.1/fabric | Fabric Loom 1.14.10 | 9.3.0 | 21 | 21 | loader 0.16.14 / API 0.116.17+1.21.1 | 34 |
| 1.21.1/neoforge | ModDevGradle 2.0.147 | 8.14.3 | 21 | 21 | NeoForge 21.1.251 | 34 |
| 1.21.4/fabric | Fabric Loom 1.14.10 | 9.3.0 | 21 | 21 | loader 0.16.14 / API 0.119.4+1.21.4 | 46 |
| 1.21.4/neoforge | ModDevGradle 2.0.147 | 8.14.3 | 21 | 21 | NeoForge 21.4.157 | 46 |
| 1.12.2/forge | RetroFuturaGradle 1.4.9 | 8.8 | 17 | 8 | Forge 14.23.5.2847（RFG 固定）/ MCP stable_39 | 无（mcmod.info） |

1.21.1 起必须用 JDK 21（MC 1.21 的 class 文件就是 Java 21）；其余 Forge 模块用 JDK 17。

## 5. 构建

```powershell
cd versions\1.20.1\forge
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-17"
.\gradlew.bat build

powershell -NoProfile -File scripts/build-all.ps1                 # 全部
powershell -NoProfile -File scripts/build-all.ps1 -SkipLegacy     # 跳过 1.12.2
powershell -NoProfile -File scripts/build-all.ps1 -Only 1.20.1    # 指定版本
```

产物命名：`texturescaler-<mc版本>-<加载器>-<mod版本>.jar`。

## 6. 新增一个「版本 × 加载器」

1. 复制最接近的现有模块目录（含自带的 `gradlew` / `gradle/wrapper/`）。
2. 修改 `gradle.properties`（`minecraft_version`、`forge_version` 等）与 `build.gradle` 的 `archivesName` 后缀。
3. 按新版 Minecraft / 加载器 API 调整平台胶水（`ScalerPlatform` 实现、包注册、配置、reload 监听器、pack 类）。
4. `gradlew build` 直到通过；**不要修改 `core/`**。

## 7. 已知坑

1. **Loom 模块不要引入 foojay-resolver**：Loom 解析 Mojang 版本清单时会因 Gson 2.9.1 无法反序列化 Java record 而报错。JDK 放在 `%USERPROFILE%\.jdks` 即被 Gradle 自动发现。ForgeGradle/NeoGradle 不受影响。
2. **ForgeGradle maven 证书探测偶发失败**：模块 `gradle.properties` 加 `systemProp.net.minecraftforge.gradle.check.certs=false` 可绕过。
3. **Fabric API 0.92+ 移除了 `ResourceType`**：用 `ResourceManagerHelper.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)`。
4. **`PackResources` API 逐版本变化**：1.16.5 `getName()`/`getResources(...)`；1.18.2/1.19.2 谓词与返回类型不同；1.20.1 `packId()`/`listResources(..., ResourceOutput)`/`IoSupplier`；1.21.x `Pack.readMetaAndCreate` 新签名、`PackResources` 新增抽象 `location()`、`ResourceLocation` 构造器私有化。1.20.1 原版没有 `PackResources.isHidden()`。
5. **ForgeGradle 5.1 的 `official` 通道对 1.16.5 实际给的是 MCP 名**，与 Loom 的 official 名不一致，故 1.16.5 两个模块不共享 glue 源码。
6. **图集精灵集合多数版本不可用**：`TextureAtlas.getTextureLocations()` 只有 1.20.1 存在。缺少它时，非 `textures/block/`、`textures/item/` 目录的图集贴图需要配置 `extraTextureDirs` 显式声明。
7. **1.21.4 的 `PreparableReloadListener.reload` 少了两个 `ProfilerFiller` 参数。**
8. **1.12.2（RFG）**：插件不在 Gradle 插件门户，`settings.gradle` 需加 GTNH 的 nexus 仓库；`skipSlowTasks` 必须保持 false；只有一次自动资源重载且发生在 `preInit` 之前，overlay pack 与事件必须在 `@Mod` 构造器 / `FMLConstructionEvent` 阶段注册；没有 `AddPackFindersEvent`，通过 `ObfuscationReflectionHelper` 向 `Minecraft.defaultResourcePacks` 追加到末尾注入；`IResourceManager` 无目录列举能力，故 `listAllTextures()` 返回空，机制是 `getResource` 钩子（`extraTextureDirs` 默认 `["blocks","items"]`）。
9. **动画条带（filmstrip）只能整数倍缩小**：原版 `AnimationMetadataSection.calculateFrameSize(w, h)` 在 `.mcmeta` 未写 `width`/`height` 时缺省帧尺寸为 `min(w, h)` 的正方形；按最长边等比会让两边缩放比不等，原版仍按新图 `min(宽,高)` 切帧 → 帧距漂移 → 动画错位。规则：带 `animation` 的贴图只允许 `w/k, h/k`（k 同时整除两边），且只在 `max(宽,高) > GL_MAX_TEXTURE_SIZE` 时才缩；`.mcmeta` 显式写了帧尺寸的一律不动。
10. **GL 上下文时序**：入口初始化阶段（Fabric client entrypoint 在 `Minecraft` 构造器内执行，早于 `RenderSystem.initRenderer()`）绝不能调用 GL —— 此时 LWJGL 函数表为空，`glGetInteger` 会让 JVM 在原生代码中崩溃，`catch (Throwable)` 无效。cap 只在首次资源重载（GL 已就绪）解析；平台的 `gpuMaxTextureSize()` 必须先探测 capabilities（LWJGL 3 `GL.getCapabilities()`；LWJGL 2 `GLContext.getCapabilities()`），未就绪返回 0，核心锁存首个正值。
