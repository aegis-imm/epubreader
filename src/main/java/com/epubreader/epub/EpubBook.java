package com.epubreader.epub;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A parsed EPUB whose contents have been extracted to {@link #rootDir()}.
 * Call {@link #close()} to delete the extracted files.
 *
 * @param spine      the documents to show, in reading order
 * @param htmlCopies chapters that were not valid XHTML, mapped to the HTML copies shown instead
 *                   (the spine lists the copies; links in the book still name the originals)
 */
public record EpubBook(
        Path source,
        Path rootDir,
        String title,
        String author,
        List<Path> spine,
        List<TocEntry> toc,
        Map<Path, Path> htmlCopies) implements AutoCloseable {

    /** A table-of-contents node. {@code target} may carry a {@code #fragment}. */
    public record TocEntry(String label, URI target, List<TocEntry> children) {}

    /** Returns the spine index of the document a URI points to, or -1. */
    public int spineIndexOf(URI uri) {
        Path path = filePath(uri);
        if (path == null) {
            return -1;
        }
        path = htmlCopies.getOrDefault(path, path);
        for (int i = 0; i < spine.size(); i++) {
            if (spine.get(i).equals(path)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the URI to load for a link: the same URI, or, for a chapter that is shown from an HTML
     * copy, the copy with the same {@code #fragment}.
     */
    public URI renderTarget(URI uri) {
        Path path = filePath(uri);
        Path copy = path == null ? null : htmlCopies.get(path);
        if (copy == null) {
            return uri;
        }
        String target = copy.toUri().toString();
        return URI.create(uri.getRawFragment() == null ? target : target + "#" + uri.getRawFragment());
    }

    private static Path filePath(URI uri) {
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        try {
            return Path.of(new URI("file", null, uri.getPath(), null)).normalize();
        } catch (Exception e) {
            return null;
        }
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
