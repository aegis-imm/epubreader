package com.epubreader.dict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A plain-text word list, one entry per line: {@code word<TAB>meaning} (.tsv/.txt) or
 * {@code word,meaning} (.csv). A literal {@code \n} in the meaning starts a new line.
 * Lines starting with {@code #} are comments.
 */
public final class WordListDictionary implements Dictionary {

    private final Path file;
    private final String name;
    private final Map<String, List<Definition>> entries = new HashMap<>();

    private WordListDictionary(Path file) {
        this.file = file;
        this.name = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
    }

    public static WordListDictionary load(Path file) throws IOException {
        WordListDictionary d = new WordListDictionary(file);
        boolean csv = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        for (String line : text.split("\r?\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int sep = line.indexOf(csv ? ',' : '\t');
            if (sep <= 0) {
                continue;
            }
            String word = unquote(line.substring(0, sep).strip());
            String meaning = unquote(line.substring(sep + 1).strip()).replace("\\n", "\n");
            if (!word.isEmpty() && !meaning.isEmpty()) {
                d.entries.computeIfAbsent(word.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(new Definition(word, plainToHtml(meaning)));
            }
        }
        if (d.entries.isEmpty()) {
            throw new IOException(file.getFileName() + " has no entries. Expected lines like: word<TAB>meaning");
        }
        return d;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")
                ? s.substring(1, s.length() - 1).replace("\"\"", "\"")
                : s;
    }

    static String plainToHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Path file() {
        return file;
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public List<Definition> lookup(String word) {
        return entries.getOrDefault(word.toLowerCase(Locale.ROOT), List.of());
    }
}
