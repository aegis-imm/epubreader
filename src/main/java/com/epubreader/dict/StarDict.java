package com.epubreader.dict;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A StarDict dictionary: {@code name.ifo} plus {@code name.idx[.gz]}, {@code name.dict[.dz]} and an
 * optional {@code name.syn}. The whole dictionary is held in memory.
 */
public final class StarDict implements Dictionary {

    private final Path ifo;
    private final String name;
    private final String sameTypeSequence;
    private final String[] words;
    private final long[] offsets;
    private final int[] sizes;
    private final Map<String, int[]> byWord = new HashMap<>();
    private final byte[] data;

    private StarDict(Path ifo, String name, String sameTypeSequence, String[] words, long[] offsets,
                     int[] sizes, byte[] data) {
        this.ifo = ifo;
        this.name = name;
        this.sameTypeSequence = sameTypeSequence;
        this.words = words;
        this.offsets = offsets;
        this.sizes = sizes;
        this.data = data;
    }

    public static StarDict load(Path ifo) throws IOException {
        Map<String, String> info = new HashMap<>();
        List<String> lines = Files.readAllLines(ifo, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().contains("StarDict")) {
            throw new IOException(ifo.getFileName() + " is not a StarDict .ifo file");
        }
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                info.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        String base = ifo.getFileName().toString().replaceFirst("(?i)\\.ifo$", "");
        Path dir = ifo.toAbsolutePath().getParent();
        byte[] idx = readMaybeGzipped(dir, base + ".idx", base + ".idx.gz");
        byte[] dict = readMaybeGzipped(dir, base + ".dict", base + ".dict.dz");
        boolean offset64 = "64".equals(info.get("idxoffsetbits"));

        List<String> words = new ArrayList<>();
        List<long[]> entries = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(idx);
        while (buf.hasRemaining()) {
            int start = buf.position();
            while (buf.get() != 0) {
                // scan to the terminating NUL
            }
            words.add(new String(idx, start, buf.position() - start - 1, StandardCharsets.UTF_8));
            long offset = offset64 ? buf.getLong() : Integer.toUnsignedLong(buf.getInt());
            entries.add(new long[]{offset, Integer.toUnsignedLong(buf.getInt())});
        }

        int n = words.size();
        long[] offsets = new long[n];
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = entries.get(i)[0];
            sizes[i] = (int) entries.get(i)[1];
        }
        String bookName = info.getOrDefault("bookname", base);
        StarDict d = new StarDict(ifo, bookName, info.get("sametypesequence"), words.toArray(String[]::new),
                offsets, sizes, dict);
        for (int i = 0; i < n; i++) {
            d.index(words.get(i), i);
        }
        Path syn = dir.resolve(base + ".syn");
        if (Files.isRegularFile(syn)) {
            byte[] synBytes = Files.readAllBytes(syn);
            ByteBuffer sb = ByteBuffer.wrap(synBytes);
            while (sb.hasRemaining()) {
                int start = sb.position();
                while (sb.get() != 0) {
                    // scan to the terminating NUL
                }
                String word = new String(synBytes, start, sb.position() - start - 1, StandardCharsets.UTF_8);
                int target = sb.getInt();
                if (target >= 0 && target < n) {
                    d.index(word, target);
                }
            }
        }
        return d;
    }

    private void index(String word, int entry) {
        byWord.merge(word.toLowerCase(Locale.ROOT), new int[]{entry}, (a, b) -> {
            for (int x : a) {
                if (x == b[0]) {
                    return a;
                }
            }
            int[] merged = java.util.Arrays.copyOf(a, a.length + 1);
            merged[a.length] = b[0];
            return merged;
        });
    }

    private static byte[] readMaybeGzipped(Path dir, String plain, String gzipped) throws IOException {
        Path p = dir.resolve(plain);
        if (Files.isRegularFile(p)) {
            return Files.readAllBytes(p);
        }
        Path gz = dir.resolve(gzipped);
        if (Files.isRegularFile(gz)) {
            // dictzip (.dz) files are valid gzip streams.
            try (InputStream in = new GZIPInputStream(Files.newInputStream(gz), 1 << 16)) {
                return in.readAllBytes();
            }
        }
        throw new IOException("Missing " + plain + " (or " + gzipped + ") next to the .ifo file");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Path file() {
        return ifo;
    }

    @Override
    public int size() {
        return words.length;
    }

    @Override
    public List<Definition> lookup(String word) {
        int[] hits = byWord.get(word.toLowerCase(Locale.ROOT));
        if (hits == null) {
            return List.of();
        }
        List<Definition> result = new ArrayList<>();
        for (int i : hits) {
            if (offsets[i] + sizes[i] <= data.length) {
                result.add(new Definition(words[i], render((int) offsets[i], sizes[i])));
            }
        }
        return result;
    }

    /** Converts an entry's typed fields to HTML. See the StarDict file format documentation. */
    private String render(int offset, int size) {
        StringBuilder html = new StringBuilder();
        int pos = offset;
        int end = offset + size;
        if (sameTypeSequence != null && !sameTypeSequence.isEmpty()) {
            for (int t = 0; t < sameTypeSequence.length() && pos < end; t++) {
                char type = sameTypeSequence.charAt(t);
                boolean last = t == sameTypeSequence.length() - 1;
                pos = field(type, pos, end, last, html);
            }
        } else {
            while (pos < end) {
                char type = (char) data[pos++];
                pos = field(type, pos, end, false, html);
            }
        }
        return html.toString();
    }

    private int field(char type, int pos, int end, boolean lastField, StringBuilder html) {
        if (Character.isUpperCase(type)) {
            // binary field (sound, picture, ...): 32-bit size then data. Skip it.
            if (lastField) {
                return end;
            }
            int len = ByteBuffer.wrap(data, pos, 4).getInt();
            return pos + 4 + len;
        }
        int stop = pos;
        if (lastField) {
            stop = end;
        } else {
            while (stop < end && data[stop] != 0) {
                stop++;
            }
        }
        String text = new String(data, pos, stop - pos, StandardCharsets.UTF_8);
        html.append(switch (type) {
            case 'h', 'g' -> text; // HTML / Pango markup
            case 'x' -> xdxfToHtml(text);
            default -> WordListDictionary.plainToHtml(text);
        });
        return lastField ? end : stop + 1;
    }

    private static String xdxfToHtml(String xdxf) {
        return xdxf.replaceAll("<k>.*?</k>", "")
                .replace("<abr>", "<i>").replace("</abr>", "</i>")
                .replace("<ex>", "<span class=ex>").replace("</ex>", "</span>")
                .replace("<c>", "<span>").replace("</c>", "</span>")
                .replace("\n", "<br>");
    }
}
