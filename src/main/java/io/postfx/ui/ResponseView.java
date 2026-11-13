package io.postfx.ui;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.engine.ConsoleEntry;
import io.postfx.engine.ExecutionResult;
import io.postfx.engine.HttpResult;
import io.postfx.engine.JsonUtil;
import io.postfx.engine.ResolvedRequest;
import io.postfx.engine.TestResult;
import io.postfx.model.KeyValue;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.util.function.BiConsumer;

/** Shows a response: status line, pretty/raw/tree body, headers, test results, console and the sent request. */
public class ResponseView extends BorderPane {
    private final Label status = new Label();
    private final Label time = new Label();
    private final Label size = new Label();
    private final Label placeholder = new Label("Enter a URL and click Send (Ctrl+Enter) to get a response");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final CodeEditor pretty = new CodeEditor("json");
    private final CodeEditor raw = new CodeEditor("text");
    private final JsonTreeView tree = new JsonTreeView();
    private final StackPane bodyStack = new StackPane();
    private final ToggleGroup bodyMode = new ToggleGroup();
    private final TableView<KeyValue> headers = new TableView<>();
    private final ListView<TestResult> tests = new ListView<>();
    private final ListView<ConsoleEntry> console = new ListView<>();
    private final CodeEditor sent = new CodeEditor("text");
    private final Tab testsTab = new Tab("Test Results");
    private final Tab consoleTab = new Tab("Console");
    private final TabPane tabs = new TabPane();
    private final StackPane center = new StackPane();
    private HttpResult current;

