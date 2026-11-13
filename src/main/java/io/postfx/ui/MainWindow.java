package io.postfx.ui;

import io.postfx.engine.HttpResult;
import io.postfx.engine.ResolvedRequest;
import io.postfx.engine.VariableResolver;
import io.postfx.io.ImportResult;
import io.postfx.io.Importer;
import io.postfx.io.SampleWorkspace;
import io.postfx.model.Collection;
import io.postfx.model.Environment;
import io.postfx.model.Folder;
import io.postfx.model.Item;
import io.postfx.model.Items;
import io.postfx.model.KeyValue;
import io.postfx.model.RequestItem;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.File;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Main window: menus, toolbar, sidebar and editor tabs. */
public class MainWindow {
    private static final String APP_CSS = MainWindow.class.getResource("/io/postfx/ui/app.css").toExternalForm();
    private static final String DARK_CSS = MainWindow.class.getResource("/io/postfx/ui/dark.css").toExternalForm();

    private final AppContext ctx;
    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final TabPane editors = new TabPane();
    private final Map<Object, Tab> open = new LinkedHashMap<>();
    private final ComboBox<Environment> envCombo = new ComboBox<>();
    private final CollectionTree tree;
    private final Scene scene;
    private boolean dark;

    public MainWindow(AppContext ctx, Stage stage, boolean firstRun) {
        this.ctx = ctx;
        this.stage = stage;
        ctx.setMain(this);
        Dialogs.owner = stage;
        tree = new CollectionTree(ctx);

        // ---- sidebar
        TabPane side = new TabPane();
        side.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        side.getTabs().addAll(new Tab("Collections", tree), new Tab("Environments", new EnvironmentsView(ctx)),
                new Tab("History", new HistoryView(ctx)));
        side.setMinWidth(220);
        side.getStyleClass().add("sidebar");

        editors.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        editors.getStyleClass().add("editors");
        editors.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);

        SplitPane split = new SplitPane(side, editors);
        split.setDividerPositions(0.22);
        SplitPane.setResizableWithParent(side, false);

        VBox top = new VBox(menuBar(), toolBar());
        root.setTop(top);
        root.setCenter(split);
        root.setBottom(statusBar());

        scene = new Scene(root, 1360, 860);
        scene.getStylesheets().add(APP_CSS);
        setDark(ctx.workspace.settings.darkTheme);
        shortcuts();
        dragAndDropImport();

