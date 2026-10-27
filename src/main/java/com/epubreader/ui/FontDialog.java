package com.epubreader.ui;

import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.List;
import java.util.Locale;

/**
 * Picks the reading font: the publisher's, a generic serif or sans-serif, or any font installed on
 * this computer. Changes apply to the open book immediately; Cancel puts the previous font back.
 */
final class FontDialog {

    private static final String SAMPLE = """
            The quick brown fox jumps over the lazy dog. 0123456789
            ภาษาไทย: นกน้อยบินข้ามทุ่งนาเขียวขจียามเช้า""";

    private FontDialog() {}

    static void show(AppContext ctx) {
        ReaderFont oldFont = ctx.font.get();
        String oldInstalled = ctx.installedFont.get();
        int oldPercent = ctx.fontPercent.get();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(ctx.window());
        dialog.setTitle("Reading Font");
        dialog.setHeaderText("Choose the font for book text. Code samples keep their own font.");

        ToggleGroup group = new ToggleGroup();
        VBox choices = new VBox(6);
        RadioButton installedChoice = null;
        for (ReaderFont f : ReaderFont.values()) {
            RadioButton b = new RadioButton(f == ReaderFont.INSTALLED ? "Installed font:" : f.label);
            b.setUserData(f);
            b.setToggleGroup(group);
            b.setSelected(f == oldFont);
            choices.getChildren().add(b);
            if (f == ReaderFont.INSTALLED) {
                installedChoice = b;
            }
        }
        RadioButton installed = installedChoice;

        List<String> families = Font.getFamilies().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER).distinct().toList();
        FilteredList<String> shown = new FilteredList<>(FXCollections.observableArrayList(families));
        TextField search = new TextField();
        search.setPromptText("Search " + families.size() + " installed fonts");
        search.textProperty().addListener((o, a, text) -> {
            String q = text.strip().toLowerCase(Locale.ROOT);
            shown.setPredicate(q.isEmpty() ? null : name -> name.toLowerCase(Locale.ROOT).contains(q));
        });
        ListView<String> list = new ListView<>(shown);
        list.setPrefSize(300, 260);
        if (oldInstalled != null && families.contains(oldInstalled)) {
            list.getSelectionModel().select(oldInstalled);
            list.scrollTo(Math.max(0, families.indexOf(oldInstalled) - 3));
        }

        Slider size = new Slider(ReaderSettings.MIN_FONT, ReaderSettings.MAX_FONT, oldPercent);
        size.setMajorTickUnit(10);
        size.setMinorTickCount(0);
        size.setSnapToTicks(true);
        Label sizeValue = new Label(oldPercent + "%");
        sizeValue.setMinWidth(44);
        HBox sizeRow = new HBox(8, new Label("Size"), size, sizeValue);
        HBox.setHgrow(size, Priority.ALWAYS);

        Label preview = new Label(SAMPLE);
        preview.setWrapText(true);
        preview.setMinHeight(90);
        preview.setPadding(new Insets(10));
        preview.setMaxWidth(Double.MAX_VALUE);
        preview.setStyle("-fx-background-color: -fx-control-inner-background; -fx-border-color: -fx-box-border;");

        Runnable apply = () -> {
            ReaderFont choice = (ReaderFont) group.getSelectedToggle().getUserData();
            String family = list.getSelectionModel().getSelectedItem();
            if (choice == ReaderFont.INSTALLED && family == null) {
                family = ctx.installedFont.get();
            }
            if (choice == ReaderFont.INSTALLED && family != null) {
                ctx.installedFont.set(family);
            }
            ctx.font.set(choice);
            int percent = (int) Math.round(size.getValue());
            sizeValue.setText(percent + "%");
            ctx.fontPercent.set(percent);
            String previewFamily = switch (choice) {
                case ORIGINAL -> Font.getDefault().getFamily();
                case SERIF -> "Serif";
                case SANS -> "SansSerif";
                case INSTALLED -> family == null ? Font.getDefault().getFamily() : family;
            };
            preview.setFont(Font.font(previewFamily, 17 * percent / 100.0));
        };
        group.selectedToggleProperty().addListener((o, a, v) -> {
            if (v != null) {
                apply.run();
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, v) -> {
            if (v != null) {
                installed.setSelected(true); // picking a font means "use an installed font"
                apply.run();
            }
        });
        size.valueProperty().addListener((o, a, v) -> apply.run());
        apply.run();

        VBox left = new VBox(10, choices, search, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox content = new VBox(12, left, sizeRow, new Label("Preview"), preview);
        content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResizable(true);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            ctx.installedFont.set(oldInstalled);
            ctx.font.set(oldFont);
            ctx.fontPercent.set(oldPercent);
        }
    }
}
