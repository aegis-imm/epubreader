package com.epubreader.epub;

import org.w3c.dom.Document;

import javax.swing.text.html.parser.DTD;
import javax.swing.text.html.parser.Entity;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixes common mistakes in EPUB content documents so that WebView, which parses {@code .xhtml}
 * files as strict XML, can render them. Typical problems in real books: an {@code <html>} root
 * without the XHTML namespace (nothing renders as HTML), {@code epub:} attributes whose prefix is
 * never declared, and HTML entities such as {@code &nbsp;} that XML does not define.
 */
final class XhtmlRepair {

    static final String XHTML_NS = "http://www.w3.org/1999/xhtml";

    /** Prefixes books commonly use without declaring them. */
    private static final Map<String, String> PREFIXES = new LinkedHashMap<>();

    static {
        PREFIXES.put("epub", "http://www.idpf.org/2007/ops");
        PREFIXES.put("xlink", "http://www.w3.org/1999/xlink");
        PREFIXES.put("m", "http://www.w3.org/1998/Math/MathML");
        PREFIXES.put("mml", "http://www.w3.org/1998/Math/MathML");
        PREFIXES.put("svg", "http://www.w3.org/2000/svg");
    }

    private static final Map<String, Integer> ENTITIES = Map.ofEntries(
            Map.entry("nbsp", 160), Map.entry("ensp", 8194), Map.entry("emsp", 8195), Map.entry("thinsp", 8201),
            Map.entry("zwnj", 8204), Map.entry("zwj", 8205), Map.entry("shy", 173),
            Map.entry("mdash", 8212), Map.entry("ndash", 8211), Map.entry("hellip", 8230), Map.entry("minus", 8722),
            Map.entry("lsquo", 8216), Map.entry("rsquo", 8217), Map.entry("sbquo", 8218),
            Map.entry("ldquo", 8220), Map.entry("rdquo", 8221), Map.entry("bdquo", 8222),
            Map.entry("laquo", 171), Map.entry("raquo", 187), Map.entry("lsaquo", 8249), Map.entry("rsaquo", 8250),
            Map.entry("bull", 8226), Map.entry("middot", 183), Map.entry("prime", 8242), Map.entry("Prime", 8243),
            Map.entry("dagger", 8224), Map.entry("Dagger", 8225), Map.entry("trade", 8482), Map.entry("euro", 8364),
            Map.entry("larr", 8592), Map.entry("uarr", 8593), Map.entry("rarr", 8594), Map.entry("darr", 8595),
            Map.entry("harr", 8596), Map.entry("rArr", 8658), Map.entry("lArr", 8656), Map.entry("hArr", 8660),
            Map.entry("le", 8804), Map.entry("ge", 8805), Map.entry("ne", 8800), Map.entry("asymp", 8776),
            Map.entry("infin", 8734), Map.entry("sum", 8721), Map.entry("radic", 8730), Map.entry("loz", 9674),
            Map.entry("alpha", 945), Map.entry("beta", 946), Map.entry("gamma", 947), Map.entry("delta", 948),
            Map.entry("lambda", 955), Map.entry("mu", 956), Map.entry("pi", 960), Map.entry("sigma", 963),
            Map.entry("omega", 969), Map.entry("Omega", 937), Map.entry("check", 10003));

    private static final Pattern ROOT_TAG = Pattern.compile("<html(?=[\\s>/])[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_ENTITY = Pattern.compile("&([A-Za-z][A-Za-z0-9]*);");
    private static final Pattern XML_ENCODING =
            Pattern.compile("\\A(\\s*<\\?xml[^>]*?encoding\\s*=\\s*[\"'])([^\"']+)");

    private XhtmlRepair() {}

    /**
     * Returns the file WebView should load for a spine document: the file itself (repaired in place
     * when needed), or, when it can't be made valid XML, an HTML copy that WebView parses leniently.
     */
    static Path prepare(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".xhtml") || name.endsWith(".xht") || name.endsWith(".xml"))) {
            return file; // .html/.htm are parsed as HTML already
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (isRenderable(bytes)) {
                return file;
            }
            String text = decode(bytes);
            String repaired = repair(text);
            if (isRenderable(repaired.getBytes(StandardCharsets.UTF_8))) {
                Files.writeString(file, repaired, StandardCharsets.UTF_8);
                return file;
            }
            Path copy = file.resolveSibling(file.getFileName() + ".html");
            Files.writeString(copy, "<meta charset=\"utf-8\">" + text, StandardCharsets.UTF_8);
            return copy;
        } catch (IOException | RuntimeException e) {
            return file;
        }
    }

    /** Declares missing namespaces on the root element and turns HTML entities into character references. */
    static String repair(String xml) {
        String result = replaceEntities(xml);
        Matcher root = ROOT_TAG.matcher(result);
        if (root.find()) {
            String tag = root.group();
            StringBuilder extra = new StringBuilder();
            if (!tag.matches("(?s).*\\sxmlns\\s*=.*")) {
                extra.append(" xmlns=\"").append(XHTML_NS).append('"');
            }
            for (var prefix : PREFIXES.entrySet()) {
                String p = prefix.getKey();
                boolean used = Pattern.compile("[<\\s]" + p + ":[A-Za-z]").matcher(result).find();
                if (used && !tag.contains("xmlns:" + p + "=")) {
                    extra.append(" xmlns:").append(p).append("=\"").append(prefix.getValue()).append('"');
                }
            }
            if (!extra.isEmpty()) {
                int insertAt = root.start() + "<html".length();
                result = result.substring(0, insertAt) + extra + result.substring(insertAt);
            }
        }
        // The repaired text is written back as UTF-8.
        return XML_ENCODING.matcher(result).replaceFirst("$1utf-8");
    }

    /** Decodes a document using its BOM or XML declaration, defaulting to UTF-8. */
    static String decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, StandardCharsets.UTF_16);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, StandardCharsets.UTF_16);
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.ISO_8859_1);
        Matcher m = XML_ENCODING.matcher(head);
        Charset charset = StandardCharsets.UTF_8;
        if (m.find()) {
            try {
                charset = Charset.forName(m.group(2).strip());
            } catch (IllegalArgumentException ignored) {
                // unknown encoding name: assume UTF-8
            }
        }
        String text = new String(bytes, charset);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** True if the bytes are well-formed XML whose root element is in the XHTML namespace. */
    private static boolean isRenderable(byte[] bytes) {
        try {
            Document doc = EpubParser.newBuilder().parse(new ByteArrayInputStream(bytes));
            return XHTML_NS.equals(doc.getDocumentElement().getNamespaceURI());
        } catch (Exception e) {
            return false;
        }
    }

    private static String replaceEntities(String xml) {
        Matcher m = NAMED_ENTITY.matcher(xml);
        StringBuilder out = new StringBuilder(xml.length());
        while (m.find()) {
            String name = m.group(1);
            Integer code = switch (name) {
                case "amp", "lt", "gt", "quot", "apos" -> null; // already defined in XML
                default -> ENTITIES.getOrDefault(name, latin1Entity(name));
            };
            m.appendReplacement(out, code == null ? Matcher.quoteReplacement(m.group()) : "&#" + code + ";");
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Looks up the remaining HTML 3.2 entities (accented letters and Latin-1 symbols). */
    private static Integer latin1Entity(String name) {
        try {
            new ParserDelegator(); // loads the bundled HTML 3.2 DTD
            Entity e = DTD.getDTD("html32").getEntity(name);
            return e == null || e.getData().length != 1 ? null : (int) e.getData()[0];
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
