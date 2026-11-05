package games.qore.fastload;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A zip's files sorted by name, so listing a folder is a lookup rather than a pass over every entry of the zip (vanilla
 * does that for each folder a pack lists: dozens per reload, over tens of thousands of entries). Results come in the
 * zip's order, as vanilla's.
 */
public final class ZipIndex {
    private static final Map<ZipFile, ZipIndex> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /** The files in the zip's order. */
    private final ZipEntry[] files;
    /** Their names, sorted. */
    private final String[] names;
    /** The position in the zip of each sorted name. */
    private final int[] order;

    private ZipIndex(List<ZipEntry> files) {
        this.files = files.toArray(new ZipEntry[0]);
        Integer[] sorted = new Integer[this.files.length];
        for (int i = 0; i < sorted.length; i++) sorted[i] = i;
        Arrays.sort(sorted, (a, b) -> this.files[a].getName().compareTo(this.files[b].getName()));
        names = new String[sorted.length];
        order = new int[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            names[i] = this.files[sorted[i]].getName();
            order[i] = sorted[i];
        }
    }

    /** Indexes the zip's files (its entries that aren't directories). */
    public static ZipIndex of(ZipFile zip) {
        List<ZipEntry> files = new ArrayList<>();
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            ZipEntry entry = all.nextElement();
            // A JarFile's entries refer to it: plain copies keep no zip alive (reads go by name).
            if (!entry.isDirectory()) files.add(entry.getClass() == ZipEntry.class ? entry : new ZipEntry(entry));
        }
        return new ZipIndex(files);
    }

    /** Opens the zip at path, indexes its files and closes it: entries work with any ZipFile opened on that file. */
    public static ZipIndex of(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            return of(zip);
        }
    }

    /** The index of zip, made on the first call and kept as long as that ZipFile object lives (thread-safe). */
    public static ZipIndex cached(ZipFile zip) {
        ZipIndex index = CACHE.get(zip);
        if (index != null) return index;
        index = of(zip);
        ZipIndex previous = CACHE.putIfAbsent(zip, index);
        return previous != null ? previous : index;
    }

    /** The number of files. */
    public int size() {
        return files.length;
    }

    /** The files whose name starts with prefix, in the zip's order, to action. */
    public void forEachStartingWith(String prefix, Consumer<ZipEntry> action) {
        int from = Arrays.binarySearch(names, prefix);
        if (from < 0) from = -from - 1;
        int to = from;
        while (to < names.length && names[to].startsWith(prefix)) to++;
        if (from == to) return;
        int[] positions = Arrays.copyOfRange(order, from, to);
        Arrays.sort(positions);
        for (int position : positions) action.accept(files[position]);
    }

    /** The files whose name starts with prefix, in the zip's order. */
    public List<ZipEntry> startingWith(String prefix) {
        List<ZipEntry> found = new ArrayList<>();
        forEachStartingWith(prefix, found::add);
        return found;
    }

    /** The files in folder and its subfolders ("assets/minecraft/textures", with or without a trailing slash) by name, in the zip's order. */
    public Map<String, ZipEntry> folder(String folder) {
        Map<String, ZipEntry> found = new LinkedHashMap<>();
        forEachStartingWith(folder.isEmpty() || folder.endsWith("/") ? folder : folder + "/", entry -> found.put(entry.getName(), entry));
        return found;
    }
}
