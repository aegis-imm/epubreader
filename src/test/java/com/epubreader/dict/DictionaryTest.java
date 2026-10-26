package com.epubreader.dict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DictionaryTest {

    @TempDir
    Path tmp;

    @Test
    void readsStarDictWithCompressedData() throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src"));
        writeStarDict(src, "test", List.of("apple", "run", "แมว"),
                List.of("a round fruit", "to move <fast>", "cat"), true);

        DictionaryManager manager = new DictionaryManager(tmp.resolve("store"));
        List<Dictionary> loaded = manager.importFile(src.resolve("test.ifo"));
        assertEquals(1, loaded.size());
        assertEquals("Test Dict", loaded.getFirst().name());
        assertEquals(3, loaded.getFirst().size());
        assertTrue(Files.isRegularFile(tmp.resolve("store/test/test.dict.dz")), "copied into the store");

        assertEquals("a round fruit", manager.lookup("Apple.").getFirst().definition().html());
        assertEquals("to move &lt;fast&gt;", manager.lookup("running").getFirst().definition().html());
        assertEquals("cat", manager.lookup("แมว").getFirst().definition().html());
        assertTrue(manager.lookup("banana").isEmpty());

        manager.remove(loaded.getFirst());
        assertFalse(Files.exists(tmp.resolve("store/test")));
        assertTrue(manager.lookup("apple").isEmpty());
    }

    @Test
    void readsWordLists() throws IOException {
        Path tsv = tmp.resolve("words.tsv");
        Files.writeString(tsv, "﻿# comment\nhello\tสวัสดี\\nhi\nbook\tหนังสือ\n", StandardCharsets.UTF_8);
        Path csv = tmp.resolve("more.csv");
        Files.writeString(csv, "\"cat\",\"a small animal, with fur\"\n", StandardCharsets.UTF_8);

        DictionaryManager manager = new DictionaryManager(tmp.resolve("store"));
        manager.importFile(tsv);
        manager.importFile(csv);
        assertEquals("สวัสดี<br>hi", manager.lookup("Hello").getFirst().definition().html());
        assertEquals("หนังสือ", manager.lookup("books").getFirst().definition().html());
        assertEquals("a small animal, with fur", manager.lookup("cats").getFirst().definition().html());
    }

    @Test
    void generatesBaseForms() {
        assertTrue(DictionaryManager.candidates("Studies").contains("study"));
        assertTrue(DictionaryManager.candidates("stopped").contains("stop"));
        assertTrue(DictionaryManager.candidates("making").contains("make"));
        assertTrue(DictionaryManager.candidates("“word,”").contains("word"));
        assertEquals("two words", DictionaryManager.normalize("  two\n words. "));
    }

    static void writeStarDict(Path dir, String base, List<String> words, List<String> meanings, boolean compress)
            throws IOException {
        ByteArrayOutputStream dict = new ByteArrayOutputStream();
        ByteArrayOutputStream idx = new ByteArrayOutputStream();
        for (int i = 0; i < words.size(); i++) {
            byte[] m = meanings.get(i).getBytes(StandardCharsets.UTF_8);
            idx.write(words.get(i).getBytes(StandardCharsets.UTF_8));
            idx.write(0);
            idx.write(ByteBuffer.allocate(8).putInt(dict.size()).putInt(m.length).array());
            dict.write(m);
        }
        Files.write(dir.resolve(base + ".idx"), idx.toByteArray());
        if (compress) {
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(dir.resolve(base + ".dict.dz")))) {
                out.write(dict.toByteArray());
            }
        } else {
            Files.write(dir.resolve(base + ".dict"), dict.toByteArray());
        }
        Files.writeString(dir.resolve(base + ".ifo"), """
                StarDict's dict ifo file
                version=2.4.2
                wordcount=%d
                idxfilesize=%d
                bookname=Test Dict
                sametypesequence=m
                """.formatted(words.size(), idx.size()));
    }
}
