package io.postfx.ui;

import io.postfx.engine.CollectionRunner;
import io.postfx.engine.ConsoleEntry;
import io.postfx.engine.ExecutionResult;
import io.postfx.engine.RunOptions;
import io.postfx.engine.RunReport;
import io.postfx.engine.TestResult;
import io.postfx.engine.VariableContext;
import io.postfx.io.DataFileReader;
import io.postfx.io.ReportWriter;
import io.postfx.model.Collection;
import io.postfx.model.Environment;
import io.postfx.model.Folder;
import io.postfx.model.Items;
import io.postfx.model.RequestItem;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Collection runner: choose and order requests, iterations, data file, then run the chain and inspect results. */
public class RunnerView extends BorderPane {
    private final AppContext ctx;
    private final Collection collection;
    private final Folder scope;
    private final StringProperty title = new SimpleStringProperty();
    private final ListView<RequestItem> order = new ListView<>();
    private final Map<RequestItem, SimpleBooleanProperty> selected = new HashMap<>();
    private final ComboBox<Environment> environment = new ComboBox<>();
    private final Spinner<Integer> iterations = new Spinner<>(1, 10000, 1);
    private final Spinner<Integer> delay = new Spinner<>(0, 60000, 0, 100);
    private final CheckBox stopOnFailure = new CheckBox("Stop on first failure");
    private final CheckBox persist = new CheckBox("Keep variable values after the run");
    private final Label dataLabel = new Label("No data file");
    private final Button run = new Button("▶ Run");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label summary = new Label("Configure the run and press Run.");
    private final TreeView<Object> results = new TreeView<>();
    private final TextArea details = new TextArea();
    private final MenuButton exportReport = new MenuButton("Export results");
    private List<Map<String, String>> data = new ArrayList<>();
    private AtomicBoolean cancel;
    private RunReport lastReport;

    public RunnerView(AppContext ctx, Collection collection, Folder scope) {
        this.ctx = ctx;
        this.collection = collection;
        this.scope = scope == null ? collection : scope;
        title.set("Runner: " + this.scope.name);
        getStyleClass().add("runner");

        // ---- left: configuration
        for (RequestItem r : Items.flatten(this.scope)) {
            order.getItems().add(r);
            selected.put(r, new SimpleBooleanProperty(true));
        }
        order.setCellFactory(CheckBoxListCell.forListView(selected::get, new javafx.util.StringConverter<>() {
            @Override
            public String toString(RequestItem r) {
                return r.request.method + "  " + r.name;
            }

            @Override
            public RequestItem fromString(String s) {
                return null;
            }
        }));
        order.setPrefHeight(260);
        Button up = new Button("↑");
        up.setTooltip(new Tooltip("Move up (run earlier)"));
        up.setOnAction(e -> moveSelected(-1));
        Button down = new Button("↓");
        down.setTooltip(new Tooltip("Move down (run later)"));
        down.setOnAction(e -> moveSelected(1));
        Button all = new Button("All");
        all.setOnAction(e -> selected.values().forEach(p -> p.set(true)));
        Button none = new Button("None");
        none.setOnAction(e -> selected.values().forEach(p -> p.set(false)));
        HBox orderBar = new HBox(4, up, down, all, none);

        environment.getItems().add(null);
        environment.getItems().addAll(ctx.workspace.environments);
        environment.setValue(ctx.activeEnvironment.get());
        environment.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Environment e) {
                return e == null ? "No environment" : e.name;
            }

