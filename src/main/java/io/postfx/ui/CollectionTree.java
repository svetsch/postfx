package io.postfx.ui;

import io.postfx.engine.JsonUtil;
import io.postfx.model.Collection;
import io.postfx.model.Folder;
import io.postfx.model.Ids;
import io.postfx.model.Item;
import io.postfx.model.Items;
import io.postfx.model.RequestItem;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.HashSet;
import java.util.Set;

/** Sidebar tree of collections, folders and requests with context menus and drag &amp; drop. */
public class CollectionTree extends BorderPane {
    private final AppContext ctx;
    private final TreeView<Item> tree = new TreeView<>();
    private final TextField filter = new TextField();
    private Item dragged;
    private Label empty;

    public CollectionTree(AppContext ctx) {
        this.ctx = ctx;
        filter.setPromptText("Filter requests");
        Button add = new Button("+");
        add.setTooltip(new Tooltip("New collection"));
        add.setOnAction(e -> newCollection());
        HBox.setHgrow(filter, Priority.ALWAYS);
        HBox top = new HBox(4, filter, add);
        top.setPadding(new Insets(4));
        top.setAlignment(Pos.CENTER_LEFT);
        setTop(top);
        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new ItemCell());
        tree.setOnMouseClicked(e -> {
            TreeItem<Item> sel = tree.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            if (sel.getValue() instanceof RequestItem r && e.getClickCount() == 1) ctx.main().openRequest(r);
            else if (sel.getValue() instanceof Folder f && e.getClickCount() == 2) ctx.main().openFolder(f);
        });
        tree.setOnKeyPressed(e -> {
            TreeItem<Item> sel = tree.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            if (e.getCode() == KeyCode.DELETE) delete(sel.getValue());
            if (e.getCode() == KeyCode.F2) rename(sel.getValue());
            if (e.getCode() == KeyCode.ENTER) {
                if (sel.getValue() instanceof RequestItem r) ctx.main().openRequest(r);
                else ctx.main().openFolder((Folder) sel.getValue());
            }
        });
        Label empty = new Label("No collections yet.\nCreate one with + or import a file (Ctrl+I).");
        empty.getStyleClass().add("hint");
        empty.setMouseTransparent(true);
        this.empty = empty;
        setCenter(new javafx.scene.layout.StackPane(tree, empty));
        filter.textProperty().addListener((o, a, b) -> refresh());
        ctx.onTreeChanged(this::refresh);
        refresh();
    }

    public void refresh() {
        Set<String> expanded = new HashSet<>();
        if (tree.getRoot() != null) collectExpanded(tree.getRoot(), expanded);
        Item selected = tree.getSelectionModel().getSelectedItem() == null ? null : tree.getSelectionModel().getSelectedItem().getValue();
        TreeItem<Item> root = new TreeItem<>();
        String f = filter.getText() == null ? "" : filter.getText().toLowerCase().trim();
        for (Collection c : ctx.workspace.collections) {
            TreeItem<Item> ti = build(c, f, expanded);
            if (ti != null) root.getChildren().add(ti);
        }
        tree.setRoot(root);
        if (empty != null) empty.setVisible(ctx.workspace.collections.isEmpty());
        if (selected != null) select(selected);
    }

    private void collectExpanded(TreeItem<Item> ti, Set<String> out) {
        if (ti.getValue() != null && ti.isExpanded()) out.add(ti.getValue().id);
        for (TreeItem<Item> c : ti.getChildren()) collectExpanded(c, out);
    }

    private TreeItem<Item> build(Item item, String filter, Set<String> expanded) {
        TreeItem<Item> ti = new TreeItem<>(item);
        if (item instanceof Folder folder) {
            for (Item child : folder.items) {
                TreeItem<Item> c = build(child, filter, expanded);
                if (c != null) ti.getChildren().add(c);
            }
            ti.setExpanded(!filter.isEmpty() || expanded.contains(item.id) || (expanded.isEmpty() && item instanceof Collection));
            if (!filter.isEmpty() && ti.getChildren().isEmpty() && !item.name.toLowerCase().contains(filter)) return null;
            return ti;
        }
        RequestItem r = (RequestItem) item;
        if (!filter.isEmpty() && !r.name.toLowerCase().contains(filter) && !r.request.url.toLowerCase().contains(filter)) return null;
        return ti;
    }

    public void select(Item item) {
        TreeItem<Item> ti = find(tree.getRoot(), item);
        if (ti != null) {
            for (TreeItem<Item> p = ti.getParent(); p != null; p = p.getParent()) p.setExpanded(true);
            tree.getSelectionModel().select(ti);
        }
    }

    private TreeItem<Item> find(TreeItem<Item> ti, Item item) {
        if (ti == null) return null;
        if (ti.getValue() == item) return ti;
        for (TreeItem<Item> c : ti.getChildren()) {
            TreeItem<Item> f = find(c, item);
            if (f != null) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------ actions

    public void newCollection() {
        Dialogs.text("New collection", "Collection name", "New Collection").ifPresent(n -> {
            Collection c = new Collection(n);
            ctx.workspace.collections.add(c);
            ctx.fireTreeChanged();
            select(c);
            ctx.main().openFolder(c);
        });
    }

    private void addRequest(Folder parent) {
        RequestItem r = new RequestItem("New Request", "GET", "");
        parent.items.add(r);
        ctx.fireTreeChanged();
        select(r);
        ctx.main().openRequest(r);
    }

    private void addFolder(Folder parent) {
        Dialogs.text("New folder", "Folder name", "New Folder").ifPresent(n -> {
            Folder f = new Folder(n);
            parent.items.add(f);
            ctx.fireTreeChanged();
            select(f);
        });
    }

    private void rename(Item item) {
        Dialogs.text("Rename", "New name", item.name).ifPresent(n -> {
            item.name = n;
            ctx.fireTreeChanged();
            ctx.main().refreshTabTitles();
        });
    }

    private void duplicate(Item item) {
        Item copy = JsonUtil.copy(item);
        reassignIds(copy);
        copy.name = item.name + " Copy";
        if (item instanceof Collection c) {
            ctx.workspace.collections.add(ctx.workspace.collections.indexOf(c) + 1, (Collection) copy);
        } else {
            Folder parent = parentOf(item);
            if (parent == null) return;
            parent.items.add(parent.items.indexOf(item) + 1, copy);
        }
        ctx.fireTreeChanged();
        select(copy);
    }

    private static void reassignIds(Item item) {
        item.id = Ids.newId();
        if (item instanceof Folder f) f.items.forEach(CollectionTree::reassignIds);
    }

    private void delete(Item item) {
        int count = item instanceof Folder f ? Items.flatten(f).size() : 1;
        String what = item instanceof Collection ? "collection" : item instanceof Folder ? "folder" : "request";
        if (!Dialogs.confirm("Delete " + what, "Delete \"" + item.name + "\"" + (item instanceof Folder ? " and its " + count + " request(s)" : "") + "?")) return;
        if (item instanceof Collection c) ctx.workspace.collections.remove(c);
        else {
            Folder parent = parentOf(item);
            if (parent != null) parent.items.remove(item);
        }
        ctx.main().closeEditorsOf(item);
        ctx.fireTreeChanged();
    }

    private Folder parentOf(Item item) {
        for (Collection c : ctx.workspace.collections) {
            Folder p = Items.parentOf(c, item);
            if (p != null) return p;
        }
        return null;
    }

    private void move(Item item, int delta) {
        java.util.List<? extends Item> list = item instanceof Collection ? ctx.workspace.collections : parentOf(item) == null ? null : parentOf(item).items;
        if (list == null) return;
        @SuppressWarnings("unchecked") java.util.List<Item> l = (java.util.List<Item>) list;
        int i = l.indexOf(item);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= l.size()) return;
        l.remove(i);
        l.add(j, item);
        ctx.fireTreeChanged();
        select(item);
    }

    private ContextMenu menu(Item item) {
        ContextMenu m = new ContextMenu();
        if (item instanceof Folder f) {
            MenuItem addReq = new MenuItem("Add request");
            addReq.setOnAction(e -> addRequest(f));
            MenuItem addFolder = new MenuItem("Add folder");
            addFolder.setOnAction(e -> addFolder(f));
            MenuItem run = new MenuItem("Run " + (f instanceof Collection ? "collection" : "folder") + "…");
            run.setOnAction(e -> ctx.main().openRunner(ctx.collectionOf(f), f));
            MenuItem edit = new MenuItem("Edit (variables, auth, scripts)");
            edit.setOnAction(e -> ctx.main().openFolder(f));
            m.getItems().addAll(addReq, addFolder, new SeparatorMenuItem(), run, edit);
            if (f instanceof Collection c) {
                MenuItem exportPostman = new MenuItem("Export as Postman collection…");
                exportPostman.setOnAction(e -> Dialogs.exportPostman(ctx, c));
                MenuItem exportJava = new MenuItem("Export as Java tests (JUnit + REST Assured)…");
                exportJava.setOnAction(e -> Dialogs.exportJava(ctx, c, null));
                MenuItem exportMd = new MenuItem("Export test document (Markdown)…");
                exportMd.setOnAction(e -> Dialogs.exportMarkdown(ctx, c, null, null));
                m.getItems().addAll(new SeparatorMenuItem(), exportPostman, exportJava, exportMd);
            } else {
                MenuItem exportJava = new MenuItem("Export folder as Java tests…");
                exportJava.setOnAction(e -> Dialogs.exportJava(ctx, ctx.collectionOf(f), f));
                MenuItem exportMd = new MenuItem("Export folder test document (Markdown)…");
                exportMd.setOnAction(e -> Dialogs.exportMarkdown(ctx, ctx.collectionOf(f), f, null));
                m.getItems().addAll(new SeparatorMenuItem(), exportJava, exportMd);
            }
        } else if (item instanceof RequestItem r) {
            MenuItem open = new MenuItem("Open");
            open.setOnAction(e -> ctx.main().openRequest(r));
            MenuItem code = new MenuItem("Generate code (cURL / Java)…");
            code.setOnAction(e -> Dialogs.showCode(ctx, r));
            m.getItems().addAll(open, code, new SeparatorMenuItem());
        }
        MenuItem rename = new MenuItem("Rename (F2)");
        rename.setOnAction(e -> rename(item));
        MenuItem dup = new MenuItem("Duplicate");
        dup.setOnAction(e -> duplicate(item));
        MenuItem up = new MenuItem("Move up");
        up.setOnAction(e -> move(item, -1));
        MenuItem down = new MenuItem("Move down");
        down.setOnAction(e -> move(item, 1));
        MenuItem del = new MenuItem("Delete (Del)");
        del.setOnAction(e -> delete(item));
        m.getItems().addAll(rename, dup, up, down, new SeparatorMenuItem(), del);
        return m;
    }

    // ------------------------------------------------------------------ cells & drag and drop

    private final class ItemCell extends TreeCell<Item> {
        ItemCell() {
            setOnDragDetected(e -> {
                if (getItem() == null || getItem() instanceof Collection) return;
                dragged = getItem();
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent c = new ClipboardContent();
                c.putString(getItem().id);
                db.setContent(c);
                e.consume();
            });
            setOnDragOver(e -> {
                if (dragged != null && getItem() != null && getItem() != dragged && !isDescendant(dragged, getItem())) {
                    e.acceptTransferModes(TransferMode.MOVE);
                    getStyleClass().add("drop-target");
                }
                e.consume();
            });
            setOnDragExited(e -> getStyleClass().remove("drop-target"));
            setOnDragDropped(e -> {
                Item target = getItem();
                if (dragged == null || target == null) return;
                Folder oldParent = parentOf(dragged);
                if (oldParent == null) return;
                oldParent.items.remove(dragged);
                if (target instanceof Folder f) {
                    f.items.add(dragged);
                } else {
                    Folder newParent = parentOf(target);
                    if (newParent == null) {
                        oldParent.items.add(dragged);
                    } else {
                        newParent.items.add(newParent.items.indexOf(target), dragged);
                    }
                }
                Item moved = dragged;
                dragged = null;
                e.setDropCompleted(true);
                ctx.fireTreeChanged();
                select(moved);
                ctx.main().refreshTabTitles();
            });
            setOnDragDone(e -> dragged = null);
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("drop-target");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setTooltip(null);
                return;
            }
            if (item instanceof RequestItem r) {
                Label badge = new Label(shortMethod(r.request.method));
                badge.getStyleClass().addAll("method-badge", "m-" + r.request.method.toLowerCase());
                badge.setMinWidth(46);
                setGraphic(badge);
                setText(r.name);
                setTooltip(new Tooltip(r.request.method + " " + r.request.url));
            } else {
                Label icon = new Label(item instanceof Collection ? "▣" : "▸");
                icon.getStyleClass().add(item instanceof Collection ? "collection-icon" : "folder-icon");
                setGraphic(icon);
                setText(item.name);
                setTooltip(item.description == null ? null : new Tooltip(item.description));
            }
            setContextMenu(menu(item));
        }
    }

    private static boolean isDescendant(Item ancestor, Item node) {
        if (!(ancestor instanceof Folder f)) return false;
        for (Item c : f.items) {
            if (c == node || isDescendant(c, node)) return true;
        }
        return false;
    }

    static String shortMethod(String m) {
        return switch (m) {
            case "DELETE" -> "DEL";
            case "OPTIONS" -> "OPT";
            case "PATCH" -> "PATCH";
            default -> m;
        };
    }
}
