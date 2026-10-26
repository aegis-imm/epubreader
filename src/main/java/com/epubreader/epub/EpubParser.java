package com.epubreader.epub;

import com.epubreader.epub.EpubBook.TocEntry;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts an EPUB (2 or 3) to a temp directory and reads its metadata, spine and TOC. */
public final class EpubParser {

    private static final String OPS_NS = "http://www.idpf.org/2007/ops";

    private record ManifestItem(String id, Path path, String mediaType, String properties) {}

    private EpubParser() {}

    public static EpubBook open(Path epubFile) throws IOException {
        Path root = Files.createTempDirectory("epubreader-").toRealPath();
        try {
            extract(epubFile, root);
            return parse(epubFile, root);
        } catch (IOException | RuntimeException e) {
            new EpubBook(epubFile, root, "", "", List.of(), List.of()).close();
            throw e;
        }
    }

    // ---------------------------------------------------------------- extraction

    private static void extract(Path epubFile, Path root) throws IOException {
        try {
            extract(epubFile, root, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedName) {
            // Some older tools write entry names in the legacy DOS code page.
            extract(epubFile, root, Charset.forName("IBM437"));
        }
    }

    private static void extract(Path epubFile, Path root, Charset charset) throws IOException {
        try (ZipFile zip = new ZipFile(epubFile.toFile(), charset)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Unsafe entry in EPUB: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- package document

    private static EpubBook parse(Path epubFile, Path root) throws IOException {
        Path opfPath = findOpf(root);
        Path opfDir = opfPath.getParent();
        Document opf = readXml(opfPath);

        Map<String, ManifestItem> manifest = new HashMap<>();
        for (Element item : descendants(opf, "item")) {
            Path path = resolveHref(opfDir, root, item.getAttribute("href"));
            if (path != null) {
                String id = item.getAttribute("id");
                manifest.put(id, new ManifestItem(id, path,
                        item.getAttribute("media-type"), item.getAttribute("properties")));
            }
        }

        List<Path> spine = new ArrayList<>();
        Element spineEl = first(descendants(opf, "spine"));
        for (Element ref : descendants(opf, "itemref")) {
            ManifestItem item = manifest.get(ref.getAttribute("idref"));
            if (item != null && Files.isRegularFile(item.path())) {
                spine.add(item.path());
            }
        }
        if (spine.isEmpty()) {
            throw new IOException("EPUB has no readable chapters");
        }

        String title = text(first(descendants(opf, "title")));
        if (title.isBlank()) {
            title = epubFile.getFileName().toString().replaceFirst("(?i)\\.epub$", "");
        }
        List<String> creators = descendants(opf, "creator").stream().map(EpubParser::text)
                .filter(s -> !s.isBlank()).toList();

        List<TocEntry> toc = readToc(manifest, spineEl);
        if (toc.isEmpty()) {
            toc = fallbackToc(spine);
        }
        return new EpubBook(epubFile, root, title, String.join(", ", creators), List.copyOf(spine), toc);
    }

    private static Path findOpf(Path root) throws IOException {
        Path container = root.resolve("META-INF/container.xml");
        if (Files.isRegularFile(container)) {
            for (Element rootfile : descendants(readXml(container), "rootfile")) {
                Path opf = resolveHref(root, root, rootfile.getAttribute("full-path"));
                if (opf != null && Files.isRegularFile(opf)) {
                    return opf;
                }
            }
        }
        // Broken container: fall back to the first .opf we can find.
        try (var files = Files.walk(root)) {
            return files.filter(p -> p.toString().toLowerCase().endsWith(".opf")).findFirst()
                    .orElseThrow(() -> new IOException("Not an EPUB: no package document found"));
        }
    }

    // ---------------------------------------------------------------- table of contents

    private static List<TocEntry> readToc(Map<String, ManifestItem> manifest, Element spineEl) {
        // EPUB 3 navigation document
        for (ManifestItem item : manifest.values()) {
            if ((" " + item.properties() + " ").contains(" nav ")) {
                try {
                    List<TocEntry> toc = readNav(item.path());
                    if (!toc.isEmpty()) {
                        return toc;
                    }
                } catch (IOException ignored) {
                    // fall through to NCX
                }
            }
        }
        // EPUB 2 NCX
        ManifestItem ncx = spineEl == null ? null : manifest.get(spineEl.getAttribute("toc"));
        if (ncx == null) {
            ncx = manifest.values().stream()
                    .filter(i -> "application/x-dtbncx+xml".equals(i.mediaType())).findFirst().orElse(null);
        }
        if (ncx != null) {
            try {
                Document doc = readXml(ncx.path());
                Element navMap = first(descendants(doc, "navMap"));
                if (navMap != null) {
                    return readNavPoints(navMap, ncx.path().getParent());
                }
            } catch (IOException ignored) {
                // no TOC
            }
        }
        return List.of();
    }

    private static List<TocEntry> readNav(Path navFile) throws IOException {
        Document doc = readXml(navFile);
        List<Element> navs = descendants(doc, "nav");
        Element toc = navs.stream()
                .filter(n -> n.getAttributeNS(OPS_NS, "type").contains("toc"))
                .findFirst().orElse(first(navs));
        if (toc == null) {
            return List.of();
        }
        Element ol = firstChild(toc, "ol");
        return ol == null ? List.of() : readNavList(ol, navFile.getParent());
    }

    private static List<TocEntry> readNavList(Element ol, Path baseDir) {
        List<TocEntry> entries = new ArrayList<>();
        for (Element li : children(ol, "li")) {
            Element a = firstChild(li, "a");
            Element label = a != null ? a : firstChild(li, "span");
            Element sub = firstChild(li, "ol");
            List<TocEntry> kids = sub == null ? List.of() : readNavList(sub, baseDir);
            URI target = a == null ? null : toTarget(baseDir, a.getAttribute("href"));
            if (target == null && !kids.isEmpty()) {
                target = kids.getFirst().target();
            }
            if (label != null && target != null) {
                entries.add(new TocEntry(text(label), target, kids));
            }
        }
        return entries;
    }

    private static List<TocEntry> readNavPoints(Element parent, Path baseDir) {
        List<TocEntry> entries = new ArrayList<>();
        for (Element point : children(parent, "navPoint")) {
            Element navLabel = firstChild(point, "navLabel");
            Element content = firstChild(point, "content");
            URI target = content == null ? null : toTarget(baseDir, content.getAttribute("src"));
            if (target != null) {
                String label = navLabel == null ? "" : text(navLabel);
                entries.add(new TocEntry(label, target, readNavPoints(point, baseDir)));
            }
        }
        return entries;
    }

    private static List<TocEntry> fallbackToc(List<Path> spine) {
        List<TocEntry> entries = new ArrayList<>();
        for (int i = 0; i < spine.size(); i++) {
            entries.add(new TocEntry("Section " + (i + 1), spine.get(i).toUri(), List.of()));
        }
        return entries;
    }

    // ---------------------------------------------------------------- path helpers

    /** Resolves an href inside the book; returns null if it escapes the book or is external. */
    static Path resolveHref(Path baseDir, Path root, String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String pathPart = href.split("#", 2)[0];
        String decoded;
        try {
            URI uri = new URI(pathPart);
            if (uri.getScheme() != null) {
                return null;
            }
            decoded = uri.getPath();
        } catch (URISyntaxException e) {
            decoded = pathPart; // not percent-encoded; use as-is
        }
        Path resolved = baseDir.resolve(decoded).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private static URI toTarget(Path baseDir, String href) {
        Path path = resolveHref(baseDir, baseDir.getRoot(), href);
        if (path == null) {
            return null;
        }
        int hash = href.indexOf('#');
        String uri = path.toUri().toString();
        if (hash >= 0 && hash < href.length() - 1) {
            uri += "#" + href.substring(hash + 1);
        }
        try {
            return new URI(uri);
        } catch (URISyntaxException e) {
            return path.toUri();
        }
    }

    // ---------------------------------------------------------------- XML helpers

    static Document readXml(Path file) throws IOException {
        return parseXml(Files.readAllBytes(file), file.getFileName().toString());
    }

    static Document parseXml(byte[] bytes, String name) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            try {
                return builder.parse(new ByteArrayInputStream(bytes));
            } catch (org.xml.sax.SAXException e) {
                // XHTML often uses named entities that only exist in the (unloaded) DTD.
                String xml = replaceHtmlEntities(new String(bytes, StandardCharsets.UTF_8));
                return builder.parse(new InputSource(new StringReader(xml)));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot parse " + name + ": " + e.getMessage(), e);
        }
    }

    private static String replaceHtmlEntities(String xml) {
        return xml.replace("&nbsp;", "&#160;").replace("&mdash;", "&#8212;").replace("&ndash;", "&#8211;")
                .replace("&hellip;", "&#8230;").replace("&rsquo;", "&#8217;").replace("&lsquo;", "&#8216;")
                .replace("&rdquo;", "&#8221;").replace("&ldquo;", "&#8220;").replace("&copy;", "&#169;");
    }

    static List<Element> descendants(Document doc, String localName) {
        return elements(doc.getElementsByTagNameNS("*", localName));
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                result.add(e);
            }
        }
        return result;
    }

    private static Element firstChild(Element parent, String localName) {
        return first(children(parent, localName));
    }

    private static List<Element> elements(NodeList list) {
        List<Element> result = new ArrayList<>(list.getLength());
        for (int i = 0; i < list.getLength(); i++) {
            result.add((Element) list.item(i));
        }
        return result;
    }

    static Element first(List<Element> list) {
        return list.isEmpty() ? null : list.getFirst();
    }

    static String text(Element e) {
        return e == null ? "" : e.getTextContent().replaceAll("[\\s\\p{Z}]+", " ").strip();
    }
}
