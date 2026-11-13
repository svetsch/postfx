package io.postfx.ui;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.engine.ExecutionResult;
import io.postfx.engine.HttpResult;
import io.postfx.engine.JsonUtil;
import io.postfx.engine.RequestPreparer;
import io.postfx.engine.UrlUtil;
import io.postfx.engine.VariableContext;
import io.postfx.engine.VariableResolver;
import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.Check;
import io.postfx.model.CheckOperator;
import io.postfx.model.CheckSource;
import io.postfx.model.Collection;
import io.postfx.model.ExtractSource;
import io.postfx.model.Extractor;
import io.postfx.model.Folder;
import io.postfx.model.HistoryEntry;
import io.postfx.model.KeyValue;
import io.postfx.model.RequestItem;
import io.postfx.model.VarScope;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;

/** Editor for one request: URL bar, request tabs and the response pane. */
public class RequestEditor extends BorderPane {
    public static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private final AppContext ctx;
    private final RequestItem item;
    private final StringProperty title = new SimpleStringProperty();
    private final ComboBox<String> method = new ComboBox<>();
    private final TextField url = new TextField();
    private final Label resolved = new Label();
    private final Label breadcrumb = new Label();
    private final TextField name = new TextField();
    private final Button send = new Button("Send");
    private final KeyValueTable params = new KeyValueTable(false, true).prompts("Parameter", "Value");
    private final KeyValueTable pathVars = new KeyValueTable(false, true).prompts("Variable", "Value").fixedKeys(true);
    private final Label pathVarsLabel = new Label("PATH VARIABLES");
    private final KeyValueTable headers = new KeyValueTable(false, true).prompts("Header", "Value");
    private final AuthEditor auth = new AuthEditor(true);
    private final BodyEditor body = new BodyEditor();
    private final ScriptPane pre = new ScriptPane("Runs before the request is sent (set variables, sign requests, change the request).",
            Snippets.PRE_REQUEST, "Scripts");
    private final ScriptPane tests = new ScriptPane("Runs after the response is received (pm.test assertions, save values with pm.environment.set).",
            Snippets.TESTS, "Scripts");
    private final ChecksEditor checks = new ChecksEditor();
    private final ExtractorsEditor extractors = new ExtractorsEditor();
    private final TextArea docs = new TextArea();
    private final ResponseView response = new ResponseView();
    private final TabPane requestTabs = new TabPane();
    private final Tab paramsTab = new Tab("Params");
    private final Tab headersTab = new Tab("Headers");
    private final Tab bodyTab = new Tab("Body");
    private final Tab preTab = new Tab("Pre-request");
    private final Tab testsTab = new Tab("Tests");
    private final Tab checksTab = new Tab("Checks");
    private final Tab extractTab = new Tab("Extract");
    private final List<KeyValue> paramRows = new ArrayList<>();
    private boolean syncing;
    private final AtomicReference<CompletableFuture<HttpResult>> inFlight = new AtomicReference<>();
    private volatile boolean running;

    public RequestEditor(AppContext ctx, RequestItem item) {
        this.ctx = ctx;
        this.item = item;
        getStyleClass().add("request-editor");
        buildHeader();
        buildTabs();
        load();

        SplitPane split = new SplitPane(requestTabs, response);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.48);
        setCenter(split);

