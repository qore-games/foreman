package games.qore.fastload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * Vanilla's mipmap generation ({@code MipmapGenerator.generateMipLevels}), with the same pixels to the bit, faster: on
 * arrays of the whole image rather than a native read or write per pixel, and skipping work whose result is known. Big
 * cutout textures (a model's 2048×2048) took vanilla half a second each, on one thread.
 * <p>
 * Pixels are ints with their alpha in the top byte: ARGB, or ABGR as a {@code NativeImage} stores them. Vanilla's
 * blends treat the three colors alike, so either order gives the same pixels in that order.
 */
public final class Mipmaps {
    /** Vanilla's {@code MipmapStrategy} values. */
    public static final int AUTO = 0, MEAN = 1, CUTOUT = 2, STRICT_CUTOUT = 3, DARK_CUTOUT = 4;

    /** Far beyond the rounding of four products and three sums of values up to 1. */
    private static final float SURE = 1.0e-4f;

    /** {@code TextureUtil.DIRECTIONS}, in its order. */
    private static final int[] DIRECTION_X = {1, -1, 0, 0};
    private static final int[] DIRECTION_Y = {0, 0, 1, -1};

    /** {@code ARGB}'s tables between sRGB channels and linear ones (of 1024 steps), computed as vanilla does. */
    private static final short[] SRGB_TO_LINEAR = new short[256];
    private static final byte[] LINEAR_TO_SRGB = new byte[1024];

    static {
        for (int i = 0; i < SRGB_TO_LINEAR.length; i++) {
            float channel = i / 255.0F;
            float linear = channel >= 0.04045F ? (float) Math.pow((channel + 0.055) / 1.055, 2.4) : channel / 12.92F;
            SRGB_TO_LINEAR[i] = (short) Math.round(linear * 1023.0F);
        }
        for (int i = 0; i < LINEAR_TO_SRGB.length; i++) {
            float channel = i / 1023.0F;
            float srgb = channel >= 0.0031308F ? (float) (1.055 * Math.pow(channel, 0.4166666666666667) - 0.055) : 12.92F * channel;
            LINEAR_TO_SRGB[i] = (byte) Math.round(srgb * 255.0F);
        }
    }

    private Mipmaps() {
    }

    /** The constant of a {@code MipmapStrategy} by its name ("AUTO", "MEAN", "CUTOUT", "STRICT_CUTOUT", "DARK_CUTOUT"). */
    public static int strategy(String name) {
        return switch (name) {
            case "AUTO" -> AUTO;
            case "MEAN" -> MEAN;
            case "CUTOUT" -> CUTOUT;
            case "STRICT_CUTOUT" -> STRICT_CUTOUT;
            case "DARK_CUTOUT" -> DARK_CUTOUT;
            default -> throw new IllegalArgumentException("Unknown mipmap strategy: " + name);
        };
    }

    /** Whether generateMipLevels would leave a sprite's levels as they are, adding none: no need to read its pixels. */
    public static boolean unchanged(int levels, int newMipLevel, int strategy, boolean hasTransparent, String path) {
        int mipmapStrategy = strategy == AUTO ? (hasTransparent ? CUTOUT : MEAN) : strategy;
        boolean fills = levels == 1 && !path.startsWith("item/") && mipmapStrategy != MEAN;
        return !fills && newMipLevel + 1 <= levels;
    }

    /**
     * {@code MipmapGenerator.generateMipLevels}: a sprite's levels (level 0 width × height, each next one half the one
     * before) down to newMipLevel, path being the sprite name's path. Never changes the given arrays: a level that
     * changed is a new array, one that didn't is the same array.
     */
    public static int[][] generateMipLevels(int[][] mips, int width, int height, int newMipLevel, int strategy,
                                            boolean hasTransparent, String path, float alphaCutoffBias) {
        int mipmapStrategy = strategy == AUTO ? (hasTransparent ? CUTOUT : MEAN) : strategy;
        int[] base = mips[0];
        if (mips.length == 1 && !path.startsWith("item/")) {
            if (mipmapStrategy == CUTOUT || mipmapStrategy == STRICT_CUTOUT) base = solidify(base, width, height);
            else if (mipmapStrategy == DARK_CUTOUT) base = fillEmptyAreasWithDarkColor(base, width, height);
        }
        if (newMipLevel + 1 <= mips.length) {
            int[][] result = mips.clone();
            result[0] = base;
            return result;
        }
        int[][] result = new int[newMipLevel + 1][];
        result[0] = base;
        boolean isCutoutMip = mipmapStrategy == CUTOUT || mipmapStrategy == STRICT_CUTOUT || mipmapStrategy == DARK_CUTOUT;
        float cutoutRef = mipmapStrategy == STRICT_CUTOUT ? 0.3F : 0.5F;
        float originalCoverage = isCutoutMip ? alphaTestCoverage(base, width, height, cutoutRef, 1.0F) : 0.0F;
        int levelWidth = width;
        int levelHeight = height;
        for (int level = 1; level <= newMipLevel; level++) {
            int lastWidth = levelWidth;
            levelWidth >>= 1;
            levelHeight >>= 1;
            boolean made = level >= mips.length;
            int[] pixels = made ? halve(result[level - 1], lastWidth, levelWidth, levelHeight, mipmapStrategy == DARK_CUTOUT) : mips[level];
            if (isCutoutMip) {
                if (!made) pixels = pixels.clone();
                scaleAlphaToCoverage(pixels, levelWidth, levelHeight, originalCoverage, cutoutRef, alphaCutoffBias);
            }
            result[level] = pixels;
        }
        return result;
    }

    /**
     * Runs action on every item on the current fork-join pool (the common pool outside one), the biggest first (they
     * take the longest, so they shouldn't be the last to start), and returns when all are done: a failure is thrown
     * once every one has ended, never while others still work on what its caller may then free.
     */
    public static <T> void inParallel(Collection<? extends T> items, ToLongFunction<? super T> size, Consumer<? super T> action) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.<T>comparingLong(size).reversed());
        List<ForkJoinTask<?>> tasks = new ArrayList<>(sorted.size());
        for (T item : sorted) tasks.add(ForkJoinTask.adapt(() -> action.accept(item)));
        // Thieves take the oldest forks first: the biggest.
        for (ForkJoinTask<?> task : tasks) task.fork();
        Throwable failure = null;
        for (ForkJoinTask<?> task : tasks) {
            task.quietlyJoin();
            if (failure == null) failure = task.getException();
        }
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
        if (failure != null) throw new RuntimeException(failure);
    }

    /** The next mip level: each pixel blends the 2×2 below it, with vanilla's blend. */
    static int[] halve(int[] last, int lastWidth, int width, int height, boolean darkened) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int top = x * 2 + y * 2 * lastWidth;
                int color1 = last[top];
                int color2 = last[top + 1];
                int color3 = last[top + lastWidth];
                int color4 = last[top + lastWidth + 1];
                pixels[x + y * width] = darkened ? darkenedAlphaBlend(color1, color2, color3, color4) : meanLinear(color1, color2, color3, color4);
            }
        }
        return pixels;
    }

    /** {@code ARGB.meanLinear}. */
    private static int meanLinear(int color1, int color2, int color3, int color4) {
        return color(
            ((color1 >>> 24) + (color2 >>> 24) + (color3 >>> 24) + (color4 >>> 24)) / 4,
            linearChannelMean(color1 >> 16 & 0xFF, color2 >> 16 & 0xFF, color3 >> 16 & 0xFF, color4 >> 16 & 0xFF),
            linearChannelMean(color1 >> 8 & 0xFF, color2 >> 8 & 0xFF, color3 >> 8 & 0xFF, color4 >> 8 & 0xFF),
            linearChannelMean(color1 & 0xFF, color2 & 0xFF, color3 & 0xFF, color4 & 0xFF));
    }

    private static int linearChannelMean(int c1, int c2, int c3, int c4) {
        int linear = (SRGB_TO_LINEAR[c1] + SRGB_TO_LINEAR[c2] + SRGB_TO_LINEAR[c3] + SRGB_TO_LINEAR[c4]) / 4;
        return LINEAR_TO_SRGB[linear] & 0xFF;
    }

    /** {@code MipmapGenerator.darkenedAlphaBlend}: the mean in linear space of the four, transparent ones counting as black. */
    private static int darkenedAlphaBlend(int color1, int color2, int color3, int color4) {
        float aTotal = 0.0F;
        float rTotal = 0.0F;
        float gTotal = 0.0F;
        float bTotal = 0.0F;
        if (color1 >>> 24 != 0) {
            aTotal += srgbToLinear(color1 >>> 24);
            rTotal += srgbToLinear(color1 >> 16 & 0xFF);
            gTotal += srgbToLinear(color1 >> 8 & 0xFF);
            bTotal += srgbToLinear(color1 & 0xFF);
        }
        if (color2 >>> 24 != 0) {
            aTotal += srgbToLinear(color2 >>> 24);
            rTotal += srgbToLinear(color2 >> 16 & 0xFF);
            gTotal += srgbToLinear(color2 >> 8 & 0xFF);
            bTotal += srgbToLinear(color2 & 0xFF);
        }
        if (color3 >>> 24 != 0) {
            aTotal += srgbToLinear(color3 >>> 24);
            rTotal += srgbToLinear(color3 >> 16 & 0xFF);
            gTotal += srgbToLinear(color3 >> 8 & 0xFF);
            bTotal += srgbToLinear(color3 & 0xFF);
        }
        if (color4 >>> 24 != 0) {
            aTotal += srgbToLinear(color4 >>> 24);
            rTotal += srgbToLinear(color4 >> 16 & 0xFF);
            gTotal += srgbToLinear(color4 >> 8 & 0xFF);
            bTotal += srgbToLinear(color4 & 0xFF);
        }
        aTotal /= 4.0F;
        rTotal /= 4.0F;
        gTotal /= 4.0F;
        bTotal /= 4.0F;
        return color(linearToSrgb(aTotal), linearToSrgb(rTotal), linearToSrgb(gTotal), linearToSrgb(bTotal));
    }

    private static float srgbToLinear(int srgb) {
        return SRGB_TO_LINEAR[srgb] / 1023.0F;
    }

    private static int linearToSrgb(float linear) {
        return LINEAR_TO_SRGB[(int) Math.floor(linear * 1023.0F)] & 0xFF;
    }

    private static int color(int alpha, int red, int green, int blue) {
        return (alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | blue & 0xFF;
    }

    private static float alphaFloat(int color) {
        return (color >>> 24) / 255.0F;
    }

    /**
     * {@code MipmapGenerator.alphaTestCoverage}: the share of the texture's area the alpha test keeps, which vanilla
     * measures with 16 bilinear samples per texel. A texel whose four corners are all clearly above or below the cutoff
     * has all its samples on that side (the weights are exact and add up to 1), so only the texels an edge crosses are
     * sampled.
     */
    static float alphaTestCoverage(int[] pixels, int width, int height, float alphaRef, float alphaScale) {
        float coverage = 0.0F;
        for (int y = 0; y < height - 1; y++) {
            int row = y * width;
            for (int x = 0; x < width - 1; x++) {
                float alpha00 = Math.clamp(alphaFloat(pixels[row + x]) * alphaScale, 0.0F, 1.0F);
                float alpha10 = Math.clamp(alphaFloat(pixels[row + x + 1]) * alphaScale, 0.0F, 1.0F);
                float alpha01 = Math.clamp(alphaFloat(pixels[row + width + x]) * alphaScale, 0.0F, 1.0F);
                float alpha11 = Math.clamp(alphaFloat(pixels[row + width + x + 1]) * alphaScale, 0.0F, 1.0F);
                if (Math.min(Math.min(alpha00, alpha10), Math.min(alpha01, alpha11)) > alphaRef + SURE) coverage += 1.0F;
                else if (Math.max(Math.max(alpha00, alpha10), Math.max(alpha01, alpha11)) >= alphaRef - SURE)
                    coverage += texelCoverage(alpha00, alpha10, alpha01, alpha11, alphaRef) / 16.0F;
            }
        }
        return coverage / ((width - 1) * (height - 1));
    }

    /** Vanilla's 16 samples of a texel, in its order and arithmetic. */
    private static float texelCoverage(float alpha00, float alpha10, float alpha01, float alpha11, float alphaRef) {
        float texelCoverage = 0.0F;
        for (int subsampleY = 0; subsampleY < 4; subsampleY++) {
            float fy = (subsampleY + 0.5F) / 4.0F;
            for (int subsampleX = 0; subsampleX < 4; subsampleX++) {
                float fx = (subsampleX + 0.5F) / 4.0F;
                float alpha = alpha00 * (1.0F - fx) * (1.0F - fy) + alpha10 * fx * (1.0F - fy) + alpha01 * (1.0F - fx) * fy + alpha11 * fx * fy;
                if (alpha > alphaRef) texelCoverage++;
            }
        }
        return texelCoverage;
    }

    /**
     * {@code MipmapGenerator.scaleAlphaToCoverage}, in place: the alpha scale (searched in 5 halvings) that keeps
     * desiredCoverage, applied to every pixel with the cutoff bias.
     */
    static void scaleAlphaToCoverage(int[] pixels, int width, int height, float desiredCoverage, float alphaRef, float alphaCutoffBias) {
        float minAlphaScale = 0.0F;
        float maxAlphaScale = 4.0F;
        float alphaScale = 1.0F;
        float bestAlphaScale = 1.0F;
        float bestError = Float.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            float currentCoverage = alphaTestCoverage(pixels, width, height, alphaRef, alphaScale);
            float error = Math.abs(currentCoverage - desiredCoverage);
            if (error < bestError) {
                bestError = error;
                bestAlphaScale = alphaScale;
            }
            if (currentCoverage < desiredCoverage) minAlphaScale = alphaScale;
            else if (currentCoverage > desiredCoverage) maxAlphaScale = alphaScale;
            else break;
            alphaScale = (minAlphaScale + maxAlphaScale) * 0.5F;
        }
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            float alpha = alphaFloat(pixel) * bestAlphaScale + alphaCutoffBias + 0.025F;
            alpha = Math.clamp(alpha, 0.0F, 1.0F);
            pixels[i] = (int) Math.floor(alpha * 255.0F) << 24 | pixel & 0xFFFFFF;
        }
    }

    /**
     * {@code TextureUtil.solidify}: every transparent pixel takes the color of its nearest opaque one (for mipmaps), found
     * by a breadth-first search from all opaque pixels, which reaches each pixel once. Same scan order, same neighbors in
     * the same order, so the same pixel wins every tie. Returns pixels itself when none is transparent.
     */
    static int[] solidify(int[] pixels, int width, int height) {
        if (!anyTransparent(pixels)) return pixels;
        int[] nearestColor = new int[width * height];
        int[] distances = new int[width * height];
        Arrays.fill(distances, Integer.MAX_VALUE);
        int[] queue = new int[width * height];
        int head = 0;
        int tail = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int packed = x + y * width;
                if (pixels[packed] >>> 24 != 0) {
                    distances[packed] = 0;
                    nearestColor[packed] = pixels[packed];
                    queue[tail++] = packed;
                }
            }
        }
        while (head < tail) {
            int packed = queue[head++];
            int x = packed % width;
            int y = packed / width;
            for (int direction = 0; direction < 4; direction++) {
                int neighborX = x + DIRECTION_X[direction];
                int neighborY = y + DIRECTION_Y[direction];
                int neighbor = neighborX + neighborY * width;
                if (neighborX >= 0 && neighborY >= 0 && neighborX < width && neighborY < height && distances[neighbor] > distances[packed] + 1) {
                    distances[neighbor] = distances[packed] + 1;
                    nearestColor[neighbor] = nearestColor[packed];
                    queue[tail++] = neighbor;
                }
            }
        }
        int[] result = pixels.clone();
        for (int i = 0; i < result.length; i++) {
            if (result[i] >>> 24 == 0) result[i] = nearestColor[i] & 0xFFFFFF;
        }
        return result;
    }

    /**
     * {@code TextureUtil.fillEmptyAreasWithDarkColor}: transparent pixels take three quarters of the darkest opaque
     * pixel's color (the first found column by column, as vanilla). Returns pixels itself when none is transparent.
     */
    static int[] fillEmptyAreasWithDarkColor(int[] pixels, int width, int height) {
        if (!anyTransparent(pixels)) return pixels;
        int darkestColor = -1;
        int minBrightness = Integer.MAX_VALUE;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int color = pixels[x + y * width];
                if (color >>> 24 != 0) {
                    int brightness = (color >> 16 & 0xFF) + (color >> 8 & 0xFF) + (color & 0xFF);
                    if (brightness < minBrightness) {
                        minBrightness = brightness;
                        darkestColor = color;
                    }
                }
            }
        }
        int darkenedColor = color(0, 3 * (darkestColor >> 16 & 0xFF) / 4, 3 * (darkestColor >> 8 & 0xFF) / 4, 3 * (darkestColor & 0xFF) / 4);
        int[] result = pixels.clone();
        for (int i = 0; i < result.length; i++) {
            if (result[i] >>> 24 == 0) result[i] = darkenedColor;
        }
        return result;
    }

    private static boolean anyTransparent(int[] pixels) {
        for (int pixel : pixels) {
            if (pixel >>> 24 == 0) return true;
        }
        return false;
    }
}
