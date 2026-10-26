package com.epubreader.ui;

import com.epubreader.dict.DictionaryManager;
import com.epubreader.dict.DictionaryManager.Result;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Window;

import java.util.List;

/** Floating panel that shows dictionary meanings for a selected word. */
final class DictionaryPopup {

    private static final double WIDTH = 400;
    private static final double MAX_WORDS = 4;

    private final AppContext ctx;
    private final Popup popup = new Popup();
    private final Label wordLabel = new Label();
    private final WebView content = new WebView();
    private final VBox box;

    DictionaryPopup(AppContext ctx) {
        this.ctx = ctx;
        content.getEngine().setJavaScriptEnabled(false); // dictionary HTML is untrusted
        content.setContextMenuEnabled(true);
        content.setPrefWidth(WIDTH);

        wordLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Button close = new Button("✕");
        close.setFocusTraversable(false);
        close.setStyle("-fx-background-color: transparent; -fx-padding: 0 4 0 4;");
        close.setOnAction(e -> popup.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, wordLabel, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        box = new VBox(6, header, content);
        box.setPadding(new Insets(10, 12, 12, 12));
        box.setPrefWidth(WIDTH + 24);
        popup.getContent().add(box);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
    }

    /** Returns true if {@code selection} looks like a word or short phrase worth looking up. */
    static boolean isLookupCandidate(String selection) {
        String word = DictionaryManager.normalize(selection);
        return !word.isEmpty() && word.length() <= 60 && word.split(" ").length <= MAX_WORDS
                && word.codePoints().anyMatch(Character::isLetter);
    }

    void show(Window owner, double screenX, double screenY, String selection) {
        String word = DictionaryManager.normalize(selection);
        if (word.isEmpty()) {
            return;
        }
        List<Result> results = ctx.dictionaries.lookup(word);
        ReaderTheme t = ctx.theme.get();
        wordLabel.setText(word);
        wordLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + t.foreground + ";");
        box.setStyle("-fx-background-color: " + t.background + "; -fx-background-radius: 8; -fx-border-radius: 8;"
                + " -fx-border-color: " + (t.dark ? "#555" : "#c8c8c8") + ";"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 14, 0, 0, 4);");
        content.setPageFill(javafx.scene.paint.Color.web(t.background));
        content.setPrefHeight(results.isEmpty() ? 64 : Math.min(320, 40 + 52.0 * results.size()));
        content.getEngine().loadContent(html(word, results, t));

        double height = content.getPrefHeight() + 60;
        Rectangle2D screen = Screen.getScreensForRectangle(screenX, screenY, 1, 1).stream().findFirst()
                .orElse(Screen.getPrimary()).getVisualBounds();
        double x = Math.min(screenX + 8, screen.getMaxX() - WIDTH - 40);
        double y = screenY + 16 + height > screen.getMaxY() ? screenY - height - 16 : screenY + 16;
        popup.show(owner, Math.max(screen.getMinX(), x), Math.max(screen.getMinY(), y));
    }

    void hide() {
        popup.hide();
    }

    private String html(String word, List<Result> results, ReaderTheme t) {
        StringBuilder body = new StringBuilder();
        if (results.isEmpty()) {
            String message = ctx.dictionaries.dictionaries().isEmpty()
                    ? "No dictionaries yet. Add one from <b>Tools → Dictionaries…</b>"
                    : "No meaning found for “" + escape(word) + "”.";
            body.append("<p class=muted>").append(message).append("</p>");
        }
        for (Result r : results) {
            body.append("<div class=entry><div class=hw>").append(escape(r.definition().headword()))
                    .append(" <span class=src>").append(escape(r.dictionary())).append("</span></div>")
                    .append("<div class=def>").append(r.definition().html()).append("</div></div>");
        }
        return """
                <html><head><meta charset="utf-8"><style>
                body { font-family: 'Segoe UI', 'Helvetica Neue', sans-serif; font-size: 14px; margin: 0;
                       color: %s; background: %s; line-height: 1.45; }
                a { color: %s; }
                .entry { padding: 4px 0 8px; border-bottom: 1px solid %s; }
                .entry:last-child { border-bottom: none; }
                .hw { font-weight: 600; margin-bottom: 2px; }
                .src { font-weight: normal; font-size: 11px; opacity: .6; margin-left: 6px; }
                .muted { opacity: .75; margin: 4px 0; }
                .ex { font-style: italic; opacity: .85; }
                </style></head><body>%s</body></html>
                """.formatted(t.foreground, t.background, t.link, t.dark ? "#3a3a3a" : "#e4e4e4", body);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
