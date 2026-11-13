package io.postfx.ui;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.engine.JsonPathLite;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;

/** Tree view of a JSON response; the context menu creates checks / extractors for the selected path. */
public class JsonTreeView extends TreeView<JsonTreeView.Entry> {
    public record Entry(String label, JsonNode node, String path) {}

    private BiConsumer<String, JsonNode> onAddCheck = (p, n) -> {};
    private BiConsumer<String, JsonNode> onExtract = (p, n) -> {};

    public JsonTreeView() {
        setShowRoot(true);
        setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Entry e, boolean empty) {
                super.updateItem(e, empty);
                getStyleClass().removeAll("json-string", "json-number", "json-literal");
                if (empty || e == null) {
                    setText(null);
                    setContextMenu(null);
                    return;
                }
                JsonNode n = e.node();
                if (n.isObject()) setText(e.label() + "  {" + n.size() + "}");
                else if (n.isArray()) setText(e.label() + "  [" + n.size() + "]");
                else {
                    setText(e.label() + ": " + (n.isTextual() ? "\"" + n.asText() + "\"" : n.toString()));
                    getStyleClass().add(n.isTextual() ? "json-string" : n.isNumber() ? "json-number" : "json-literal");
                }
                setContextMenu(menu(e));
            }
        });
    }

    public void setOnAddCheck(BiConsumer<String, JsonNode> c) {
        this.onAddCheck = c;
    }

    public void setOnExtract(BiConsumer<String, JsonNode> c) {
        this.onExtract = c;
    }

    private ContextMenu menu(Entry e) {
        MenuItem check = new MenuItem("Add check: " + e.path() + (e.node().isValueNode() ? " equals this value" : " exists"));
        check.setOnAction(a -> onAddCheck.accept(e.path(), e.node()));
        MenuItem extract = new MenuItem("Extract " + e.path() + " into a variable…");
        extract.setOnAction(a -> onExtract.accept(e.path(), e.node()));
        MenuItem copyPath = new MenuItem("Copy path");
        copyPath.setOnAction(a -> copy(e.path()));
        MenuItem copyValue = new MenuItem("Copy value");
        copyValue.setOnAction(a -> copy(JsonPathLite.asText(e.node())));
        return new ContextMenu(check, extract, new SeparatorMenuItem(), copyPath, copyValue);
    }

    private static void copy(String s) {
        ClipboardContent c = new ClipboardContent();
        c.putString(s);
        Clipboard.getSystemClipboard().setContent(c);
    }

    public void setJson(JsonNode root) {
        if (root == null) {
            setRoot(null);
            return;
        }
        TreeItem<Entry> item = build("$", root, "$", 0);
        item.setExpanded(true);
        setRoot(item);
    }

    private TreeItem<Entry> build(String label, JsonNode node, String path, int depth) {
        TreeItem<Entry> item = new TreeItem<>(new Entry(label, node, path));
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                String key = f.getKey();
                String child = key.matches("[A-Za-z_$][\\w$-]*") ? path + "." + key : path + "['" + key.replace("'", "\\'") + "']";
                item.getChildren().add(build(key, f.getValue(), child, depth + 1));
            }
        } else if (node.isArray()) {
            int limit = Math.min(node.size(), 500);
            for (int i = 0; i < limit; i++) item.getChildren().add(build("[" + i + "]", node.get(i), path + "[" + i + "]", depth + 1));
        }
        item.setExpanded(depth < 2);
        return item;
    }
}
