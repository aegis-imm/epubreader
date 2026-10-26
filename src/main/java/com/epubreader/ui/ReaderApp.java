package com.epubreader.ui;

import com.epubreader.dict.DictionaryManager;
import com.epubreader.epub.EpubParser;
import com.epubreader.library.BookFormat;
import com.epubreader.library.Library;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

public final class ReaderApp extends Application {

    private static final String APP_TITLE = "EPUB Reader";
    private static final String DARK_CHROME_CSS = "data:text/css;base64," + Base64.getEncoder().encodeToString("""
            .root {
                -fx-base: #2d2d30;
                -fx-background: #252526;
                -fx-control-inner-background: #1e1e1e;
                -fx-accent: #3d6fb4;
                -fx-focus-color: #3d6fb4;
                -fx-faint-focus-color: transparent;
                -fx-selection-bar-non-focused: #3a3d41;
            }
            """.getBytes(StandardCharsets.UTF_8));

    private final BooleanProperty noBook = new SimpleBooleanProperty(true);

    private ReaderSettings settings;
    private AppContext ctx;
    private Stage stage;
    private Scene scene;
    private final StackPane content = new StackPane();
    private LibraryView library;
    private BookView current;
    private Menu recentMenu;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        settings = new ReaderSettings(ReaderSettings.defaultConfigDir());
        DictionaryManager dictionaries = new DictionaryManager(settings.configDir().resolve("dictionaries"));
        ctx = new AppContext(settings, dictionaries, getHostServices());
        ctx.setWindow(stage);
        Thread.ofVirtual().name("dictionary-load").start(() ->
                dictionaries.loadAll(settings.dictionaries()).forEach(err -> System.err.println("Dictionary: " + err)));

        library = new LibraryView(ctx, this::open);
        content.getChildren().setAll(library.node());

        BorderPane root = new BorderPane();
        root.setTop(createMenuBar());
        root.setCenter(content);
        scene = new Scene(root, settings.windowWidth(), settings.windowHeight());
        installDragAndDrop();
        ctx.theme.addListener((o, a, v) -> applyChrome());
        applyChrome();
        refreshRecent();

        // Settings are written in batches rather than on every scroll.
        Timeline saver = new Timeline(new KeyFrame(Duration.seconds(2), e -> settings.saveIfDirty()));
        saver.setCycleCount(Timeline.INDEFINITE);
        saver.play();

        Path libraryDir = settings.libraryDir();
        settings.setLibraryDir(libraryDir); // record the default location in the config file
        library.setLibrary(new Library(libraryDir));

        stage.setTitle(APP_TITLE);
        stage.setScene(scene);
        stage.setMinWidth(560);
        stage.setMinHeight(400);
        stage.setOnCloseRequest(e -> shutDown());
        stage.show();

