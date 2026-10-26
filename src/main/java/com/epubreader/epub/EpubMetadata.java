package com.epubreader.epub;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Title, author and cover image of an EPUB, read straight from the zip without extracting it.
 *
 * @param cover     cover image bytes, or null if the book has no usable cover
 * @param coverType file extension of the cover image ("jpg", "png", ...), or null
 */
public record EpubMetadata(String title, String author, byte[] cover, String coverType) {

    private static final long MAX_COVER_BYTES = 20L * 1024 * 1024;

    public static EpubMetadata read(Path epubFile) throws IOException {
        try (ZipFile zip = new ZipFile(epubFile.toFile())) {
            String opfName = findOpf(zip);
            Document opf = EpubParser.parseXml(bytes(zip, opfName), opfName);
            String opfDir = opfName.contains("/") ? opfName.substring(0, opfName.lastIndexOf('/') + 1) : "";

            String title = EpubParser.text(EpubParser.first(EpubParser.descendants(opf, "title")));
            List<String> creators = EpubParser.descendants(opf, "creator").stream().map(EpubParser::text)
                    .filter(s -> !s.isBlank()).toList();

            byte[] cover = null;
            String coverType = null;
            String coverHref = coverHref(opf);
            if (coverHref != null) {
                String entryName = resolve(opfDir, coverHref);
                ZipEntry entry = entryName == null ? null : zip.getEntry(entryName);
                if (entry != null && entry.getSize() <= MAX_COVER_BYTES) {
                    cover = bytes(zip, entryName);
                    coverType = extension(entryName);
                }
            }
            return new EpubMetadata(title, String.join(", ", creators), cover, coverType);
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot read " + epubFile.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static String findOpf(ZipFile zip) throws IOException {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container != null) {
            Document doc = EpubParser.parseXml(bytes(zip, container.getName()), "container.xml");
            for (Element rootfile : EpubParser.descendants(doc, "rootfile")) {
                String path = resolve("", rootfile.getAttribute("full-path"));
                if (path != null && zip.getEntry(path) != null) {
                    return path;
                }
            }
        }
        return zip.stream().map(ZipEntry::getName).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".opf"))
                .findFirst().orElseThrow(() -> new IOException("Not an EPUB: no package document found"));
    }

    /** Finds the cover image href using EPUB 3 properties, the EPUB 2 meta tag, then naming conventions. */
    private static String coverHref(Document opf) {
        List<Element> items = EpubParser.descendants(opf, "item");
        for (Element item : items) {
            if ((" " + item.getAttribute("properties") + " ").contains(" cover-image ")) {
                return item.getAttribute("href");
            }
        }
        for (Element meta : EpubParser.descendants(opf, "meta")) {
            if ("cover".equals(meta.getAttribute("name"))) {
                String id = meta.getAttribute("content");
                for (Element item : items) {
                    if (id.equals(item.getAttribute("id")) && isImage(item)) {
                        return item.getAttribute("href");
                    }
                }
            }
        }
        for (Element item : items) {
            String idAndHref = (item.getAttribute("id") + " " + item.getAttribute("href")).toLowerCase(Locale.ROOT);
            if (isImage(item) && idAndHref.contains("cover")) {
                return item.getAttribute("href");
            }
        }
        return null;
    }

    private static boolean isImage(Element item) {
        String type = item.getAttribute("media-type");
        // JavaFX can't draw SVG or WebP, so skip those formats.
        return type.startsWith("image/") && !type.contains("svg") && !type.contains("webp");
    }

    /** Resolves an href against a zip directory ("OEBPS/"), returning a normalised entry name or null. */
    static String resolve(String dir, String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String path = href.split("#", 2)[0];
        try {
            path = new URI(path).getPath();
        } catch (URISyntaxException e) {
            // not percent-encoded; use as-is
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String part : (dir + path).split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "img" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.equals("jpeg") ? "jpg" : ext;
    }

    private static byte[] bytes(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException("Missing " + name);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }
}
