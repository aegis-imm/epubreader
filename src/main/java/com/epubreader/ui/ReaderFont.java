package com.epubreader.ui;

/**
 * Font family override for book text. {@link #ORIGINAL} keeps the publisher's fonts;
 * {@link #INSTALLED} uses a font installed on this computer, chosen in the font dialog.
 */
enum ReaderFont {
    ORIGINAL("Publisher default", null),
    SERIF("Serif", "Georgia, 'Times New Roman', 'Noto Serif', serif"),
    SANS("Sans-serif", "'Segoe UI', 'Helvetica Neue', Helvetica, Arial, 'Noto Sans', sans-serif"),
    INSTALLED("Installed font", null);

    final String label;
    final String cssFamily;

    ReaderFont(String label, String cssFamily) {
        this.label = label;
        this.cssFamily = cssFamily;
    }

    /** CSS font-family for this choice, or null to keep the book's own fonts. */
    String css(String installedFamily) {
        if (this != INSTALLED) {
            return cssFamily;
        }
        if (installedFamily == null || installedFamily.isBlank()) {
            return null;
        }
        // Quote the name; fall back to a generic family if the font is later uninstalled.
        return "'" + installedFamily.replace("\\", "").replace("'", "\\'") + "', serif";
    }

    @Override
    public String toString() {
        return label;
    }
}
