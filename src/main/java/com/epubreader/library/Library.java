package com.epubreader.library;

import com.epubreader.epub.EpubMetadata;
import com.epubreader.pdf.PdfSupport;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * A folder of EPUB and PDF books. Titles, authors and cover images are cached in a hidden
 * {@code .library} folder inside it, so only new or changed files need to be read on a rescan.
 */
public final class Library {

    private static final String META_DIR = ".library";
    private static final int COVER_WIDTH = 360;
    private static final int MAX_DEPTH = 4;

    private final Path dir;
    private final Path metaDir;
    private final Path coverDir;
    private final Path indexFile;
    private final Properties index = new Properties();

    public Library(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        this.metaDir = this.dir.resolve(META_DIR);
        this.coverDir = metaDir.resolve("covers");
        this.indexFile = metaDir.resolve("index.properties");
        if (Files.isRegularFile(indexFile)) {
            try (Reader in = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
                index.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                // rebuild the cache from scratch
            }
        }
    }

    public Path dir() {
        return dir;
    }

    /**
     * Lists every book in the library folder (and its subfolders), reading metadata for files that
     * are new or changed. {@code progress} receives (done, total) as files are read.
     */
    public synchronized List<BookInfo> scan(BiConsumer<Integer, Integer> progress) throws IOException {
        Files.createDirectories(coverDir);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir, MAX_DEPTH)) {
            files = walk.filter(p -> !p.startsWith(metaDir) && Files.isRegularFile(p) && BookFormat.of(p) != null)
                    .sorted().toList();
        }
        List<BookInfo> books = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            String key = key(file);
            seen.add(key);
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            BookInfo cached = fromIndex(file, key, attrs);
            if (cached != null) {
                books.add(cached);
            } else {
                books.add(readBook(file, key, attrs));
                changed = true;
            }
            progress.accept(i + 1, files.size());
        }
        changed |= index.keySet().removeIf(k -> !seen.contains((String) k));
        if (changed) {
            saveIndex();
        }
        return books;
    }

    /** Copies books into the library folder, renaming on name clashes. Returns the copies. */
    public synchronized List<Path> add(List<Path> sources) throws IOException {
        Files.createDirectories(dir);
        List<Path> added = new ArrayList<>();
        for (Path source : sources) {
            if (BookFormat.of(source) == null) {
                continue;
            }
            Path abs = source.toAbsolutePath().normalize();
            if (abs.startsWith(dir)) {
                added.add(abs); // already in the library
                continue;
            }
            Path target = uniqueTarget(source.getFileName().toString());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            // The "added" date is the file's modified time, so mark it as added now.
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(Instant.now()));
            added.add(target);
        }
        return added;
    }

    /** Moves a book's file to the system trash (or deletes it if there is no trash) and drops its cache. */
    public synchronized void remove(BookInfo book) throws IOException {
        boolean trashed = false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                trashed = Desktop.getDesktop().moveToTrash(book.file().toFile());
            }
        } catch (RuntimeException ignored) {
            // fall back to deleting
        }
        if (!trashed) {
            Files.deleteIfExists(book.file());
        }
        if (book.cover() != null) {
            Files.deleteIfExists(book.cover());
        }
        index.remove(key(book.file()));
        saveIndex();
    }

    private Path uniqueTarget(String fileName) {
        Path target = dir.resolve(fileName);
        int dot = fileName.lastIndexOf('.');
        String base = fileName.substring(0, dot);
        String ext = fileName.substring(dot);
        for (int n = 2; Files.exists(target); n++) {
            target = dir.resolve(base + " (" + n + ")" + ext);
        }
        return target;
    }

    // ---------------------------------------------------------------- metadata cache

    private BookInfo readBook(Path file, String key, BasicFileAttributes attrs) {
        BookFormat format = BookFormat.of(file);
        String title = "";
        String author = "";
        Path cover = null;
        String coverBase = hash(key);
        try {
            deleteCovers(coverBase);
            if (format == BookFormat.EPUB) {
                EpubMetadata meta = EpubMetadata.read(file);
                title = meta.title();
                author = meta.author();
                if (meta.cover() != null) {
                    cover = coverDir.resolve(coverBase + "." + meta.coverType());
                    Files.write(cover, meta.cover());
                }
            } else {
                PdfSupport.Metadata meta = PdfSupport.readMetadata(file, COVER_WIDTH);
                title = meta.title();
                author = meta.author();
                if (meta.cover() != null) {
                    cover = coverDir.resolve(coverBase + ".png");
                    ImageIO.write(meta.cover(), "png", cover.toFile());
                }
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable or unusual file: still list it, by file name, without a cover.
            System.err.println("Could not read " + file.getFileName() + ": " + e.getMessage());
        }
        if (title.isBlank()) {
            title = fileTitle(file);
        }
        index.setProperty(key, String.join("\t", Long.toString(attrs.size()),
                Long.toString(attrs.lastModifiedTime().toMillis()), clean(title), clean(author),
                cover == null ? "" : cover.getFileName().toString()));
        return new BookInfo(file, format, title, author, attrs.size(), attrs.lastModifiedTime().toInstant(), cover);
    }

    private BookInfo fromIndex(Path file, String key, BasicFileAttributes attrs) {
        String value = index.getProperty(key);
        if (value == null) {
            return null;
        }
        String[] f = value.split("\t", -1);
        if (f.length < 5 || !f[0].equals(Long.toString(attrs.size()))
                || !f[1].equals(Long.toString(attrs.lastModifiedTime().toMillis()))) {
            return null;
        }
        Path cover = f[4].isEmpty() ? null : coverDir.resolve(f[4]);
        if (cover != null && !Files.isRegularFile(cover)) {
            return null;
        }
        return new BookInfo(file, BookFormat.of(file), f[2], f[3], attrs.size(),
                attrs.lastModifiedTime().toInstant(), cover);
    }

    private void deleteCovers(String base) throws IOException {
        try (Stream<Path> list = Files.list(coverDir)) {
            for (Path p : list.filter(p -> p.getFileName().toString().startsWith(base + ".")).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private void saveIndex() throws IOException {
        Files.createDirectories(metaDir);
        Path tmp = metaDir.resolve("index.properties.tmp");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            index.store(out, "EPUB Reader library cache - safe to delete");
        }
        Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private String key(Path file) {
        return dir.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    static String fileTitle(Path file) {
        return file.getFileName().toString().replaceFirst("(?i)\\.(epub|pdf)$", "").replace('_', ' ');
    }

    private static String clean(String s) {
        return s.replaceAll("[\\t\\r\\n]+", " ").strip();
    }

    private static String hash(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
