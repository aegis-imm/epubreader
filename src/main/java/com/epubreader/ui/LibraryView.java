package com.epubreader.ui;

import com.epubreader.library.BookFormat;
import com.epubreader.library.BookInfo;
import com.epubreader.library.Library;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.TextAlignment;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** The library screen: books as large or medium cover grids, or a details table without covers. */
final class LibraryView {

    private enum Sort {
        TITLE("Title", Comparator.comparing(b -> b.title().toLowerCase(Locale.ROOT))),
        AUTHOR("Author", Comparator.<BookInfo, String>comparing(b -> b.author().toLowerCase(Locale.ROOT))
                .thenComparing(b -> b.title().toLowerCase(Locale.ROOT))),
        ADDED("Recently added", Comparator.comparing(BookInfo::added).reversed());

        final String label;
        final Comparator<BookInfo> comparator;

        Sort(String label, Comparator<BookInfo> comparator) {
            this.label = label;
            this.comparator = comparator;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final Color[] PLACEHOLDER_COLOURS = {
            Color.web("#5b7db1"), Color.web("#8a5a9e"), Color.web("#3f8f7a"), Color.web("#b0644a"),
            Color.web("#6d7f3b"), Color.web("#a14d6b"), Color.web("#4b6b8f"), Color.web("#8f7a3f")};
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault());

    private final AppContext ctx;
    private final Consumer<Path> openBook;
    private final ObservableList<BookInfo> books = FXCollections.observableArrayList();
    private final FilteredList<BookInfo> filtered = new FilteredList<>(books);
    private final SortedList<BookInfo> gridOrder = new SortedList<>(filtered);
    private final ObjectProperty<LibraryLayout> layout;
    private final ObjectProperty<BookInfo> selected = new SimpleObjectProperty<>();

    private final BorderPane root = new BorderPane();
    private final TilePane tiles = new TilePane();
    private final ScrollPane grid = new ScrollPane(tiles);
    private final TableView<BookInfo> table = new TableView<>();
    private final VBox empty = new VBox(12);
    private final Label emptyFolder = new Label();
    private final Label statusLabel = new Label();
    private final Label folderLabel = new Label();
    private final ChoiceBox<Sort> sort = new ChoiceBox<>();

    private Library library;