        stage.setTitle("PostFX " + io.postfx.BuildInfo.shortVersion() + " — API client & test runner");
        stage.setScene(scene);
        openWelcome();
        if (firstRun) {
            Platform.runLater(() -> ctx.status.set("Welcome! An example collection and environment were created for you."));
        }
    }

    public Scene scene() {
        return scene;
    }

    // ------------------------------------------------------------------ chrome

    private MenuBar menuBar() {
        Menu file = new Menu("File");
        MenuItem newReq = item("New request", "Ctrl+T", this::newRequest);
        MenuItem newColl = item("New collection", null, tree::newCollection);
        MenuItem imp = item("Import file…", "Ctrl+I", this::importFile);
        MenuItem impText = item("Import from text / cURL / link…", null, this::importText);
        MenuItem expPostman = item("Export collection (Postman v2.1)…", null, () -> withCollection(c -> Dialogs.exportPostman(ctx, c)));
        MenuItem expJava = item("Export collection as Java tests…", null, () -> withCollection(c -> Dialogs.exportJava(ctx, c, null)));
        MenuItem expGlobals = item("Export globals (Postman)…", null, () -> Dialogs.exportGlobals(ctx));
        MenuItem save = item("Save", "Ctrl+S", this::saveCurrent);
        MenuItem settings = item("Settings…", null, () -> Dialogs.settings(ctx));
        MenuItem exit = item("Exit", null, () -> {
            ctx.shutdown();
            Platform.exit();
        });
        file.getItems().addAll(newReq, newColl, new SeparatorMenuItem(), imp, impText, new SeparatorMenuItem(), expPostman, expJava,
                expGlobals, new SeparatorMenuItem(), save, settings, new SeparatorMenuItem(), exit);

        Menu run = new Menu("Run");
        run.getItems().addAll(item("Send current request", "Ctrl+Enter", this::sendCurrent),
                item("Run collection…", "Ctrl+Shift+R", this::runCurrent),
                new SeparatorMenuItem(),
                item("Clear cookies", null, () -> {
                    ctx.http.clearCookies();
                    ctx.status.set("Cookies cleared");
                }));

        Menu view = new Menu("View");
        view.getItems().addAll(item("Welcome page", null, this::openWelcome), item("Globals", null, this::openGlobals),
                item("Toggle dark theme", null, () -> setDark(!dark)));

        Menu help = new Menu("Help");
        help.getItems().add(item("User guide", "F1", () -> HelpWindow.show(stage, null)));
        for (String s : HelpWindow.sections()) help.getItems().add(item(s, null, () -> HelpWindow.show(stage, s)));
        help.getItems().addAll(new SeparatorMenuItem(),
                item("Restore example collection", null, this::restoreExamples),
                item("About PostFX", null, () -> AboutDialog.show(ctx)));
        return new MenuBar(file, run, view, help);
    }

    private MenuItem item(String text, String accel, Runnable action) {
        MenuItem mi = new MenuItem(text);
        if (accel != null) mi.setAccelerator(KeyCombination.keyCombination(accel));
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private ToolBar toolBar() {
        Button newReq = new Button("+ New request");
        newReq.setOnAction(e -> newRequest());
        newReq.setTooltip(new Tooltip("Ctrl+T"));
        Button imp = new Button("⇩ Import");
        imp.setTooltip(new Tooltip("Postman, OpenAPI / Swagger, Insomnia, HAR, cURL (Ctrl+I). You can also drop files on the window."));
        imp.setOnAction(e -> importFile());
        Button runner = new Button("▶ Runner");
        runner.setTooltip(new Tooltip("Run a collection or folder as a test chain (Ctrl+Shift+R)"));
        runner.setOnAction(e -> runCurrent());
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        envCombo.setPrefWidth(230);
        envCombo.setTooltip(new Tooltip("Active environment: its variables are used as {{name}}"));
        envCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Environment e) {
                return e == null ? "No environment" : e.name;
            }

            @Override
            public Environment fromString(String s) {
                return null;
            }
        });
        refreshEnvCombo();
        envCombo.valueProperty().addListener((o, a, b) -> {
            if (!refreshingEnv && b != ctx.activeEnvironment.get()) ctx.activeEnvironment.set(b);
        });
        ctx.activeEnvironment.addListener((o, a, b) -> envCombo.setValue(b));
        ctx.onEnvironmentsChanged(this::refreshEnvCombo);

        Button look = new Button("👁");
        look.setTooltip(new Tooltip("Quick look at the variables in scope"));
        look.setOnAction(e -> quickLook(look));
        Button settings = new Button("⚙");
        settings.setTooltip(new Tooltip("Settings"));
        settings.setOnAction(e -> Dialogs.settings(ctx));
        Button help = new Button("?");
        help.setTooltip(new Tooltip("User guide (F1)"));
        help.setOnAction(e -> HelpWindow.show(stage, null));
        return new ToolBar(newReq, imp, runner, grow, envCombo, look, settings, help);
    }

    private boolean refreshingEnv;

    private void refreshEnvCombo() {
        Environment active = ctx.activeEnvironment.get();
        refreshingEnv = true;
        try {
            List<Environment> wanted = new java.util.ArrayList<>();
            wanted.add(null);
            wanted.addAll(ctx.workspace.environments);
            if (!envCombo.getItems().equals(wanted)) envCombo.getItems().setAll(wanted);
            // force the button cell to re-render (renamed environment, items replaced)
            envCombo.setValue(null);
            envCombo.setValue(active);
        } finally {
            refreshingEnv = false;
        }
    }

    private void quickLook(Node anchor) {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(4);
        g.setPadding(new Insets(8));
        int r = 0;
        Environment env = ctx.activeEnvironment.get();
        r = section(g, r, env == null ? "No active environment" : "Environment: " + env.name, env == null ? List.of() : env.values);
        Collection c = currentCollection();
        if (c != null) r = section(g, r, "Collection: " + c.name, c.variables);
        section(g, r, "Globals", ctx.workspace.globals);
        ScrollPane sp = new ScrollPane(g);
        sp.setPrefSize(460, 360);
        CustomMenuItem cmi = new CustomMenuItem(sp, false);
        ContextMenu m = new ContextMenu(cmi);
        m.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private int section(GridPane g, int row, String title, List<KeyValue> vars) {
        Label t = new Label(title);
        t.getStyleClass().add("section-title");
        g.add(t, 0, row++, 2, 1);
        if (vars.isEmpty()) {
            Label none = new Label("(none)");
            none.getStyleClass().add("hint");
            g.add(none, 0, row++);
        }
        for (KeyValue kv : vars) {
            Label k = new Label(kv.key);
            Label v = new Label(kv.value);
            if (!kv.enabled) {
                k.getStyleClass().add("disabled-var");
                v.getStyleClass().add("disabled-var");
            }
            v.setMaxWidth(300);
            g.add(k, 0, row);
            g.add(v, 1, row++);
        }
        return row;
    }

    private HBox statusBar() {
        Label status = new Label();
        status.textProperty().bind(ctx.status);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Label ws = new Label(ctx.store.file().toString());
        ws.getStyleClass().add("hint");
        HBox bar = new HBox(8, status, grow, ws);
        bar.setPadding(new Insets(3, 8, 3, 8));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void shortcuts() {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN), this::sendCurrent);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN), () -> {
            Tab t = editors.getSelectionModel().getSelectedItem();
            if (t != null && (t.getOnCloseRequest() == null || confirmClose(t))) {
                editors.getTabs().remove(t);
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN), () -> {
            if (currentEditor() instanceof RequestEditor re) re.focusUrl();
        });
    }

    private boolean confirmClose(Tab t) {
        javafx.event.Event ev = new javafx.event.Event(Tab.TAB_CLOSE_REQUEST_EVENT);
        t.getOnCloseRequest().handle(ev);
        return !ev.isConsumed();
    }

    private void dragAndDropImport() {
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
        });
        scene.setOnDragDropped(e -> {
            if (e.getDragboard().hasFiles()) {
                importFiles(e.getDragboard().getFiles());
                e.setDropCompleted(true);
            }
        });
    }

    public boolean isDark() {
        return dark;
    }

    public void setDark(boolean value) {
        dark = value;
        ctx.workspace.settings.darkTheme = value;
        scene.getStylesheets().remove(DARK_CSS);
        if (value) scene.getStylesheets().add(DARK_CSS);
    }

    // ------------------------------------------------------------------ tabs

    private Node currentEditor() {
        Tab t = editors.getSelectionModel().getSelectedItem();
        return t == null ? null : t.getContent();
    }

    private Tab openTab(Object key, Supplier<Node> content, StringProperty title, String styleClass) {
        Tab existing = open.get(key);
        if (existing != null) {
            editors.getSelectionModel().select(existing);
            return existing;
        }
        Node n = content.get();
        Tab tab = new Tab();
        tab.setContent(n);
        if (title != null) tab.textProperty().bind(title);
        if (styleClass != null) tab.getStyleClass().add(styleClass);
        tab.setOnClosed(e -> open.remove(key));
        open.put(key, tab);
        editors.getTabs().add(tab);
        editors.getSelectionModel().select(tab);
        return tab;
    }

    public void openRequest(RequestItem item) {
        Tab existing = open.get(item);
        if (existing != null) {
            editors.getSelectionModel().select(existing);
            return;
        }
        RequestEditor ed = new RequestEditor(ctx, item);
        Tab tab = openTab(item, () -> ed, ed.titleProperty(), "request-tab");
        installReloader(tab, ed);
        tab.setOnCloseRequest(e -> {
            RequestEditor current = (RequestEditor) tab.getContent();
            if (ctx.collectionOf(current.item()) == null && !current.item().request.url.isBlank()) {
                if (!Dialogs.confirm("Close unsaved request", "This request is not saved in a collection. Close it anyway?")) e.consume();
            }
        });
    }

    private void installReloader(Tab tab, RequestEditor ed) {
        ed.setReloader(() -> {
            RequestEditor fresh = new RequestEditor(ctx, ed.item());
            tab.textProperty().unbind();
            tab.setContent(fresh);
            tab.textProperty().bind(fresh.titleProperty());
            installReloader(tab, fresh);
        });
    }

    public void openFolder(Folder f) {
        FolderEditor ed = new FolderEditor(ctx, f);
        openTab(f, () -> ed, ed.titleProperty(), "folder-tab");
    }

    public void openEnvironment(Environment env) {
        EnvironmentEditor ed = new EnvironmentEditor(ctx, env);
        openTab(env, () -> ed, ed.titleProperty(), "env-tab");
    }

    public void openGlobals() {
        EnvironmentEditor ed = new EnvironmentEditor(ctx, ctx.workspace.globals);
        openTab(ctx.workspace.globals, () -> ed, ed.titleProperty(), "env-tab");
    }

    public void openRunner(Collection c, Folder scope) {
        if (c == null) {
            Dialogs.error("Nothing to run", "Create or import a collection first.");
            return;
        }
        Folder s = scope == null ? c : scope;
        String key = "runner:" + s.id;
        Tab existing = open.get(key);
        if (existing != null) {
            editors.getTabs().remove(existing);
            open.remove(key);
        }
        RunnerView rv = new RunnerView(ctx, c, s);
        openTab(key, () -> rv, rv.titleProperty(), "runner-tab");
    }

    public void closeEditorsOf(Object removed) {
        for (Object key : List.copyOf(open.keySet())) {
            boolean match = key == removed || (removed instanceof Folder f && key instanceof Item i && Items.ancestors(f, i).isPresent());
            if (match) {
                Tab t = open.remove(key);
                editors.getTabs().remove(t);
            }
        }
    }

    public void refreshTabTitles() {
        for (Tab t : editors.getTabs()) {
            if (t.getContent() instanceof RequestEditor re) re.titleProperty().set(re.item().request.method + "  " + re.item().name);
        }
    }

    private void openWelcome() {
        String key = "welcome";
        if (open.containsKey(key)) {
            editors.getSelectionModel().select(open.get(key));
            return;
        }
        openTab(key, this::welcome, new javafx.beans.property.SimpleStringProperty("Welcome"), "welcome-tab");
    }

    private Node welcome() {
        Label title = new Label("PostFX");
        title.getStyleClass().add("welcome-title");
        Label subtitle = new Label("Build, test and chain API requests. Postman compatible. Exports to Java tests.");
        subtitle.getStyleClass().add("welcome-subtitle");

        FlowPane actions = new FlowPane(12, 12);
        actions.getChildren().addAll(
                card("➕  New request", "Start from a URL. Tip: paste a cURL command into the URL field.", this::newRequest),
                card("⇩  Import", "Postman, OpenAPI / Swagger, Insomnia, HAR or cURL. Drag & drop files here.", this::importFile),
                card("🔗  Import from a link", "e.g. an OpenAPI URL: https://petstore3.swagger.io/api/v3/openapi.json", this::importText),
                card("📘  Open the examples", "A guided tour of every feature with public demo APIs.", this::openExamples),
                card("▶  Run the example chain", "Checks & extractors chained in the runner, no code needed.", this::runExampleChain),
                card("?  Read the guide", "Variables, scripts (pm API), checks, runner, import/export, CLI.", () -> HelpWindow.show(stage, null)));

        VBox tips = new VBox(6);
        Label tipsTitle = new Label("Good to know");
        tipsTitle.getStyleClass().add("help-h2");
        tips.getChildren().add(tipsTitle);
        for (String t : List.of(
                "Use {{variable}} anywhere. Pick the active environment at the top right; 👁 shows the values in scope.",
                "Checks and Extract tabs build test chains without code: extract $.token from a login, use {{token}} afterwards.",
                "In a response, Body › Tree: right-click a value to add a check or extract it into a variable.",
                "Scripts use the Postman pm API: pm.test, pm.expect, pm.environment.set, pm.sendRequest, CryptoJS…",
                "Right-click a collection: Run, Export as Postman collection, Export as Java tests (JUnit 5 + REST Assured).",
                "Command line for CI: java -jar postfx.jar run collection.json -e env.json --reporter-junit report.xml",
                "Shortcuts: Ctrl+Enter send · Ctrl+T new · Ctrl+I import · Ctrl+Shift+R runner · F1 help")) {
            Label l = new Label("•  " + t);
            l.setWrapText(true);
            tips.getChildren().add(l);
        }
        VBox box = new VBox(18, title, subtitle, actions, tips);
        box.setPadding(new Insets(36));
        box.setMaxWidth(1000);
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("welcome");
        return sp;
    }

    private Node card(String title, String text, Runnable action) {
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        Label d = new Label(text);
        d.setWrapText(true);
        d.getStyleClass().add("hint");
        VBox card = new VBox(6, t, d);
        card.getStyleClass().add("card");
        card.setPrefSize(290, 96);
        card.setAlignment(Pos.TOP_LEFT);
        card.setOnMouseClicked(e -> action.run());
        return card;
    }

    // ------------------------------------------------------------------ actions

    public void newRequest() {
        RequestItem r = new RequestItem("New Request", "GET", "");
        openRequest(r);
        Platform.runLater(() -> {
            if (currentEditor() instanceof RequestEditor re) re.focusUrl();
        });
    }

    private void saveCurrent() {
        if (currentEditor() instanceof RequestEditor re) re.save();
        else ctx.saveNow();
    }

    private void sendCurrent() {
        if (currentEditor() instanceof RequestEditor re) re.send();
    }

    private Collection currentCollection() {
        Node n = currentEditor();
        if (n instanceof RequestEditor re) return ctx.collectionOf(re.item());
        if (n instanceof FolderEditor fe) return ctx.collectionOf(fe.folder());
        return ctx.workspace.collections.isEmpty() ? null : ctx.workspace.collections.get(0);
    }

    private void runCurrent() {
        Node n = currentEditor();
        if (n instanceof FolderEditor fe) {
            openRunner(ctx.collectionOf(fe.folder()), fe.folder());
            return;
        }
        Collection c = currentCollection();
        if (c == null) {
            Dialogs.error("Nothing to run", "Create or import a collection first.");
            return;
        }
        openRunner(c, null);
    }

    private void withCollection(java.util.function.Consumer<Collection> action) {
        Collection c = currentCollection();
        if (c == null) {
            Dialogs.error("No collection", "Open a request or folder of the collection to export, or right-click it in the tree.");
            return;
        }
        if (ctx.workspace.collections.size() > 1) {
            javafx.scene.control.ChoiceDialog<Collection> d = new javafx.scene.control.ChoiceDialog<>(c, ctx.workspace.collections);
            d.initOwner(stage);
            d.setTitle("Choose a collection");
            d.setHeaderText(null);
            d.setContentText("Collection");
            d.showAndWait().ifPresent(action);
        } else {
            action.accept(c);
        }
    }

    private void openExamples() {
        Collection c = examples();
        if (c == null) {
            restoreExamples();
            c = examples();
        }
        if (c == null) return;
        ensureExampleEnvironment();
        openFolder(c);
        tree.select(c);
        List<RequestItem> reqs = Items.flatten(c);
        if (!reqs.isEmpty()) openRequest(reqs.get(0));
    }

    private void runExampleChain() {
        Collection c = examples();
        if (c == null) {
            restoreExamples();
            c = examples();
        }
        if (c == null) return;
        ensureExampleEnvironment();
        Folder chain = c.items.stream().filter(i -> i instanceof Folder f && f.name.startsWith("2")).map(i -> (Folder) i).findFirst().orElse(c);
        openRunner(c, chain);
    }

    private Collection examples() {
        return ctx.workspace.collections.stream().filter(x -> x.name.equals("PostFX Examples")).findFirst().orElse(null);
    }

    private void ensureExampleEnvironment() {
        Environment env = ctx.workspace.environments.stream().filter(e -> e.name.equals("Public demo APIs")).findFirst().orElse(null);
        if (env == null) {
            env = SampleWorkspace.create().environments.get(0);
            ctx.workspace.environments.add(env);
            ctx.fireEnvironmentsChanged();
        }
        ctx.activeEnvironment.set(env);
    }

    private void restoreExamples() {
        Collection c = SampleWorkspace.examples();
        ctx.workspace.collections.add(c);
        ensureExampleEnvironment();
        if (ctx.workspace.globals.stream().noneMatch(g -> g.key.equals("appName"))) ctx.workspace.globals.add(new KeyValue("appName", "PostFX"));
        ctx.fireTreeChanged();
        ctx.status.set("Example collection restored");
    }

    public void importFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Import (Postman, OpenAPI, Swagger, Insomnia, HAR, cURL)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Supported files", "*.json", "*.yaml", "*.yml", "*.har", "*.txt", "*.sh", "*.curl"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files != null) importFiles(files);
    }

    private void importFiles(List<File> files) {
        StringBuilder summary = new StringBuilder();
        for (File f : files) {
            try {
                ImportResult r = Importer.importFile(f.toPath());
                apply(r);
                summary.append("✔ ").append(f.getName()).append(" — ").append(r.summary()).append('\n');
                r.warnings.forEach(w -> summary.append("     ⚠ ").append(w).append('\n'));
            } catch (Exception e) {
                summary.append("✘ ").append(f.getName()).append(" — ").append(e.getMessage()).append('\n');
            }
        }
        Dialogs.info("Import finished", summary.toString());
    }

    private void importText() {
        Dialogs.importText().ifPresent(in -> {
            if (in[0].equals("url")) importUrl(in[1]);
            else {
                try {
                    ImportResult r = Importer.importText(in[1], null);
                    apply(r);
                    Dialogs.info("Import finished", r.summary() + (r.warnings.isEmpty() ? "" : "\n\n⚠ " + String.join("\n⚠ ", r.warnings)));
                } catch (Exception e) {
                    Dialogs.error("Import failed", e.getMessage());
                }
            }
        });
    }

    private void importUrl(String url) {
        ctx.status.set("Downloading " + url + "…");
        ResolvedRequest req = new ResolvedRequest();
        req.method = "GET";
        req.url = url;
        req.headers.add(new KeyValue("Accept", "application/json, application/yaml, text/yaml, */*"));
        ctx.http.sendAsync(req, ctx.workspace.settings).thenAccept(res -> Platform.runLater(() -> {
            if (res.failed() || res.status >= 400) {
                Dialogs.error("Download failed", res.failed() ? res.error : "HTTP " + res.status);
                return;
            }
            try {
                String name = URI.create(url).getPath().replaceAll(".*/", "").replaceFirst("\\.[^.]+$", "");
                ImportResult r = Importer.importText(res.body, name);
                fixRelativeBaseUrl(r, url);
                apply(r);
                Dialogs.info("Import finished", r.summary() + (r.warnings.isEmpty() ? "" : "\n\n⚠ " + String.join("\n⚠ ", r.warnings)));
            } catch (Exception e) {
                Dialogs.error("Import failed", e.getMessage());
            }
        }));
    }

    /** OpenAPI documents with a relative server URL ("/api/v3") are resolved against the URL they came from. */
    private static void fixRelativeBaseUrl(ImportResult r, String specUrl) {
        URI u = URI.create(specUrl);
        String origin = u.getScheme() + "://" + u.getAuthority();
        for (Collection c : r.collections) {
            for (KeyValue kv : c.variables) {
                if (kv.key.equals("baseUrl") && kv.value.startsWith("http://localhost") && !specUrl.contains("localhost")) {
                    kv.value = origin + kv.value.substring("http://localhost".length());
                    r.warnings.removeIf(w -> w.contains("relative server URL"));
                }
            }
        }
    }

    private void apply(ImportResult r) {
        ctx.workspace.collections.addAll(r.collections);
        ctx.workspace.environments.addAll(r.environments);
        for (KeyValue g : r.globals) {
            ctx.workspace.globals.removeIf(x -> x.key.equals(g.key));
            ctx.workspace.globals.add(g);
        }
        ctx.fireTreeChanged();
        ctx.fireEnvironmentsChanged();
        if (!r.collections.isEmpty()) {
            Collection c = r.collections.get(0);
            tree.select(c);
            openFolder(c);
            List<RequestItem> reqs = Items.flatten(c);
            if (reqs.size() == 1) openRequest(reqs.get(0));
        } else if (!r.environments.isEmpty()) {
            openEnvironment(r.environments.get(0));
        }
        ctx.status.set("Imported " + r.summary());
    }
}
