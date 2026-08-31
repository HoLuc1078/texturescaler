# 自动贴图缩放 (Texture Scaler)

![Texture Scaler logo](texturescaler_logo.png)

一个 **客户端专用（client-side）Forge 1.20.1 Mod**，自动把过大的方块/物品贴图按显卡能力等比缩小，
从而修复 AMD 显卡 / Intel 核显上「中文变方块字 + 资源重载失败 + 崩溃」的问题。

> 英文名 *Texture Scaler* ｜ 需求说明见 [自动贴图缩放Mod-制作需求说明.md](自动贴图缩放Mod-制作需求说明.md)

## 原理（一句话）

启动时读取 `GL_MAX_TEXTURE_SIZE`（AMD RX580 / 多数核显为 16384，NVIDIA 为 32768），
用动态资源包把「会进入方块贴图集（block atlas）、最大边超过阈值」的贴图在加载时等比缩小，
atlas 就能塞进 GPU 上限，资源重载不再失败，字体（含中文字形）正常加载。

**不修改、不覆盖任何现有 Mod 的文件**（citymod / crab_city_facilities 等均不受影响），
只通过运行时资源覆盖生效。

## 缩放阈值

```
cap = clamp(GL_MAX_TEXTURE_SIZE / 32, 256, 2048)
```

| GPU | GL_MAX_TEXTURE_SIZE | cap |
|-----|--------------------|-----|
| NVIDIA RTX 系列 | 32768 | 1024（大图基本不缩） |
| AMD RX 580 / 多数 Intel UHD | 16384 | 512 |
| 老核显 | 8192 | 256 |

## 配置文件

位于 `.minecraft/config/texturescaler-client.toml`（Forge 客户端配置）：

- `enabled`：总开关，默认 `true`
- `capOverride`：手动覆盖阈值（像素），`0` = 自动（默认）
- `capDivisor` / `capMin` / `capMax`：自动阈值公式参数
- `diskCacheEnabled`：磁盘缓存，默认 `true`（缓存于 `.minecraft/texturescaler/cache/`）
- `cacheDir`：缓存目录
- `skipNamespaces`：不处理的命名空间，默认 `["minecraft", "texturescaler"]`
- `extraTextureDirs`：额外会进方块 atlas 的贴图目录（如 `["blocks"]`），默认空
- `debugLog`：调试日志，默认 `false`

## 实现要点

- `AddPackFindersEvent`（mod 总线）注册一个 `Position.TOP` + `required` 的动态资源包
  （id：`texturescaler_overlay`），优先级高于所有 mod 资源与玩家资源包。
- `getResource` 只处理 `assets/<ns>/textures/**/*.png` 且属于方块贴图集范围的贴图：
  先读 PNG 头快速跳过小图，超限的用 `NativeImage` 双线性等比缩小后返回覆盖。
- 原始贴图从「其它 pack 的快照」读取，失败时回退到实时资源管理器（带线程内重入保护，不会递归）。
- Blockbench 模型 UV 保护：扫描所有模型 JSON 的 `texture_size`，若模型按绝对像素 UV 引用贴图
  且 `texture_size` 大于新尺寸，则跳过该贴图（避免渲染错乱）。
- 缩放结果双缓存：内存（每次资源重载清空）+ 磁盘（key = 路径+原尺寸+cap 的哈希）。

## 构建

```bash
# 需要 JDK 17
gradlew build
# 产物：build/libs/texturescaler-1.0.0.jar
```

放入 `mods/` 即可，服务端不需要安装。

## 作者

- **HoLuc1078**（开发）
- **Deepseek-v4-flash**（AI 辅助开发）

## 许可证

[Mozilla Public License 2.0](LICENSE)
