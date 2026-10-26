package com.epubreader.library;

import java.nio.file.Path;
import java.util.Locale;

public enum BookFormat {
    EPUB, PDF;

    /** Returns the format of a file by extension, or null if it isn't a supported book. */
    public static BookFormat of(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".epub")) {
            return EPUB;
        } else if (name.endsWith(".pdf")) {
            return PDF;
        }
        return null;
    }
}
