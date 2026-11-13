package io.postfx.ui;

import io.postfx.model.KeyValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Editable key / value list (headers, params, form fields, variables) with an always-present empty row,
 * enable checkboxes and a "bulk edit" text mode. Edits are written straight into the backing list.
 */
public class KeyValueTable extends BorderPane {
    private final VBox rows = new VBox(2);
    private final TextArea bulk = new TextArea();
    private final ToggleButton bulkToggle = new ToggleButton("Bulk edit");
    private final ScrollPane scroll = new ScrollPane(rows);
    private List<KeyValue> data = new ArrayList<>();
    private Runnable onChange = () -> {};
    private final boolean fileSupport;
    private final boolean showDescription;
    private String keyPrompt = "Key";
    private String valuePrompt = "Value";
    private Function<String, String> valueTooltip;
    private boolean fixedKeys;

    public KeyValueTable(boolean fileSupport, boolean showDescription) {
        this.fileSupport = fileSupport;
        this.showDescription = showDescription;
        getStyleClass().add("kv-table");
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("kv-scroll");
        rows.setPadding(new Insets(4));
        bulk.setPromptText("One entry per line:  key: value   (prefix with // to disable)");
        bulk.getStyleClass().add("mono");
        bulkToggle.setOnAction(e -> toggleBulk());
        bulkToggle.setTooltip(new Tooltip("Edit all entries as text (key: value per line)"));

        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 4, 0, 4));
        Label k = new Label("KEY");
        Label v = new Label("VALUE");
        k.getStyleClass().add("kv-header");
        v.getStyleClass().add("kv-header");
        Region spacerCheck = new Region();
        spacerCheck.setMinWidth(22);
        k.setPrefWidth(10);
        HBox.setHgrow(k, Priority.ALWAYS);
        HBox.setHgrow(v, Priority.ALWAYS);
        k.setMaxWidth(Double.MAX_VALUE);
        v.setMaxWidth(Double.MAX_VALUE);
        header.getChildren().addAll(spacerCheck, k, v);
        if (showDescription) {
            Label d = new Label("DESCRIPTION");
            d.getStyleClass().add("kv-header");
            d.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(d, Priority.ALWAYS);
            header.getChildren().add(d);
        }
        Region grow = new Region();
        header.getChildren().addAll(grow, bulkToggle);
        setTop(header);
        setCenter(scroll);
    }

    public KeyValueTable prompts(String key, String value) {
        this.keyPrompt = key;
        this.valuePrompt = value;
        return this;
    }

    /** Tooltip showing e.g. the resolved value of a variable. */
    public KeyValueTable valueTooltip(Function<String, String> f) {
        this.valueTooltip = f;
        return this;
    }

    /** Keys cannot be added / removed (path variables). */
    public KeyValueTable fixedKeys(boolean fixed) {
        this.fixedKeys = fixed;
        bulkToggle.setVisible(!fixed);
        return this;
    }

    public void setOnChange(Runnable r) {
        this.onChange = r == null ? () -> {} : r;
    }

    public void setData(List<KeyValue> list) {
        this.data = list;
        if (bulkToggle.isSelected()) {
            bulkToggle.setSelected(false);
            setCenter(scroll);
        }
        rebuild();
    }

    public List<KeyValue> getData() {
        return data;
    }

    public void rebuild() {
        rows.getChildren().clear();
        for (KeyValue kv : data) rows.getChildren().add(row(kv, false));
        if (!fixedKeys) rows.getChildren().add(row(new KeyValue(), true));
    }

    private HBox row(KeyValue kv, boolean placeholder) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("kv-row");
        CheckBox enabled = new CheckBox();
        enabled.setSelected(kv.enabled);
        enabled.setDisable(placeholder);
        TextField key = new TextField(kv.key);
        key.setPromptText(keyPrompt);
        key.setEditable(!fixedKeys);
        TextField value = new TextField(kv.value);
        value.setPromptText(valuePrompt);
        HBox.setHgrow(key, Priority.ALWAYS);
        HBox.setHgrow(value, Priority.ALWAYS);
        key.setPrefWidth(10);
        value.setPrefWidth(10);
        key.setMaxWidth(Double.MAX_VALUE);
        value.setMaxWidth(Double.MAX_VALUE);
        row.getChildren().addAll(enabled, key);

        ComboBox<String> type = null;
        Button browse = null;
        if (fileSupport) {
            type = new ComboBox<>();
            type.getItems().addAll("Text", "File");
            type.setValue(kv.isFile() ? "File" : "Text");
            type.setPrefWidth(80);
            browse = new Button("…");
            browse.setTooltip(new Tooltip("Choose a file"));
            browse.setVisible(kv.isFile());
            browse.setManaged(kv.isFile());
            row.getChildren().add(type);
        }
        row.getChildren().add(value);
        if (browse != null) row.getChildren().add(browse);
        TextField desc = null;
        if (showDescription) {
            desc = new TextField(kv.description == null ? "" : kv.description);
            desc.setPromptText("Description");
            desc.setPrefWidth(10);
            desc.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(desc, Priority.ALWAYS);
            row.getChildren().add(desc);
        }
        Button delete = new Button("✕");
        delete.getStyleClass().add("icon-button");
        delete.setTooltip(new Tooltip("Remove"));
        delete.setVisible(!placeholder && !fixedKeys);
        row.getChildren().add(delete);

        if (valueTooltip != null) {
            Tooltip tip = new Tooltip();
            value.setTooltip(tip);
            value.setOnMouseEntered(e -> {
                String t = valueTooltip.apply(value.getText());
                tip.setText(t == null || t.isEmpty() ? "(empty)" : t);
            });
        }

        // placeholder row becomes a real one on first keystroke
        final boolean[] isPlaceholder = {placeholder};
        Runnable materialize = () -> {
            if (isPlaceholder[0]) {
                isPlaceholder[0] = false;
                data.add(kv);
                enabled.setDisable(false);
                enabled.setSelected(true);
                kv.enabled = true;
                delete.setVisible(true);
                rows.getChildren().add(row(new KeyValue(), true));
            }
        };
        key.textProperty().addListener((o, a, b) -> {
            if (!b.isEmpty()) materialize.run();
            kv.key = b;
            onChange.run();
        });
        value.textProperty().addListener((o, a, b) -> {
            if (!b.isEmpty()) materialize.run();
            kv.value = b;
            onChange.run();
        });
        if (desc != null) {
            desc.textProperty().addListener((o, a, b) -> {
                if (!b.isEmpty()) materialize.run();
                kv.description = b.isEmpty() ? null : b;
                onChange.run();
            });
        }
        enabled.selectedProperty().addListener((o, a, b) -> {
            kv.enabled = b;
            onChange.run();
        });
        if (type != null) {
            Button br = browse;
            ComboBox<String> t = type;
            type.valueProperty().addListener((o, a, b) -> {
                materialize.run();
                kv.type = "File".equals(b) ? "file" : null;
                br.setVisible(kv.isFile());
                br.setManaged(kv.isFile());
                value.setPromptText(kv.isFile() ? "File path" : valuePrompt);
                onChange.run();
            });
            browse.setOnAction(e -> {
                FileChooser fc = new FileChooser();
                File f = fc.showOpenDialog(getScene().getWindow());
                if (f != null) value.setText(f.getAbsolutePath());
            });
            if (kv.isFile()) value.setPromptText("File path");
            t.setTooltip(new Tooltip("Text field or file upload"));
        }
        delete.setOnAction(e -> {
            data.remove(kv);
            rows.getChildren().remove(row);
            onChange.run();
        });
        return row;
    }

    private void toggleBulk() {
        if (bulkToggle.isSelected()) {
            StringBuilder sb = new StringBuilder();
            for (KeyValue kv : data) {
                if (kv.isBlank()) continue;
                if (!kv.enabled) sb.append("//");
                sb.append(kv.key).append(": ").append(kv.value == null ? "" : kv.value).append('\n');
            }
            bulk.setText(sb.toString());
            setCenter(bulk);
        } else {
            List<KeyValue> parsed = new ArrayList<>();
            for (String line : bulk.getText().split("\r?\n")) {
                if (line.isBlank()) continue;
                boolean disabled = line.startsWith("//");
                String l = disabled ? line.substring(2) : line;
                int i = l.indexOf(':');
                KeyValue kv = i < 0 ? new KeyValue(l.trim(), "") : new KeyValue(l.substring(0, i).trim(), l.substring(i + 1).trim());
                kv.enabled = !disabled;
                // keep type/description of existing entries with the same key
                for (KeyValue old : data) {
                    if (old.key.equals(kv.key)) {
                        kv.type = old.type;
                        kv.description = old.description;
                    }
                }
                parsed.add(kv);
            }
            data.clear();
            data.addAll(parsed);
            setCenter(scroll);
            rebuild();
            onChange.run();
        }
    }
}