    public ResponseView() {
        getStyleClass().add("response-view");
        status.getStyleClass().add("status-label");
        time.getStyleClass().add("meta-label");
        size.getStyleClass().add("meta-label");
        placeholder.getStyleClass().add("hint");
        progress.setMaxSize(48, 48);
        progress.setVisible(false);

        Button copy = new Button("Copy");
        copy.setTooltip(new Tooltip("Copy the response body"));
        copy.setOnAction(e -> {
            if (current == null) return;
            ClipboardContent c = new ClipboardContent();
            c.putString(current.body);
            Clipboard.getSystemClipboard().setContent(c);
        });
        Button save = new Button("Save…");
        save.setTooltip(new Tooltip("Save the response body to a file"));
        save.setOnAction(e -> {
            if (current == null) return;
            FileChooser fc = new FileChooser();
            fc.setInitialFileName(current.contentType().contains("json") ? "response.json" : "response.txt");
            File f = fc.showSaveDialog(getScene().getWindow());
            if (f != null) {
                try {
                    Files.write(f.toPath(), current.bodyBytes);
                } catch (Exception ex) {
                    Dialogs.error("Could not save the response", ex.getMessage());
                }
            }
        });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Label title = new Label("Response");
        title.getStyleClass().add("section-title");
        HBox bar = new HBox(12, title, status, time, size, grow, copy, save);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        setTop(bar);

        // body
        pretty.setEditable(false);
        raw.setEditable(false);
        raw.setWrap(true);
        sent.setEditable(false);
        HBox modes = new HBox(0);
        for (String m : new String[]{"Pretty", "Raw", "Tree"}) {
            ToggleButton b = new ToggleButton(m);
            b.setToggleGroup(bodyMode);
            b.setUserData(m);
            b.getStyleClass().add("segment");
            modes.getChildren().add(b);
        }
        Label treeHint = new Label("Tree: right-click a value to add a check or extract it into a variable");
        treeHint.getStyleClass().add("hint");
        HBox bodyBar = new HBox(10, modes, treeHint);
        bodyBar.setAlignment(Pos.CENTER_LEFT);
        bodyBar.setPadding(new Insets(4));
        bodyStack.getChildren().addAll(pretty, raw, tree);
        bodyMode.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) {
                bodyMode.selectToggle(a);
                return;
            }
            String m = (String) b.getUserData();
            pretty.setVisible(m.equals("Pretty"));
            raw.setVisible(m.equals("Raw"));
            tree.setVisible(m.equals("Tree"));
            treeHint.setVisible(m.equals("Tree"));
        });
        bodyMode.selectToggle(bodyMode.getToggles().get(0));
        BorderPane bodyPane = new BorderPane(bodyStack);
        bodyPane.setTop(bodyBar);

        // headers
        TableColumn<KeyValue, String> k = new TableColumn<>("Key");
        k.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().key));
        k.setPrefWidth(220);
        TableColumn<KeyValue, String> v = new TableColumn<>("Value");
        v.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().value));
        v.setPrefWidth(500);
        headers.getColumns().add(k);
        headers.getColumns().add(v);
        headers.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        tests.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(TestResult t, boolean empty) {
                super.updateItem(t, empty);
                getStyleClass().removeAll("pass", "fail", "skip");
                if (empty || t == null) {
                    setText(null);
                    return;
                }
                String kind = "check".equals(t.origin) ? "[check] " : "";
                if (t.skipped) {
                    setText("SKIPPED  " + kind + t.name);
                    getStyleClass().add("skip");
                } else if (t.passed) {
                    setText("PASS  " + kind + t.name);
                    getStyleClass().add("pass");
                } else {
                    setText("FAIL  " + kind + t.name + "\n        " + t.error);
                    getStyleClass().add("fail");
                }
            }
        });
        console.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ConsoleEntry c, boolean empty) {
                super.updateItem(c, empty);
                getStyleClass().removeAll("log-warn", "log-error", "log-info");
                if (empty || c == null) {
                    setText(null);
                    return;
                }
                setText((c.source == null ? "" : "[" + c.source + "]  ") + c.message);
                if (c.level.equals("warn")) getStyleClass().add("log-warn");
                if (c.level.equals("error")) getStyleClass().add("log-error");
                if (c.level.equals("info")) getStyleClass().add("log-info");
            }
        });

        Tab body = new Tab("Body", bodyPane);
        Tab hdr = new Tab("Headers", headers);
        testsTab.setContent(tests);
        consoleTab.setContent(console);
        Tab req = new Tab("Request", sent);
        tabs.getTabs().addAll(body, hdr, testsTab, consoleTab, req);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setVisible(false);
        center.getChildren().addAll(placeholder, tabs, progress);
        setCenter(center);
        status.setText("");
    }

    public void setOnAddCheck(BiConsumer<String, JsonNode> c) {
        tree.setOnAddCheck(c);
    }

    public void setOnExtract(BiConsumer<String, JsonNode> c) {
        tree.setOnExtract(c);
    }

    public void showLoading(boolean loading) {
        progress.setVisible(loading);
        if (loading) placeholder.setText("Sending…");
    }

    public void show(ExecutionResult r) {
        progress.setVisible(false);
        HttpResult res = r.response;
        current = res;
        status.getStyleClass().removeAll("s2", "s3", "s4", "s5", "serr");
        tests.getItems().setAll(r.tests);
        console.getItems().setAll(r.console);
        long failed = r.failedCount();
        testsTab.setText(r.tests.isEmpty() ? "Test Results" : "Test Results (" + (r.tests.size() - failed) + "/" + r.tests.size() + ")");
        testsTab.getStyleClass().removeAll("tab-fail", "tab-pass");
        if (!r.tests.isEmpty()) testsTab.getStyleClass().add(failed > 0 ? "tab-fail" : "tab-pass");
        consoleTab.setText(r.console.isEmpty() ? "Console" : "Console (" + r.console.size() + ")");
        sent.setText(describe(r.sent));

        if (res == null) {
            placeholder.setText(r.skipped ? "Request skipped by the pre-request script" : String.join("\n", r.errors));
            tabs.setVisible(!r.console.isEmpty());
            status.setText(r.skipped ? "Skipped" : "Not sent");
            time.setText("");
            size.setText("");
            if (!r.console.isEmpty()) tabs.getSelectionModel().select(consoleTab);
            return;
        }
        tabs.setVisible(true);
        if (res.failed()) {
            status.setText("Error");
            status.getStyleClass().add("serr");
            time.setText(res.timeMs + " ms");
            size.setText("");
            pretty.setText(res.error);
            raw.setText(res.error);
            tree.setJson(null);
            headers.getItems().clear();
            tabs.getSelectionModel().select(consoleTab);
            return;
        }
        status.setText(res.status + " " + res.statusText);
        status.getStyleClass().add(res.status >= 500 ? "s5" : res.status >= 400 ? "s4" : res.status >= 300 ? "s3" : "s2");
        time.setText(res.timeMs + " ms");
        size.setText(humanSize(res.size()));
        headers.getItems().setAll(res.headers);
        String ct = res.contentType();
        JsonNode json = res.json();
        String lang = json != null ? "json" : ct.contains("xml") || ct.contains("html") ? "xml" : "text";
        pretty.setLanguage(lang);
        pretty.setText(json != null && json.isContainerNode() ? JsonUtil.toPrettyJson(json) : res.body);
        raw.setText(res.body);
        tree.setJson(json);
        if (!r.errors.isEmpty()) tabs.getSelectionModel().select(consoleTab);
        else if (failed > 0) tabs.getSelectionModel().select(testsTab);
    }

    static String describe(ResolvedRequest r) {
        if (r == null) return "";
        StringBuilder sb = new StringBuilder(r.method).append(' ').append(r.url).append("\n\n");
        for (KeyValue h : r.headers) sb.append(h.key).append(": ").append(h.value).append('\n');
        if (r.bodyPreview != null && !r.bodyPreview.isEmpty()) sb.append('\n').append(r.bodyPreview);
        return sb.toString();
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
