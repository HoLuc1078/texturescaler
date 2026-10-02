# 更新记录

## 2.0.3 — 动画贴图不再被压坏

**现象**：动画条带（如地铁线路图 / 时刻表 / 行人灯）被缩小后播放时糊成一坨。

**根因**：原版 `AnimationMetadataSection.calculateFrameSize(w, h)` 在 `.mcmeta` 未写 `width`/`height` 时，缺省帧尺寸是 `min(w, h)` 的正方形。按最长边等比缩放会让宽、高缩放比不相等，而原版仍按新图的 `min(宽, 高)` 切帧，帧距随之漂移。

**修复**：动画贴图只允许**整数倍缩放**（`w/k, h/k`），帧尺寸恰好变成 `min(w, h)/k`、帧数不变；并且只在 `max(宽,高) > GL_MAX_TEXTURE_SIZE`（拼接器物理上放不下）时才缩，能装下就整张不动；找不到合适的整数因子也整张不动。`.mcmeta` 显式写了 `width`/`height` 的一律不动。新增 `core/AnimationInfo`，`DiskCache` 缓存键加 `variant` 维度。

## 2.0.2 — 分档缩放

单一 cap（`clamp(GL_MAX/32, 256, 2048)`）对整张 4096² 图集和 64×11776 长条一视同仁，导致大显示贴图被过度压缩。改为按用途分档：

| 档位 | 命中条件（16384 GPU） | cap |
|---|---|---|
| 普通 | 最长边 ≤ 1024 | 256 |
| 大显示贴图 | 最长边 > 1024 | 2048 |
| 长条 | 长宽比 ≥ 4:1 | 4096 |

随 GPU 缩放（NVIDIA 32768 → 512/4096/8192；8192 → 256/1024/2048）。`capOverride > 0` 时忽略分档统一覆盖；`tieredCaps = false` 退回旧的单一 cap 公式。

## 2.0.1 — 修复启动期 GL 时序崩溃与首次重载 overlay 不生效

**GL 时序**：Fabric 的 client entrypoint 在 `Minecraft` 构造器内、`RenderSystem.initRenderer()` 之前执行，此时 LWJGL 的 GL 函数表未初始化，调用 `glGetInteger(GL_MAX_TEXTURE_SIZE)` 会让 JVM 在 `lwjgl_opengl.dll` 中原生崩溃，`catch (Throwable)` 无效。

- 入口层：Fabric 不再从 `onInitializeClient()` 查询 GPU；Forge / NeoForge 的 `clientSetup()` 改为 `enqueueWork`。
- 平台层：`gpuMaxTextureSize()` 先探测 GL capabilities，未就绪返回 0，成功一次后缓存。
- 核心层：`ScalerEngine.updateCap()` 锁存首个正值（失败查询永不降级），每次资源重载重试。

**overlay 首次重载不生效**：`MultiPackResourceManager` 在构造时（早于任何 reload listener）就调用本包的 `getNamespaces()`，此刻读到的仍是上一轮（首次为空）的资源管理器 → overlay 声明 0 个命名空间，不会被 `push` 进任何 `FallbackResourceManager`，一张贴图都不会被缩放 → 图集溢出 `StitcherException`。修复：各平台 `claimedNamespaces()` 末尾补 `collectRepositoryNamespaces()`（遍历 `getResourcePackRepository().getSelectedPacks()`），核心在并集为空时打 warning。

## 1.1.0 — 启动提速

- 磁盘尺寸清单（`cache/sizes.json`）、缓存命中不解码原图、精简缺失贴图查询、跨重载复用扫描结果；mod 对启动耗时的贡献从 ~10s 降到 ~2-3s。

## 1.0.1 — 正式修复版

- 实现 `listResources`，让缩放结果真正进入方块图集；按目录前缀过滤 emit。

## 0.0.2

- 修复「图集通过 `listResources` 枚举而非 `getResource`」的根因。

## 0.0.1

- 修复命名空间发现时序问题，加入多来源兜底与统计日志。
