package com.epubreader.ui;

/** How the library shows books: cover grids at two sizes, or a details table without covers. */
enum LibraryLayout {
    LARGE("Large Icons", 170),
    MEDIUM("Medium Icons", 115),
    DETAILS("Details", 0);

    final String label;
    /** Cover width in pixels; covers use a 2:3 aspect ratio. */
    final int coverWidth;

    LibraryLayout(String label, int coverWidth) {
        this.label = label;
        this.coverWidth = coverWidth;
    }

    @Override
    public String toString() {
        return label;
    }
}
