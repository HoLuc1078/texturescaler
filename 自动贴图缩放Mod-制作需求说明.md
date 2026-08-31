# citymod「中文方块字」问题说明 & 独立修复 Mod 制作需求

> 本说明写给接手开发的工程师。目标：**单独制作一个新 Mod**，自动把过大贴图按显卡能力压缩，从而修复 AMD 显卡 / 核显上中文显示为方块字的问题。**不改动 citymod 及其它任何 Mod 的文件。**

---

## 一、问题现象

- 游戏版本：**Minecraft 1.20.1**，加载器：**Forge**（个别玩家用 Fabric）。
- 整合包规模：100+ Mod，包含 Embeddium（Sodium Forge 移植）、Oculus（Iris Forge 移植）、自动汉化模组 I18nUpdateMod + 汉化资源包等。
- 现象：
  - **NVIDIA 显卡（如 RTX 2060 / 其它 RTX 系列）**：一切正常。
  - **AMD 显卡（实测 AMD Radeon RX 580 2048SP）和 Intel 核显**：一进游戏，**所有中文字符显示为方块（豆腐块 tofu）**，且日志最后会崩溃。
  - 实测：**把 citymod 从 mods 文件夹移除后，方块字消失**，游戏恢复正常。

> 结论初判：方块字与「显卡能力」和「贴图集大小」强相关，citymod 是放大触发条件的关键 mod（但不是唯一原因）。

---

## 二、根因分析（已实锤，来自坏机器日志）

在 AMD RX 580 机器上抓到的 `latest.log` 关键证据：

```
[ImmediatelyFast] Initializing ... on AMD Radeon RX 580 2048SP (ATI Technologies Inc.)
    with OpenGL 4.6.0 Core Profile Context 25.8.1.250617

[ModernFix] Requested atlas size 32768x16384 exceeds maximum of 16384x16384

[net.minecraft.client.Minecraft] Caught error loading resourcepacks, removing all selected resourcepacks

java.util.concurrent.CompletionException: net.minecraft.ReportedException: Stitching
Caused by: net.minecraft.client.renderer.texture.StitcherException:
    Unable to fit: crab_city_facilities:block/spj - size: 4096x4096 - Maybe try a lower resolution resourcepack?
```

### 因果链

1. 游戏启动时会把**所有方块/物品贴图拼进一张「方块贴图集（atlas）」**。
2. 这个整合包里贴图过大过密，**atlas 需要 32768×16384** 才能装下。
3. 但 AMD RX 580 / Intel 核显的 `GL_MAX_TEXTURE_SIZE` **只有 16384**（NVIDIA RTX 为 32768），塞不下 → 抛 `StitcherException`。
4. **整个资源重载失败**，Minecraft 直接丢弃所有资源包 → **字体（含中文字形）这一阶段也没被加载** → 中文全部变成方块字；后续重试继续失败，最终硬崩溃。
5. 在 NVIDIA 机器上 `GL_MAX_TEXTURE_SIZE = 32768`，atlas 刚好塞得下 → 一切正常。

> 简单说：**不是字体文件的锅，是「方块贴图集太大，超过 GPU 单张贴图尺寸上限」导致资源重载整体失败，字体跟着没加载。**

### 谁把 atlas 撑爆了？

- **crab_city_facilities**（另一 mod）：2 张 **4096×4096** 贴图。
- **citymod**：一批远超常规的方块贴图——
  - **2048×2048** ×5：`laptop`、`laptopon`、`modern_screen`、`modern_screen_on`、`road_sign2`
  - **64×1920** ×2：`traffictimescreengreen`、`traffictimescreenred`
  - **1024×1024** ×40+：`ac`、`ac_01`、`ac_04`、`ac_2`、`ac_3`、`ac_out_01~06`、`ac_out_40hx`、`ac_out_amd/fake/intel/nvidia`、`all_in_one_pc(_on)`、`cac_out_hd1`、`electric_water_heater`、`expr5a~g`、`expressbox1/2`、`exprexit`、`exprsign5d`、`fire_extinguisher_box`、`freezer`、`fridge` 等
