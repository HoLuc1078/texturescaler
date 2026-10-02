# 更新记录

## 2.0.2 — 分档缩放

单一 cap（`clamp(GL_MAX/32, 256, 2048)`）对整张 4096² 图集和 64×11776 长条一视同仁，导致大显示贴图被过度压缩。改为按用途分档：

| 档位 | 命中条件（16384 GPU） | cap |
|---|---|---|
| 普通 | 最长边 ≤ 1024 | 256 |
| 大显示贴图 | 最长边 > 1024 | 2048 |
| 长条 | 长宽比 ≥ 4:1 | 4096 |

随 GPU 缩放（NVIDIA 32768 → 512/4096/8192；8192 → 256/1024/2048）。`capOverride > 0` 时忽略分档统一覆盖；`tieredCaps = false` 退回旧的单一 cap 公式。

## 2.0.1 — 多版本 / 多加载器重构

- 缩放算法提取到**平台无关的共享核心** `core/`（纯 Java 8，只依赖 Gson 与 `javax.imageio`），新增 `common/` 原版适配层；每个「Minecraft 版本 × 加载器」拆成独立 Gradle 模块 `versions/<mc>/<loader>`，由 `scripts/build-all.ps1` 统一构建并汇总到 `dist/`。mod 元数据集中在 `gradle/mod.properties`。
- 用 `javax.imageio` 的渐进式缩放替代 `NativeImage`，使算法可在 1.12.2–1.21.4 间完全共享；采用「逐级减半 + 双线性」。
- 修复：配置关闭（`enabled=false`）时图集列举路径仍可能输出上一轮缩放结果；跨资源重载复用扫描结果时未校验 cap 是否变化。
- Fabric 侧新增 `PackRepository` Mixin 注入高层级动态资源包；配置改为 `config/texturescaler.json`（mtime 变化自动重载）。
- 新增无依赖核心自测：`pwsh -File scripts/test-core.ps1`。

### 修复启动期 GL 时序崩溃

Fabric 的 client entrypoint 在 `Minecraft` 构造器内、`RenderSystem.initRenderer()` 之前执行，此时 LWJGL 的 GL 函数表未初始化，调用 `glGetInteger(GL_MAX_TEXTURE_SIZE)` 会让 JVM 在 `lwjgl_opengl.dll` 中原生崩溃，`catch (Throwable)` 无效。

- 入口层：Fabric 不再从 `onInitializeClient()` 查询 GPU；Forge / NeoForge 的 `clientSetup()` 改为 `enqueueWork`。
- 平台层：`gpuMaxTextureSize()` 先探测 GL capabilities，未就绪返回 0，成功一次后缓存。
- 核心层：`ScalerEngine.updateCap()` 锁存首个正值（失败查询永不降级），每次资源重载重试。

### 修复首次重载 overlay 不生效

`MultiPackResourceManager` 在构造时（早于任何 reload listener）就调用本包的 `getNamespaces()`，此刻读到的仍是上一轮（首次为空）的资源管理器 → overlay 声明 0 个命名空间，不会被 `push` 进任何 `FallbackResourceManager`，一张贴图都不会被缩放 → 图集溢出 `StitcherException`。修复：各平台 `claimedNamespaces()` 末尾补 `collectRepositoryNamespaces()`（遍历 `getResourcePackRepository().getSelectedPacks()`），核心在并集为空时打 warning。

## 1.1.0 — 启动提速

- 磁盘尺寸清单（`cache/sizes.json`）、缓存命中不解码原图、精简缺失贴图查询、跨重载复用扫描结果；mod 对启动耗时的贡献从 ~10s 降到 ~2-3s。

## 1.0.1 — 正式修复版

- 实现 `listResources`，让缩放结果真正进入方块图集；按目录前缀过滤 emit。

## 0.0.2

- 修复「图集通过 `listResources` 枚举而非 `getResource`」的根因。

## 0.0.1

- 修复命名空间发现时序问题，加入多来源兜底与统计日志。
