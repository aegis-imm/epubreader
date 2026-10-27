# EPUB Reader

A desktop reader for EPUB and PDF books on Windows and macOS, built with **Java 25**, **JavaFX 25** and
**Apache PDFBox 3**.

## Features

**Library**
- Add EPUB and PDF books with **Add Books…** or by dropping files on the window. Books are copied into
  your library folder.
- Three layouts: **Large Icons** and **Medium Icons** (cover grids) and **Details** (a sortable table
  with title, author, format, size, date added and progress, without covers).
- Covers come from the EPUB's cover image or the PDF's first page. Books without a cover get a coloured
  placeholder with the title.
- Search by title or author, sort the grids, and right-click a book to open it, show it in its folder,
  or remove it (the file goes to the Recycle Bin / Trash).
- The library folder can be changed with **Tools → Library Location…**. It is saved in the config file.

**Reading**
- EPUB 2 and EPUB 3 books with a table of contents. PDFs show their bookmarks in the same sidebar.
- Light, sepia and dark themes; publisher, serif or sans-serif fonts; text size 60–250% (EPUB) or zoom
  25–400% (PDF).
- Remembers where you stopped in every book.

**Dictionary popup**
- In an EPUB, select a word (double-click or drag) to see its meaning. In a PDF, double-click the word.
- Common word forms are matched, so "running" finds "run" and "studies" finds "study". Thai text is
  split into words automatically.
- Add dictionaries with **Tools → Dictionaries…**:
  - **StarDict**: pick the `.ifo` file. Its `.idx`, `.dict` / `.dict.dz` and `.syn` files must be in
    the same folder. You can also pick a `.zip` that contains them.
  - **Word lists**: a UTF-8 text file with one entry per line, `word<TAB>meaning` (`.tsv` / `.txt`),
    or `word,meaning` (`.csv`). Write `\n` inside a meaning to start a new line.
- Turn the popup off with **Tools → Show Meaning on Selection**.

### Keyboard

| Key | Action |
| --- | --- |
| `→` / `Page Down` / `Space` | Next screen (continues into the next chapter or page) |
| `←` / `Page Up` / `Shift+Space` | Previous screen |
| `Ctrl/Cmd + →` / `←` | Next / previous chapter or page |
| `Ctrl/Cmd + =` / `-` / `0` | Zoom in / out / reset |
| `Ctrl/Cmd + T` | Show or hide the table of contents |
| `Ctrl/Cmd + L` | Back to the library |
| `Ctrl/Cmd + O` | Open a file without adding it to the library |
| `Ctrl/Cmd + Shift + O` | Add books to the library |
| `Ctrl/Cmd + Shift + D` | Manage dictionaries |

## Where things are stored

| What | Where |
| --- | --- |
| Config file (`settings.properties`) | Windows: `%APPDATA%\EPUB Reader`<br>macOS: `~/Library/Application Support/EPUB Reader`<br>Linux: `~/.config/epub-reader` |
| Imported dictionaries | `dictionaries` folder next to the config file |
| Library books | `Documents/EPUB Reader Library` by default (`library.dir` in the config file) |
| Covers and the metadata cache | `.library` inside the library folder (safe to delete; it is rebuilt) |

**Tools → Open Settings Folder** opens the config folder. To keep settings somewhere else, for example
in a portable install, start Java with `-Depubreader.configDir=<folder>`.

## Requirements

- JDK 25 (for example Azul Zulu, Eclipse Temurin or Oracle JDK)
- Nothing else: the Maven wrapper (`mvnw`) downloads Maven, and Maven downloads JavaFX and PDFBox.

## Run during development

```bash
./mvnw javafx:run
```

On Windows use `mvnw.cmd javafx:run`. Run the tests with `./mvnw test`.

## Build a standalone app

The packaged app includes its own Java runtime, so people who use it don't need Java installed.
JavaFX includes native code, so build each platform's app on that platform.

**Windows** creates the folder `target\dist\EPUB Reader\EPUB Reader.exe`:

```powershell
.\package-windows.cmd
```

Close the app before rebuilding. Run `.\package-windows.cmd installer` to build an `.msi` instead. It adds Start-menu and desktop shortcuts and lets you open
`.epub` and `.pdf` files with the app. This needs the [WiX Toolset](https://wixtoolset.org/) installed.

**macOS** creates `target/dist/EPUB Reader-1.0.0.dmg`:

```bash
./package-mac.sh
```

## Project layout

```
src/main/java/com/epubreader/
  epub/      EpubParser (extract + spine/TOC), EpubMetadata (title, author, cover straight from the zip)
  pdf/       PdfSupport: PDF metadata, cover rendering, and word positions for dictionary lookup
  library/   Library: scans the library folder, copies books in, caches metadata and covers
  dict/      StarDict and word-list dictionaries, DictionaryManager (import, word forms, lookup)
  ui/        ReaderApp (window and menus), LibraryView, EpubView, PdfView, DictionaryPopup,
             DictionariesDialog, ReaderSettings (the config file)
```

EPUB chapters are rendered by JavaFX `WebView`, with themes applied through a user stylesheet. PDF pages
are rendered by PDFBox on a background thread.
