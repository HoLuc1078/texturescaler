package com.evernight.texturescaler.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/**
 * The part of a {@code <texture>.png.mcmeta} sidecar that the scaler has to respect.
 *
 * <p>Vanilla decides the animation frame grid from the <b>scaled</b> image dimensions, in
 * {@code AnimationMetadataSection.calculateFrameSize(imageWidth, imageHeight)}:</p>
 *
 * <pre>
 * if (this.frameWidth != -1) { ... } else if (this.frameHeight != -1) { ... }
 * else { int i = Math.min(imageWidth, imageHeight); return new FrameSize(i, i); }
 * </pre>
 *
 * <p>So a filmstrip with a bare {@code {"animation":{"frametime":n}}} sidecar is cut into
 * <b>square frames as wide as the shorter image edge</b>. Resizing such a texture by a
 * non-integer factor destroys the grid: e.g. 64x11776 (= 184 square 64x64 frames) scaled to
 * 22x4096 makes vanilla slice it into 22x22 squares, which is 186 frames at a pitch
 * (64 -> 22.26) that no longer matches the scaled frame height. The animation then shears
 * further out of alignment frame by frame and renders as a smear.</p>
 *
 * <p>The only lossless resize is an integer one: dividing both edges by the same
 * {@code k} keeps the frame size at {@code min(w, h) / k} and the frame count identical.</p>
 */
public final class AnimationInfo {

    /** Shared sentinel meaning "this texture is not an animated filmstrip". */
    public static final AnimationInfo NONE = new AnimationInfo(false, false);

    /** True when the sidecar contains an {@code animation} section. */
    public final boolean animated;

    /**
     * True when the section pins {@code width} and/or {@code height}.
     *
     * <p>Vanilla then uses those pixel values verbatim, so the frame size is no longer
     * derived from the image dimensions and the texture cannot be resized at all without
     * also re-emitting a patched sidecar (which the overlay does not do).</p>
     */
    public final boolean explicitFrameSize;

    private AnimationInfo(boolean animated, boolean explicitFrameSize) {
        this.animated = animated;
        this.explicitFrameSize = explicitFrameSize;
    }

    /**
     * @return an {@link AnimationInfo} describing {@code mcmeta}, or {@code null} when the
     *         bytes are not a readable sidecar with an {@code animation} section.
     */
    public static AnimationInfo parse(byte[] mcmeta) {
        if (mcmeta == null || mcmeta.length == 0) {
            return null;
        }
        try {
            // Instance API on purpose: the static JsonParser.parseString only exists since
            // Gson 2.8.6, while Minecraft 1.12.2/1.16.5 ship Gson 2.8.0.
            @SuppressWarnings("deprecation")
            JsonElement root = new JsonParser().parse(new String(mcmeta, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("animation") || !obj.get("animation").isJsonObject()) {
                return null;
            }
            JsonObject animation = obj.getAsJsonObject("animation");
            boolean explicit = intOr(animation, "width") != -1 || intOr(animation, "height") != -1;
            return new AnimationInfo(true, explicit);
        } catch (Exception e) {
            // A malformed sidecar must never break a reload; treat it as "not animated".
            return null;
        }
    }

    private static int intOr(JsonObject obj, String member) {
        try {
            if (obj.has(member) && obj.get(member).isJsonPrimitive()) {
                return obj.get(member).getAsInt();
            }
        } catch (Exception ignored) {
            // fall through to the default
        }
        return -1;
    }

    /**
     * Smallest integer divisor {@code k >= 2} of both edges that brings
     * {@code max(w/k, h/k)} within {@code cap}, so the output is the largest safe resize.
     *
     * @return {@code k}, or {@code 1} when no such divisor exists (the caller must then
     *         leave the texture untouched rather than shear the animation)
     */
    public static int safeDivisor(int w, int h, int cap) {
        if (w <= 0 || h <= 0 || cap <= 0) {
            return 1;
        }
        int g = gcd(w, h);
        for (int k = 2; k <= g; k++) {
            if (g % k != 0) {
                continue;
            }
            if (w / k <= cap && h / k <= cap) {
                return k;
            }
        }
        return 1;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
