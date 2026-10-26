package com.epubreader.dict;

import java.nio.file.Path;
import java.util.List;

/** A loaded dictionary. Lookups are case-insensitive. */
public interface Dictionary {

    /** One meaning of a word, as an HTML fragment safe to show in the popup. */
    record Definition(String headword, String html) {}

    String name();

    /** The file the dictionary was loaded from (an .ifo or word-list file). */
    Path file();

    int size();

    List<Definition> lookup(String word);
}
