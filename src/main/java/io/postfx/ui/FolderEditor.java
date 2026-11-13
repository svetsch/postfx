package io.postfx.ui;

import io.postfx.model.Collection;
import io.postfx.model.Folder;
import io.postfx.model.Items;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Settings of a collection or folder: docs, variables (collection), auth and scripts inherited by children. */
public class FolderEditor extends BorderPane {
    private final StringProperty title = new SimpleStringProperty();
    private final Folder folder;

    public FolderEditor(AppContext ctx, Folder folder) {
        this.folder = folder;
        boolean isCollection = folder instanceof Collection;
        TextField name = new TextField(folder.name);
        name.getStyleClass().add("title-field");
        HBox.setHgrow(name, Priority.ALWAYS);
        Button run = new Button("▶ Run");
        run.getStyleClass().add("send-button");
        run.setOnAction(e -> ctx.main().openRunner(ctx.collectionOf(folder), folder));
        Button exportJava = new Button("Export Java tests…");
        exportJava.setOnAction(e -> Dialogs.exportJava(ctx, ctx.collectionOf(folder), isCollection ? null : folder));
        HBox top = new HBox(8, new Label(isCollection ? "Collection" : "Folder"), name, exportJava, run);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(10));
        setTop(top);
        name.textProperty().addListener((o, a, b) -> {
            folder.name = b;
            title.set(b);
            ctx.fireTreeChanged();
        });
        title.set(folder.name);

        TextArea docs = new TextArea(folder.description == null ? "" : folder.description);
        docs.setWrapText(true);
        docs.setPromptText("Documentation of this " + (isCollection ? "collection" : "folder"));
        docs.textProperty().addListener((o, a, b) -> {
            folder.description = b.isBlank() ? null : b;
            ctx.changed();
        });
        Label stats = new Label(Items.flatten(folder).size() + " request(s)");
        stats.getStyleClass().add("hint");
        VBox overview = new VBox(6, stats, docs);
        overview.setPadding(new Insets(8));
        VBox.setVgrow(docs, Priority.ALWAYS);

        AuthEditor auth = new AuthEditor(!isCollection);
        auth.setContext(ctx, () -> ctx.variables(ctx.collectionOf(folder)));
        auth.setAuth(folder.auth);
        auth.setOnChange(ctx::changed);

        ScriptPane pre = new ScriptPane("Runs before EVERY request of this " + (isCollection ? "collection" : "folder")
                + " (before the request's own pre-request script).", Snippets.PRE_REQUEST, "Scripts");
        pre.setText(folder.preRequestScript);
        pre.onChange(t -> {
            folder.preRequestScript = t;
            ctx.changed();
        });
        ScriptPane tests = new ScriptPane("Runs after EVERY request of this " + (isCollection ? "collection" : "folder")
                + " (before the request's own test script).", Snippets.TESTS, "Scripts");
        tests.setText(folder.testScript);
        tests.onChange(t -> {
            folder.testScript = t;
            ctx.changed();
        });

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Overview", overview));
        if (folder instanceof Collection c) {
            KeyValueTable vars = new KeyValueTable(false, true).prompts("Variable", "Value");
            vars.setData(c.variables);
            vars.setOnChange(ctx::changed);
            Label hint = new Label("Collection variables are available to every request of the collection as {{name}}. "
                    + "Environment variables with the same name take precedence.");
            hint.getStyleClass().add("hint");
            hint.setWrapText(true);
            BorderPane p = new BorderPane(vars);
            p.setTop(hint);
            BorderPane.setMargin(hint, new Insets(6));
            tabs.getTabs().add(new Tab("Variables", p));
        }
        tabs.getTabs().addAll(new Tab("Auth", auth), new Tab("Pre-request", pre), new Tab("Tests", tests));
        setCenter(tabs);
    }

    public Folder folder() {
        return folder;
    }

    public StringProperty titleProperty() {
        return title;
    }
}
