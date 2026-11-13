package io.postfx.ui;

import io.postfx.model.Environment;
import io.postfx.model.KeyValue;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** Editor for an environment or for the global variables. */
public class EnvironmentEditor extends BorderPane {
    private final StringProperty title = new SimpleStringProperty();
    private final Object target;
    private final KeyValueTable table = new KeyValueTable(false, true).prompts("Variable", "Value");

    /** Environment editor. */
    public EnvironmentEditor(AppContext ctx, Environment env) {
        this.target = env;
        TextField name = new TextField(env.name);
        name.getStyleClass().add("title-field");
        HBox.setHgrow(name, Priority.ALWAYS);
        Button activate = new Button("Set active");
        activate.setOnAction(e -> ctx.activeEnvironment.set(env));
        Button export = new Button("Export (Postman)…");
        export.setOnAction(e -> Dialogs.exportEnvironment(ctx, env));
        HBox top = new HBox(8, new Label("Environment"), name, activate, export);
        top.setAlignment(Pos.CENTER_LEFT);
        name.textProperty().addListener((o, a, b) -> {
            env.name = b;
            title.set("ENV  " + b);
            ctx.fireEnvironmentsChanged();
        });
        title.set("ENV  " + env.name);
        init(ctx, top, env.values, "Variables of this environment, used as {{name}} when it is the active environment "
                + "(selector at the top right). They override collection and global variables with the same name.");
    }

    /** Globals editor. */
    public EnvironmentEditor(AppContext ctx, List<KeyValue> globals) {
        this.target = globals;
        title.set("Globals");
        Label l = new Label("Global variables");
        l.getStyleClass().add("section-title");
        HBox top = new HBox(8, l);
        init(ctx, top, globals, "Global variables are visible to every request of every collection, whatever the active environment. "
                + "They have the lowest precedence.");
    }

    private void init(AppContext ctx, HBox top, List<KeyValue> values, String hint) {
        Label h = new Label(hint);
        h.getStyleClass().add("hint");
        h.setWrapText(true);
        VBox header = new VBox(6, top, h);
        header.setPadding(new Insets(10));
        setTop(header);
        table.setData(values);
        table.setOnChange(ctx::changed);
        setCenter(table);
        // refresh when scripts / extractors change values
        ctx.onEnvironmentsChanged(() -> {
            if (!table.isFocusWithin()) table.rebuild();
        });
    }

    public Object target() {
        return target;
    }

    public StringProperty titleProperty() {
        return title;
    }
}
