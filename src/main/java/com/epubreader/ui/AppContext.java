package com.epubreader.ui;

import com.epubreader.dict.DictionaryManager;
import javafx.application.HostServices;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.stage.Window;

/** State shared between the library and the book views. Property changes are saved to settings. */
final class AppContext {

    final ReaderSettings settings;
    final DictionaryManager dictionaries;
    final HostServices hostServices;
    final DictionaryPopup popup;

    final ObjectProperty<ReaderTheme> theme;
    final ObjectProperty<ReaderFont> font;
    final StringProperty installedFont;
    final IntegerProperty fontPercent;
    final IntegerProperty pdfZoom;
    final BooleanProperty tocVisible;
    final BooleanProperty lookupOnSelect;

    private Window window;

    AppContext(ReaderSettings settings, DictionaryManager dictionaries, HostServices hostServices) {
        this.settings = settings;
        this.dictionaries = dictionaries;
        this.hostServices = hostServices;
        this.popup = new DictionaryPopup(this);

        theme = new SimpleObjectProperty<>(settings.theme());
        font = new SimpleObjectProperty<>(settings.font());
        installedFont = new SimpleStringProperty(settings.installedFont());
        fontPercent = new SimpleIntegerProperty(settings.fontPercent());
        pdfZoom = new SimpleIntegerProperty(settings.pdfZoom());
        tocVisible = new SimpleBooleanProperty(settings.tocVisible());
        lookupOnSelect = new SimpleBooleanProperty(settings.lookupOnSelect());

        theme.addListener((o, a, v) -> settings.setTheme(v));
        font.addListener((o, a, v) -> settings.setFont(v));
        installedFont.addListener((o, a, v) -> settings.setInstalledFont(v));
        fontPercent.addListener((o, a, v) -> settings.setFontPercent(v.intValue()));
        pdfZoom.addListener((o, a, v) -> settings.setPdfZoom(v.intValue()));
        tocVisible.addListener((o, a, v) -> settings.setTocVisible(v));
        lookupOnSelect.addListener((o, a, v) -> settings.setLookupOnSelect(v));
    }

    /** CSS font-family for book text, or null to keep the book's own fonts. */
    String fontCss() {
        return font.get().css(installedFont.get());
    }

    Window window() {
        return window;
    }

    void setWindow(Window window) {
        this.window = window;
    }
}
