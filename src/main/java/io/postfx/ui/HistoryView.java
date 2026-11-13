package io.postfx.ui;

import io.postfx.engine.JsonUtil;
import io.postfx.model.HistoryEntry;
import io.postfx.model.RequestItem;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Recently sent requests; click to reopen as a new unsaved request. */
public class HistoryView extends BorderPane {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault());
    private final AppContext ctx;
    private final ListView<HistoryEntry> list = new ListView<>();

    public HistoryView(AppContext ctx) {
        this.ctx = ctx;
        Button clear = new Button("Clear history");
        clear.setOnAction(e -> {
            if (Dialogs.confirm("Clear history", "Remove all history entries?")) {
                ctx.workspace.history.clear();
                ctx.fireHistoryChanged();
            }
        });
        HBox top = new HBox(4, clear);
        top.setPadding(new Insets(4));
        setTop(top);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(HistoryEntry h, boolean empty) {
                super.updateItem(h, empty);
                if (empty || h == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    return;
                }
                Label badge = new Label(CollectionTree.shortMethod(h.request.method));
                badge.getStyleClass().addAll("method-badge", "m-" + h.request.method.toLowerCase());
                badge.setMinWidth(46);
                setGraphic(badge);
                setText(h.request.url + "\n" + FMT.format(Instant.ofEpochMilli(h.timestamp)) + "   " + (h.status == 0 ? "error" : h.status) + "   " + h.timeMs + " ms");
                MenuItem open = new MenuItem("Open as new request");
                open.setOnAction(e -> openEntry(h));
                MenuItem del = new MenuItem("Remove");
                del.setOnAction(e -> {
                    ctx.workspace.history.remove(h);
                    ctx.fireHistoryChanged();
                });
                setContextMenu(new ContextMenu(open, del));
            }
        });
        list.setOnMouseClicked(e -> {
            HistoryEntry h = list.getSelectionModel().getSelectedItem();
            if (h != null && e.getClickCount() == 2) openEntry(h);
        });
        Label empty = new Label("Requests you send appear here.");
        empty.getStyleClass().add("hint");
        list.setPlaceholder(empty);
        setCenter(list);
        ctx.onHistoryChanged(this::refresh);
        refresh();
    }

    private void openEntry(HistoryEntry h) {
        RequestItem r = new RequestItem(h.name == null ? "History request" : h.name);
        r.request = JsonUtil.copy(h.request);
        ctx.main().openRequest(r);
    }

    public void refresh() {
        list.getItems().setAll(ctx.workspace.history);
    }
}
