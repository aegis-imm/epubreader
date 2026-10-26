package com.epubreader.dict;

import com.epubreader.dict.Dictionary.Definition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The user's dictionaries. Imported files are copied into {@code <configDir>/dictionaries} so they
 * keep working if the originals are moved.
 */
public final class DictionaryManager {

    /** A definition plus the dictionary it came from. */
    public record Result(String dictionary, Definition definition) {}

    private final Path storeDir;
    private final List<Dictionary> dictionaries = new CopyOnWriteArrayList<>();

    public DictionaryManager(Path storeDir) {
        this.storeDir = storeDir;
    }

    public Path storeDir() {
        return storeDir;
    }

    public List<Dictionary> dictionaries() {
        return List.copyOf(dictionaries);
    }

    /** Loads previously imported dictionaries, skipping (and reporting) any that fail. */
    public List<String> loadAll(List<Path> files) {
        List<String> errors = new ArrayList<>();
        for (Path file : files) {
            try {
                dictionaries.add(load(file));
            } catch (IOException | RuntimeException e) {
                errors.add(file.getFileName() + ": " + e.getMessage());
            }
        }
        return errors;
    }

    /**
     * Imports a StarDict {@code .ifo} (with its sibling files), a word list ({@code .tsv/.txt/.csv}),
     * or a {@code .zip} containing either. Returns the newly loaded dictionaries.
     */
    public List<Dictionary> importFile(Path source) throws IOException {
        String base = source.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Path target = Files.createDirectories(storeDir).resolve(uniqueDirName(base));
        Files.createDirectories(target);
        try {
            String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip")) {
                unzip(source, target);
            } else if (lower.endsWith(".ifo")) {
                String stem = source.getFileName().toString().replaceFirst("(?i)\\.ifo$", "");
                for (String ext : List.of(".ifo", ".idx", ".idx.gz", ".dict", ".dict.dz", ".syn")) {
                    Path sibling = source.resolveSibling(stem + ext);
                    if (Files.isRegularFile(sibling)) {
                        Files.copy(sibling, target.resolve(sibling.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } else {
                Files.copy(source, target.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }

            List<Dictionary> loaded = new ArrayList<>();
            for (Path file : dictionaryFiles(target)) {
                loaded.add(load(file));
            }
            if (loaded.isEmpty()) {
                throw new IOException("No dictionary found. Choose a StarDict .ifo file, a .tsv/.txt/.csv word list, "
                        + "or a .zip containing one.");
            }
            dictionaries.addAll(loaded);
            return loaded;
        } catch (IOException | RuntimeException e) {
            deleteTree(target);
            throw e;
        }
    }

    /** Unloads a dictionary and deletes its imported copy. */
    public void remove(Dictionary dictionary) throws IOException {
        dictionaries.remove(dictionary);
        Path file = dictionary.file().toAbsolutePath();
        Path folder = file.getParent();
        if (folder != null && folder.startsWith(storeDir.toAbsolutePath()) && !folder.equals(storeDir.toAbsolutePath())) {
            deleteTree(folder);
        }
    }

    /**
     * Looks a selection up in every dictionary, trying the text as selected and then simple base
     * forms (plural, past tense, -ing, ...), so "running" also finds "run".
     */
    public List<Result> lookup(String selection) {
        List<Result> results = new ArrayList<>();
        for (String candidate : candidates(selection)) {
            for (Dictionary d : dictionaries) {
                for (Definition def : d.lookup(candidate)) {
                    results.add(new Result(d.name(), def));
                }
            }
            if (!results.isEmpty()) {
                break;
            }
        }
        return results;
    }

    /** Normalises a selection: trims spaces and surrounding punctuation and joins wrapped lines. */
    public static String normalize(String selection) {
        String s = selection == null ? "" : selection.replaceAll("[\\s\\p{Z}]+", " ").strip();
        return s.replaceAll("^[\\p{P}\\p{S}]+|[\\p{P}\\p{S}]+$", "");
    }

    static List<String> candidates(String selection) {
        String word = normalize(selection);
        Set<String> forms = new LinkedHashSet<>();
        if (word.isEmpty()) {
            return List.of();
        }
        forms.add(word);
        String w = word.toLowerCase(Locale.ROOT).replaceAll("['’]s$", "");
        forms.add(w);
        if (w.matches("[a-z]+")) {
            if (w.endsWith("ies")) forms.add(w.substring(0, w.length() - 3) + "y");
            if (w.endsWith("ied")) forms.add(w.substring(0, w.length() - 3) + "y");
            if (w.endsWith("es")) forms.add(w.substring(0, w.length() - 2));
            if (w.endsWith("s") && !w.endsWith("ss")) forms.add(w.substring(0, w.length() - 1));
            for (String suffix : List.of("ing", "ed", "er", "est", "ly")) {
                if (w.endsWith(suffix) && w.length() > suffix.length() + 2) {
                    String stem = w.substring(0, w.length() - suffix.length());
                    forms.add(stem);
                    forms.add(stem + "e");
                    int n = stem.length();
                    if (n > 2 && stem.charAt(n - 1) == stem.charAt(n - 2)) {
                        forms.add(stem.substring(0, n - 1)); // running -> run
                    }
                    if (stem.endsWith("i")) {
                        forms.add(stem.substring(0, n - 1) + "y"); // happily -> happy
                    }
                }
            }
        }
        return List.copyOf(forms);
    }

    // ---------------------------------------------------------------- files

    public static Dictionary load(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ifo") ? StarDict.load(file) : WordListDictionary.load(file);
    }

    private static List<Path> dictionaryFiles(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = walk.filter(Files::isRegularFile).sorted().toList();
            List<Path> ifos = all.stream().filter(p -> lower(p).endsWith(".ifo")).toList();
            if (!ifos.isEmpty()) {
                return ifos;
            }
            return all.stream().filter(p -> lower(p).endsWith(".tsv") || lower(p).endsWith(".txt")
                    || lower(p).endsWith(".csv")).toList();
        }
    }

    private static String lower(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private String uniqueDirName(String base) {
        String safe = base.replaceAll("[^\\p{L}\\p{N} ._-]", "_").strip();
        if (safe.isEmpty()) {
            safe = "dictionary";
        }
        String name = safe;
        for (int n = 2; Files.exists(storeDir.resolve(name)); n++) {
            name = safe + " (" + n + ")";
        }
        return name;
    }

    private static void unzip(Path zipFile, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("Unsafe entry in zip: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
