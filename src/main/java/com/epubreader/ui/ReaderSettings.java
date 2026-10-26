package com.epubreader.ui;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * User preferences, the library location, dictionaries and per-book reading positions, persisted in
 * {@code settings.properties} inside {@link #configDir()}. Changes are written by {@link #saveIfDirty()}.
 */
final class ReaderSettings {

    static final int MIN_FONT = 60;
    static final int MAX_FONT = 250;
    static final int MIN_ZOOM = 25;
    static final int MAX_ZOOM = 400;
    private static final int MAX_RECENT = 10;

    /**
     * Where the reader was in a book: chapter (EPUB) or page (PDF) index, the scroll fraction (0..1)
     * within it, and overall progress through the book (0..1).
     */
    record Position(int index, double fraction, double progress) {}

    private final Path configDir;
    private final Path file;
    private final Properties props = new Properties();
    private boolean dirty;

    ReaderSettings(Path configDir) {
        this.configDir = configDir;
        this.file = configDir.resolve("settings.properties");
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("Ignoring unreadable settings file " + file + ": " + e.getMessage());
            }
        }
    }

    /** Per-user application folder: %APPDATA% on Windows, Application Support on macOS, ~/.config elsewhere. */
    static Path defaultConfigDir() {
        String override = System.getProperty("epubreader.configDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override); // e.g. a portable install keeping settings next to the app
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("win") && System.getenv("APPDATA") != null) {
            return Path.of(System.getenv("APPDATA"), "EPUB Reader");
        } else if (os.contains("mac")) {
            return home.resolve("Library/Application Support/EPUB Reader");
        }
        return home.resolve(".config/epub-reader");
    }

    Path configDir() {
        return configDir;
    }

    Path file() {
        return file;
    }

    synchronized void saveIfDirty() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(configDir);
            Path tmp = configDir.resolve("settings.properties.tmp");
            try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(out, "EPUB Reader settings");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            System.err.println("Could not save settings to " + file + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- library

    Path libraryDir() {
        String value = get("library.dir", null);
        if (value != null) {
            return Path.of(value);
        }
        Path home = Path.of(System.getProperty("user.home"));
        Path documents = home.resolve("Documents");
        return (Files.isDirectory(documents) ? documents : home).resolve("EPUB Reader Library");
    }

    void setLibraryDir(Path dir) {
        put("library.dir", dir.toAbsolutePath().toString());
    }

    LibraryLayout libraryLayout() {
        return getEnum("library.layout", LibraryLayout.LARGE);
    }

    void setLibraryLayout(LibraryLayout layout) {
        put("library.layout", layout.name());
    }

    // ---------------------------------------------------------------- dictionaries

    List<Path> dictionaries() {
        List<Path> result = new ArrayList<>();
        for (String line : get("dictionaries", "").split("\n")) {
            if (!line.isBlank()) {
                result.add(Path.of(line));
            }
        }
        return result;
    }

    void setDictionaries(List<Path> files) {
        put("dictionaries", String.join("\n", files.stream().map(Path::toString).toList()));
    }

    boolean lookupOnSelect() {
        return Boolean.parseBoolean(get("dictionary.lookupOnSelect", "true"));
    }

    void setLookupOnSelect(boolean value) {
        put("dictionary.lookupOnSelect", Boolean.toString(value));
    }

    // ---------------------------------------------------------------- reading appearance

    int fontPercent() {
        return Math.clamp(getInt("fontPercent", 100), MIN_FONT, MAX_FONT);
    }

    void setFontPercent(int value) {
        put("fontPercent", Integer.toString(Math.clamp(value, MIN_FONT, MAX_FONT)));
    }

    int pdfZoom() {
        return Math.clamp(getInt("pdf.zoom", 100), MIN_ZOOM, MAX_ZOOM);
    }

    void setPdfZoom(int value) {
        put("pdf.zoom", Integer.toString(Math.clamp(value, MIN_ZOOM, MAX_ZOOM)));
    }

    ReaderTheme theme() {
        return getEnum("theme", ReaderTheme.LIGHT);
    }

    void setTheme(ReaderTheme theme) {
        put("theme", theme.name());
    }

    ReaderFont font() {
        return getEnum("font", ReaderFont.ORIGINAL);
    }

    void setFont(ReaderFont font) {
        put("font", font.name());
    }

    boolean tocVisible() {
        return Boolean.parseBoolean(get("tocVisible", "true"));
    }

    void setTocVisible(boolean visible) {
        put("tocVisible", Boolean.toString(visible));
    }

    double windowWidth() {
        return getDouble("window.width", 1180);
    }

    double windowHeight() {
        return getDouble("window.height", 800);
    }

    void setWindowSize(double width, double height) {
        put("window.width", Double.toString(width));
        put("window.height", Double.toString(height));
    }

    // ---------------------------------------------------------------- recent books and positions

    List<Path> recentFiles() {
        List<Path> result = new ArrayList<>();
        for (String line : get("recent", "").split("\n")) {
            if (!line.isBlank() && Files.isRegularFile(Path.of(line))) {
                result.add(Path.of(line));
            }
        }
        return result;
    }

    void addRecent(Path file) {
        List<Path> recent = recentFiles();
        Path abs = file.toAbsolutePath();
        recent.remove(abs);
        recent.addFirst(abs);
        put("recent", String.join("\n", recent.stream().limit(MAX_RECENT).map(Path::toString).toList()));
    }

    void clearRecent() {
        remove("recent");
    }

    Position position(Path book) {
        String value = get(positionKey(book), null);
        if (value != null) {
            String[] parts = value.split(";");
            try {
                double progress = parts.length > 2 ? Double.parseDouble(parts[2]) : 0;
                return new Position(Integer.parseInt(parts[0]), Double.parseDouble(parts[1]), progress);
            } catch (RuntimeException ignored) {
                // corrupt value; start from the beginning
            }
        }
        return new Position(0, 0, 0);
    }

    void setPosition(Path book, Position p) {
        put(positionKey(book), p.index() + ";" + p.fraction() + ";" + p.progress());
    }

    void forgetPosition(Path book) {
        remove(positionKey(book));
    }

    private static String positionKey(Path book) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(book.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
            return "pos." + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- raw access

    private synchronized String get(String key, String def) {
        return props.getProperty(key, def);
    }

    private synchronized void put(String key, String value) {
        if (!value.equals(props.getProperty(key))) {
            props.setProperty(key, value);
            dirty = true;
        }
    }

    private synchronized void remove(String key) {
        if (props.remove(key) != null) {
            dirty = true;
        }
    }

    private int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, Integer.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private double getDouble(String key, double def) {
        try {
            return Double.parseDouble(get(key, Double.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private <E extends Enum<E>> E getEnum(String key, E def) {
        try {
            return Enum.valueOf(def.getDeclaringClass(), get(key, def.name()));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
