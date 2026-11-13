package io.postfx.ui;

import io.postfx.engine.TestResult;
import io.postfx.model.Check;
import io.postfx.model.CheckOperator;
import io.postfx.model.CheckSource;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** No-code assertions: one row per check (source, property, operator, expected value). */
public class ChecksEditor extends BorderPane {
    private final VBox rows = new VBox(4);
    private final List<Label> resultLabels = new ArrayList<>();
    private List<Check> checks = new ArrayList<>();
    private Runnable onChange = () -> {};

    public ChecksEditor() {
        Label hint = new Label("Checks are evaluated after each response, without writing code. Expected values may use {{variables}}. "
                + "Tip: right-click a value in the response JSON tree to add a check.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        Button add = new Button("+ Add check");
        add.setOnAction(e -> addCheck(new Check()));
        MenuButton presets = new MenuButton("Presets");
        presets.getItems().addAll(
                preset("Status code is 200", new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "200")),
                preset("Status code is 201", new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "201")),
                preset("Response time < 1000 ms", new Check(CheckSource.RESPONSE_TIME, "", CheckOperator.LESS_THAN, "1000")),
                preset("Content-Type is JSON", new Check(CheckSource.HEADER, "Content-Type", CheckOperator.CONTAINS, "application/json")),
                preset("JSON field exists", new Check(CheckSource.JSON_PATH, "$.id", CheckOperator.EXISTS, "")),
                preset("JSON array is not empty", new Check(CheckSource.JSON_PATH, "$", CheckOperator.IS_NOT_EMPTY, "")),
                preset("Body contains text", new Check(CheckSource.BODY, "", CheckOperator.CONTAINS, "text")));
        Button help = new Button("? Help");
        help.getStyleClass().add("link-button");
        help.setOnAction(e -> HelpWindow.show(getScene().getWindow(), "Checks and extractors"));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox bar = new HBox(8, add, presets, grow, help);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(6, hint, bar);
        top.setPadding(new Insets(6));
        setTop(top);
        ScrollPane sp = new ScrollPane(rows);
        sp.setFitToWidth(true);
        rows.setPadding(new Insets(4, 6, 4, 6));
        setCenter(sp);
    }

    private MenuItem preset(String name, Check c) {
        MenuItem mi = new MenuItem(name);
        mi.setOnAction(e -> addCheck(new Check(c.source, c.property, c.operator, c.expected)));
        return mi;
    }

    public void setOnChange(Runnable r) {
        this.onChange = r;
    }

    public void setChecks(List<Check> list) {
        this.checks = list;
        rows.getChildren().clear();
        resultLabels.clear();
        for (Check c : list) rows.getChildren().add(row(c));
    }

    public void addCheck(Check c) {
        checks.add(c);
        rows.getChildren().add(row(c));
        onChange.run();
    }

    /** Shows the outcome of the last execution next to each enabled check. */
    public void showResults(List<TestResult> tests) {
        List<TestResult> checkResults = tests.stream().filter(t -> "check".equals(t.origin)).toList();
        int i = 0;
        for (int r = 0; r < checks.size() && r < resultLabels.size(); r++) {
            Label l = resultLabels.get(r);
            l.getStyleClass().removeAll("pass", "fail");
            if (!checks.get(r).enabled) {
                l.setText("");
                l.setTooltip(null);
                continue;
            }
            if (i < checkResults.size()) {
                TestResult t = checkResults.get(i++);
                l.setText(t.passed ? "✔" : "✘");
                l.getStyleClass().add(t.passed ? "pass" : "fail");
                l.setTooltip(new Tooltip(t.passed ? "Passed" : t.error));
            }
        }
    }

    private HBox row(Check c) {
        CheckBox enabled = new CheckBox();
        enabled.setSelected(c.enabled);
        ComboBox<CheckSource> source = new ComboBox<>();
        source.getItems().addAll(CheckSource.values());
        source.setValue(c.source);
        TextField property = new TextField(c.property);
        ComboBox<CheckOperator> op = new ComboBox<>();
        op.getItems().addAll(CheckOperator.values());
        op.setValue(c.operator);
        TextField expected = new TextField(c.expected);
        expected.setPromptText("expected value");
        Label result = new Label();
        result.setMinWidth(18);
        resultLabels.add(result);
        Button del = new Button("✕");
        del.getStyleClass().add("icon-button");
        del.setTooltip(new Tooltip("Remove check"));
        HBox.setHgrow(property, Priority.ALWAYS);
        HBox.setHgrow(expected, Priority.ALWAYS);
        property.setPrefWidth(10);
        expected.setPrefWidth(10);
        property.setMaxWidth(Double.MAX_VALUE);
        expected.setMaxWidth(Double.MAX_VALUE);

        Runnable refresh = () -> {
            property.setDisable(!c.source.needsProperty());
            property.setPromptText(c.source == CheckSource.HEADER ? "header name" : c.source == CheckSource.JSON_PATH ? "$.path.to[0].value" : "");
            expected.setDisable(!c.operator.needsExpected());
            expected.setPromptText(c.operator == CheckOperator.TYPE_IS ? "string|number|boolean|object|array|null" : "expected value");
        };
        refresh.run();
        enabled.selectedProperty().addListener((o, a, b) -> { c.enabled = b; onChange.run(); });
        source.valueProperty().addListener((o, a, b) -> { c.source = b; refresh.run(); onChange.run(); });
        property.textProperty().addListener((o, a, b) -> { c.property = b; onChange.run(); });
        op.valueProperty().addListener((o, a, b) -> { c.operator = b; refresh.run(); onChange.run(); });
        expected.textProperty().addListener((o, a, b) -> { c.expected = b; onChange.run(); });

        HBox row = new HBox(6, enabled, source, property, op, expected, result, del);
        row.setAlignment(Pos.CENTER_LEFT);
        del.setOnAction(e -> {
            int idx = rows.getChildren().indexOf(row);
            checks.remove(c);
            rows.getChildren().remove(row);
            if (idx >= 0 && idx < resultLabels.size()) resultLabels.remove(idx);
            onChange.run();
        });
        return row;
    }
}
