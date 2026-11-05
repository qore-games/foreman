package games.qore.fastload;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FontProviders against vanilla's FontSet.selectProviders: every codepoint tried on every provider in order. */
class FontProvidersTest {
    @Test
    void matchesVanilla() {
        Random random = new Random(11);
        for (int round = 0; round < 200; round++) {
            int count = 1 + random.nextInt(12);
            int[][] supported = new int[count][];
            for (int i = 0; i < count; i++) {
                if (random.nextInt(8) == 0) continue;
                int size = random.nextInt(60);
                supported[i] = new int[size];
                int base = random.nextInt(0x110000 - 200);
                for (int j = 0; j < size; j++) supported[i][j] = base + random.nextInt(random.nextBoolean() ? 100 : 0x10000);
            }
            check(supported);
        }
    }

    @Test
    void iconFonts() {
        int[][] supported = new int[5000][];
        for (int i = 0; i < supported.length; i++) supported[i] = new int[]{0xE000 + i, 0xE000 + i / 2};
        supported[supported.length - 1] = null;
        check(supported);
    }

    @Test
    void fallbackTakesWhatComesAfterIt() {
        FontProviders.Selection selection = FontProviders.select(new int[][]{{1, 2}, null, {2, 3, 4}, {5}});
        assertArrayEquals(new int[]{3, 4, 5}, selection.codepoints(1));
        assertEquals(0, selection.provider(2));
        assertEquals(1, selection.provider(5));
        assertEquals(-1, selection.provider(6));
        assertEquals(false, selection.used(2));
    }

    @Test
    void askedProvidersMatchVanilla() {
        Random random = new Random(23);
        for (int round = 0; round < 300; round++) {
            int count = 1 + random.nextInt(12);
            int[][] supported = new int[count][];
            Set<Integer>[] glyphs = glyphSets(count);
            IntPredicate[] has = new IntPredicate[count];
            for (int i = 0; i < count; i++) {
                if (random.nextInt(8) == 0) continue;
                int size = random.nextInt(60);
                supported[i] = new int[size];
                for (int j = 0; j < size; j++) supported[i][j] = random.nextInt(300);
                if (random.nextInt(3) > 0) continue;
                // Another kind: some supported codepoints without a glyph, some glyphs it doesn't list.
                for (int codepoint : supported[i]) if (random.nextInt(4) > 0) glyphs[i].add(codepoint);
                for (int j = random.nextInt(30); j > 0; j--) glyphs[i].add(random.nextInt(300));
                Set<Integer> mine = glyphs[i];
                has[i] = mine::contains;
            }
            check(supported, has, glyphs);
        }
    }

    /** A wrapper that answers like the provider it wraps (ModernFix's lazy unifont) in an icon font, asked little. */
    @Test
    void wrapperInIconFont() {
        int[][] supported = new int[3002][];
        Set<Integer>[] glyphs = glyphSets(supported.length);
        IntPredicate[] has = new IntPredicate[supported.length];
        for (int i = 0; i < 3000; i++) supported[i] = new int[]{0xE000 + i, 0x41 + i % 50};
        supported[3000] = new int[60000];
        for (int j = 0; j < 60000; j++) {
            supported[3000][j] = j;
            glyphs[3000].add(j);
        }
        int[] asked = new int[1];
        has[3000] = codepoint -> {
            asked[0]++;
            return codepoint < 60000;
        };
        check(supported, has, glyphs);
        assertTrue(asked[0] <= 60000 + 3000, "asked " + asked[0] + " times");
    }

    @Test
    void askedOnlyAboutFreeCodepointsOnce() {
        List<Integer> asked = new ArrayList<>();
        IntPredicate[] has = {null, codepoint -> {
            asked.add(codepoint);
            return codepoint == 3 || codepoint == 9;
        }, null, null};
        FontProviders.Selection selection = FontProviders.select(new int[][]{{1, 2}, {2, 3, 4}, {4, 5, 1}, null}, has);
        assertEquals(List.of(3, 4, 5), asked);
        assertArrayEquals(new int[]{3}, selection.codepoints(1));
        assertArrayEquals(new int[]{4, 5}, selection.codepoints(2));
        assertEquals(false, selection.used(3));
    }

    @Test
    void fallbackTakesWhatAskedProvidersLeft() {
        IntPredicate[] has = {codepoint -> false, null, null};
        FontProviders.Selection selection = FontProviders.select(new int[][]{{1, 2}, null, {3}}, has);
        assertArrayEquals(new int[]{1, 2, 3}, selection.codepoints(1));
        assertEquals(false, selection.used(0));
    }

    private static void check(int[][] supported) {
        Set<Integer>[] glyphs = glyphSets(supported.length);
        for (int i = 0; i < supported.length; i++) if (supported[i] != null) for (int codepoint : supported[i]) glyphs[i].add(codepoint);
        check(supported, new IntPredicate[supported.length], glyphs, FontProviders.select(supported));
    }

    private static void check(int[][] supported, IntPredicate[] has, Set<Integer>[] glyphs) {
        for (int i = 0; i < supported.length; i++) {
            if (has[i] == null && supported[i] != null) for (int codepoint : supported[i]) glyphs[i].add(codepoint);
        }
        check(supported, has, glyphs, FontProviders.select(supported, has));
    }

    /** glyphs[i] is the codepoints provider i has a glyph for (null in supported: every one). */
    private static void check(int[][] supported, IntPredicate[] has, Set<Integer>[] glyphs, FontProviders.Selection selection) {
        Set<Integer> union = new LinkedHashSet<>();
        for (int[] codepoints : supported) if (codepoints != null) for (int codepoint : codepoints) union.add(codepoint);
        List<Set<Integer>> given = new ArrayList<>();
        for (int i = 0; i < supported.length; i++) given.add(new HashSet<>());
        for (int codepoint : union) {
            int provider = vanillaProvider(supported, has, glyphs, codepoint);
            assertEquals(provider, selection.provider(codepoint), "codepoint " + codepoint);
            if (provider >= 0) given.get(provider).add(codepoint);
        }
        for (int i = 0; i < supported.length; i++) {
            Set<Integer> fast = new HashSet<>();
            for (int codepoint : selection.codepoints(i)) fast.add(codepoint);
            assertEquals(given.get(i), fast, "provider " + i);
            assertEquals(selection.codepoints(i).length, fast.size(), "provider " + i + " gives a codepoint twice");
            assertEquals(!given.get(i).isEmpty(), selection.used(i), "provider " + i);
        }
    }

    /** The first provider with a glyph for codepoint (vanilla's getGlyph in order); the all-missing fallback has all. */
    private static int vanillaProvider(int[][] supported, IntPredicate[] has, Set<Integer>[] glyphs, int codepoint) {
        for (int i = 0; i < supported.length; i++) {
            if (supported[i] == null && has[i] == null) return i;
            if (glyphs[i].contains(codepoint)) return i;
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer>[] glyphSets(int count) {
        Set<Integer>[] sets = new Set[count];
        for (int i = 0; i < count; i++) sets[i] = new HashSet<>();
        return sets;
    }
}