- 正常方块贴图是 **16×16**；一张 2048×2048 = 16384 个 16×16 贴图的面积。

---

## 三、解决方案（要单独制作的新 Mod）

### 目标

做一个 **客户端（client-side）Forge Mod**：启动时检测显卡的 `GL_MAX_TEXTURE_SIZE`，自动把「会进入方块贴图集的、最大边超过阈值的贴图」等比缩小，以动态资源包的方式**覆盖原贴图**。这样 atlas 就能塞进 GPU 上限，资源重载不再失败，字体（中文）恢复正常。

### 为什么能解决

方块字不是字体文件坏，而是「atlas 超限 → 整次重载失败 → 字体没加载」。只要把进 atlas 的贴图按 GPU 上限缩小，atlas 就装得下，字体加载恢复正常。这个 Mod 不需要碰 citymod，也不需要玩家手动操作。

### 为什么不直接改 citymod

- 需求方明确要求**不改动原包内容**，修复要独立成一个新 Mod。
- 自动 Mod 还能顺带覆盖其它 mod 的过大贴图（如 crab 的 4096²），适用面更广。

---

## 四、技术实现要点（Forge 1.20.1）

### 1. 检测 GPU 上限

- 用 `RenderSystem.maxSupportedTextureSize()`（1.20.1 存在），或 `GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)`。
- **必须在 GL 上下文初始化后调用**：放在 `FMLClientSetupEvent` 的 `event.enqueueWork(...)`，或首次资源重载时。Mod 构造阶段拿不到。

### 2. 计算缩放阈值

建议：

```
cap = clamp(maxTextureSize / 32, 256, 2048)
```

- `maxTextureSize = 32768`（NVIDIA）→ cap ≈ 1024，大图基本不动，画质无损。
- `maxTextureSize = 16384`（RX 580 / 多数 Intel UHD）→ cap = **512**。
- `maxTextureSize = 8192`（老核显）→ cap = 256。

做成配置文件，允许玩家手动覆盖。**缩放规则：保持纵横比，把最大边压到 ≤ cap**（如 2048² → 512²，64×1920 → 64×480 之类）。

### 3. 动态资源包（核心）

- 用 Forge 的 **`AddPackFindersEvent`** 注册自定义 `IPackFinder` → 返回 `Pack`（`Pack.Position.TOP`，优先级高于 mod 资源）。
- 实现 `PackResources`（mojmap 下的 `net.minecraft.server.packs.PackResources`）。
- 在 `getResource(PackType.CLIENT_RESOURCES, location)` 中：
  1. 只处理路径匹配 `assets/<namespace>/textures/**/*.png` 且**会被方块/物品模型引用**的贴图（`block/`、`item/`、自定义模型贴图目录）；
  2. 读原始 PNG → 用 `NativeImage`（`com.mojang.blaze3d.platform.NativeImage`）解码；
  3. 若最大边 > cap → `NativeImage.resize(...)` 等比缩小 → 重新编码为 PNG 字节；
  4. 结果存入 `ConcurrentHashMap<ResourceLocation, byte[]>` 缓存，命中直接返回，避免重复缩放。

### 4. 读原始贴图，避免递归（关键坑）

你的 pack 的 `getResource` 是在资源管理器正在加载时被回调的，**不能**再走 `Minecraft.getInstance().getResourceManager()` 去读原图（会递归 / 读不到）。两种可行方案：

- **方案 A**：在首次资源重载时快照其它 pack 的列表，`getResource` 时遍历快照，找第一个「非自己」且包含该资源的 pack 读取。
- **方案 B**：直接读文件——遍历 `mods/*.jar` 与 `resourcepacks/*`，用 ZipFile 按 `assets/<ns>/textures/<path>.png` 读原图。无法覆盖纯动态生成的资源，但能覆盖绝大多数情况（含 citymod、汉化包）。

