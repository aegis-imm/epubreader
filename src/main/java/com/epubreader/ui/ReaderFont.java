package com.epubreader.ui;

/** Font family override for book text. {@link #ORIGINAL} keeps the publisher's fonts. */
enum ReaderFont {
    ORIGINAL("Publisher default", null),
    SERIF("Serif", "Georgia, 'Times New Roman', 'Noto Serif', serif"),
    SANS("Sans-serif", "'Segoe UI', 'Helvetica Neue', Helvetica, Arial, 'Noto Sans', sans-serif");

    final String label;
    final String cssFamily;

    ReaderFont(String label, String cssFamily) {
        this.label = label;
        this.cssFamily = cssFamily;
    }

    @Override
    public String toString() {
        return label;
    }
}
