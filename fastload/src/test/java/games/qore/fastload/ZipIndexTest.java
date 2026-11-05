package games.qore.fastload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** ZipIndex against vanilla's way: every entry of the zip, in its order, for each folder listed. */
class ZipIndexTest {
    @TempDir Path dir;

    @Test
    void listsFoldersAsVanilla() throws IOException {
        Path file = zip(new Random(1), 3000);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipIndex index = ZipIndex.cached(zip);
            assertSame(index, ZipIndex.cached(zip));
            ZipIndex fromPath = ZipIndex.of(file);
            assertEquals(index.size(), fromPath.size());
            for (String prefix : List.of("", "assets/", "assets/a/", "assets/a/textures/", "assets/b/models/block/", "assets/c/x", "missing/", "assets/a/textures/item/")) {
                List<String> vanilla = new ArrayList<>();
                zip.stream().filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix)).forEach(entry -> vanilla.add(entry.getName()));
                assertEquals(vanilla, index.startingWith(prefix).stream().map(ZipEntry::getName).toList(), prefix);
                assertEquals(vanilla, fromPath.startingWith(prefix).stream().map(ZipEntry::getName).toList(), prefix);
                if (prefix.endsWith("/")) assertEquals(vanilla, List.copyOf(index.folder(prefix.substring(0, prefix.length() - 1)).keySet()), prefix);
            }
            for (ZipEntry entry : fromPath.startingWith("assets/a/")) {
                try (var in = zip.getInputStream(entry)) {
                    assertEquals(entry.getName(), new String(in.readAllBytes()));
                }
            }
        }
    }

    /** A zip of files under assets/<a|b|c>/<kind>/…, in a shuffled order, with a few folder entries. */
    private Path zip(Random random, int files) throws IOException {
        List<String> names = new ArrayList<>();
        String[] kinds = {"textures/block", "textures/item", "models/block", "models/item", "font", "x"};
        for (int i = 0; i < files; i++) {
            names.add("assets/" + "abc".charAt(random.nextInt(3)) + "/" + kinds[random.nextInt(kinds.length)] + "/" + Integer.toString(random.nextInt(1_000_000), 36) + i + ".png");
        }
        names.add("assets/a/textures/");
        names.add("assets/b/models/block/");
        names.add("pack.mcmeta");
        Collections.shuffle(names, random);
        Path file = dir.resolve("pack.zip");
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) zip.write(name.getBytes());
                zip.closeEntry();
            }
        }
        return file;
    }
}
