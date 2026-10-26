package com.epubreader.ui;

import javafx.scene.Node;

/** A reader for one open book (EPUB or PDF), driven by the app's menus. */
interface BookView {

    Node node();

    /** Window title for the book. */
    String title();

    void nextSection();

    void previousSection();

    void goToStart();

    void zoomIn();

    void zoomOut();

    void zoomReset();

    /** Saves the reading position and releases the book's resources. */
    void close();
}
