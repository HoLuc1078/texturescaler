# Changelog

## 2.0.3
- Animated textures are scaled by an integer factor only, so the frame grid and frame count stay
  intact. They are touched only when `max(width, height) > GL_MAX_TEXTURE_SIZE`.
- A texture whose `.mcmeta` states an explicit `width`/`height` is never touched.

## 2.0.2
- Tiered caps by use: normal (longest edge ≤ 1024), large (> 1024), strip (aspect ratio ≥ 4:1).
- `capOverride` overrides every tier.

## 2.0.1
- Fixed a JVM crash when `GL_MAX_TEXTURE_SIZE` was queried before the render backend existed.
  The cap is now resolved on the first resource reload and latched.
- Fixed the overlay claiming no namespace on the first reload, which left the atlas unscaled.

## 1.1.0
- Persistent size manifest (`cache/sizes.json`), no decode on cache hit, scan reuse across reloads.
- Startup cost down to roughly 2–3 s.

## 1.0.1
- Implemented `listResources`, so scaled textures actually reach the block atlas.

## 0.0.2
- Read atlas sprites through `listResources` instead of `getResource`.

## 0.0.1
- First release.