        response.setOnAddCheck(this::addCheckFromResponse);
        response.setOnExtract(this::extractFromResponse);
        ctx.activeEnvironment.addListener((o, a, b) -> updateResolved());
        ctx.onEnvironmentsChanged(this::updateResolved);
    }

    public RequestItem item() {
        return item;
    }

    public StringProperty titleProperty() {
        return title;
    }

    // ------------------------------------------------------------------ layout

    private void buildHeader() {
        name.getStyleClass().add("title-field");
        name.setTooltip(new Tooltip("Request name"));
        breadcrumb.getStyleClass().add("breadcrumb");
        Button save = new Button("Save");
        save.setTooltip(new Tooltip("Save (Ctrl+S). Requests in a collection are saved automatically."));
        save.setOnAction(e -> save());
        Button code = new Button("</> Code");
        code.setTooltip(new Tooltip("Generate cURL / Java code for this request"));
        code.setOnAction(e -> Dialogs.showCode(ctx, item));
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox top = new HBox(8, breadcrumb, name, code, save);
        top.setAlignment(Pos.CENTER_LEFT);

        method.getItems().addAll(METHODS);
        method.setEditable(true);
        method.setPrefWidth(110);
        method.getStyleClass().add("method-combo");
        url.setPromptText("Enter a URL, e.g. {{baseUrl}}/users?page=1  — or paste a cURL command");
        url.getStyleClass().add("url-field");
        HBox.setHgrow(url, Priority.ALWAYS);
        send.getStyleClass().add("send-button");
        send.setDefaultButton(false);
        send.setOnAction(e -> {
            if (running) cancel();
            else send();
        });
        url.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) send();
        });
        Tooltip urlTip = new Tooltip();
        url.setTooltip(urlTip);
        url.setOnMouseEntered(e -> urlTip.setText(variablesSummary(url.getText())));
        HBox bar = new HBox(6, method, url, send);
        bar.setAlignment(Pos.CENTER_LEFT);
        resolved.getStyleClass().add("resolved-url");
        VBox header = new VBox(6, top, bar, resolved);
        header.setPadding(new Insets(8, 10, 4, 10));
        setTop(header);
    }

    private void buildTabs() {
        VBox paramsBox = new VBox(4);
        Label q = new Label("QUERY PARAMS");
        q.getStyleClass().add("section-title");
        pathVarsLabel.getStyleClass().add("section-title");
        VBox.setVgrow(params, Priority.ALWAYS);
        paramsBox.getChildren().addAll(q, params, pathVarsLabel, pathVars);
        paramsBox.setPadding(new Insets(4));
        paramsTab.setContent(paramsBox);
        headersTab.setContent(withHint(headers, "Request headers. Auth headers, Content-Type and User-Agent are added automatically when missing."));
        bodyTab.setContent(body);
        preTab.setContent(pre);
        testsTab.setContent(tests);
        checksTab.setContent(checks);
        extractTab.setContent(extractors);
        docs.setPromptText("Describe what this request does (Markdown/plain text).");
        docs.setWrapText(true);
        requestTabs.getTabs().addAll(paramsTab, new Tab("Auth", auth), headersTab, bodyTab, preTab, testsTab, checksTab, extractTab,
                new Tab("Docs", docs));
        requestTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    }

    private static BorderPane withHint(javafx.scene.Node n, String hint) {
        Label l = new Label(hint);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        l.setPadding(new Insets(4, 6, 0, 6));
        BorderPane p = new BorderPane(n);
        p.setTop(l);
        return p;
    }

    // ------------------------------------------------------------------ model binding

    private void load() {
        name.setText(item.name);
        method.setValue(item.request.method);
        url.setText(item.request.url);
        reloadParams();
        reloadPathVariables();
        headers.setData(item.request.headers);
        auth.setInheritedDescription(this::inheritedAuthText);
        auth.setContext(ctx, this::currentVariables);
        auth.setAuth(item.request.auth);
        body.setBody(item.request.body);
        pre.setText(item.preRequestScript);
        tests.setText(item.testScript);
        checks.setChecks(item.checks);
        extractors.setExtractors(item.extractors);
        docs.setText(item.description == null ? "" : item.description);
        updateTitle();
        updateBadges();
        updateResolved();

        name.textProperty().addListener((o, a, b) -> {
            item.name = b;
            updateTitle();
            ctx.fireTreeChanged();
        });
        method.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            item.request.method = b.toUpperCase();
            updateTitle();
            ctx.fireTreeChanged();
        });
        url.textProperty().addListener((o, a, b) -> {
            if (CurlCodecHelper.isCurl(b)) {
                Platform.runLater(() -> importCurl(b));
                return;
            }
            item.request.url = b;
            if (!syncing) reloadParams();
            reloadPathVariables();
            updateResolved();
            changed();
        });
        params.setOnChange(() -> {
            List<KeyValue> enabled = paramRows.stream().filter(p -> p.enabled).toList();
            item.request.disabledParams = new ArrayList<>(paramRows.stream().filter(p -> !p.enabled && !p.isBlank()).toList());
            syncing = true;
            url.setText(UrlUtil.withQuery(url.getText(), enabled));
            syncing = false;
            updateBadges();
            changed();
        });
        pathVars.setOnChange(() -> {
            updateResolved();
            changed();
        });
        headers.setOnChange(() -> {
            updateBadges();
            changed();
        });
        auth.setOnChange(this::changed);
        body.setOnChange(() -> {
            updateBadges();
            changed();
        });
        pre.onChange(t -> {
            item.preRequestScript = t;
            updateBadges();
            changed();
        });
        tests.onChange(t -> {
            item.testScript = t;
            updateBadges();
            changed();
        });
        checks.setOnChange(() -> {
            updateBadges();
            changed();
        });
        extractors.setOnChange(() -> {
            updateBadges();
            changed();
        });
        docs.textProperty().addListener((o, a, b) -> {
            item.description = b.isBlank() ? null : b;
            changed();
        });
    }

    private void changed() {
        if (ctx.collectionOf(item) != null) ctx.changed();
    }

    private void reloadParams() {
        paramRows.clear();
        paramRows.addAll(UrlUtil.parseQuery(item.request.url));
        for (KeyValue d : item.request.disabledParams) {
            KeyValue kv = new KeyValue(d.key, d.value, false);
            kv.description = d.description;
            paramRows.add(kv);
        }
        params.setData(paramRows);
        updateBadges();
    }

    private void reloadPathVariables() {
        List<String> names = RequestPreparer.pathVariableNames(item.request.url);
        List<KeyValue> current = item.request.pathVariables;
        List<String> currentNames = current.stream().map(k -> k.key).toList();
        if (names.equals(currentNames) && pathVars.getData() == current) {
            boolean visible = !names.isEmpty();
            pathVars.setVisible(visible);
            pathVars.setManaged(visible);
            pathVarsLabel.setVisible(visible);
            pathVarsLabel.setManaged(visible);
            return;
        }
        Map<String, KeyValue> byName = new LinkedHashMap<>();
        for (KeyValue kv : current) byName.put(kv.key, kv);
        List<KeyValue> next = new ArrayList<>();
        for (String n : names) next.add(byName.getOrDefault(n, new KeyValue(n, "")));
        current.clear();
        current.addAll(next);
        pathVars.setData(current);
        boolean visible = !names.isEmpty();
        pathVars.setVisible(visible);
        pathVars.setManaged(visible);
        pathVarsLabel.setVisible(visible);
        pathVarsLabel.setManaged(visible);
    }

    private void updateTitle() {
        title.set(item.request.method + "  " + (item.name == null || item.name.isBlank() ? "Untitled" : item.name));
        Collection c = ctx.collectionOf(item);
        if (c == null) {
            breadcrumb.setText("Unsaved request ›");
        } else {
            StringBuilder sb = new StringBuilder();
            for (Folder f : ctx.ancestorsOf(item)) sb.append(f.name).append(" › ");
            breadcrumb.setText(sb.toString());
        }
    }

    private void updateBadges() {
        badge(paramsTab, "Params", (int) paramRows.stream().filter(p -> p.enabled && !p.isBlank()).count());
        badge(headersTab, "Headers", (int) item.request.headers.stream().filter(h -> h.enabled && !h.isBlank()).count());
        bodyTab.setText(item.request.body.mode == io.postfx.model.BodyMode.NONE ? "Body" : "Body •");
        preTab.setText(item.preRequestScript == null || item.preRequestScript.isBlank() ? "Pre-request" : "Pre-request •");
        testsTab.setText(item.testScript == null || item.testScript.isBlank() ? "Tests" : "Tests •");
        badge(checksTab, "Checks", item.checks.size());
        badge(extractTab, "Extract", item.extractors.size());
    }

    private static void badge(Tab t, String label, int count) {
        t.setText(count == 0 ? label : label + " (" + count + ")");
    }

    private VariableContext currentVariables() {
        return ctx.variables(ctx.collectionOf(item));
    }

    private void updateResolved() {
        VariableContext vars = currentVariables();
        String withPath = RequestPreparer.applyPathVariables(item.request.url == null ? "" : item.request.url, item.request.pathVariables);
        String r = vars.resolve(withPath);
        boolean unresolved = VariableResolver.hasPlaceholders(r);
        resolved.setText(r.equals(item.request.url) || r.isBlank() ? "" : "→ " + r + (unresolved ? "   (undefined variables)" : ""));
        resolved.getStyleClass().remove("unresolved");
        if (unresolved) resolved.getStyleClass().add("unresolved");
    }

    private String variablesSummary(String text) {
        VariableContext vars = currentVariables();
        Matcher m = VariableResolver.PLACEHOLDER.matcher(text == null ? "" : text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String n = m.group(1);
            String v = n.startsWith("$") ? "(dynamic variable)" : vars.get(n);
            sb.append(n).append(" = ").append(v == null ? "⚠ undefined" : v).append('\n');
        }
        return sb.length() == 0 ? "Use {{variable}} to insert environment or collection variables" : sb.toString().strip();
    }

    private String inheritedAuthText() {
        Auth a = RequestPreparer.effectiveAuth(new io.postfx.model.Request(), ctx.ancestorsOf(item));
        return a.type == AuthType.NONE ? "(Currently: no auth.)" : "(Currently: " + a.type.label + ".)";
    }

    private void importCurl(String text) {
        try {
            RequestItem parsed = io.postfx.io.CurlCodec.parse(text);
            item.request = parsed.request;
            if (item.name == null || item.name.isBlank() || item.name.equals("New Request")) item.name = parsed.name;
            ctx.status.set("cURL command imported");
            reloadAll();
            changed();
        } catch (Exception e) {
            Dialogs.error("Could not parse the cURL command", e.getMessage());
        }
    }

    private Runnable reloader;

    /** Called when the whole editor must be rebuilt from the model (e.g. after a cURL paste). */
    public void setReloader(Runnable r) {
        this.reloader = r;
    }

    private void reloadAll() {
        if (reloader != null) reloader.run();
    }

    // ------------------------------------------------------------------ actions

    public void save() {
        if (ctx.collectionOf(item) == null) {
            if (Dialogs.saveRequestAs(ctx, item)) {
                updateTitle();
                ctx.fireTreeChanged();
            }
            return;
        }
        ctx.saveNow();
    }

    public void send() {
        if (running) return;
        Collection collection = ctx.collectionOf(item);
        VariableContext vars = ctx.variables(collection);
        running = true;
        send.setText("Cancel");
        send.getStyleClass().add("cancel");
        response.showLoading(true);
        var settings = ctx.workspace.settings;
        ctx.background.submit(() -> {
            ExecutionResult r;
            try {
                r = ctx.executor.execute(item, collection, vars, settings, 0, 1, req -> {
                    CompletableFuture<HttpResult> f = ctx.http.sendAsync(req, settings);
                    inFlight.set(f);
                    try {
                        return f.join();
                    } catch (CancellationException e) {
                        HttpResult cancelled = new HttpResult();
                        cancelled.error = "Request cancelled";
                        return cancelled;
                    } finally {
                        inFlight.set(null);
                    }
                });
            } catch (Exception e) {
                r = new ExecutionResult();
                r.name = item.name;
                r.errors.add("Internal error: " + e);
            }
            ExecutionResult result = r;
            Platform.runLater(() -> done(result, vars, collection));
        });
    }

    private void cancel() {
        CompletableFuture<HttpResult> f = inFlight.get();
        if (f != null) f.cancel(true);
    }

    private void done(ExecutionResult r, VariableContext vars, Collection collection) {
        running = false;
        send.setText("Send");
        send.getStyleClass().remove("cancel");
        response.show(r);
        checks.showResults(r.tests);
        ctx.writeBack(vars, collection);
        addHistory(r);
        long failed = r.failedCount();
        ctx.status.set(r.response == null || r.response.failed() ? "Request failed" :
                r.response.status + " " + r.response.statusText + " in " + r.response.timeMs + " ms"
                        + (r.tests.isEmpty() ? "" : " — tests: " + (r.tests.size() - failed) + "/" + r.tests.size() + " passed"));
    }

    private void addHistory(ExecutionResult r) {
        HistoryEntry h = new HistoryEntry();
        h.timestamp = System.currentTimeMillis();
        h.name = item.name;
        h.request = JsonUtil.copy(item.request);
        if (r.response != null) {
            h.status = r.response.status;
            h.timeMs = r.response.timeMs;
        }
        ctx.workspace.history.add(0, h);
        while (ctx.workspace.history.size() > Math.max(10, ctx.workspace.settings.historyLimit)) {
            ctx.workspace.history.remove(ctx.workspace.history.size() - 1);
        }
        ctx.fireHistoryChanged();
    }

    private void addCheckFromResponse(String path, JsonNode node) {
        Check c = node.isValueNode()
                ? new Check(CheckSource.JSON_PATH, path, CheckOperator.EQUALS, node.isNull() ? "null" : node.asText())
                : new Check(CheckSource.JSON_PATH, path, CheckOperator.EXISTS, "");
        checks.addCheck(c);
        requestTabs.getSelectionModel().select(checksTab);
        ctx.status.set("Check added: " + c.describe());
    }

    private void extractFromResponse(String path, JsonNode node) {
        String suggestion = path.replaceAll(".*[.\\[']", "").replaceAll("[^A-Za-z0-9_]", "");
        if (suggestion.isEmpty() || Character.isDigit(suggestion.charAt(0))) suggestion = "value";
        TextInputDialog d = new TextInputDialog(suggestion);
        d.setTitle("Extract into a variable");
        d.setHeaderText("Store " + path + " into the environment variable:");
        d.setContentText("Variable name");
        d.initOwner(getScene().getWindow());
        d.showAndWait().ifPresent(n -> {
            if (n.isBlank()) return;
            extractors.addExtractor(new Extractor(n.strip(), ExtractSource.JSON_PATH, path, VarScope.ENVIRONMENT));
            requestTabs.getSelectionModel().select(extractTab);
            ctx.status.set("Extractor added: " + n + " = " + path + " (use it as {{" + n + "}})");
        });
    }

    public void focusUrl() {
        url.requestFocus();
    }

    /** Helper kept separate to avoid a hard dependency on the parser in the listener. */
    static final class CurlCodecHelper {
        static boolean isCurl(String s) {
            return s != null && s.length() > 5 && io.postfx.io.CurlCodec.looksLikeCurl(s);
        }
    }
}
