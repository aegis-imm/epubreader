package com.epubreader.epub;

import com.epubreader.epub.EpubBook.TocEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpubParserTest {

    @TempDir
    Path tmp;

    @Test
    void readsEpub3NavigationDocument() throws IOException {
        Path file = tmp.resolve("book3.epub");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip");
        entries.put("META-INF/container.xml", container("OEBPS/content.opf"));
        entries.put("OEBPS/content.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Test  Book</dc:title><dc:creator>Ann Author</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="Text/chapter%201.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                </package>""");
        entries.put("OEBPS/nav.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
                  <nav epub:type="landmarks"><ol><li><a href="Text/ch2.xhtml">Wrong</a></li></ol></nav>
                  <nav epub:type="toc"><ol>
                    <li><a href="Text/chapter%201.xhtml">One&nbsp;</a></li>
                    <li><span>Part</span><ol><li><a href="Text/ch2.xhtml#s2">Two</a></li></ol></li>
                  </ol></nav>
                </body></html>""");
        entries.put("OEBPS/Text/chapter 1.xhtml", chapter("One"));
        entries.put("OEBPS/Text/ch2.xhtml", chapter("Two"));
        writeZip(file, entries);

        try (EpubBook book = EpubParser.open(file)) {
            assertEquals("Test Book", book.title());
            assertEquals("Ann Author", book.author());
            assertEquals(2, book.spine().size());
            assertTrue(Files.isRegularFile(book.spine().getFirst()));

            assertEquals(2, book.toc().size());
            assertEquals("One", book.toc().getFirst().label());
            TocEntry part = book.toc().get(1);
            assertEquals("Part", part.label());
            assertEquals("s2", part.children().getFirst().target().getFragment());
            assertEquals(1, book.spineIndexOf(part.children().getFirst().target()));
            assertEquals(0, book.spineIndexOf(book.toc().getFirst().target()));
        }
    }

    @Test
    void readsEpub2Ncx() throws IOException {
        Path file = tmp.resolve("book2.epub");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip");
        entries.put("META-INF/container.xml", container("content.opf"));
        entries.put("content.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Old</dc:title></metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="a" href="a.html" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx"><itemref idref="a"/></spine>
                </package>""");
        entries.put("toc.ncx", """
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
                  <navPoint id="p1"><navLabel><text>Start</text></navLabel><content src="a.html"/>
                    <navPoint id="p2"><navLabel><text>Inner</text></navLabel><content src="a.html#x"/></navPoint>
                  </navPoint>
                </navMap></ncx>""");
        entries.put("a.html", chapter("A"));
        writeZip(file, entries);

        try (EpubBook book = EpubParser.open(file)) {
            assertEquals("Old", book.title());
            assertEquals("", book.author());
            assertEquals("Start", book.toc().getFirst().label());
            assertEquals("Inner", book.toc().getFirst().children().getFirst().label());
        }
    }

    @Test
    void closeDeletesExtractedFiles() throws IOException {
        Path file = tmp.resolve("min.epub");
        writeZip(file, Map.of(
                "META-INF/container.xml", container("p.opf"),
                "p.opf", """
                        <package xmlns="http://www.idpf.org/2007/opf"><metadata/>
                        <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
                        <spine><itemref idref="a"/></spine></package>""",
                "a.xhtml", chapter("A")));
        EpubBook book = EpubParser.open(file);
        assertEquals("min", book.title());
        assertFalse(book.toc().isEmpty(), "falls back to a generated TOC");
        book.close();
        assertFalse(Files.exists(book.rootDir()));
    }

    @Test
    void rejectsZipSlip() throws IOException {
        Path file = tmp.resolve("evil.epub");
        writeZip(file, Map.of("../../evil.txt", "x"));
        assertThrows(IOException.class, () -> EpubParser.open(file));
    }

    private static String container(String opf) {
        return """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="%s" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".formatted(opf);
    }

    private static String chapter(String title) {
        return "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>" + title + "</h1></body></html>";
    }

    private static void writeZip(Path file, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
