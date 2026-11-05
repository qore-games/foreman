package games.qore.fastload;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * {@code FontSet.selectProviders} in linear time. Vanilla tries every provider for every codepoint, which is quadratic
 * for fonts made of many small providers (one per icon); a provider of vanilla's kinds (bitmap, space, TrueType,
 * unihex) has a glyph exactly for its supported codepoints, so each can claim those not taken yet, in order. Providers
 * of other kinds (a mod's wrapper around one of vanilla's) are asked about each codepoint still free at their turn,
 * which are the questions vanilla asks them.
 */
public final class FontProviders {
    private static final int[] NONE = new int[0];

    private FontProviders() {
    }

    /**
     * Which provider gives each codepoint: supported[i] is provider i's codepoints (non-negative), null for a provider
     * with a glyph for every codepoint (vanilla's all-missing fallback), which takes all those not taken before it.
     */
    public static Selection select(int[][] supported) {
        return select(supported, new IntPredicate[supported.length]);
    }

    /**
     * The same with providers whose glyphs may not be exactly their supported codepoints: has[i], when not null, is
     * provider i's {@code getGlyph(codepoint) != null}, asked once about each codepoint of the font (every provider's
     * supported ones) that no provider before it took, as vanilla asks it; supported[i] is then its supported
     * codepoints (null for none), part of the font's. Providers whose has is null are as in {@link #select(int[][])}.
     */
    public static Selection select(int[][] supported, IntPredicate[] has) {
        if (has.length != supported.length) throw new IllegalArgumentException("supported and has differ in length");
        int total = 0;
        for (int[] codepoints : supported) {
            if (codepoints != null) total += codepoints.length;
        }
        CodepointMap owners = new CodepointMap(total);
        int[][] given = new int[supported.length][];
        Arrays.fill(given, NONE);
        int[] font = null;
        for (int i = 0; i < supported.length; i++) {
            if (has[i] == null && supported[i] != null) {
                given[i] = claim(supported[i], i, owners);
                continue;
            }
            if (font == null) font = distinct(supported, total);
            given[i] = ask(font, i, has[i], owners);
            // The fallback took every codepoint left.
            if (has[i] == null) break;
        }
        return new Selection(given, owners);
    }

    /** Every provider's codepoints, each once, in the providers' order. */
    private static int[] distinct(int[][] supported, int total) {
        CodepointMap seen = new CodepointMap(total);
        int[] font = new int[total];
        int count = 0;
        for (int[] codepoints : supported) {
            if (codepoints == null) continue;
            for (int codepoint : codepoints) {
                if (seen.putIfAbsent(codepoint, 0)) font[count++] = codepoint;
            }
        }
        return count == font.length ? font : Arrays.copyOf(font, count);
    }

    /** The font's codepoints nobody has yet that provider has (all of them when has is null), now provider's. */
    private static int[] ask(int[] font, int provider, IntPredicate has, CodepointMap owners) {
        int[] mine = new int[font.length];
        int count = 0;
        for (int codepoint : font) {
            if (owners.get(codepoint) < 0 && (has == null || has.test(codepoint))) {
                owners.putIfAbsent(codepoint, provider);
                mine[count++] = codepoint;
            }
        }
        return Arrays.copyOf(mine, count);
    }

    /** The codepoints nobody has yet, now provider's, in their order. */
    private static int[] claim(int[] codepoints, int provider, CodepointMap owners) {
        int[] mine = new int[codepoints.length];
        int count = claim(codepoints, provider, owners, mine, 0);
        return count == mine.length ? mine : Arrays.copyOf(mine, count);
    }

    /** Puts the codepoints nobody has yet into mine from count on, now provider's: returns the new count. */
    private static int claim(int[] codepoints, int provider, CodepointMap owners, int[] mine, int count) {
        for (int codepoint : codepoints) {
            if (owners.putIfAbsent(codepoint, provider)) mine[count++] = codepoint;
        }
        return count;
    }

    /** The providers each codepoint goes to. */
    public static final class Selection {
        private final int[][] given;
        private final CodepointMap owners;

        private Selection(int[][] given, CodepointMap owners) {
            this.given = given;
            this.owners = owners;
        }

        /** The number of providers given. */
        public int providers() {
            return given.length;
        }

        /**
         * The codepoints provider gives, in the order of its array (a fallback's, or those of a provider asked with has:
         * in the order of the providers' arrays).
         */
        public int[] codepoints(int provider) {
            return given[provider];
        }

        /** Whether provider gives any codepoint: vanilla's font set keeps only those. */
        public boolean used(int provider) {
            return given[provider].length > 0;
        }

        /** The provider that gives codepoint, or -1 when none has it. */
        public int provider(int codepoint) {
            return owners.get(codepoint);
        }
    }

    /** Codepoints to provider indexes: open addressing, keys stored plus one so that zero is an empty slot. */
    private static final class CodepointMap {
        private final int[] keys;
        private final int[] values;
        private final int mask;

        CodepointMap(int expected) {
            int capacity = Integer.highestOneBit(Math.max(4, expected) * 2 - 1) << 1;
            keys = new int[capacity];
            values = new int[capacity];
            mask = capacity - 1;
        }

        /** Maps codepoint to value unless it has one: true if it didn't. */
        boolean putIfAbsent(int codepoint, int value) {
            int key = codepoint + 1;
            for (int slot = slot(key); ; slot = slot + 1 & mask) {
                if (keys[slot] == key) return false;
                if (keys[slot] == 0) {
                    keys[slot] = key;
                    values[slot] = value;
                    return true;
                }
            }
        }

        int get(int codepoint) {
            int key = codepoint + 1;
            for (int slot = slot(key); ; slot = slot + 1 & mask) {
                if (keys[slot] == key) return values[slot];
                if (keys[slot] == 0) return -1;
            }
        }

        private int slot(int key) {
            int hash = key * 0x9E3779B9;
            return (hash ^ hash >>> 16) & mask;
        }
    }
}
