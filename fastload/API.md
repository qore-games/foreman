# fastload API

`games.qore:fastload:<version>` (the version is Foreman's), package `games.qore.fastload`. Plain Java 21, no
dependencies, MIT. Its API has JDK types, arrays and primitives only, so any class loader can call it, and several
copies of it (a mod's, a Java agent's) share one state: the claims live in system properties.

Built to `fastload/build/libs/fastload-<version>.jar`; `./gradlew :fastload:publishToMavenLocal` publishes it.

## Claims

Who handles a feature, so two pieces of code never accelerate the same thing: the first to claim it owns it, the others
step aside (leave vanilla's code, or the owner's, alone). Stored in system properties `fastload.claim.<feature>` =
`<owner>`.

```java
public final class Claims {
    public static final String LOADING_SCREEN = "loading-screen";
    public static final String TITLE_FADE = "title-fade";
    public static final String MIPMAPS = "mipmaps";
    public static final String FONTS = "fonts";
    public static final String ZIP_INDEX = "zip-index";
    /** The system property of a feature is this prefix and the feature. */
    public static final String PROPERTY_PREFIX = "fastload.claim.";

    /** Claims feature for owner unless someone else has it (thread-safe, first caller wins): true if owner owns it now. */
    public static boolean claim(String feature, String owner);
    /** The feature's owner, or null while nobody claimed it. */
    public static String owner(String feature);
}
```

A launcher's agent claims at its start, before any mod loads. Foreman claims a feature the first time it would use it
(with its fast reloads on), as `foreman`, and its mixins step aside at runtime while another owner has it. It never
claims `title-fade` (a name for others to share), and its keep playing option draws reloads under its own progress bar
whoever owns `loading-screen` (the game's start keeps the owner's loading screen). Foreman's mixins stay in the methods
they inject into: a patch that looks for mixin traces should go by the claims instead.

Foreman's mod bundles it under its own package. Another mod or agent should bundle it relocated (shadow's `relocate`):
copies at other versions then never shadow each other's classes, and still share the claims.

## Mipmaps

`MipmapGenerator.generateMipLevels` on pixel arrays, the same pixels to the bit, faster; and an atlas's sprites on
every background thread.

```java
public final class Mipmaps {
    /** Vanilla's MipmapStrategy values. */
    public static final int AUTO = 0, MEAN = 1, CUTOUT = 2, STRICT_CUTOUT = 3, DARK_CUTOUT = 4;

    /** The constant of a MipmapStrategy by its name ("AUTO", "MEAN", "CUTOUT", "STRICT_CUTOUT", "DARK_CUTOUT"). */
    public static int strategy(String name);

    /** Whether generateMipLevels would leave a sprite's levels as they are, adding none: no need to read its pixels. */
    public static boolean unchanged(int levels, int newMipLevel, int strategy, boolean hasTransparent, String path);

    /**
     * MipmapGenerator.generateMipLevels(name, currentMips, newMipLevel, strategy, alphaCutoffBias, transparency):
     * mips are the sprite's levels (level 0 is width × height, each next one half of the one before), path is the
     * sprite name's path ("block/stone"), hasTransparent is transparency.hasTransparent().
     * Pixels have their alpha in the top byte: ARGB or ABGR (NativeImage's), the three colors are treated alike.
     * Returns the levels: mips.length of them when there were newMipLevel + 1 or more, else newMipLevel + 1.
     * Never changes the arrays it is given: a level that changed is a new array, one that didn't is the same array
     * (write back the levels below mips.length whose array differs; the others are new levels).
     */
    public static int[][] generateMipLevels(int[][] mips, int width, int height, int newMipLevel, int strategy,
                                            boolean hasTransparent, String path, float alphaCutoffBias);

    /**
     * Runs action on every item on the current fork-join pool (the common pool outside one), the biggest first (they
     * take the longest), and returns when all are done. For an atlas: SpriteLoader's sprites.forEach(increaseMipLevel)
     * with size = width × height of the sprite's contents.
     */
    public static <T> void inParallel(Collection<? extends T> items, ToLongFunction<? super T> size, Consumer<? super T> action);
}
```

