package games.qore.fastload;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Mipmaps against vanilla's MipmapGenerator, TextureUtil and ARGB as they are written (26.2 and 26.3), pixel by pixel. */
class MipmapsTest {
    private static final String[] PATHS = {"block/stone", "item/apple"};
    private static final float[] BIASES = {0.0F, 0.1F, -0.2F};

    @Test
    void matchesVanillaToTheBit() {
        Random random = new Random(42);
        int[][] sizes = {{16, 16}, {32, 32}, {64, 32}, {16, 64}, {128, 128}, {8, 8}, {2, 2}, {1, 1}, {256, 64}};
        int checked = 0;
        for (int[] size : sizes) {
            for (int kind = 0; kind < Kind.values().length; kind++) {
                int[] image = image(Kind.values()[kind], size[0], size[1], random);
                for (int strategy = Mipmaps.AUTO; strategy <= Mipmaps.DARK_CUTOUT; strategy++) {
                    for (boolean transparent : new boolean[]{false, true}) {
                        for (String path : PATHS) {
                            float bias = BIASES[random.nextInt(BIASES.length)];
                            int levels = Math.min(4, Integer.numberOfTrailingZeros(Math.min(size[0], size[1])));
                            for (int level = 0; level <= levels; level++) {
                                check(new int[][]{image}, size[0], size[1], level, strategy, transparent, path, bias);
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(4440, checked);
    }

    @Test
    void keepsExistingLevels() {
        Random random = new Random(7);
        int[] base = image(Kind.CUTOUT, 32, 32, random);
        int[] level1 = image(Kind.RANDOM, 16, 16, random);
        for (int strategy = Mipmaps.AUTO; strategy <= Mipmaps.DARK_CUTOUT; strategy++) {
            for (int level = 0; level <= 4; level++) check(new int[][]{base, level1}, 32, 32, level, strategy, true, "block/glass", 0.0F);
        }
    }

    @Test
    void sameInAbgr() {
        Random random = new Random(3);
        int[] argb = image(Kind.CUTOUT, 64, 64, random);
        for (int strategy = Mipmaps.AUTO; strategy <= Mipmaps.DARK_CUTOUT; strategy++) {
            int[][] fromArgb = Mipmaps.generateMipLevels(new int[][]{argb}, 64, 64, 4, strategy, true, "block/leaves", 0.0F);
            int[][] fromAbgr = Mipmaps.generateMipLevels(new int[][]{swap(argb)}, 64, 64, 4, strategy, true, "block/leaves", 0.0F);
            for (int level = 0; level < fromArgb.length; level++) assertArrayEquals(swap(fromArgb[level]), fromAbgr[level], "level " + level);
        }
    }

    @Test
    void newArraysOnlyForChangedLevels() {
        Random random = new Random(5);
        int[] opaque = image(Kind.OPAQUE, 16, 16, random);
        int[][] mean = Mipmaps.generateMipLevels(new int[][]{opaque}, 16, 16, 2, Mipmaps.MEAN, false, "block/dirt", 0.0F);
        assertSame(opaque, mean[0]);
        int[] cutout = image(Kind.CUTOUT, 16, 16, random);
        int[] copy = cutout.clone();
        int[][] solid = Mipmaps.generateMipLevels(new int[][]{cutout}, 16, 16, 2, Mipmaps.CUTOUT, true, "block/glass", 0.0F);
        assertNotSame(cutout, solid[0]);
        assertArrayEquals(copy, cutout);
    }

    @Test
    void inParallelRunsEveryItemOnce() {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 1000; i++) items.add(i);
        AtomicInteger sum = new AtomicInteger();
        Mipmaps.inParallel(items, Integer::longValue, sum::addAndGet);
        assertEquals(999 * 1000 / 2, sum.get());
    }

    private static void check(int[][] mips, int width, int height, int newMipLevel, int strategy, boolean transparent, String path, float bias) {
        int[][] given = Arrays.stream(mips).map(int[]::clone).toArray(int[][]::new);
        int[][] fast = Mipmaps.generateMipLevels(given, width, height, newMipLevel, strategy, transparent, path, bias);
        if (Mipmaps.unchanged(mips.length, newMipLevel, strategy, transparent, path)) {
            assertEquals(given.length, fast.length);
            for (int level = 0; level < given.length; level++) assertSame(given[level], fast[level]);
        }
        int[][] vanilla = Vanilla.generateMipLevels(Arrays.stream(mips).map(int[]::clone).toArray(int[][]::new), width, height, newMipLevel, strategy, transparent, path, bias);
        String what = width + "x" + height + " strategy " + strategy + " transparent " + transparent + " " + path + " bias " + bias;
        assertEquals(vanilla.length, fast.length, what);
        for (int level = 0; level < vanilla.length; level++) assertArrayEquals(vanilla[level], fast[level], what + " level " + level);
        for (int level = 0; level < mips.length; level++) assertArrayEquals(mips[level], given[level], what + ": changed the given level " + level);
    }

    private enum Kind { OPAQUE, CUTOUT, GRADIENT, RANDOM, EMPTY, SPECKS }

    private static int[] image(Kind kind, int width, int height, Random random) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = random.nextInt() & 0xFFFFFF;
                int alpha = switch (kind) {
                    case OPAQUE -> 255;
                    case CUTOUT -> (x / 3 + y / 5) % 3 == 0 ? 0 : 255;
                    case GRADIENT -> (x * 255 / Math.max(1, width - 1) + y * 7) & 0xFF;
                    case RANDOM -> random.nextInt(256);
                    case EMPTY -> 0;
                    case SPECKS -> random.nextInt(40) == 0 ? 128 + random.nextInt(128) : 0;
                };
                pixels[x + y * width] = alpha << 24 | rgb;
            }
        }
        return pixels;
    }

    private static int[] swap(int[] pixels) {
        int[] swapped = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            swapped[i] = color & 0xFF00FF00 | (color & 0xFF0000) >> 16 | (color & 0xFF) << 16;
        }
        return swapped;
    }

    /** Vanilla's code as written, on ARGB arrays standing for NativeImage's getPixel and setPixel. */
    private static final class Vanilla {
        private static final short[] SRGB_TO_LINEAR = new short[256];
        private static final byte[] LINEAR_TO_SRGB = new byte[1024];
        private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        static {
            for (int i = 0; i < SRGB_TO_LINEAR.length; i++) SRGB_TO_LINEAR[i] = (short) Math.round(computeSrgbToLinear(i / 255.0F) * 1023.0F);
            for (int i = 0; i < LINEAR_TO_SRGB.length; i++) LINEAR_TO_SRGB[i] = (byte) Math.round(computeLinearToSrgb(i / 1023.0F) * 255.0F);
        }

        private static float computeSrgbToLinear(float x) {
            return x >= 0.04045F ? (float) Math.pow((x + 0.055) / 1.055, 2.4) : x / 12.92F;
        }

        private static float computeLinearToSrgb(float x) {
            return x >= 0.0031308F ? (float) (1.055 * Math.pow(x, 0.4166666666666667) - 0.055) : 12.92F * x;
        }

        static int alpha(int color) { return color >>> 24; }
        static int red(int color) { return color >> 16 & 0xFF; }
        static int green(int color) { return color >> 8 & 0xFF; }
        static int blue(int color) { return color & 0xFF; }
        static int color(int alpha, int red, int green, int blue) { return (alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | blue & 0xFF; }
        static int color(int alpha, int rgb) { return alpha << 24 | rgb & 16777215; }
        static int color(float alpha, int rgb) { return floor(alpha * 255.0F) << 24 | rgb & 16777215; }
        static float alphaFloat(int color) { return alpha(color) / 255.0F; }
        static int floor(float value) { return (int) Math.floor(value); }
        static float srgbToLinearChannel(int srgb) { return SRGB_TO_LINEAR[srgb] / 1023.0F; }
        static int linearToSrgbChannel(float linear) { return LINEAR_TO_SRGB[floor(linear * 1023.0F)] & 0xFF; }

        static int meanLinear(int srgb1, int srgb2, int srgb3, int srgb4) {
            return color((alpha(srgb1) + alpha(srgb2) + alpha(srgb3) + alpha(srgb4)) / 4,
                linearChannelMean(red(srgb1), red(srgb2), red(srgb3), red(srgb4)),
                linearChannelMean(green(srgb1), green(srgb2), green(srgb3), green(srgb4)),
                linearChannelMean(blue(srgb1), blue(srgb2), blue(srgb3), blue(srgb4)));
        }

        static int linearChannelMean(int c1, int c2, int c3, int c4) {
            int linear = (SRGB_TO_LINEAR[c1] + SRGB_TO_LINEAR[c2] + SRGB_TO_LINEAR[c3] + SRGB_TO_LINEAR[c4]) / 4;
            return LINEAR_TO_SRGB[linear] & 0xFF;
        }

        static float alphaTestCoverage(int[] image, int width, int height, float alphaRef, float alphaScale) {
            float coverage = 0.0F;
            for (int y = 0; y < height - 1; y++) {
                for (int x = 0; x < width - 1; x++) {
                    float alpha00 = Math.clamp(alphaFloat(image[x + y * width]) * alphaScale, 0.0F, 1.0F);
                    float alpha10 = Math.clamp(alphaFloat(image[x + 1 + y * width]) * alphaScale, 0.0F, 1.0F);
                    float alpha01 = Math.clamp(alphaFloat(image[x + (y + 1) * width]) * alphaScale, 0.0F, 1.0F);
                    float alpha11 = Math.clamp(alphaFloat(image[x + 1 + (y + 1) * width]) * alphaScale, 0.0F, 1.0F);
                    float texelCoverage = 0.0F;
                    for (int subsampleY = 0; subsampleY < 4; subsampleY++) {
                        float fy = (subsampleY + 0.5F) / 4.0F;
                        for (int subsampleX = 0; subsampleX < 4; subsampleX++) {
                            float fx = (subsampleX + 0.5F) / 4.0F;
                            float alpha = alpha00 * (1.0F - fx) * (1.0F - fy) + alpha10 * fx * (1.0F - fy) + alpha01 * (1.0F - fx) * fy + alpha11 * fx * fy;
                            if (alpha > alphaRef) texelCoverage++;
                        }
                    }
                    coverage += texelCoverage / 16.0F;
                }
            }
            return coverage / ((width - 1) * (height - 1));
        }

        static void scaleAlphaToCoverage(int[] image, int width, int height, float desiredCoverage, float alphaRef, float alphaCutoffBias) {
            float minAlphaScale = 0.0F;
            float maxAlphaScale = 4.0F;
            float alphaScale = 1.0F;
            float bestAlphaScale = 1.0F;
            float bestError = Float.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                float currentCoverage = alphaTestCoverage(image, width, height, alphaRef, alphaScale);
                float error = Math.abs(currentCoverage - desiredCoverage);
                if (error < bestError) {
                    bestError = error;
                    bestAlphaScale = alphaScale;
                }
                if (currentCoverage < desiredCoverage) {
                    minAlphaScale = alphaScale;
                } else {
                    if (!(currentCoverage > desiredCoverage)) break;
                    maxAlphaScale = alphaScale;
                }
                alphaScale = (minAlphaScale + maxAlphaScale) * 0.5F;
            }
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = image[x + y * width];
                    float alpha = alphaFloat(pixel);
                    alpha = alpha * bestAlphaScale + alphaCutoffBias + 0.025F;
                    alpha = Math.clamp(alpha, 0.0F, 1.0F);
                    image[x + y * width] = color(alpha, pixel);
                }
            }
        }