        getParameters().getRaw().stream().map(Path::of).filter(Files::isRegularFile)
                .findFirst().ifPresent(this::open);
    }

    @Override
    public void stop() {
        shutDown();
    }

    /**
     * Saves state and releases the book while the main window still exists. When the window is
     * closed with its X button, Windows destroys it together with the pop-up windows it owns
     * (tooltips, menus, the dictionary popup); tidying those up from JavaFX afterwards reaches
     * window handles that are already gone and glass logs "GetMessage() failed ... [1400]".
     */
    private void shutDown() {
        if (stage.isShowing()) {
            settings.setWindowSize(stage.getWidth(), stage.getHeight());
        }
        closeBook();
        ctx.popup.hide();
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window != stage && window.isShowing()) {
                window.hide();
            }
        }
        settings.saveIfDirty();
    }

    // ---------------------------------------------------------------- menus

    private MenuBar createMenuBar() {
        MenuItem open = item("Open File…", "Shortcut+O", this::chooseFile);
        MenuItem add = item("Add Books to Library…", "Shortcut+Shift+O", () -> {
            showLibrary();
            library.chooseBooks();
        });
        recentMenu = new Menu("Open Recent");
        MenuItem showLibrary = item("Library", "Shortcut+L", this::showLibrary);
        MenuItem close = item("Close Book", "Shortcut+W", this::showLibrary);
        close.disableProperty().bind(noBook);
        MenuItem quit = item("Exit", null, Platform::exit);
        Menu file = new Menu("File", null, open, add, recentMenu, new SeparatorMenuItem(), showLibrary, close,
                new SeparatorMenuItem(), quit);

        CheckMenuItem contents = new CheckMenuItem("Table of Contents");
        contents.setAccelerator(KeyCombination.keyCombination("Shortcut+T"));
        contents.selectedProperty().bindBidirectional(ctx.tocVisible);
        MenuItem bigger = item("Zoom In", "Shortcut+Equals", () -> onBook(BookView::zoomIn));
        MenuItem smaller = item("Zoom Out", "Shortcut+Minus", () -> onBook(BookView::zoomOut));
        MenuItem reset = item("Actual Size", "Shortcut+0", () -> onBook(BookView::zoomReset));
        for (MenuItem m : List.of(bigger, smaller, reset)) {
            m.disableProperty().bind(noBook);
        }
        Menu view = new Menu("View", null,
                radioMenu("Library Layout", LibraryLayout.values(), library.layoutProperty()),
                new SeparatorMenuItem(), contents, bigger, smaller, reset, new SeparatorMenuItem(),
                radioMenu("Theme", ReaderTheme.values(), ctx.theme),
                radioMenu("Font", ReaderFont.values(), ctx.font));

        MenuItem prev = item("Previous Chapter / Page", "Shortcut+Left", () -> onBook(BookView::previousSection));
        MenuItem next = item("Next Chapter / Page", "Shortcut+Right", () -> onBook(BookView::nextSection));
        MenuItem first = item("Beginning of Book", "Shortcut+Home", () -> onBook(BookView::goToStart));
        Menu go = new Menu("Go", null, prev, next, first);
        go.disableProperty().bind(noBook);

        MenuItem dictionaries = item("Dictionaries…", "Shortcut+Shift+D", () -> DictionariesDialog.show(ctx));
        CheckMenuItem lookup = new CheckMenuItem("Show Meaning on Selection");
        lookup.selectedProperty().bindBidirectional(ctx.lookupOnSelect);
        MenuItem location = item("Library Location…", null, () -> {
            showLibrary();
            library.chooseLibraryFolder();
        });
        MenuItem config = item("Open Settings Folder", null, () -> {
            settings.saveIfDirty();
            getHostServices().showDocument(settings.configDir().toUri().toString());
        });
        Menu tools = new Menu("Tools", null, dictionaries, lookup, new SeparatorMenuItem(), location, config);

        MenuBar bar = new MenuBar(file, view, go, tools);
        bar.setUseSystemMenuBar(true); // native menu bar on macOS
        return bar;
    }

    private void onBook(Consumer<BookView> action) {
        if (current != null) {
            action.accept(current);
        }
    }

    private static MenuItem item(String text, String accelerator, Runnable action) {
        MenuItem item = new MenuItem(text);
        if (accelerator != null) {
            item.setAccelerator(KeyCombination.keyCombination(accelerator));
        }
        item.setOnAction(e -> action.run());
        return item;
    }

    private static <T> Menu radioMenu(String title, T[] values, ObjectProperty<T> property) {
        Menu menu = new Menu(title);
        ToggleGroup group = new ToggleGroup();
        for (T value : values) {
            RadioMenuItem item = new RadioMenuItem(value.toString());
            item.setToggleGroup(group);
            item.setSelected(value == property.get());
            item.setOnAction(e -> property.set(value));
            property.addListener((obs, old, now) -> item.setSelected(now == value));
            menu.getItems().add(item);
        }
        return menu;
    }

    private void refreshRecent() {
        List<Path> recent = settings.recentFiles();
        recentMenu.getItems().clear();
        for (Path path : recent) {
            recentMenu.getItems().add(item(path.getFileName().toString(), null, () -> open(path)));
        }
        if (recent.isEmpty()) {
            MenuItem none = new MenuItem("No recent books");
            none.setDisable(true);
            recentMenu.getItems().add(none);
        } else {
            recentMenu.getItems().addAll(new SeparatorMenuItem(), item("Clear Recent", null, () -> {
                settings.clearRecent();
                refreshRecent();
            }));
        }
    }

    private void applyChrome() {
        scene.getStylesheets().remove(DARK_CHROME_CSS);
        if (ctx.theme.get().dark) {
            scene.getStylesheets().add(DARK_CHROME_CSS);
        }
    }

    private void installDragAndDrop() {
        scene.setOnDragOver(e -> {
            if (!books(e.getDragboard().getFiles()).isEmpty()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            List<Path> books = books(e.getDragboard().getFiles());
            if (current == null) {
                library.addBooks(books); // dropped on the library: add them
            } else if (!books.isEmpty()) {
                open(books.getFirst()); // dropped on a book: just read it
            }
            e.setDropCompleted(!books.isEmpty());
            e.consume();
        });
    }

    private static List<Path> books(List<File> files) {
        return files == null ? List.of() : files.stream().map(File::toPath)
                .filter(p -> Files.isRegularFile(p) && BookFormat.of(p) != null).toList();
    }

    // ---------------------------------------------------------------- opening and closing books

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Book");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Books (*.epub, *.pdf)", "*.epub", "*.pdf"));
        List<Path> recent = settings.recentFiles();
        if (!recent.isEmpty() && recent.getFirst().getParent() != null) {
            chooser.setInitialDirectory(recent.getFirst().getParent().toFile());
        }
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            open(file.toPath());
        }
    }

    private void open(Path file) {
        BookFormat format = BookFormat.of(file);
        if (format == null) {
            return;
        }
        Task<Object> task = new Task<>() {
            @Override
            protected Object call() throws Exception {
                return format == BookFormat.EPUB ? EpubParser.open(file) : PdfView.load(file);
            }
        };
        scene.setCursor(Cursor.WAIT);
        task.setOnSucceeded(e -> {
            scene.setCursor(Cursor.DEFAULT);
            closeBook();
            current = switch (task.getValue()) {
                case com.epubreader.epub.EpubBook book -> new EpubView(ctx, book, this::showLibrary);
                case PdfView.Loaded pdf -> new PdfView(ctx, pdf, this::showLibrary);
                default -> throw new IllegalStateException();
            };
            content.getChildren().setAll(current.node());
            stage.setTitle(current.title());
            noBook.set(false);
            settings.addRecent(file);
            refreshRecent();
        });
        task.setOnFailed(e -> {
            scene.setCursor(Cursor.DEFAULT);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.initOwner(stage);
            alert.setHeaderText("Could not open " + file.getFileName());
            alert.setContentText(String.valueOf(task.getException().getMessage()));
            alert.showAndWait();
        });
        Thread.ofVirtual().name("book-open").start(task);
    }

    private void showLibrary() {
        closeBook();
        if (!content.getChildren().contains(library.node())) {
            content.getChildren().setAll(library.node());
        }
        library.refreshProgress();
        stage.setTitle(APP_TITLE);
    }

    private void closeBook() {
        if (current != null) {
            current.close();
            current = null;
            noBook.set(true);
        }
    }
}
