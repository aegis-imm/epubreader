package com.epubreader.library;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryTest {

    @TempDir
    Path tmp;

    @Test
    void addsAndScansEpubAndPdfWithCovers() throws IOException {
        Path outside = Files.createDirectories(tmp.resolve("downloads"));
        Path epub = outside.resolve("novel.epub");
        writeEpub(epub, true);
        Path pdf = outside.resolve("paper.pdf");
        writePdf(pdf);
        Files.writeString(outside.resolve("notes.txt"), "ignored");

        Library library = new Library(tmp.resolve("library"));
        List<Path> added = library.add(List.of(epub, pdf, outside.resolve("notes.txt")));
        assertEquals(2, added.size());
        library.add(List.of(epub)); // a second copy gets a new name
        assertTrue(Files.isRegularFile(tmp.resolve("library/novel (2).epub")));

        List<BookInfo> books = library.scan((done, total) -> {}).stream()
                .sorted(Comparator.comparing(b -> b.file().getFileName().toString())).toList();
        assertEquals(3, books.size());

        BookInfo novel = books.get(0);
        assertEquals(BookFormat.EPUB, novel.format());
        assertEquals("A Novel", novel.title());
        assertEquals("Writer One", novel.author());
        assertNotNull(novel.cover());
        assertNotNull(ImageIO.read(novel.cover().toFile()), "cover is a readable image");

        BookInfo paper = books.get(2);
        assertEquals(BookFormat.PDF, paper.format());
        assertEquals("Research Paper", paper.title());
        assertEquals("Dr. Author", paper.author());
        assertNotNull(paper.cover());

        // Second scan comes from the cache, and a fresh Library reads the saved index.
        List<BookInfo> again = new Library(tmp.resolve("library")).scan((d, t) -> {});
        assertEquals(3, again.size());
        assertTrue(again.stream().anyMatch(b -> b.title().equals("A Novel") && b.cover() != null));

        library.remove(paper);
        assertEquals(2, library.scan((d, t) -> {}).size());
    }

    @Test
    void bookWithoutCoverOrTitleUsesFileName() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("lib"));
        writeEpub(dir.resolve("my_book.epub"), false);
        BookInfo book = new Library(dir).scan((d, t) -> {}).getFirst();
        assertNull(book.cover());
        assertEquals("A Novel", book.title());

        Files.writeString(dir.resolve("broken.pdf"), "not really a pdf");
        BookInfo broken = new Library(dir).scan((d, t) -> {}).stream()
                .filter(b -> b.format() == BookFormat.PDF).findFirst().orElseThrow();
        assertEquals("broken", broken.title());
    }

    private static void writePdf(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Research Paper");
            info.setAuthor("Dr. Author");
            doc.setDocumentInformation(info);
            doc.save(file.toFile());
        }
    }

    private static void writeEpub(Path file, boolean withCover) throws IOException {
        BufferedImage img = new BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        String coverItem = withCover
                ? "<item id=\"cimg\" href=\"images/c%20over.png\" media-type=\"image/png\" properties=\"cover-image\"/>"
                : "";
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "META-INF/container.xml", """
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                    <rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""".getBytes(StandardCharsets.UTF_8));
            put(zip, "OPS/book.opf", ("""
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>A Novel</dc:title><dc:creator>Writer One</dc:creator></metadata>
                    <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>%s</manifest>
                    <spine><itemref idref="a"/></spine></package>""").formatted(coverItem).getBytes(StandardCharsets.UTF_8));
            put(zip, "OPS/a.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>x</body></html>"
                    .getBytes(StandardCharsets.UTF_8));
            if (withCover) {
                put(zip, "OPS/images/c over.png", png.toByteArray());
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