### 5. 缓存与性能

- 每次启动都要缩放几百张图会拖慢加载，**把结果缓存到磁盘**：如 `gameDir/<新mod>/texture-cache/`，缓存 key 用「GPU 上限 + 贴图路径 + 原始尺寸」的哈希。首次生成后直接读缓存。
- 用 **`RegisterClientReloadListenersEvent`** 注册一个 `IClientResourceReloadListener`，每次资源重载时清内存缓存（贴图可能变化）。
- 绝大多数 16×16 小贴图直接跳过，CPU 开销很低。

### 6. 必须注意的三个坑

1. **GL 上下文时序**：`GL_MAX_TEXTURE_SIZE` 必须在窗口初始化后查询（见第 1 节）。
2. **Blockbench 自定义模型 UV**：若模型 JSON 里 `texture_size` 写得很大（如 `[2048,2048]`）且 UV 按像素坐标写，缩小贴图会导致 UV 越界、渲染错乱。**安全做法**：缩放前检查引用该贴图的模型的 `texture_size`，若其最大值大于新尺寸则跳过或放低压缩力度。citymod 的大贴图基本是标准模型（`texture_size` 小），缩到 512 是安全的，但必须保留这个检查。
3. **范围限定**：只缩「会进方块 atlas」的贴图（block/item/custom 模型引用的）。GUI、全景图、实体贴图不进方块 atlas，不要动。

---

## 五、验收标准

1. 在 **AMD RX 580** 和 **Intel 核显**机器上：启动日志**不再出现 `StitcherException` / `Requested atlas size ... exceeds maximum`**，中文正常显示，游戏不崩溃。
2. 在 **NVIDIA RTX** 机器上：贴图质量不劣化（cap ≥ 1024 时大图基本不缩）。
3. **不修改、不覆盖任何现有 Mod 的文件**（不碰 citymod 等），仅通过动态资源包在运行时覆盖。
4. 首次启动后的二次启动加载时间可接受（依赖磁盘缓存）。
5. 支持在配置文件中调整缩放阈值。

---

## 六、约束与禁止事项

- **禁止**修改 citymod 及其它 Mod 的代码、贴图、模型、语言文件。
- **禁止**把缩小后的贴图写回原 Mod 的目录。
- 新 Mod 应独立命名空间、独立 jar，客户端专用（`environment: client`，服务端可不装）。
- 若需同时覆盖 Fabric，可用 Architectury 抽象（需求方当前项目即用 Architectury）；若只修 Forge，直接写 Forge 原生也可。

---

## 附：关键日志证据原文

坏机器（AMD RX 580）`logs/latest.log` 摘录：

```
[Render thread/INFO] [ImmediatelyFast/]: Initializing ImmediatelyFast 1.5.5+1.20.4 on AMD Radeon RX 580 2048SP (ATI Technologies Inc.) with OpenGL 4.6.0 Core Profile Context 25.8.1.250617
[Worker-ResourceReload-4/ERROR] [ModernFix/]: Requested atlas size 32768x16384 exceeds maximum of 16384x16384
[Render thread/INFO] [net.minecraft.client.Minecraft/]: Caught error loading resourcepacks, removing all selected resourcepacks
Caused by: net.minecraft.client.renderer.texture.StitcherException: Unable to fit: crab_city_facilities:block/spj - size: 4096x4096 - Maybe try a lower resolution resourcepack?
```

atlas 内大贴图清单（日志 Sprites 节选）：

```
crab_city_facilities:block/spj[4096x4096], crab_city_facilities:block/spjxia[4096x4096],
citymod:block/laptop[2048x2048], citymod:block/laptopon[2048x2048],
citymod:block/modern_screen[2048x2048], citymod:block/modern_screen_on[2048x2048],
citymod:block/road_sign2[2048x2048],
citymod:block/ac[1024x1024], citymod:block/ac_01[1024x1024], ... （大量 1024x1024）
```
