package com.epubreader.ui;

import com.epubreader.dict.Dictionary;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;

/** Lets the user import and remove dictionaries used by the word popup. */
final class DictionariesDialog {

    private DictionariesDialog() {}

    static void show(AppContext ctx) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(ctx.window());
        dialog.setTitle("Dictionaries");
        dialog.setHeaderText("Select a word while reading (or double-click it in a PDF) to see its meaning.");

        ListView<Dictionary> list = new ListView<>();
        list.setPrefSize(520, 240);
        list.setPlaceholder(new Label("No dictionaries yet. Click \"Add…\" to import one."));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Dictionary d, boolean empty) {
                super.updateItem(d, empty);
                setText(empty || d == null ? null
                        : d.name() + "   (" + String.format("%,d", d.size()) + " words, "
                        + (d.file().getFileName().toString().toLowerCase().endsWith(".ifo") ? "StarDict" : "word list")
                        + ")");
            }
        });
        list.getItems().setAll(ctx.dictionaries.dictionaries());

        Label busy = new Label();
        Button add = new Button("Add…");
        Button remove = new Button("Remove");
        remove.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());

        add.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Import Dictionary");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Dictionaries (*.ifo, *.zip, *.tsv, *.txt, *.csv)",
                            "*.ifo", "*.zip", "*.tsv", "*.txt", "*.csv"),
                    new FileChooser.ExtensionFilter("StarDict (*.ifo)", "*.ifo"),
                    new FileChooser.ExtensionFilter("Word list (*.tsv, *.txt, *.csv)", "*.tsv", "*.txt", "*.csv"),
                    new FileChooser.ExtensionFilter("Zip archive (*.zip)", "*.zip"));
            File file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (file == null) {
                return;
            }
            busy.setText("Loading " + file.getName() + "…");
            add.setDisable(true);
            Task<List<Dictionary>> task = new Task<>() {
                @Override
                protected List<Dictionary> call() throws Exception {
                    return ctx.dictionaries.importFile(file.toPath());
                }
            };
            task.setOnSucceeded(ev -> {
                busy.setText("");
                add.setDisable(false);
                list.getItems().setAll(ctx.dictionaries.dictionaries());
                save(ctx);
            });
            task.setOnFailed(ev -> {
                busy.setText("");
                add.setDisable(false);
                error(dialog, "Could not import " + file.getName(), task.getException().getMessage());
            });
            Thread.ofVirtual().name("dictionary-import").start(task);
        });

        remove.setOnAction(e -> {
            Dictionary d = list.getSelectionModel().getSelectedItem();
            try {
                ctx.dictionaries.remove(d);
            } catch (Exception ex) {
                error(dialog, "Could not delete the dictionary files", ex.getMessage());
            }
            list.getItems().setAll(ctx.dictionaries.dictionaries());
            save(ctx);
        });

        CheckBox onSelect = new CheckBox("Show meanings when I select a word");
        onSelect.selectedProperty().bindBidirectional(ctx.lookupOnSelect);

        Label formats = new Label("""
                Supported: StarDict (.ifo with its .idx and .dict/.dict.dz files, or a .zip of them) \
                and word lists with one "word<TAB>meaning" per line (.tsv/.txt) or "word,meaning" (.csv). \
                Imported dictionaries are copied to:
                """ + ctx.dictionaries.storeDir());
        formats.setWrapText(true);
        formats.setMaxWidth(520);
        formats.setStyle("-fx-opacity: 0.75; -fx-font-size: 11px;");

        HBox buttons = new HBox(8, add, remove, busy);
        VBox right = new VBox(10, list, buttons, onSelect, formats);
        BorderPane content = new BorderPane(right);
        content.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private static void save(AppContext ctx) {
        ctx.settings.setDictionaries(ctx.dictionaries.dictionaries().stream().map(Dictionary::file).toList());
        ctx.settings.saveIfDirty();
    }

    private static void error(Dialog<?> owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.initOwner(owner.getDialogPane().getScene().getWindow());
        alert.setHeaderText(header);
        alert.showAndWait();
    }
}
