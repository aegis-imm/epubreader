package com.epubreader.library;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A book in the library.
 *
 * @param cover cached cover image file, or null if the book has none
 */
public record BookInfo(Path file, BookFormat format, String title, String author, long size,
                       Instant added, Path cover) {}
