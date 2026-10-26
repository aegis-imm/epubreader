package com.epubreader.epub;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A parsed EPUB whose contents have been extracted to {@link #rootDir()}.
 * Call {@link #close()} to delete the extracted files.
 */
public record EpubBook(
        Path source,
        Path rootDir,
        String title,
        String author,
        List<Path> spine,
        List<TocEntry> toc) implements AutoCloseable {

    /** A table-of-contents node. {@code target} may carry a {@code #fragment}. */
    public record TocEntry(String label, URI target, List<TocEntry> children) {}

    /** Returns the spine index of the document a URI points to, or -1. */
    public int spineIndexOf(URI uri) {
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
            return -1;
        }
        Path path;
        try {
            path = Path.of(new URI("file", null, uri.getPath(), null)).normalize();
        } catch (Exception e) {
            return -1;
        }
        for (int i = 0; i < spine.size(); i++) {
            if (spine.get(i).equals(path)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void close() {
        try (Stream<Path> walk = Files.walk(rootDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort; temp dir will be cleaned up by the OS eventually
                }
            });
        } catch (IOException ignored) {
            // nothing to clean
        }
    }
}
