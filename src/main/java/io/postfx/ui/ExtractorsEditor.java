package io.postfx.ui;

import io.postfx.model.ExtractSource;
import io.postfx.model.Extractor;
import io.postfx.model.VarScope;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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

/** Extractors: copy response values into variables so the next requests of a chain can use them. */
public class ExtractorsEditor extends BorderPane {
    private final VBox rows = new VBox(4);
    private List<Extractor> extractors = new ArrayList<>();
    private Runnable onChange = () -> {};

    public ExtractorsEditor() {
        Label hint = new Label("After the response, each extractor stores a value in a variable (use it later as {{name}}). "
                + "This is how request chains are built: e.g. extract $.token from a login and use {{token}} in the next requests.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        Button add = new Button("+ Add extractor");
        add.setOnAction(e -> addExtractor(new Extractor()));
        Button help = new Button("? Help");
        help.getStyleClass().add("link-button");
        help.setOnAction(e -> HelpWindow.show(getScene().getWindow(), "Checks and extractors"));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox bar = new HBox(8, add, grow, help);
        VBox top = new VBox(6, hint, bar);
        top.setPadding(new Insets(6));
        setTop(top);
        ScrollPane sp = new ScrollPane(rows);
        sp.setFitToWidth(true);
        rows.setPadding(new Insets(4, 6, 4, 6));
        setCenter(sp);
    }

    public void setOnChange(Runnable r) {
        this.onChange = r;
    }

    public void setExtractors(List<Extractor> list) {
        this.extractors = list;
        rows.getChildren().clear();
        for (Extractor e : list) rows.getChildren().add(row(e));
    }

    public void addExtractor(Extractor e) {
        extractors.add(e);
        rows.getChildren().add(row(e));
        onChange.run();
    }

    private HBox row(Extractor ex) {
        CheckBox enabled = new CheckBox();
        enabled.setSelected(ex.enabled);
        TextField variable = new TextField(ex.variable);
        variable.setPromptText("variable name");
        variable.setPrefWidth(140);
        ComboBox<ExtractSource> source = new ComboBox<>();
        source.getItems().addAll(ExtractSource.values());
        source.setValue(ex.source);
        TextField expression = new TextField(ex.expression);
        HBox.setHgrow(expression, Priority.ALWAYS);
        expression.setMaxWidth(Double.MAX_VALUE);
        Label into = new Label("into");
        ComboBox<VarScope> scope = new ComboBox<>();
        scope.getItems().addAll(VarScope.values());
        scope.setValue(ex.scope);
        scope.setTooltip(new Tooltip("Variable scope where the value is stored"));
        Button del = new Button("✕");
        del.getStyleClass().add("icon-button");

        Runnable refresh = () -> {
            boolean needs = ex.source != ExtractSource.STATUS && ex.source != ExtractSource.BODY;
            expression.setDisable(!needs);
            expression.setPromptText(switch (ex.source) {
                case JSON_PATH -> "$.data.token";
                case HEADER -> "header name, e.g. Location";
                case BODY_REGEX -> "regex, e.g. id=(\\d+)";
                default -> "";
            });
        };
        refresh.run();
        enabled.selectedProperty().addListener((o, a, b) -> { ex.enabled = b; onChange.run(); });
        variable.textProperty().addListener((o, a, b) -> { ex.variable = b; onChange.run(); });
        source.valueProperty().addListener((o, a, b) -> { ex.source = b; refresh.run(); onChange.run(); });
        expression.textProperty().addListener((o, a, b) -> { ex.expression = b; onChange.run(); });
        scope.valueProperty().addListener((o, a, b) -> { ex.scope = b; onChange.run(); });

        Label from = new Label("=");
        HBox row = new HBox(6, enabled, variable, from, source, expression, into, scope, del);
        row.setAlignment(Pos.CENTER_LEFT);
        del.setOnAction(e -> {
            extractors.remove(ex);
            rows.getChildren().remove(row);
            onChange.run();
        });
        return row;
    }
}