            @Override
            public Environment fromString(String s) {
                return null;
            }
        });
        iterations.setEditable(true);
        delay.setEditable(true);
        persist.setSelected(true);
        Button chooseData = new Button("Select file…");
        chooseData.setOnAction(e -> chooseData());
        Button clearData = new Button("✕");
        clearData.getStyleClass().add("icon-button");
        clearData.setOnAction(e -> {
            data = new ArrayList<>();
            dataLabel.setText("No data file");
        });
        dataLabel.getStyleClass().add("hint");

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        int row = 0;
        form.add(new Label("Environment"), 0, row);
        form.add(environment, 1, row++);
        form.add(new Label("Iterations"), 0, row);
        form.add(iterations, 1, row++);
        form.add(new Label("Delay (ms)"), 0, row);
        form.add(delay, 1, row++);
        form.add(new Label("Data file"), 0, row);
        form.add(new HBox(4, chooseData, clearData), 1, row++);
        form.add(dataLabel, 1, row++);
        form.add(stopOnFailure, 0, row++, 2, 1);
        form.add(persist, 0, row, 2, 1);

        Label orderTitle = new Label("RUN ORDER");
        orderTitle.getStyleClass().add("section-title");
        Label orderHint = new Label("Untick to skip; reorder to change the chain. Scripts can jump with pm.execution.setNextRequest().");
        orderHint.getStyleClass().add("hint");
        orderHint.setWrapText(true);
        Label settingsTitle = new Label("SETTINGS");
        settingsTitle.getStyleClass().add("section-title");
        run.getStyleClass().add("send-button");
        run.setMaxWidth(Double.MAX_VALUE);
        run.setOnAction(e -> {
            if (cancel != null) cancel.set(true);
            else start();
        });
        Button help = new Button("? Runner help");
        help.getStyleClass().add("link-button");
        help.setOnAction(e -> HelpWindow.show(getScene().getWindow(), "Runner and test chains"));
        VBox left = new VBox(8, orderTitle, orderHint, order, orderBar, settingsTitle, form, run, help);
        left.setPadding(new Insets(10));
        VBox.setVgrow(order, Priority.ALWAYS);
        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.setFitToHeight(true);
        leftScroll.setMinWidth(330);

        // ---- right: results
        progress.setMaxWidth(Double.MAX_VALUE);
        summary.getStyleClass().add("section-title");
        MenuItem junit = new MenuItem("JUnit XML…");
        junit.setOnAction(e -> exportReport("xml"));
        MenuItem json = new MenuItem("JSON…");
        json.setOnAction(e -> exportReport("json"));
        MenuItem text = new MenuItem("Text summary…");
        text.setOnAction(e -> exportReport("txt"));
        exportReport.getItems().addAll(junit, json, text);
        exportReport.setDisable(true);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox resBar = new HBox(8, summary, grow, exportReport);
        resBar.setAlignment(Pos.CENTER_LEFT);
        results.setShowRoot(false);
        results.setCellFactory(tv -> new ResultCell());
        results.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showDetails(b == null ? null : b.getValue()));
        details.setEditable(false);
        details.getStyleClass().add("mono");
        details.setPromptText("Select a request to see its details");
        SplitPane resSplit = new SplitPane(results, details);
        resSplit.setDividerPositions(0.6);
        VBox right = new VBox(6, resBar, progress, resSplit);
        right.setPadding(new Insets(10));
        VBox.setVgrow(resSplit, Priority.ALWAYS);

        SplitPane split = new SplitPane(leftScroll, right);
        split.setDividerPositions(0.3);
        setCenter(split);
    }

    public StringProperty titleProperty() {
        return title;
    }

    private void moveSelected(int delta) {
        int i = order.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= order.getItems().size()) return;
        RequestItem r = order.getItems().remove(i);
        order.getItems().add(j, r);
        order.getSelectionModel().select(j);
    }

    private void chooseData() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Iteration data (CSV or JSON)");
        fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("CSV / JSON", "*.csv", "*.json", "*.txt"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File f = fc.showOpenDialog(getScene().getWindow());
        if (f == null) return;
        try {
            data = DataFileReader.read(f.toPath());
            String cols = data.isEmpty() ? "" : String.join(", ", data.get(0).keySet());
            dataLabel.setText(f.getName() + ": " + data.size() + " row(s) [" + cols + "]");
            if (!data.isEmpty()) iterations.getValueFactory().setValue(data.size());
        } catch (Exception ex) {
            Dialogs.error("Could not read the data file", ex.getMessage());
        }
    }

    private void start() {
        List<RequestItem> selection = order.getItems().stream().filter(r -> selected.get(r).get()).toList();
        if (selection.isEmpty()) {
            Dialogs.error("Nothing to run", "Select at least one request.");
            return;
        }
        RunOptions opts = new RunOptions();
        opts.selection = new ArrayList<>(selection);
        opts.iterations = iterations.getValue();
        opts.delayMs = delay.getValue();
        opts.stopOnFailure = stopOnFailure.isSelected();
        opts.data = data;
        if (!data.isEmpty() && opts.iterations != data.size()) opts.iterations = Math.max(opts.iterations, 1);
        Environment env = environment.getValue();
        VariableContext vars = VariableContext.of(ctx.workspace, collection, env);
        boolean keep = persist.isSelected();

        TreeItem<Object> root = new TreeItem<>();
        results.setRoot(root);
        details.clear();
        cancel = new AtomicBoolean(false);
        run.setText("■ Stop");
        exportReport.setDisable(true);
        int total = Math.max(1, opts.iterations) * selection.size();
        int[] done = {0};
        long[] failures = {0};
        Map<Integer, TreeItem<Object>> iterationNodes = new HashMap<>();
        summary.setText("Running…");
        progress.setProgress(0);

        AtomicBoolean myCancel = cancel;
        ctx.background.submit(() -> {
            RunReport report = ctx.runner.run(collection, vars, ctx.workspace.settings, opts, new CollectionRunner.Listener() {
                @Override
                public void onIterationStart(int iteration, int count) {
                    Platform.runLater(() -> {
                        TreeItem<Object> it = new TreeItem<>("Iteration " + (iteration + 1) + " / " + count);
                        it.setExpanded(true);
                        iterationNodes.put(iteration, it);
                        root.getChildren().add(it);
                    });
                }

                @Override
                public void onRequestDone(ExecutionResult r) {
                    Platform.runLater(() -> {
                        done[0]++;
                        if (!r.passed()) failures[0]++;
                        progress.setProgress(Math.min(1.0, done[0] / (double) total));
                        summary.setText("Running… " + done[0] + " request(s), " + failures[0] + " failed");
                        TreeItem<Object> req = new TreeItem<>(r);
                        req.setExpanded(!r.passed());
                        for (TestResult t : r.tests) req.getChildren().add(new TreeItem<>(t));
                        for (String err : r.errors) req.getChildren().add(new TreeItem<>("⚠ " + err));
                        TreeItem<Object> parent = iterationNodes.getOrDefault(r.iteration, root);
                        parent.getChildren().add(req);
                    });
                }
            }, myCancel);
            Platform.runLater(() -> finished(report, vars, env, keep));
        });
    }

    private void finished(RunReport report, VariableContext vars, Environment env, boolean keep) {
        cancel = null;
        lastReport = report;
        run.setText("▶ Run again");
        progress.setProgress(1);
        exportReport.setDisable(false);
        String state = report.cancelled ? "Stopped" : report.stoppedOnFailure ? "Stopped on failure" : report.success() ? "✔ Passed" : "✘ Failed";
        summary.setText(state + " — " + report.totalRequests() + " request(s), " + report.failedRequests() + " failed · tests "
                + report.passedTests() + " passed, " + report.failedTests() + " failed · avg " + report.averageResponseTime()
                + " ms · " + report.durationMs + " ms");
        summary.getStyleClass().removeAll("pass", "fail");
        summary.getStyleClass().add(report.success() ? "pass" : "fail");
        if (!report.errors.isEmpty()) results.getRoot().getChildren().add(new TreeItem<>("⚠ " + String.join("; ", report.errors)));
        if (keep) {
            vars.writeBack(ctx.workspace, collection, env);
            ctx.fireEnvironmentsChanged();
        }
        ctx.status.set("Run finished: " + state);
    }

    private void showDetails(Object o) {
        if (o instanceof ExecutionResult r) {
            StringBuilder sb = new StringBuilder();
            sb.append(ResponseView.describe(r.sent)).append("\n\n");
            if (r.response != null) {
                if (r.response.failed()) sb.append("ERROR: ").append(r.response.error).append('\n');
                else {
                    sb.append("HTTP ").append(r.response.status).append(' ').append(r.response.statusText).append("  (")
                            .append(r.response.timeMs).append(" ms)\n");
                    r.response.headers.forEach(h -> sb.append(h.key).append(": ").append(h.value).append('\n'));
                    String body = io.postfx.engine.JsonUtil.prettyOrSame(r.response.body);
                    sb.append('\n').append(body.length() > 20000 ? body.substring(0, 20000) + "\n…" : body).append('\n');
                }
            }
            if (!r.console.isEmpty()) {
                sb.append("\n--- console ---\n");
                for (ConsoleEntry c : r.console) sb.append('[').append(c.level).append("] ").append(c.message).append('\n');
            }
            details.setText(sb.toString());
        } else if (o instanceof TestResult t) {
            details.setText((t.passed ? "PASS " : "FAIL ") + t.name + (t.error == null ? "" : "\n\n" + t.error));
        } else {
            details.clear();
        }
    }

    private void exportReport(String kind) {
        if (lastReport == null) return;
        FileChooser fc = new FileChooser();
        fc.setInitialFileName("postfx-report." + kind);
        File f = fc.showSaveDialog(getScene().getWindow());
        if (f == null) return;
        try {
            switch (kind) {
                case "xml" -> ReportWriter.writeJUnit(lastReport, f.toPath());
                case "json" -> ReportWriter.writeJson(lastReport, f.toPath());
                default -> java.nio.file.Files.writeString(f.toPath(), ReportWriter.text(lastReport));
            }
            ctx.status.set("Report written to " + f);
        } catch (Exception e) {
            Dialogs.error("Could not write the report", e.getMessage());
        }
    }

    private static final class ResultCell extends TreeCell<Object> {
        @Override
        protected void updateItem(Object o, boolean empty) {
            super.updateItem(o, empty);
            getStyleClass().removeAll("pass", "fail", "skip");
            setGraphic(null);
            if (empty || o == null) {
                setText(null);
                return;
            }
            if (o instanceof ExecutionResult r) {
                String status = r.skipped ? "skipped" : r.response == null ? "not sent" : r.response.failed() ? r.response.error
                        : r.response.status + " " + r.response.statusText + " · " + r.response.timeMs + " ms";
                setText((r.passed() ? "✔ " : "✘ ") + r.name + "   [" + status + "]"
                        + (r.tests.isEmpty() ? "" : "   " + r.passedCount() + "/" + r.tests.size() + " tests"));
                getStyleClass().add(r.skipped ? "skip" : r.passed() ? "pass" : "fail");
            } else if (o instanceof TestResult t) {
                setText((t.skipped ? "– " : t.passed ? "✔ " : "✘ ") + t.name + (t.passed || t.error == null ? "" : "  — " + t.error));
                getStyleClass().add(t.skipped ? "skip" : t.passed ? "pass" : "fail");
            } else {
                setText(o.toString());
                if (o.toString().startsWith("⚠")) getStyleClass().add("fail");
            }
        }
    }
}
