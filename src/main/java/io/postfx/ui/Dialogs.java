package io.postfx.ui;

import io.postfx.engine.JsonUtil;
import io.postfx.engine.RequestPreparer;
import io.postfx.engine.VariableContext;
import io.postfx.io.CurlCodec;
import io.postfx.io.JavaTestExporter;
import io.postfx.io.PostmanExporter;
import io.postfx.model.Collection;
import io.postfx.model.Environment;
import io.postfx.model.Folder;
import io.postfx.model.Item;
import io.postfx.model.RequestItem;
import io.postfx.model.Settings;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Small dialogs used across the application. */
public final class Dialogs {
    static Window owner;

    private Dialogs() {}

    private static <T extends Dialog<?>> T own(T d) {
        if (owner != null) d.initOwner(owner);
        AppIcons.apply(d.getDialogPane().getScene().getWindow());
        return d;
    }

    public static Optional<String> text(String title, String label, String initial) {
        TextInputDialog d = own(new TextInputDialog(initial));
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(label);
        return d.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
    }

    public static boolean confirm(String title, String message) {
        Alert a = own(new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL));
        a.setTitle(title);
        a.setHeaderText(null);
        return a.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    public static void error(String title, String message) {
        Alert a = own(new Alert(Alert.AlertType.ERROR));
        a.setTitle("PostFX");
        a.setHeaderText(title);
        a.setContentText(message);
        a.showAndWait();
    }

    public static void info(String title, String message) {
        Alert a = own(new Alert(Alert.AlertType.INFORMATION));
        a.setTitle("PostFX");
        a.setHeaderText(title);
        a.setContentText(message);
        a.getDialogPane().setMinWidth(480);
        a.showAndWait();
    }

    // ------------------------------------------------------------------ save as

