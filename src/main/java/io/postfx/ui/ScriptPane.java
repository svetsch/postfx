package io.postfx.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/** A JavaScript editor with a list of clickable snippets and a link to the scripting help. */
public class ScriptPane extends BorderPane {
    private final CodeEditor editor = new CodeEditor("javascript");

    public ScriptPane(String hint, List<Snippets.Snippet> snippets, String helpSection) {
        Label info = new Label(hint);
        info.getStyleClass().add("hint");
        info.setWrapText(true);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Button help = new Button("? Scripting help");
        help.getStyleClass().add("link-button");
        help.setOnAction(e -> HelpWindow.show(getScene().getWindow(), helpSection));
        HBox top = new HBox(8, info, grow, help);
        top.setPadding(new Insets(4, 6, 4, 6));
        setTop(top);

        ListView<Snippets.Snippet> list = new ListView<>();
        list.getItems().setAll(snippets);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Snippets.Snippet s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(s.title());
                    setTooltip(new Tooltip(s.description() + "\n\n" + s.code()));
                }
            }
        });
        list.setOnMouseClicked(e -> {
            Snippets.Snippet s = list.getSelectionModel().getSelectedItem();
            if (s != null && e.getClickCount() >= 1) {
                editor.insert(s.code());
                list.getSelectionModel().clearSelection();
            }
        });
        Label title = new Label("SNIPPETS");
        title.getStyleClass().add("kv-header");
        Label tip = new Label("Click to insert at the cursor");
        tip.getStyleClass().add("hint");
        VBox side = new VBox(4, title, tip, list);
        side.setPadding(new Insets(4));
        VBox.setVgrow(list, Priority.ALWAYS);
        side.setMinWidth(160);

        SplitPane split = new SplitPane(editor, side);
        split.setDividerPositions(0.74);
        SplitPane.setResizableWithParent(side, false);
        setCenter(split);
    }

    public void setText(String text) {
        editor.setText(text);
    }

    public void onChange(Consumer<String> c) {
        editor.onChange(c);
    }

    public CodeEditor editor() {
        return editor;
    }
}
