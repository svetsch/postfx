package io.postfx.ui;

import io.postfx.engine.JsonUtil;
import io.postfx.model.Environment;
import io.postfx.model.Ids;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Sidebar list of environments (plus the Globals entry). */
public class EnvironmentsView extends BorderPane {
    private static final Object GLOBALS = new Object();
    private final AppContext ctx;
    private final ListView<Object> list = new ListView<>();

    public EnvironmentsView(AppContext ctx) {
        this.ctx = ctx;
        Button add = new Button("+ New");
        add.setTooltip(new Tooltip("New environment"));
        add.setOnAction(e -> Dialogs.text("New environment", "Environment name", "New Environment").ifPresent(n -> {
            Environment env = new Environment(n);
            ctx.workspace.environments.add(env);
            ctx.fireEnvironmentsChanged();
            ctx.main().openEnvironment(env);
        }));
        Button imp = new Button("Import…");
        imp.setOnAction(e -> ctx.main().importFile());
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox top = new HBox(4, add, grow, imp);
        top.setPadding(new Insets(4));
        setTop(top);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Object o, boolean empty) {
                super.updateItem(o, empty);
                getStyleClass().remove("active-env");
                if (empty || o == null) {
                    setText(null);
                    setContextMenu(null);
                    return;
                }
                if (o == GLOBALS) {
                    setText("🌐  Globals");
                    setContextMenu(null);
                    return;
                }
                Environment env = (Environment) o;
                boolean active = env == ctx.activeEnvironment.get();
                setText((active ? "● " : "○ ") + env.name + "  (" + env.values.size() + ")");
                if (active) getStyleClass().add("active-env");
                MenuItem activate = new MenuItem("Set active");
                activate.setOnAction(e -> ctx.activeEnvironment.set(env));
                MenuItem dup = new MenuItem("Duplicate");
                dup.setOnAction(e -> {
                    Environment c = JsonUtil.copy(env);
                    c.id = Ids.newId();
                    c.name = env.name + " Copy";
                    ctx.workspace.environments.add(c);
                    ctx.fireEnvironmentsChanged();
                });
                MenuItem export = new MenuItem("Export (Postman)…");
                export.setOnAction(e -> Dialogs.exportEnvironment(ctx, env));
                MenuItem del = new MenuItem("Delete");
                del.setOnAction(e -> {
                    if (!Dialogs.confirm("Delete environment", "Delete \"" + env.name + "\"?")) return;
                    ctx.workspace.environments.remove(env);
                    if (ctx.activeEnvironment.get() == env) ctx.activeEnvironment.set(null);
                    ctx.main().closeEditorsOf(env);
                    ctx.fireEnvironmentsChanged();
                });
                setContextMenu(new ContextMenu(activate, dup, export, del));
            }
        });
        list.setOnMouseClicked(e -> {
            Object o = list.getSelectionModel().getSelectedItem();
            if (o == GLOBALS) ctx.main().openGlobals();
            else if (o instanceof Environment env) {
                if (e.getClickCount() == 2) ctx.activeEnvironment.set(env);
                ctx.main().openEnvironment(env);
            }
        });
        Label hint = new Label("Double-click to activate. Right-click for more.");
        hint.getStyleClass().add("hint");
        hint.setPadding(new Insets(4));
        setBottom(hint);
        setCenter(list);
        ctx.onEnvironmentsChanged(this::refresh);
        ctx.activeEnvironment.addListener((o, a, b) -> refresh());
        refresh();
    }

    public void refresh() {
        list.getItems().setAll(GLOBALS);
        list.getItems().addAll(ctx.workspace.environments);
        list.refresh();
    }
}
