package games.qore.fastload;

/**
 * Who handles a feature, so two pieces of code never accelerate the same thing: the first to claim it owns it, the
 * others step aside. Kept in system properties ({@value #PROPERTY_PREFIX}{@code <feature>} = owner), which every class
 * loader and every copy of this library sees.
 */
public final class Claims {
    /** The loading screen: when it closes, how long it stays, how it looks. */
    public static final String LOADING_SCREEN = "loading-screen";
    /** The title screen's fade-in after the game started. */
    public static final String TITLE_FADE = "title-fade";
    /** Sprites' mip levels ({@link Mipmaps}). */
    public static final String MIPMAPS = "mipmaps";
    /** The selection of a font's glyph providers ({@link FontProviders}). */
    public static final String FONTS = "fonts";
    /** Listing a zip pack's folders ({@link ZipIndex}). */
    public static final String ZIP_INDEX = "zip-index";
    /** The system property of a feature is this prefix and the feature. */
    public static final String PROPERTY_PREFIX = "fastload.claim.";

    private Claims() {
    }

    /** Claims feature for owner unless someone else has it (thread-safe, first caller wins): true if owner owns it now. */
    public static boolean claim(String feature, String owner) {
        String current = owner(feature);
        if (current == null) {
            Object previous = System.getProperties().putIfAbsent(PROPERTY_PREFIX + feature, owner);
            current = previous == null ? owner : previous.toString();
        }
        return current.equals(owner);
    }

    /** The feature's owner, or null while nobody claimed it. */
    public static String owner(String feature) {
        return System.getProperty(PROPERTY_PREFIX + feature);
    }
}
