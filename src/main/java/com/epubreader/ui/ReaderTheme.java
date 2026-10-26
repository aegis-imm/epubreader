package com.epubreader.ui;

/** Page colour schemes. {@code dark} also switches the application chrome to a dark palette. */
enum ReaderTheme {
    LIGHT("Light", "#ffffff", "#1d1d1f", "#1a5fb4", false),
    SEPIA("Sepia", "#f4ecd8", "#5b4636", "#8a4b0f", false),
    DARK("Dark", "#1e1e1e", "#d8d8d8", "#7fb4ff", true);

    final String label;
    final String background;
    final String foreground;
    final String link;
    final boolean dark;

    ReaderTheme(String label, String background, String foreground, String link, boolean dark) {
        this.label = label;
        this.background = background;
        this.foreground = foreground;
        this.link = link;
        this.dark = dark;
    }

    @Override
    public String toString() {
        return label;
    }
}
