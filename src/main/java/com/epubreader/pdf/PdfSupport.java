package com.epubreader.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

/** PDFBox helpers shared by the library (metadata, covers) and the PDF reader (words for lookup). */
public final class PdfSupport {

    /** A word on a page, in PDF points with a top-left origin. */
    public record Word(String text, double x, double y, double width, double height) {
        public boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    public record Metadata(String title, String author, BufferedImage cover) {}

    private PdfSupport() {}

    /** Reads title and author, and renders page 1 at roughly {@code coverWidth} pixels wide. */
    public static Metadata readMetadata(Path file, int coverWidth) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDDocumentInformation info = doc.getDocumentInformation();
            String title = info.getTitle() == null ? "" : info.getTitle().strip();
            String author = info.getAuthor() == null ? "" : info.getAuthor().strip();
            BufferedImage cover = null;
            if (doc.getNumberOfPages() > 0) {
                PDRectangle box = doc.getPage(0).getCropBox();
                float scale = coverWidth / Math.max(1f, box.getWidth());
                cover = new PDFRenderer(doc).renderImage(0, scale);
            }
            return new Metadata(title, author, cover);
        }
    }

    /**
     * Extracts the words of one page with their positions. Text without spaces between words, such as
     * Thai, is split with the JDK's dictionary-based word breaker.
     */
    public static List<Word> words(PDDocument doc, int pageIndex) throws IOException {
        List<Word> words = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                collectWords(text, positions, words);
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.writeText(doc, new StringWriter());
        return words;
    }

    private static void collectWords(String text, List<TextPosition> positions, List<Word> out) {
        if (positions.isEmpty()) {
            return;
        }
        // Map each char of `text` to a glyph position; they normally match 1:1, otherwise approximate.
        int n = text.length();
        BreakIterator breaker = BreakIterator.getWordInstance();
        breaker.setText(text);
        int start = breaker.first();
        for (int end = breaker.next(); end != BreakIterator.DONE; start = end, end = breaker.next()) {
            String word = text.substring(start, end).strip();
            if (word.isEmpty() || !Character.isLetterOrDigit(word.codePointAt(0))) {
                continue;
            }
            TextPosition first = positions.get(Math.min(positions.size() - 1, start * positions.size() / n));
            TextPosition last = positions.get(Math.min(positions.size() - 1, (end - 1) * positions.size() / n));
            double x = first.getXDirAdj();
            double width = Math.max(1, last.getXDirAdj() + last.getWidthDirAdj() - x);
            double height = Math.max(first.getHeightDir(), last.getHeightDir()) * 1.3;
            double baseline = Math.max(first.getYDirAdj(), last.getYDirAdj());
            out.add(new Word(word, x, baseline - height, width, height * 1.15));
        }
    }
}