## FontProviders

`FontSet.selectProviders` in linear time, as plain data: providers whose glyphs are exactly their supported codepoints
(vanilla's bitmap, space, TrueType and unihex providers; `getGlyph` is non-null exactly for `getSupportedGlyphs`) by
their codepoints, up to vanilla's all-missing fallback. Providers of other kinds (a mod's wrapper around one of
vanilla's, like ModernFix's lazy unifont) are asked about each codepoint still free at their turn: the questions vanilla
asks them, so the result is vanilla's whatever they are, and as fast while they are few.

```java
public final class FontProviders {
    /**
     * Which provider gives each codepoint: supported[i] is provider i's codepoints (the font set's providers in order,
     * those its options select), null for a provider with a glyph for every codepoint (vanilla's all-missing fallback),
     * which takes every codepoint not taken before it. A codepoint goes to the first provider that has it.
     */
    public static Selection select(int[][] supported);

    /**
     * The same with providers whose glyphs may not be exactly their supported codepoints: has[i], when not null, is
     * provider i's getGlyph(codepoint) != null, asked once about each codepoint of the font (every provider's supported
     * ones) that no provider before it took, as vanilla asks it; supported[i] is then its supported codepoints (null
     * for none), part of the font's. Providers whose has is null are as in select(supported).
     */
    public static Selection select(int[][] supported, IntPredicate[] has);

    public static final class Selection {
        /** The number of providers given. */
        public int providers();
        /**
         * The codepoints provider gives, in the order of its array (a fallback's, or those of a provider asked with has:
         * in the order of the providers' arrays).
         */
        public int[] codepoints(int provider);
        /** Whether provider gives any codepoint: vanilla's font set keeps only those. */
        public boolean used(int provider);
        /** The provider that gives codepoint, or -1 when none has it. */
        public int provider(int codepoint);
    }
}
```

The font set's `glyphsByWidth`: for each provider (not the fallback, whose glyphs are the missing glyph), each of its
codepoints in order, `getGlyph(codepoint)` and its advance (`Mth.ceil(info().getAdvance(false))`), leaving out the
glyphs whose info is `SpecialGlyphs.MISSING`.

## ZipIndex

A zip's files sorted by name, so listing a folder is a lookup rather than a pass over every entry of the zip (vanilla's
`FilePackResources.listResources` goes through all of them for each folder it lists: dozens per reload, over tens of
thousands of entries). Results come in the zip's order, as vanilla's.

```java
public final class ZipIndex {
    /** Indexes the zip's files (its entries that aren't directories). */
    public static ZipIndex of(ZipFile zip);
    /** Opens the zip at path, indexes its files and closes it: entries work with any ZipFile opened on that file. */
    public static ZipIndex of(Path path) throws IOException;
    /** The index of zip, made on the first call and kept as long as that ZipFile object lives (thread-safe). */
    public static ZipIndex cached(ZipFile zip);

    /** The number of files. */
    public int size();
    /** The files whose name starts with prefix, in the zip's order, to action. */
    public void forEachStartingWith(String prefix, Consumer<ZipEntry> action);
    /** The files whose name starts with prefix, in the zip's order. */
    public List<ZipEntry> startingWith(String prefix);
    /** The files in folder and its subfolders ("assets/minecraft/textures", with or without a trailing slash) by name, in the zip's order. */
    public Map<String, ZipEntry> folder(String folder);
}
```

`FilePackResources.listResources(type, namespace, directory, output)`: with `root = addPrefix(type.getDirectory() + "/"
+ namespace + "/")`, the files starting with `root + directory + "/"`; each is `Identifier.tryBuild(namespace,
name.substring(root.length()))` (null: vanilla's "Invalid path in datapack" warning) → `IoSupplier.create(zipFile, entry)`.
