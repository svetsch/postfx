package io.postfx.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** User guide: sections loaded from help.md, rendered with a tiny Markdown subset, with search. */
public final class HelpWindow {
    private static Stage stage;
    private static ListView<String> toc;
    private static final Map<String, String> SECTIONS = load();

    private HelpWindow() {}

    private static Map<String, String> load() {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = HelpWindow.class.getResourceAsStream("/io/postfx/ui/help.md")) {
            String text = in == null ? "# Help\nHelp file missing." : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String current = null;
            StringBuilder sb = new StringBuilder();
            for (String line : text.split("\r?\n", -1)) {
                if (line.startsWith("# ")) {
                    if (current != null) out.put(current, sb.toString());
                    current = line.substring(2).strip();
                    sb.setLength(0);
                } else if (current != null) {
                    sb.append(line).append('\n');
                }
            }
            if (current != null) out.put(current, sb.toString());
        } catch (IOException e) {
            out.put("Help", "Could not load the help: " + e.getMessage());
        }
        return out;
    }

    public static List<String> sections() {
        return new ArrayList<>(SECTIONS.keySet());
    }

    public static void show(Window owner, String section) {
        if (stage == null) {
            stage = new Stage();
            stage.setTitle("PostFX Help");
            toc = new ListView<>();
            toc.getItems().setAll(SECTIONS.keySet());
            ScrollPane content = new ScrollPane();
            content.setFitToWidth(true);
            content.getStyleClass().add("help-content");
            TextField search = new TextField();
            search.setPromptText("Search the help…");
            search.textProperty().addListener((o, a, b) -> {
                String q = b.toLowerCase().strip();
                toc.getItems().setAll(SECTIONS.entrySet().stream()
                        .filter(e -> q.isEmpty() || e.getKey().toLowerCase().contains(q) || e.getValue().toLowerCase().contains(q))
                        .map(Map.Entry::getKey).toList());
                if (!toc.getItems().isEmpty()) toc.getSelectionModel().select(0);
            });
            toc.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
                if (b != null) {
                    content.setContent(render(b, SECTIONS.get(b)));
                    content.setVvalue(0);
                }
            });
            VBox left = new VBox(6, search, toc);
            left.setPadding(new Insets(6));
            javafx.scene.layout.VBox.setVgrow(toc, javafx.scene.layout.Priority.ALWAYS);
            SplitPane split = new SplitPane(left, content);
            split.setDividerPositions(0.26);
            Scene scene = new Scene(new BorderPane(split), 1040, 720);
            if (owner != null && owner.getScene() != null) scene.getStylesheets().setAll(owner.getScene().getStylesheets());
            stage.setScene(scene);
        }
        if (owner != null && owner.getScene() != null) stage.getScene().getStylesheets().setAll(owner.getScene().getStylesheets());
        String target = section != null && SECTIONS.containsKey(section) ? section : SECTIONS.keySet().iterator().next();
        toc.getSelectionModel().select(target);
        stage.show();
        stage.toFront();
    }

    /** Renders headings (##), bullets (- ), code blocks (```), inline `code` and **bold**. */
    static Node render(String title, String md) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(16, 24, 24, 24));
        box.getStyleClass().add("help-page");
        Label h1 = new Label(title);
        h1.getStyleClass().add("help-h1");
        box.getChildren().add(h1);
        String[] lines = md.split("\n", -1);
        StringBuilder para = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("```")) {
                flush(box, para);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].startsWith("```")) code.append(lines[i++]).append('\n');
                Label c = new Label(code.toString().stripTrailing());
                c.getStyleClass().add("help-code");
                c.setWrapText(false);
                c.setMaxWidth(Double.MAX_VALUE);
                box.getChildren().add(c);
            } else if (line.startsWith("## ")) {
                flush(box, para);
                Label h = new Label(line.substring(3));
                h.getStyleClass().add("help-h2");
                box.getChildren().add(h);
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                flush(box, para);
                TextFlow f = inline("•  " + line.substring(2));
                f.setPadding(new Insets(0, 0, 0, 12));
                box.getChildren().add(f);
            } else if (line.matches("^\\d+\\. .*")) {
                flush(box, para);
                TextFlow f = inline(line);
                f.setPadding(new Insets(0, 0, 0, 12));
                box.getChildren().add(f);
            } else if (line.isBlank()) {
                flush(box, para);
            } else {
                if (para.length() > 0) para.append(' ');
                para.append(line.strip());
            }
        }
        flush(box, para);
        return box;
    }

    private static void flush(VBox box, StringBuilder para) {
        if (para.length() == 0) return;
        box.getChildren().add(inline(para.toString()));
        para.setLength(0);
    }

    private static TextFlow inline(String s) {
        TextFlow f = new TextFlow();
        f.getStyleClass().add("help-text");
        int i = 0;
        while (i < s.length()) {
            int code = s.indexOf('`', i);
            int bold = s.indexOf("**", i);
            int next = code < 0 ? bold : bold < 0 ? code : Math.min(code, bold);
            if (next < 0) {
                f.getChildren().add(text(s.substring(i), "help-normal"));
                break;
            }
            if (next > i) f.getChildren().add(text(s.substring(i, next), "help-normal"));
            if (next == code) {
                int end = s.indexOf('`', code + 1);
                if (end < 0) end = s.length();
                f.getChildren().add(text(s.substring(code + 1, end), "help-inline-code"));
                i = end + 1;
            } else {
                int end = s.indexOf("**", bold + 2);
                if (end < 0) end = s.length();
                f.getChildren().add(text(s.substring(bold + 2, end), "help-bold"));
                i = end + 2;
            }
        }
        return f;
    }

    private static Text text(String s, String cls) {
        Text t = new Text(s);
        t.getStyleClass().add(cls);
        return t;
    }
}
