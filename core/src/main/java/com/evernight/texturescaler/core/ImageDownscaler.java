package com.evernight.texturescaler.core;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Pure-JDK image downscaler.
 *
 * <p>Uses {@code javax.imageio}/{@code java.awt} instead of Minecraft's
 * {@code com.mojang.blaze3d.platform.NativeImage} on purpose: the {@code NativeImage}
 * API (and even its pixel getters/setters) changed several times between 1.12.2 and
 * 1.21.4, while {@code ImageIO} is stable across every Java version we target (8..21)
 * and every Minecraft version. That keeps the whole scaling algorithm in the shared
 * core instead of being duplicated per version.</p>
 *
 * <p>Quality: for large reductions the image is halved repeatedly (a cheap box filter)
 * before the final bilinear step, which avoids the aliasing a single bilinear sample
 * would produce when e.g. 4096 px is reduced to 512 px in one go.</p>
 */
public final class ImageDownscaler {

    private ImageDownscaler() {
    }

    /** Outcome of a successful downscale. */
    public static final class Result {
        public final byte[] png;
        public final int srcW;
        public final int srcH;
        public final int outW;
        public final int outH;

        Result(byte[] png, int srcW, int srcH, int outW, int outH) {
            this.png = png;
            this.srcW = srcW;
            this.srcH = srcH;
            this.outW = outW;
            this.outH = outH;
        }
    }

    /**
     * Downscales {@code original} so that its largest edge becomes {@code cap}, preserving
     * the aspect ratio. Returns {@code null} when the image is already small enough or
     * cannot be decoded (the caller then leaves the texture untouched).
     */
    public static Result downscale(byte[] original, int cap) {
        if (original == null || cap <= 0) {
            return null;
        }
        int[] header = PngInfo.read(original);
        if (header != null && Math.max(header[0], header[1]) <= cap) {
            return null;
        }
        BufferedImage src;
        try {
            // Never spill to temp files: the client is on the render/worker thread and the
            // images can be tens of MB.
            ImageIO.setUseCache(false);
            src = ImageIO.read(new ByteArrayInputStream(original));
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int maxEdge = Math.max(w, h);
        if (w <= 0 || h <= 0 || maxEdge <= cap) {
            return null;
        }
        int newW = Math.max(1, (int) Math.round((long) w * cap / maxEdge));
        int newH = Math.max(1, (int) Math.round((long) h * cap / maxEdge));

        BufferedImage out = progressiveScale(src, newW, newH);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(4096, newW * newH));
            if (!ImageIO.write(out, "png", bos)) {
                return null;
            }
            return new Result(bos.toByteArray(), w, h, newW, newH);
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage progressiveScale(BufferedImage src, int newW, int newH) {
        BufferedImage cur = src;
        int w = src.getWidth();
        int h = src.getHeight();
        while (w > newW * 2 || h > newH * 2) {
            w = Math.max(newW, w / 2);
            h = Math.max(newH, h / 2);
            cur = drawScaled(cur, w, h);
        }
        if (cur == src || w != newW || h != newH) {
            cur = drawScaled(cur, newW, newH);
        }
        return cur;
    }

    private static BufferedImage drawScaled(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }
}