        static int[][] generateMipLevels(int[][] currentMips, int width, int height, int newMipLevel, int mipmapStrategy, boolean hasTransparent, String path, float alphaCutoffBias) {
            if (mipmapStrategy == Mipmaps.AUTO) mipmapStrategy = hasTransparent ? Mipmaps.CUTOUT : Mipmaps.MEAN;
            if (currentMips.length == 1 && !path.startsWith("item/")) {
                if (mipmapStrategy == Mipmaps.CUTOUT || mipmapStrategy == Mipmaps.STRICT_CUTOUT) solidify(currentMips[0], width, height);
                else if (mipmapStrategy == Mipmaps.DARK_CUTOUT) fillEmptyAreasWithDarkColor(currentMips[0], width, height);
            }
            if (newMipLevel + 1 <= currentMips.length) return currentMips;
            int[][] result = new int[newMipLevel + 1][];
            int[] widths = new int[newMipLevel + 1];
            int[] heights = new int[newMipLevel + 1];
            result[0] = currentMips[0];
            widths[0] = width;
            heights[0] = height;
            boolean isCutoutMip = mipmapStrategy == Mipmaps.CUTOUT || mipmapStrategy == Mipmaps.STRICT_CUTOUT || mipmapStrategy == Mipmaps.DARK_CUTOUT;
            float cutoutRef = mipmapStrategy == Mipmaps.STRICT_CUTOUT ? 0.3F : 0.5F;
            float originalCoverage = isCutoutMip ? alphaTestCoverage(currentMips[0], width, height, cutoutRef, 1.0F) : 0.0F;
            for (int level = 1; level <= newMipLevel; level++) {
                widths[level] = widths[level - 1] >> 1;
                heights[level] = heights[level - 1] >> 1;
                if (level < currentMips.length) {
                    result[level] = currentMips[level];
                } else {
                    int[] lastData = result[level - 1];
                    int lastWidth = widths[level - 1];
                    int levelWidth = widths[level];
                    int[] data = new int[levelWidth * heights[level]];
                    for (int x = 0; x < levelWidth; x++) {
                        for (int y = 0; y < heights[level]; y++) {
                            int color1 = lastData[x * 2 + y * 2 * lastWidth];
                            int color2 = lastData[x * 2 + 1 + y * 2 * lastWidth];
                            int color3 = lastData[x * 2 + (y * 2 + 1) * lastWidth];
                            int color4 = lastData[x * 2 + 1 + (y * 2 + 1) * lastWidth];
                            data[x + y * levelWidth] = mipmapStrategy == Mipmaps.DARK_CUTOUT ? darkenedAlphaBlend(color1, color2, color3, color4) : meanLinear(color1, color2, color3, color4);
                        }
                    }
                    result[level] = data;
                }
                if (isCutoutMip) scaleAlphaToCoverage(result[level], widths[level], heights[level], originalCoverage, cutoutRef, alphaCutoffBias);
            }
            return result;
        }