    LibraryView(AppContext ctx, Consumer<Path> openBook) {
        this.ctx = ctx;
        this.openBook = openBook;
        this.layout = new SimpleObjectProperty<>(ctx.settings.libraryLayout());

        tiles.setHgap(18);
        tiles.setVgap(22);
        tiles.setPadding(new Insets(20));
        tiles.setAlignment(Pos.TOP_LEFT);
        grid.setFitToWidth(true);
        grid.setOnMouseClicked(e -> {
            if (e.getTarget() == tiles) {
                select(null);
            }
        });
        grid.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && selected.get() != null) {
                openBook.accept(selected.get().file());
            }
        });

        buildTable();
        buildEmptyState();

        StackPane centre = new StackPane(grid, table, empty);
        root.setTop(buildToolBar());
        root.setCenter(centre);
        root.setBottom(buildStatusBar());

        filtered.addListener((javafx.collections.ListChangeListener<BookInfo>) c -> updateVisibility());
        gridOrder.addListener((javafx.collections.ListChangeListener<BookInfo>) c -> rebuildTiles());
        layout.addListener((o, a, v) -> {
            ctx.settings.setLibraryLayout(v);
            rebuildTiles();
            updateVisibility();
        });
        selected.addListener((o, a, v) -> highlightSelection());
        updateVisibility();
    }

    Node node() {
        return root;
    }

    ObjectProperty<LibraryLayout> layoutProperty() {
        return layout;
    }

    Library library() {
        return library;
    }

    void setLibrary(Library library) {
        this.library = library;
        folderLabel.setText(library.dir().toString());
        emptyFolder.setText("Library folder: " + library.dir());
        rescan();
    }

    /** Redraws progress indicators (for example after closing a book). */
    void refreshProgress() {
        rebuildTiles();
        table.refresh();
    }

    // ---------------------------------------------------------------- toolbar and status

    private ToolBar buildToolBar() {
        Button add = new Button("Add Books…");
        add.setOnAction(e -> chooseBooks());

        TextField search = new TextField();
        search.setPromptText("Search title or author");
        search.setPrefColumnCount(22);
        search.textProperty().addListener((o, a, text) -> {
            String q = text.strip().toLowerCase(Locale.ROOT);
            filtered.setPredicate(q.isEmpty() ? null : b -> b.title().toLowerCase(Locale.ROOT).contains(q)
                    || b.author().toLowerCase(Locale.ROOT).contains(q));
        });

        sort.getItems().setAll(Sort.values());
        sort.setValue(Sort.TITLE);
        sort.setTooltip(new Tooltip("Sort order for the icon views"));
        gridOrder.comparatorProperty().bind(sort.valueProperty().map(s -> s.comparator));
        sort.disableProperty().bind(layout.isEqualTo(LibraryLayout.DETAILS));

        ToggleGroup group = new ToggleGroup();
        HBox layouts = new HBox();
        for (LibraryLayout l : LibraryLayout.values()) {
            ToggleButton b = new ToggleButton(l.label);
            b.setToggleGroup(group);
            b.setSelected(layout.get() == l);
            b.setOnAction(e -> {
                layout.set(l);
                b.setSelected(true); // keep one layout selected
            });
            layout.addListener((o, a, v) -> b.setSelected(v == l));
            layouts.getChildren().add(b);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return new ToolBar(add, new javafx.scene.control.Separator(), search, new Label("Sort:"), sort,
                spacer, layouts);
    }

    private HBox buildStatusBar() {
        Hyperlink change = new Hyperlink("Change location…");
        change.setOnAction(e -> chooseLibraryFolder());
        Hyperlink open = new Hyperlink("Open folder");
        open.setOnAction(e -> ctx.hostServices.showDocument(library.dir().toUri().toString()));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        folderLabel.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        folderLabel.setMinWidth(80);
        HBox bar = new HBox(8, statusLabel, spacer, new Label("Library:"), folderLabel, open, change);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(2, 10, 2, 10));
        return bar;
    }

    private void buildEmptyState() {
        Label title = new Label("Your library is empty");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label hint = new Label("Add EPUB or PDF books, or drop them anywhere in this window.");
        Button add = new Button("Add Books…");
        add.setDefaultButton(true);
        add.setOnAction(e -> chooseBooks());
        emptyFolder.setStyle("-fx-opacity: 0.7;");
        empty.getChildren().addAll(title, hint, add, emptyFolder);
        empty.setAlignment(Pos.CENTER);
        empty.setStyle("-fx-background-color: -fx-background;");
    }

    private void updateVisibility() {
        boolean none = books.isEmpty();
        boolean details = layout.get() == LibraryLayout.DETAILS;
        empty.setVisible(none);
        grid.setVisible(!none && !details);
        table.setVisible(!none && details);
        int shown = filtered.size();
        statusLabel.setText(shown == books.size()
                ? books.size() + (books.size() == 1 ? " book" : " books")
                : shown + " of " + books.size() + " books");
    }

    // ---------------------------------------------------------------- grid

    private void rebuildTiles() {
        LibraryLayout l = layout.get();
        if (l == LibraryLayout.DETAILS) {
            tiles.getChildren().clear();
            return;
        }
        tiles.setPrefTileWidth(l.coverWidth);
        tiles.getChildren().setAll(gridOrder.stream().map(b -> tile(b, l.coverWidth)).toList());
        highlightSelection();
    }

    private VBox tile(BookInfo book, int coverWidth) {
        double coverHeight = coverWidth * 1.5;
        StackPane cover = new StackPane();
        cover.setMinSize(coverWidth, coverHeight);
        cover.setMaxSize(coverWidth, coverHeight);

        Rectangle backdrop = new Rectangle(coverWidth, coverHeight,
                PLACEHOLDER_COLOURS[Math.floorMod(book.title().hashCode(), PLACEHOLDER_COLOURS.length)]);
        backdrop.setArcWidth(6);
        backdrop.setArcHeight(6);
        cover.getChildren().add(backdrop);
        if (book.cover() != null) {
            ImageView image = new ImageView(new Image(book.cover().toUri().toString(),
                    coverWidth * 2.0, coverHeight * 2.0, true, true, true));
            image.setFitWidth(coverWidth);
            image.setFitHeight(coverHeight);
            image.setPreserveRatio(true);
            image.setSmooth(true);
            cover.getChildren().add(image);
            backdrop.setFill(Color.gray(0.5, 0.25));
        } else {
            Label text = new Label(book.title());
            text.setWrapText(true);
            text.setTextAlignment(TextAlignment.CENTER);
            text.setTextFill(Color.WHITE);
            text.setStyle("-fx-font-size: " + (coverWidth > 140 ? 15 : 12) + "px; -fx-font-weight: bold;");
            text.setMaxWidth(coverWidth - 16);
            cover.getChildren().add(text);
        }
        Label badge = new Label(book.format().name());
        badge.setStyle("-fx-background-color: rgba(0,0,0,0.6); -fx-text-fill: white; -fx-font-size: 10px;"
                + " -fx-padding: 1 5 1 5; -fx-background-radius: 3;");
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);
        StackPane.setMargin(badge, new Insets(5));
        cover.getChildren().add(badge);

        double progress = ctx.settings.position(book.file()).progress();
        if (progress > 0.005) {
            ProgressBar bar = new ProgressBar(progress);
            bar.setMaxWidth(coverWidth - 10);
            bar.setPrefHeight(8);
            bar.setStyle("-fx-padding: 0; -fx-accent: #3d6fb4;");
            StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
            StackPane.setMargin(bar, new Insets(0, 0, 5, 0));
            cover.getChildren().add(bar);
        }

        Label title = new Label(book.title());
        title.setMaxWidth(coverWidth);
        title.setStyle("-fx-font-weight: bold;");
        Label author = new Label(book.author().isBlank() ? " " : book.author());
        author.setMaxWidth(coverWidth);
        author.setStyle("-fx-opacity: 0.7; -fx-font-size: 11px;");

        VBox tile = new VBox(5, cover, title, author);
        tile.setPadding(new Insets(6));
        tile.setUserData(book);
        Tooltip.install(tile, new Tooltip(book.title() + (book.author().isBlank() ? "" : "\n" + book.author())));
        tile.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                select(book);
                grid.requestFocus();
                if (e.getClickCount() == 2) {
                    openBook.accept(book.file());
                }
            }
        });
        tile.setOnContextMenuRequested(e -> {
            select(book);
            contextMenu(book).show(tile, e.getScreenX(), e.getScreenY());
        });
        return tile;
    }

    private void select(BookInfo book) {
        selected.set(book);
    }

    private void highlightSelection() {
        for (Node n : tiles.getChildren()) {
            boolean on = n.getUserData() != null && n.getUserData().equals(selected.get());
            n.setStyle(on ? "-fx-background-color: rgba(61,111,180,0.30); -fx-background-radius: 8;" : "");
        }
    }

    // ---------------------------------------------------------------- details table

    private void buildTable() {
        TableColumn<BookInfo, String> title = column("Title", 320, b -> b.title());
        TableColumn<BookInfo, String> author = column("Author", 200, b -> b.author());
        TableColumn<BookInfo, String> format = column("Format", 70, b -> b.format().name());
        TableColumn<BookInfo, Long> size = new TableColumn<>("Size");
        size.setPrefWidth(90);
        size.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().size()));
        size.setCellFactory(c -> textCell(LibraryView::formatSize));
        TableColumn<BookInfo, java.time.Instant> added = new TableColumn<>("Added");
        added.setPrefWidth(120);
        added.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().added()));
        added.setCellFactory(c -> textCell(DATE::format));
        TableColumn<BookInfo, Double> progress = new TableColumn<>("Progress");
        progress.setPrefWidth(80);
        progress.setCellValueFactory(c -> new SimpleObjectProperty<>(
                ctx.settings.position(c.getValue().file()).progress()));
        progress.setCellFactory(c -> textCell(p -> p < 0.005 ? "" : Math.round(p * 100) + "%"));
        table.getColumns().setAll(List.of(title, author, format, size, added, progress));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No books match your search."));

        SortedList<BookInfo> tableOrder = new SortedList<>(filtered);
        tableOrder.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(tableOrder);
        table.getSortOrder().add(title);

        table.setRowFactory(tv -> {
            TableRow<BookInfo> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty() && e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    openBook.accept(row.getItem().file());
                }
            });
            row.setOnContextMenuRequested(e -> {
                if (!row.isEmpty()) {
                    contextMenu(row.getItem()).show(row, e.getScreenX(), e.getScreenY());
                }
            });
            return row;
        });
        table.setOnKeyPressed(e -> {
            BookInfo b = table.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && b != null) {
                openBook.accept(b.file());
            }
        });
    }

    private static TableColumn<BookInfo, String> column(String name, double width,
                                                        java.util.function.Function<BookInfo, String> value) {
        TableColumn<BookInfo, String> col = new TableColumn<>(name);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        return col;
    }

    private static <T> javafx.scene.control.TableCell<BookInfo, T> textCell(
            java.util.function.Function<T, String> format) {
        return new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(T item, boolean emptyCell) {
                super.updateItem(item, emptyCell);
                setText(emptyCell || item == null ? null : format.apply(item));
            }
        };
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private ContextMenu contextMenu(BookInfo book) {
        MenuItem open = new MenuItem("Open");
        open.setOnAction(e -> openBook.accept(book.file()));
        MenuItem show = new MenuItem("Show in Folder");
        show.setOnAction(e -> ctx.hostServices.showDocument(book.file().getParent().toUri().toString()));
        MenuItem remove = new MenuItem("Remove from Library…");
        remove.setOnAction(e -> confirmRemove(book));
        return new ContextMenu(open, show, new SeparatorMenuItem(), remove);
    }

    // ---------------------------------------------------------------- actions

    void chooseBooks() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add Books to Library");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Books (*.epub, *.pdf)", "*.epub", "*.pdf"),
                new FileChooser.ExtensionFilter("EPUB", "*.epub"),
                new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        List<File> files = chooser.showOpenMultipleDialog(ctx.window());
        if (files != null && !files.isEmpty()) {
            addBooks(files.stream().map(File::toPath).toList());
        }
    }

    /** Copies books into the library folder in the background, then rescans. */
    void addBooks(List<Path> files) {
        List<Path> supported = files.stream().filter(f -> BookFormat.of(f) != null).toList();
        if (supported.isEmpty() || library == null) {
            return;
        }
        Library lib = library;
        statusLabel.setText("Adding " + supported.size() + (supported.size() == 1 ? " book…" : " books…"));
        run(new Task<List<Path>>() {
            @Override
            protected List<Path> call() throws Exception {
                return lib.add(supported);
            }
        }, added -> rescan(), "Could not add books");
    }

    private void confirmRemove(BookInfo book) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "\"" + book.title() + "\" will be moved to the Recycle Bin / Trash.",
                ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(ctx.window());
        alert.setHeaderText("Remove this book from the library?");
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        Library lib = library;
        run(new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                lib.remove(book);
                return null;
            }
        }, ignored -> {
            ctx.settings.forgetPosition(book.file());
            rescan();
        }, "Could not remove the book");
    }

    void chooseLibraryFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose Library Folder");
        if (library != null && library.dir().toFile().isDirectory()) {
            chooser.setInitialDirectory(library.dir().toFile());
        }
        File dir = chooser.showDialog(ctx.window());
        if (dir != null) {
            ctx.settings.setLibraryDir(dir.toPath());
            ctx.settings.saveIfDirty();
            setLibrary(new Library(dir.toPath()));
        }
    }

    void rescan() {
        Library lib = library;
        Task<List<BookInfo>> scan = new Task<>() {
            @Override
            protected List<BookInfo> call() throws Exception {
                return lib.scan((done, total) -> updateMessage("Reading books… " + done + " of " + total));
            }
        };
        scan.messageProperty().addListener((o, a, m) -> statusLabel.setText(m));
        run(scan, result -> {
            if (lib == library) {
                BookInfo keep = selected.get();
                books.setAll(result);
                select(keep != null && result.contains(keep) ? keep : null);
                updateVisibility();
            }
        }, "Could not read the library folder");
    }

    private <T> void run(Task<T> task, Consumer<T> onSuccess, String errorTitle) {
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> {
            updateVisibility();
            Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(task.getException().getMessage()));
            alert.initOwner(ctx.window());
            alert.setHeaderText(errorTitle);
            alert.show();
        });
        Thread.ofVirtual().name("library").start(task);
    }
}