    /** Lets the user pick a collection / folder for an unsaved request. */
    public static boolean saveRequestAs(AppContext ctx, RequestItem item) {
        List<Folder> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Collection c : ctx.workspace.collections) collectFolders(c, "", targets, labels);
        Dialog<ButtonType> d = own(new Dialog<>());
        d.setTitle("Save request");
        TextField name = new TextField(item.name);
        ComboBox<String> where = new ComboBox<>();
        where.getItems().add("➕ New collection…");
        where.getItems().addAll(labels);
        where.getSelectionModel().select(labels.isEmpty() ? 0 : 1);
        GridPane g = grid();
        g.addRow(0, new Label("Request name"), name);
        g.addRow(1, new Label("Save to"), where);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return false;
        item.name = name.getText().isBlank() ? "New Request" : name.getText().strip();
        int idx = where.getSelectionModel().getSelectedIndex();
        Folder target;
        if (idx <= 0) {
            Optional<String> n = text("New collection", "Collection name", "My Collection");
            if (n.isEmpty()) return false;
            Collection c = new Collection(n.get());
            ctx.workspace.collections.add(c);
            target = c;
        } else {
            target = targets.get(idx - 1);
        }
        target.items.add(item);
        ctx.fireTreeChanged();
        ctx.saveNow();
        return true;
    }

    private static void collectFolders(Folder f, String prefix, List<Folder> out, List<String> labels) {
        String label = prefix + f.name;
        out.add(f);
        labels.add(label);
        for (Item child : f.items) if (child instanceof Folder sub) collectFolders(sub, label + " / ", out, labels);
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        g.setPadding(new Insets(10));
        return g;
    }

    // ------------------------------------------------------------------ code generation

    public static void showCode(AppContext ctx, RequestItem item) {
        Collection c = ctx.collectionOf(item);
        VariableContext vars = ctx.variables(c);
        List<Folder> chain = ctx.ancestorsOf(item);
        String curl = CurlCodec.toCurl(item.request, RequestPreparer.effectiveAuth(item.request, chain), vars);

        Collection single = new Collection(c == null ? "Request" : c.name);
        if (c != null) {
            single.variables.addAll(c.variables);
            single.auth = c.auth;
        }
        RequestItem copy = JsonUtil.copy(item);
        if (copy.request.auth.type == io.postfx.model.AuthType.INHERIT) copy.request.auth = RequestPreparer.effectiveAuth(item.request, chain);
        single.items.add(copy);
        JavaTestExporter.Options o = new JavaTestExporter.Options();
        o.environment = ctx.activeEnvironment.get();
        o.globals = ctx.workspace.globals;
        o.className = JavaTestExporter.defaultClassName(item.name);
        String java = JavaTestExporter.generate(single, null, o);
        String postman = JsonUtil.toPrettyJson(PostmanExporter.exportCollection(single).get("item").get(0));

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(codeTab("cURL", curl, "text"), codeTab("Java (JUnit + REST Assured)", java, "javascript"),
                codeTab("Postman item JSON", postman, "json"));
        Dialog<ButtonType> d = own(new Dialog<>());
        d.setTitle("Code: " + item.name);
        d.setResizable(true);
        Label hint = new Label("cURL uses the current variable values. Copy copies the selected tab.");
        hint.getStyleClass().add("hint");
        VBox box = new VBox(6, hint, tabs);
        // the code tabs take all the space left by the hint, also when the dialog is resized
        VBox.setVgrow(tabs, javafx.scene.layout.Priority.ALWAYS);
        tabs.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        box.setPrefSize(860, 560);
        d.getDialogPane().setContent(box);
        ButtonType copyBtn = new ButtonType("Copy", ButtonBar.ButtonData.LEFT);
        d.getDialogPane().getButtonTypes().addAll(copyBtn, ButtonType.CLOSE);
        d.getDialogPane().lookupButton(copyBtn).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            CodeEditor ed = (CodeEditor) tabs.getSelectionModel().getSelectedItem().getContent();
            ClipboardContent cc = new ClipboardContent();
            cc.putString(ed.getText());
            Clipboard.getSystemClipboard().setContent(cc);
            ctx.status.set("Copied to clipboard");
            e.consume();
        });
        d.showAndWait();
    }

    private static Tab codeTab(String title, String code, String lang) {
        CodeEditor ed = new CodeEditor(lang);
        ed.setText(code);
        ed.setEditable(false);
        return new Tab(title, ed);
    }

    // ------------------------------------------------------------------ export

    public static void exportPostman(AppContext ctx, Collection c) {
        FileChooser fc = jsonChooser("Export Postman collection v2.1", c.name + ".postman_collection.json");
        File f = fc.showSaveDialog(owner);
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), JsonUtil.toPrettyJson(PostmanExporter.exportCollection(c)), StandardCharsets.UTF_8);
            ctx.status.set("Exported " + c.name + " to " + f);
            info("Collection exported", "Saved as Postman collection v2.1:\n" + f
                    + "\n\nDeclarative checks and extractors are converted to test scripts so the collection also works in Postman, "
                    + "and are restored as checks when imported back into PostFX.");
        } catch (Exception e) {
            error("Export failed", e.getMessage());
        }
    }

    public static void exportEnvironment(AppContext ctx, Environment env) {
        FileChooser fc = jsonChooser("Export Postman environment", env.name + ".postman_environment.json");
        File f = fc.showSaveDialog(owner);
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), JsonUtil.toPrettyJson(PostmanExporter.exportEnvironment(env)), StandardCharsets.UTF_8);
            ctx.status.set("Exported environment to " + f);
        } catch (Exception e) {
            error("Export failed", e.getMessage());
        }
    }

    public static void exportGlobals(AppContext ctx) {
        FileChooser fc = jsonChooser("Export Postman globals", "globals.postman_globals.json");
        File f = fc.showSaveDialog(owner);
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), JsonUtil.toPrettyJson(PostmanExporter.exportGlobals(ctx.workspace.globals)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            error("Export failed", e.getMessage());
        }
    }

    private static FileChooser jsonChooser(String title, String name) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.setInitialFileName(name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        return fc;
    }

    public static void exportJava(AppContext ctx, Collection c, Folder scope) {
        if (c == null) return;
        Dialog<ButtonType> d = own(new Dialog<>());
        d.setTitle("Export Java tests");
        d.setHeaderText("JUnit 5 + REST Assured test class for \"" + (scope == null ? c.name : scope.name) + "\"");
        TextField pkg = new TextField("com.example.api");
        TextField cls = new TextField(JavaTestExporter.defaultClassName(scope == null ? c.name : scope.name));
        ComboBox<Environment> env = new ComboBox<>();
        env.getItems().add(null);
        env.getItems().addAll(ctx.workspace.environments);
        env.setValue(ctx.activeEnvironment.get());
        env.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Environment e) {
                return e == null ? "No environment" : e.name;
            }

            @Override
            public Environment fromString(String s) {
                return null;
            }
        });
        CheckBox translate = new CheckBox("Translate Postman scripts into Java assertions (best effort)");
        translate.setSelected(true);
        CheckBox relaxed = new CheckBox("Accept any HTTPS certificate");
        relaxed.setSelected(!ctx.workspace.settings.verifySsl);
        ToggleGroup mode = new ToggleGroup();
        RadioButton maven = new RadioButton("Maven project (pom.xml + test class, runs with 'mvn test')");
        RadioButton file = new RadioButton("Single .java file");
        maven.setToggleGroup(mode);
        file.setToggleGroup(mode);
        maven.setSelected(true);
        GridPane g = grid();
        int r = 0;
        g.addRow(r++, new Label("Package"), pkg);
        g.addRow(r++, new Label("Class name"), cls);
        g.addRow(r++, new Label("Variables from"), env);
        g.add(translate, 0, r++, 2, 1);
        g.add(relaxed, 0, r++, 2, 1);
        g.add(maven, 0, r++, 2, 1);
        g.add(file, 0, r++, 2, 1);
        Label hint = new Label("Requests become ordered @Test methods sharing a variable map, so chained requests keep working.\n"
                + "Checks and extractors become assertions and vars.put(...). Untranslatable script lines are kept as TODO comments.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        g.add(hint, 0, r, 2, 1);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        JavaTestExporter.Options o = new JavaTestExporter.Options();
        o.packageName = pkg.getText().strip();
        o.className = cls.getText().strip();
        o.environment = env.getValue();
        o.globals = ctx.workspace.globals;
        o.translateScripts = translate.isSelected();
        o.relaxedHttps = relaxed.isSelected();
        try {
            if (maven.isSelected()) {
                DirectoryChooser dc = new DirectoryChooser();
                dc.setTitle("Folder for the Maven project");
                File dir = dc.showDialog(owner);
                if (dir == null) return;
                Path target = dir.toPath();
                if (Files.isDirectory(target) && Files.list(target).findAny().isPresent()) {
                    target = target.resolve(o.className.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase());
                }
                Path written = JavaTestExporter.writeMavenProject(target, c, scope, o);
                ctx.status.set("Java tests written to " + written);
                info("Java tests exported", "Project: " + target + "\nTest class: " + written + "\n\nRun them with:\n  cd \"" + target + "\"\n  mvn test");
            } else {
                FileChooser fc = new FileChooser();
                fc.setInitialFileName(o.className + ".java");
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java", "*.java"));
                File f = fc.showSaveDialog(owner);
                if (f == null) return;
                Files.writeString(f.toPath(), JavaTestExporter.generate(c, scope, o), StandardCharsets.UTF_8);
                ctx.status.set("Java test written to " + f);
                info("Java test exported", f + "\n\nDependencies: io.rest-assured:rest-assured:" + JavaTestExporter.REST_ASSURED_VERSION
                        + " and org.junit.jupiter:junit-jupiter:" + JavaTestExporter.JUNIT_VERSION + " (test scope).");
            }
        } catch (Exception e) {
            error("Export failed", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Markdown test document

    /** Exports a Markdown test document (test plan), or a test report when {@code report} is given. */
    public static void exportMarkdown(AppContext ctx, Collection c, Folder scope, io.postfx.engine.RunReport report) {
        if (c == null) return;
        Folder root = scope == null ? c : scope;
        Dialog<ButtonType> d = own(new Dialog<>());
        d.setTitle(report == null ? "Export test document" : "Export test report");
        d.setHeaderText((report == null ? "Markdown test document for \"" : "Markdown test report for \"") + root.name + "\"");
        ComboBox<Environment> env = new ComboBox<>();
        env.getItems().add(null);
        env.getItems().addAll(ctx.workspace.environments);
        env.setValue(ctx.activeEnvironment.get());
        env.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Environment e) {
                return e == null ? "No environment" : e.name;
            }

            @Override
            public Environment fromString(String s) {
                return null;
            }
        });
        CheckBox scripts = new CheckBox("Include pre-request and test scripts (collapsible)");
        scripts.setSelected(true);
        CheckBox checklist = new CheckBox("Add check boxes and a result line for manual testing");
        checklist.setSelected(report == null);
        checklist.setDisable(report != null);
        GridPane g = grid();
        int r = 0;
        g.addRow(r++, new Label("Variables from"), env);
        g.add(scripts, 0, r++, 2, 1);
        g.add(checklist, 0, r++, 2, 1);
        Label hint = new Label(report == null
                ? "One test case per request: request details, expected results (checks and pm.test names) and the values passed to the next requests."
                : "The test cases with the actual results of the last run (✅ / ❌, status, time and failure messages).");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        hint.setMaxWidth(460);
        g.add(hint, 0, r, 2, 1);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        io.postfx.io.MarkdownExporter.Options o = new io.postfx.io.MarkdownExporter.Options();
        o.environment = env.getValue();
        o.globals = ctx.workspace.globals;
        o.includeScripts = scripts.isSelected();
        o.checklist = checklist.isSelected();
        o.report = report;
        FileChooser fc = new FileChooser();
        fc.setTitle("Save the Markdown document");
        fc.setInitialFileName((root.name + (report == null ? " - test document" : " - test report") + ".md").replaceAll("[\\\\/:*?\"<>|]", "_"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
        File f = fc.showSaveDialog(owner);
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), io.postfx.io.MarkdownExporter.generate(c, scope, o), StandardCharsets.UTF_8);
            ctx.status.set("Markdown document written to " + f);
            info("Markdown document exported", f.toString());
        } catch (Exception e) {
            error("Export failed", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ import from text / URL

    /** Paste dialog: returns the pasted content (cURL, JSON or YAML) or a URL to fetch. */
    public static Optional<String[]> importText() {
        Dialog<ButtonType> d = own(new Dialog<>());
        d.setTitle("Import from text or link");
        d.setHeaderText("Paste a cURL command, a Postman / Insomnia / HAR JSON, an OpenAPI / Swagger document (JSON or YAML)\n"
                + "or a link to one (e.g. https://petstore3.swagger.io/api/v3/openapi.json).");
        TextArea area = new TextArea();
        area.setPromptText("curl https://api.example.com/users -H 'Accept: application/json'");
        area.getStyleClass().add("mono");
        area.setPrefSize(720, 380);
        d.getDialogPane().setContent(area);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResizable(true);
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return Optional.empty();
        String t = area.getText().strip();
        if (t.isEmpty()) return Optional.empty();
        boolean isUrl = t.matches("(?i)^https?://\\S+$");
        return Optional.of(new String[]{isUrl ? "url" : "text", t});
    }

    // ------------------------------------------------------------------ settings

    public static void settings(AppContext ctx) {
        Settings s = ctx.workspace.settings;
        Dialog<ButtonType> d = own(new Dialog<>());
        d.setTitle("Settings");
        Spinner<Integer> timeout = new Spinner<>(1000, 600000, s.timeoutMs, 1000);
        timeout.setEditable(true);
        Spinner<Integer> scriptTimeout = new Spinner<>(100, 600000, s.scriptTimeoutMs, 1000);
        scriptTimeout.setEditable(true);
        Spinner<Integer> history = new Spinner<>(10, 10000, s.historyLimit, 10);
        history.setEditable(true);
        CheckBox redirects = new CheckBox("Follow redirects automatically");
        redirects.setSelected(s.followRedirects);
        CheckBox ssl = new CheckBox("Verify SSL certificates");
        ssl.setSelected(s.verifySsl);
        CheckBox dark = new CheckBox("Dark theme");
        java.util.Map<String, String> tabChoices = new java.util.LinkedHashMap<>();
        tabChoices.put(Settings.RESPONSE_TAB_BODY, "Response body");
        tabChoices.put(Settings.RESPONSE_TAB_TESTS, "Test results");
        tabChoices.put(Settings.RESPONSE_TAB_CONSOLE, "Console");
        tabChoices.put(Settings.RESPONSE_TAB_HEADERS, "Headers");
        tabChoices.put(Settings.RESPONSE_TAB_AUTO, "Automatic (failed tests or script errors first)");
        tabChoices.put(Settings.RESPONSE_TAB_KEEP, "Keep the last selected tab");
        ComboBox<String> responseTab = new ComboBox<>();
        responseTab.getItems().addAll(tabChoices.keySet());
        responseTab.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String key) {
                return key == null ? "" : tabChoices.getOrDefault(key, key);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        responseTab.setValue(tabChoices.containsKey(s.responseTab) ? s.responseTab : Settings.RESPONSE_TAB_BODY);
        responseTab.setTooltip(new javafx.scene.control.Tooltip("Response tab shown after sending a request"));
        dark.setSelected(ctx.main().isDark());
        GridPane g = grid();
        int r = 0;
        g.addRow(r++, new Label("Request timeout (ms)"), timeout);
        g.addRow(r++, new Label("Script timeout (ms)"), scriptTimeout);
        g.addRow(r++, new Label("History size"), history);
        g.addRow(r++, new Label("After sending, show"), responseTab);
        g.add(redirects, 0, r++, 2, 1);
        g.add(ssl, 0, r++, 2, 1);
        g.add(dark, 0, r++, 2, 1);
        Label where = new Label("Workspace file: " + ctx.store.file());
        where.getStyleClass().add("hint");
        g.add(where, 0, r, 2, 1);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        s.timeoutMs = timeout.getValue();
        s.scriptTimeoutMs = scriptTimeout.getValue();
        s.historyLimit = history.getValue();
        s.followRedirects = redirects.isSelected();
        s.verifySsl = ssl.isSelected();
        s.responseTab = responseTab.getValue();
        ctx.main().setDark(dark.isSelected());
        ctx.changed();
    }
}