        static int darkenedAlphaBlend(int color1, int color2, int color3, int color4) {
            float aTotal = 0.0F;
            float rTotal = 0.0F;
            float gTotal = 0.0F;
            float bTotal = 0.0F;
            for (int color : new int[]{color1, color2, color3, color4}) {
                if (alpha(color) != 0) {
                    aTotal += srgbToLinearChannel(alpha(color));
                    rTotal += srgbToLinearChannel(red(color));
                    gTotal += srgbToLinearChannel(green(color));
                    bTotal += srgbToLinearChannel(blue(color));
                }
            }
            aTotal /= 4.0F;
            rTotal /= 4.0F;
            gTotal /= 4.0F;
            bTotal /= 4.0F;
            return color(linearToSrgbChannel(aTotal), linearToSrgbChannel(rTotal), linearToSrgbChannel(gTotal), linearToSrgbChannel(bTotal));
        }

        static void solidify(int[] image, int width, int height) {
            int[] nearestColor = new int[width * height];
            int[] distances = new int[width * height];
            Arrays.fill(distances, Integer.MAX_VALUE);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    int color = image[x + y * width];
                    if (alpha(color) != 0) {
                        int packed = x + y * width;
                        distances[packed] = 0;
                        nearestColor[packed] = color;
                        queue.add(packed);
                    }
                }
            }
            while (!queue.isEmpty()) {
                int packed = queue.poll();
                int x = packed % width;
                int y = packed / width;
                for (int[] direction : DIRECTIONS) {
                    int neighborX = x + direction[0];
                    int neighborY = y + direction[1];
                    int neighbor = neighborX + neighborY * width;
                    if (neighborX >= 0 && neighborY >= 0 && neighborX < width && neighborY < height && distances[neighbor] > distances[packed] + 1) {
                        distances[neighbor] = distances[packed] + 1;
                        nearestColor[neighbor] = nearestColor[packed];
                        queue.add(neighbor);
                    }
                }
            }
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    int color = image[x + y * width];
                    image[x + y * width] = alpha(color) == 0 ? color(0, nearestColor[x + y * width]) : color;
                }
            }
        }

        static void fillEmptyAreasWithDarkColor(int[] image, int width, int height) {
            int darkestColor = -1;
            int minBrightness = Integer.MAX_VALUE;
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    int color = image[x + y * width];
                    if (alpha(color) != 0) {
                        int brightness = red(color) + green(color) + blue(color);
                        if (brightness < minBrightness) {
                            minBrightness = brightness;
                            darkestColor = color;
                        }
                    }
                }
            }
            int darkenedColor = color(0, 3 * red(darkestColor) / 4, 3 * green(darkestColor) / 4, 3 * blue(darkestColor) / 4);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    if (alpha(image[x + y * width]) == 0) image[x + y * width] = darkenedColor;
                }
            }
        }
    }
}
