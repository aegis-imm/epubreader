package com.epubreader.ui;

import javafx.beans.InvalidationListener;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Toolbar, status bar and table-of-contents pieces shared by the EPUB and PDF views. */
final class ViewChrome {

    private ViewChrome() {}

    /** A status line with a progress bar on the right. */
    static final class StatusBar extends HBox {
        private final Label label = new Label();
        private final ProgressBar bar = new ProgressBar(0);

        StatusBar() {
            super(10);
            bar.setPrefWidth(180);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            getChildren().addAll(label, spacer, bar);
            setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(4, 10, 4, 10));
        }

        void set(String text, double progress) {
            label.setText(text);
            bar.setProgress(progress);
            bar.setVisible(progress >= 0);
        }
    }

    static ToolBar toolBar(Runnable onLibrary, AppContext ctx, Node... items) {
        Button library = new Button("‹ Library");
        library.setTooltip(new Tooltip("Back to the library (Ctrl/Cmd+L)"));
        library.setOnAction(e -> onLibrary.run());
        ToggleButton contents = new ToggleButton("Contents");
        contents.selectedProperty().bindBidirectional(ctx.tocVisible);
        contents.setTooltip(new Tooltip("Show or hide the table of contents"));

        List<Node> all = new ArrayList<>(List.of(library, contents, new Separator()));
        for (Node item : items) {
            all.add(item);
            all.add(new Separator());
        }
        all.removeLast();
        return new ToolBar(all.toArray(Node[]::new));
    }

    static HBox zoomControls(BookView view, Label value) {
        Button smaller = new Button("A−");
        smaller.setOnAction(e -> view.zoomOut());
        smaller.setTooltip(new Tooltip("Smaller"));
        Button bigger = new Button("A+");
        bigger.setOnAction(e -> view.zoomIn());
        bigger.setTooltip(new Tooltip("Larger"));
        value.setMinWidth(42);
        value.setAlignment(Pos.CENTER);
        HBox box = new HBox(4, smaller, value, bigger);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    static ChoiceBox<ReaderTheme> themeChoice(AppContext ctx) {
        ChoiceBox<ReaderTheme> box = new ChoiceBox<>();
        box.getItems().setAll(ReaderTheme.values());
        box.valueProperty().bindBidirectional(ctx.theme);
        box.setTooltip(new Tooltip("Colours"));
        return box;
    }

    static <T> TreeView<T> tocTree(Function<T, String> label, Consumer<T> onOpen) {
        TreeView<T> tree = new TreeView<>();
        tree.setShowRoot(false);
        tree.setMinWidth(160);
        tree.setPrefWidth(280);
        tree.setCellFactory(tv -> {
            TreeCell<T> cell = new TreeCell<>() {
                @Override
                protected void updateItem(T entry, boolean empty) {
                    super.updateItem(entry, empty);
                    setText(empty || entry == null ? null : label.apply(entry));
                }
            };
            cell.setOnMouseClicked(e -> {
                Node arrow = cell.getDisclosureNode();
                boolean onArrow = arrow != null && arrow.isVisible()
                        && arrow.getBoundsInParent().contains(e.getX(), e.getY());
                if (!cell.isEmpty() && e.getButton() == MouseButton.PRIMARY && !onArrow) {
                    onOpen.accept(cell.getItem());
                }
            });
            return cell;
        });
        tree.setOnKeyPressed(e -> {
            TreeItem<T> selected = tree.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && selected != null) {
                onOpen.accept(selected.getValue());
            }
        });
        return tree;
    }

    /** Shows the panel in the split pane while {@code visible} is true. Returns a call that unbinds it. */
    static Runnable bindToc(SplitPane split, Node tree, BooleanProperty visible) {
        SplitPane.setResizableWithParent(tree, false);
        InvalidationListener update = o -> {
            boolean shown = split.getItems().contains(tree);
            if (visible.get() && !shown) {
                split.getItems().addFirst(tree);
                split.setDividerPositions(0.25);
            } else if (!visible.get() && shown) {
                split.getItems().remove(tree);
            }
        };
        visible.addListener(update);
        update.invalidated(visible);
        return () -> visible.removeListener(update);
    }

    /** Selects (and reveals) the first item matching {@code test}, unless the selection already matches. */
    static <T> void selectTocItem(TreeView<T> tree, Predicate<T> test) {
        TreeItem<T> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && test.test(selected.getValue())) {
            return;
        }
        TreeItem<T> match = find(tree.getRoot(), test);
        if (match == null) {
            tree.getSelectionModel().clearSelection();
            return;
        }
        for (TreeItem<T> p = match.getParent(); p != null; p = p.getParent()) {
            p.setExpanded(true);
        }
        tree.getSelectionModel().select(match);
        int row = tree.getRow(match);
        if (row >= 0) {
            tree.scrollTo(Math.max(0, row - 3));
        }
    }

    private static <T> TreeItem<T> find(TreeItem<T> node, Predicate<T> test) {
        if (node == null) {
            return null;
        }
        for (TreeItem<T> child : node.getChildren()) {
            if (test.test(child.getValue())) {
                return child;
            }
            TreeItem<T> found = find(child, test);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
