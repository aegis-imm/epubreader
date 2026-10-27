package com.epubreader.ui;

import com.epubreader.epub.EpubBook;
import com.epubreader.epub.EpubBook.TocEntry;
import com.epubreader.ui.ReaderSettings.Position;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Reads an EPUB: chapters in a WebView with a table of contents beside it. */
final class EpubView implements BookView {

    private static final String MAX_SCROLL_JS =
            "Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)"
                    + " - window.innerHeight";

    private final AppContext ctx;
    private final EpubBook book;
    private final BorderPane root = new BorderPane();
    private final WebView webView = new WebView();
    private final WebEngine engine = webView.getEngine();
    private final TreeView<TocEntry> tocTree;
    private final SplitPane split;
    private final ViewChrome.StatusBar status = new ViewChrome.StatusBar();
    private final Timeline poller;
    private final InvalidationListener appearance = o -> applyAppearance();
    private final Runnable unbindToc;

    private int chapter = -1;
    /** Scroll fraction to apply once the loading chapter finishes, or -1 for none. */
    private double pendingFraction = -1;
    private double lastFraction;
    private boolean closed;

    EpubView(AppContext ctx, EpubBook book, Runnable onLibrary) {
        this.ctx = ctx;
        this.book = book;

        engine.locationProperty().addListener((obs, old, location) -> onLocationChanged(location));
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                onChapterLoaded();
            }
        });
        webView.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        webView.setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.PRIMARY && ctx.lookupOnSelect.get()) {
                Object selection = engine.executeScript("window.getSelection().toString()");
                if (selection instanceof String s && DictionaryPopup.isLookupCandidate(s)) {
                    ctx.popup.show(webView.getScene().getWindow(), e.getScreenX(), e.getScreenY(), s);
                }
            }
        });

        tocTree = ViewChrome.tocTree(TocEntry::label, entry -> navigateTo(entry.target()));
        TreeItem<TocEntry> tocRoot = new TreeItem<>();
        tocRoot.getChildren().setAll(treeItems(book.toc()));
        tocTree.setRoot(tocRoot);
        split = new SplitPane(webView);
        unbindToc = ViewChrome.bindToc(split, tocTree, ctx.tocVisible);

        Button prev = new Button("‹ Previous");
        prev.setOnAction(e -> previousSection());
        Button next = new Button("Next ›");
        next.setOnAction(e -> nextSection());
        Label size = new Label();
        size.textProperty().bind(ctx.fontPercent.asString("%d%%"));
        root.setTop(ViewChrome.toolBar(onLibrary, ctx, prev, next, ViewChrome.zoomControls(this, size),
                ViewChrome.themeChoice(ctx)));
        root.setCenter(split);
        root.setBottom(status);

        ctx.theme.addListener(appearance);
        ctx.font.addListener(appearance);
        ctx.fontPercent.addListener(appearance);
        applyAppearance();

        // Track scrolling for the progress bar and to remember the reading position.
        poller = new Timeline(new KeyFrame(Duration.millis(500), e -> pollScroll()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();

        Position pos = ctx.settings.position(book.source());
        int index = Math.clamp(pos.index(), 0, book.spine().size() - 1);
        lastFraction = pos.fraction();
        loadChapter(index, pos.fraction());
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return book.author().isBlank() ? book.title() : book.title() + " — " + book.author();
    }

    @Override
    public void nextSection() {
        loadChapter(chapter + 1, 0);
    }

    @Override
    public void previousSection() {
        loadChapter(chapter - 1, 0);
    }

    @Override
    public void goToStart() {
        loadChapter(0, 0);
    }

    @Override
    public void zoomIn() {
        ctx.fontPercent.set(Math.min(ReaderSettings.MAX_FONT, ctx.fontPercent.get() + 10));
    }

    @Override
    public void zoomOut() {
        ctx.fontPercent.set(Math.max(ReaderSettings.MIN_FONT, ctx.fontPercent.get() - 10));
    }

    @Override
    public void zoomReset() {
        ctx.fontPercent.set(100);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        savePosition();
        poller.stop();
        unbindToc.run();
        ctx.theme.removeListener(appearance);
        ctx.font.removeListener(appearance);
        ctx.fontPercent.removeListener(appearance);
        ctx.popup.hide();
        engine.load("about:blank");
        book.close();
    }

    // ---------------------------------------------------------------- appearance

    private void applyAppearance() {
        ReaderTheme t = ctx.theme.get();
        webView.setFontScale(ctx.fontPercent.get() / 100.0);
        webView.setPageFill(Color.web(t.background));
        String css = Base64.getEncoder().encodeToString(bookCss(t, ctx.font.get()).getBytes(StandardCharsets.UTF_8));
        engine.setUserStyleSheetLocation("data:text/css;charset=utf-8;base64," + css);
    }

    /** User stylesheet laid over every chapter; user {@code !important} rules beat the book's own. */
    private static String bookCss(ReaderTheme t, ReaderFont f) {
        StringBuilder css = new StringBuilder();
        css.append("html, body { background-color: %s !important; }\n".formatted(t.background));
        css.append("""
                body {
                    color: %s !important;
                    max-width: 44em;
                    margin: 0 auto !important;
                    padding: 2.5em 2.5em 5em !important;
                    line-height: 1.6;
                    box-sizing: border-box;
                    word-wrap: break-word;
                }
                img { max-width: 100%% !important; height: auto !important; }
                svg, video, table { max-width: 100%% !important; }
                """.formatted(t.foreground));
        if (t != ReaderTheme.LIGHT) {
            // Books often hard-code black text or white boxes; neutralise them for tinted themes.
            css.append("body * { color: inherit !important; background-color: transparent !important; }\n");
        }
        css.append("body a, body a:visited { color: %s !important; }\n".formatted(t.link));
        if (f.cssFamily != null) {
            css.append("body, body * { font-family: %s !important; }\n".formatted(f.cssFamily));
        }
        return css.toString();
    }

    private static List<TreeItem<TocEntry>> treeItems(List<TocEntry> entries) {
        return entries.stream().map(entry -> {
            TreeItem<TocEntry> item = new TreeItem<>(entry);
            item.getChildren().setAll(treeItems(entry.children()));
            return item;
        }).toList();
    }

    // ---------------------------------------------------------------- navigation

    private void loadChapter(int index, double fraction) {
        if (closed || index < 0 || index >= book.spine().size()) {
            return;
        }
        ctx.popup.hide();
        pendingFraction = fraction;
        engine.load(book.spine().get(index).toUri().toString());
    }

    private void navigateTo(URI target) {
        if (!closed && target != null) {
            pendingFraction = -1; // let the #fragment decide where to scroll
            engine.load(book.renderTarget(target).toString());
        }
    }

    private void onLocationChanged(String location) {
        if (closed || location == null || location.isEmpty()) {
            return;
        }
        URI uri;
        try {
            uri = new URI(location);
        } catch (URISyntaxException e) {
            return;
        }
        URI rendered = book.renderTarget(uri);
        if (rendered != uri) {
            // A link inside the book named a chapter that is shown from its HTML copy.
            Platform.runLater(() -> engine.load(rendered.toString()));
            return;
        }
        int index = book.spineIndexOf(uri);
        if (index >= 0) {
            if (index != chapter) {
                chapter = index;
                lastFraction = 0;
            }
            updateStatus();
            ViewChrome.selectTocItem(tocTree, entry -> book.spineIndexOf(entry.target()) == chapter);
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto")) {
            // Links out of the book open in the system browser; stay on the current page.
            ctx.hostServices.showDocument(location);
            double back = lastFraction;
            Platform.runLater(() -> loadChapter(chapter, back));
        }
    }

    private void onChapterLoaded() {
        if (closed) {
            return;
        }
        if (pendingFraction >= 0) {
            double fraction = pendingFraction;
            pendingFraction = -1;
            engine.executeScript("window.scrollTo(0, (" + MAX_SCROLL_JS + ") * " + fraction + ")");
        }
        lastFraction = scrollFraction();
        updateStatus();
        webView.requestFocus();
    }

    private void onKey(KeyEvent e) {
        if (e.isShortcutDown() || e.isAltDown()) {
            return;
        }
        switch (e.getCode()) {
            case RIGHT, PAGE_DOWN -> pageForward();
            case LEFT, PAGE_UP -> pageBack();
            case SPACE -> {
                if (e.isShiftDown()) {
                    pageBack();
                } else {
                    pageForward();
                }
            }
            default -> {
                return;
            }
        }
        e.consume();
    }

    /** Scrolls one screen down, continuing into the next chapter at the end of this one. */
    private void pageForward() {
        if (scrollY() >= maxScroll() - 2) {
            loadChapter(chapter + 1, 0);
        } else {
            engine.executeScript("window.scrollBy(0, window.innerHeight * 0.92)");
        }
    }

    /** Scrolls one screen up, continuing at the end of the previous chapter at the top of this one. */
    private void pageBack() {
        if (scrollY() <= 2) {
            loadChapter(chapter - 1, 1);
        } else {
            engine.executeScript("window.scrollBy(0, -window.innerHeight * 0.92)");
        }
    }

    // ---------------------------------------------------------------- scroll position

    private double scrollY() {
        return number(engine.executeScript("window.scrollY"));
    }

    private double maxScroll() {
        return number(engine.executeScript(MAX_SCROLL_JS));
    }

    private double scrollFraction() {
        double max = maxScroll();
        return max > 0 ? Math.clamp(scrollY() / max, 0, 1) : 0;
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private void pollScroll() {
        if (closed || chapter < 0 || pendingFraction >= 0
                || engine.getLoadWorker().getState() != Worker.State.SUCCEEDED) {
            return;
        }
        double fraction = scrollFraction();
        if (Math.abs(fraction - lastFraction) > 0.0005) {
            lastFraction = fraction;
            updateStatus();
            savePosition();
        }
    }

    private double progress() {
        return (chapter + lastFraction) / book.spine().size();
    }

    private void savePosition() {
        if (chapter >= 0) {
            ctx.settings.setPosition(book.source(), new Position(chapter, lastFraction, progress()));
        }
    }

    private void updateStatus() {
        if (chapter < 0) {
            return;
        }
        int total = book.spine().size();
        double progress = progress();
        status.set("Section %d of %d  ·  %d%%".formatted(chapter + 1, total, Math.round(progress * 100)), progress);
    }
}
