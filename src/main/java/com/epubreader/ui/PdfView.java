package com.epubreader.ui;

import com.epubreader.pdf.PdfSupport;
import com.epubreader.pdf.PdfSupport.Word;
import com.epubreader.ui.ReaderSettings.Position;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads a PDF one page at a time. Pages are rendered by PDFBox on a single background thread
 * (PDFBox documents are not thread-safe). Double-click a word to look it up.
 */
final class PdfView implements BookView {

    /** An outline (bookmark) entry pointing at a 0-based page index. */
    record OutlineItem(String label, int page, List<OutlineItem> children) {}

    /** A document loaded off the FX thread, ready to show. */
    record Loaded(Path file, PDDocument doc, String title, double[] widths, double[] heights,
                  List<OutlineItem> outline) {}

    private static final double PAGE_MARGIN = 24;
    private static final double MAX_RENDER_PIXELS = 24_000_000;

    private final AppContext ctx;
    private final Loaded pdf;
    private final PDFRenderer renderer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-render");
        t.setDaemon(true);
        return t;
    });
    /** Words per page; only touched on the worker thread. */
    private final Map<Integer, List<Word>> words = new HashMap<>();

    private final BorderPane root = new BorderPane();
    private final ScrollPane scroll = new ScrollPane();
    private final ImageView pageImage = new ImageView();
    private final Rectangle highlight = new Rectangle();
    private final Pane pageBox = new Pane(pageImage, highlight);
    private final TreeView<OutlineItem> outlineTree;
    private final TextField pageField = new TextField();
    private final ViewChrome.StatusBar status = new ViewChrome.StatusBar();
    private final PauseTransition resizeDelay = new PauseTransition(Duration.millis(150));
    private final InvalidationListener rerender = o -> render();
    private final InvalidationListener background = o -> applyBackground();
    private final Runnable unbindToc;

    private int page = -1;
    private double pendingFraction = -1;
    private double lastFraction;
    /** Rendered pixels per PDF point currently on screen. */
    private double displayScale = 1;
    private double renderedViewportWidth;
    private volatile long generation;
    private boolean closed;

    static Loaded load(Path file) throws IOException {
        PDDocument doc = Loader.loadPDF(file.toFile());
        try {
            int n = doc.getNumberOfPages();
            if (n == 0) {
                throw new IOException("The PDF has no pages");
            }
            double[] widths = new double[n];
            double[] heights = new double[n];
            for (int i = 0; i < n; i++) {
                PDPage p = doc.getPage(i);
                PDRectangle box = p.getCropBox();
                boolean sideways = p.getRotation() % 180 != 0;
                widths[i] = sideways ? box.getHeight() : box.getWidth();
                heights[i] = sideways ? box.getWidth() : box.getHeight();
            }
            String title = doc.getDocumentInformation().getTitle();
            if (title == null || title.isBlank()) {
                title = file.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
            }
            return new Loaded(file, doc, title.strip(), widths, heights, readOutline(doc));
        } catch (IOException | RuntimeException e) {
            doc.close();
            throw e;
        }
    }

    PdfView(AppContext ctx, Loaded pdf, Runnable onLibrary) {
        this.ctx = ctx;
        this.pdf = pdf;
        this.renderer = new PDFRenderer(pdf.doc());

        pageImage.setSmooth(true);
        pageImage.setPreserveRatio(true);
        highlight.setFill(Color.rgb(255, 200, 0, 0.35));
        highlight.setVisible(false);
        highlight.setMouseTransparent(true);
        pageBox.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 2);");
        pageBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        pageBox.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && ctx.lookupOnSelect.get()) {
                lookUpAt(e.getX(), e.getY(), e.getScreenX(), e.getScreenY());
            } else if (e.getClickCount() == 1) {
                highlight.setVisible(false);
            }
        });

        StackPane centre = new StackPane(pageBox);
        centre.setPadding(new Insets(PAGE_MARGIN));
        scroll.setContent(centre);
        scroll.setFitToWidth(true);
        scroll.setPannable(false);
        scroll.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        scroll.vvalueProperty().addListener((o, a, v) -> onScrolled());
        scroll.viewportBoundsProperty().addListener((o, a, b) -> {
            if (Math.abs(b.getWidth() - renderedViewportWidth) > 2) {
                resizeDelay.playFromStart();
            }
        });
        resizeDelay.setOnFinished(e -> render());

        outlineTree = ViewChrome.tocTree(OutlineItem::label, item -> goToPage(item.page(), 0));
        TreeItem<OutlineItem> outlineRoot = new TreeItem<>();
        outlineRoot.getChildren().setAll(treeItems(pdf.outline()));
        outlineTree.setRoot(outlineRoot);
        SplitPane split = new SplitPane(scroll);
        Node tocPanel = outlineTree;
        if (pdf.outline().isEmpty()) {
            Label none = new Label("This PDF has no table of contents.");
            none.setWrapText(true);
            none.setStyle("-fx-opacity: 0.7;");
            StackPane placeholder = new StackPane(none);
            placeholder.setPadding(new Insets(16));
            placeholder.setMinWidth(160);
            placeholder.setPrefWidth(240);
            tocPanel = placeholder;
        }
        unbindToc = ViewChrome.bindToc(split, tocPanel, ctx.tocVisible);

        Button prev = new Button("‹ Previous");
        prev.setOnAction(e -> previousSection());
        Button next = new Button("Next ›");
        next.setOnAction(e -> nextSection());
        pageField.setPrefColumnCount(4);
        pageField.setOnAction(e -> {
            try {
                goToPage(Integer.parseInt(pageField.getText().strip()) - 1, 0);
            } catch (NumberFormatException ex) {
                pageField.setText(Integer.toString(page + 1));
            }
            scroll.requestFocus();
        });
        HBox pageBoxControls = new HBox(6, new Label("Page"), pageField, new Label("of " + pageCount()));
        pageBoxControls.setAlignment(javafx.geometry.Pos.CENTER);
        Label zoom = new Label();
        zoom.textProperty().bind(ctx.pdfZoom.asString("%d%%"));
        root.setTop(ViewChrome.toolBar(onLibrary, ctx, prev, next, pageBoxControls,
                ViewChrome.zoomControls(this, zoom), ViewChrome.themeChoice(ctx)));
        root.setCenter(split);
        root.setBottom(status);

        ctx.pdfZoom.addListener(rerender);
        ctx.theme.addListener(background);
        applyBackground();

        Position pos = ctx.settings.position(pdf.file());
        goToPage(Math.clamp(pos.index(), 0, pageCount() - 1), pos.fraction());
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return pdf.title();
    }

    @Override
    public void nextSection() {
        goToPage(page + 1, 0);
    }

    @Override
    public void previousSection() {
        goToPage(page - 1, 0);
    }

    @Override
    public void goToStart() {
        goToPage(0, 0);
    }

    @Override
    public void zoomIn() {
        ctx.pdfZoom.set(Math.min(ReaderSettings.MAX_ZOOM, ctx.pdfZoom.get() + 10));
    }

    @Override
    public void zoomOut() {
        ctx.pdfZoom.set(Math.max(ReaderSettings.MIN_ZOOM, ctx.pdfZoom.get() - 10));
    }

    @Override
    public void zoomReset() {
        ctx.pdfZoom.set(100);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        savePosition();
        generation++;
        resizeDelay.stop();
        unbindToc.run();
        ctx.pdfZoom.removeListener(rerender);
        ctx.theme.removeListener(background);
        ctx.popup.hide();
        worker.execute(() -> {
            try {
                pdf.doc().close();
            } catch (IOException ignored) {
                // nothing more to do
            }
        });
        worker.shutdown();
    }

    // ---------------------------------------------------------------- pages

    private int pageCount() {
        return pdf.widths().length;
    }

    private void goToPage(int index, double fraction) {
        if (closed || index < 0 || index >= pageCount()) {
            return;
        }
        ctx.popup.hide();
        highlight.setVisible(false);
        page = index;
        pendingFraction = fraction;
        lastFraction = fraction;
        pageField.setText(Integer.toString(page + 1));
        ViewChrome.selectTocItem(outlineTree, item -> item.page() == page);
        updateStatus();
        render();
    }

    /** At 100% zoom the page fills the window width. */
    private void render() {
        if (closed || page < 0) {
            return;
        }
        double viewport = scroll.getViewportBounds().getWidth();
        if (viewport <= 0) {
            viewport = 900; // not laid out yet; re-rendered when the viewport size arrives
        }
        renderedViewportWidth = scroll.getViewportBounds().getWidth();
        double width = Math.max(100, (viewport - 2 * PAGE_MARGIN) * ctx.pdfZoom.get() / 100.0);
        double scale = width / pdf.widths()[page];
        double height = pdf.heights()[page] * scale;
        double outputScale = root.getScene() == null || root.getScene().getWindow() == null
                ? 1 : root.getScene().getWindow().getOutputScaleX();
        double renderScale = scale * outputScale;
        double pixels = pdf.widths()[page] * pdf.heights()[page] * renderScale * renderScale;
        if (pixels > MAX_RENDER_PIXELS) {
            renderScale *= Math.sqrt(MAX_RENDER_PIXELS / pixels);
        }

        long gen = ++generation;
        int p = page;
        float rs = (float) renderScale;
        worker.execute(() -> {
            if (gen != generation) {
                return; // superseded by a newer request
            }
            try {
                WritableImage image = toFxImage(renderer.renderImage(p, rs));
                Platform.runLater(() -> show(gen, image, scale, width, height));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> status.set("Could not render page " + (p + 1) + ": " + e.getMessage(), -1));
            }
        });
    }

    private void show(long gen, WritableImage image, double scale, double width, double height) {
        if (gen != generation || closed) {
            return;
        }
        displayScale = scale;
        pageImage.setImage(image);
        pageImage.setFitWidth(width);
        pageImage.setFitHeight(height);
        pageBox.setPrefSize(width, height);
        highlight.setVisible(false);
        if (pendingFraction >= 0) {
            double f = pendingFraction;
            pendingFraction = -1;
            scroll.layout();
            scroll.setVvalue(f);
        }
    }

    private static WritableImage toFxImage(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        WritableImage fx = new WritableImage(w, h);
        fx.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        return fx;
    }

    private void applyBackground() {
        String colour = switch (ctx.theme.get()) {
            case LIGHT -> "#e6e6e6";
            case SEPIA -> "#d8ccb0";
            case DARK -> "#2a2a2a";
        };
        scroll.setStyle("-fx-background: " + colour + "; -fx-background-color: " + colour + ";");
    }

    // ---------------------------------------------------------------- keyboard and scrolling

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
            case HOME -> goToPage(0, 0);
            case END -> goToPage(pageCount() - 1, 0);
            default -> {
                return;
            }
        }
        e.consume();
    }

    /** Fraction of the scroll range one screen covers, or 1 when the page fits. */
    private double screenStep() {
        double content = pageBox.getHeight() + 2 * PAGE_MARGIN;
        double viewport = scroll.getViewportBounds().getHeight();
        return content <= viewport ? 1 : 0.92 * viewport / (content - viewport);
    }

    private void pageForward() {
        if (scroll.getVvalue() >= 0.999 || screenStep() >= 1) {
            goToPage(page + 1, 0);
        } else {
            scroll.setVvalue(Math.min(1, scroll.getVvalue() + screenStep()));
        }
    }

    private void pageBack() {
        if (scroll.getVvalue() <= 0.001 || screenStep() >= 1) {
            goToPage(page - 1, 1);
        } else {
            scroll.setVvalue(Math.max(0, scroll.getVvalue() - screenStep()));
        }
    }

    private void onScrolled() {
        if (pendingFraction >= 0 || closed) {
            return;
        }
        lastFraction = Math.clamp(scroll.getVvalue(), 0, 1);
        updateStatus();
        savePosition();
    }

    private double progress() {
        return (page + lastFraction) / pageCount();
    }

    private void savePosition() {
        if (page >= 0) {
            ctx.settings.setPosition(pdf.file(), new Position(page, lastFraction, progress()));
        }
    }

    private void updateStatus() {
        double progress = progress();
        status.set("Page %d of %d  ·  %d%%".formatted(page + 1, pageCount(), Math.round(progress * 100)), progress);
    }

    // ---------------------------------------------------------------- dictionary lookup

    private void lookUpAt(double x, double y, double screenX, double screenY) {
        int p = page;
        double scale = displayScale;
        double px = x / scale;
        double py = y / scale;
        worker.execute(() -> {
            if (closed) {
                return;
            }
            try {
                List<Word> pageWords = words.get(p);
                if (pageWords == null) {
                    pageWords = PdfSupport.words(pdf.doc(), p);
                    words.put(p, pageWords);
                }
                Word hit = pageWords.stream().filter(w -> w.contains(px, py)).findFirst().orElse(null);
                Platform.runLater(() -> {
                    if (hit == null || p != page || closed) {
                        highlight.setVisible(false);
                        return;
                    }
                    highlight.setX(hit.x() * scale);
                    highlight.setY(hit.y() * scale);
                    highlight.setWidth(hit.width() * scale);
                    highlight.setHeight(hit.height() * scale);
                    highlight.setVisible(true);
                    ctx.popup.show(root.getScene().getWindow(), screenX, screenY, hit.text());
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> status.set("Could not read text on this page: " + e.getMessage(), -1));
            }
        });
    }

    // ---------------------------------------------------------------- outline

    private static List<OutlineItem> readOutline(PDDocument doc) {
        try {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            return outline == null ? List.of() : outlineChildren(doc, outline, 0, new int[]{0});
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static List<OutlineItem> outlineChildren(PDDocument doc, PDOutlineNode node, int depth, int[] count)
            throws IOException {
        List<OutlineItem> items = new ArrayList<>();
        if (depth > 8) {
            return items;
        }
        for (PDOutlineItem item : node.children()) {
            if (++count[0] > 5000) {
                break;
            }
            PDPage target = item.findDestinationPage(doc);
            int index = target == null ? -1 : doc.getPages().indexOf(target);
            List<OutlineItem> kids = outlineChildren(doc, item, depth + 1, count);
            if (index < 0 && !kids.isEmpty()) {
                index = kids.getFirst().page();
            }
            if (index >= 0) {
                String label = item.getTitle() == null ? "" : item.getTitle().strip();
                items.add(new OutlineItem(label, index, kids));
            }
        }
        return items;
    }

    private static List<TreeItem<OutlineItem>> treeItems(List<OutlineItem> items) {
        return items.stream().map(i -> {
            TreeItem<OutlineItem> t = new TreeItem<>(i);
            t.getChildren().setAll(treeItems(i.children()));
            return t;
        }).toList();
    }
}
