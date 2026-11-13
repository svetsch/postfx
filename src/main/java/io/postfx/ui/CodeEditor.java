package io.postfx.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Code editor with line numbers, syntax highlighting (js / json / xml / text) and auto indent. */
public class CodeEditor extends StackPane {
    private static final String[] JS_KEYWORDS = {"break", "case", "catch", "class", "const", "continue", "default", "delete",
            "do", "else", "export", "extends", "finally", "for", "function", "if", "import", "in", "instanceof", "let", "new",
            "return", "super", "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with", "yield", "async", "await", "of"};
    private static final Pattern JS = Pattern.compile(
            "(?<COMMENT>//[^\n]*|/\\*(.|\\R)*?\\*/)"
                    + "|(?<STRING>\"([^\"\\\\\n]|\\\\.)*\"|'([^'\\\\\n]|\\\\.)*'|`([^`\\\\]|\\\\.)*`)"
                    + "|(?<VAR>\\{\\{[^{}]+}})"
                    + "|(?<KEYWORD>\\b(" + String.join("|", JS_KEYWORDS) + ")\\b)"
                    + "|(?<LITERAL>\\b(true|false|null|undefined|NaN)\\b)"
                    + "|(?<PM>\\b(pm|postman|console|CryptoJS|_|tests|responseBody)\\b)"
                    + "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)");
    private static final Pattern JSON = Pattern.compile(
            "(?<KEY>\"([^\"\\\\\n]|\\\\.)*\"(?=\\s*:))"
                    + "|(?<STRING>\"([^\"\\\\\n]|\\\\.)*\")"
                    + "|(?<VAR>\\{\\{[^{}]+}})"
                    + "|(?<LITERAL>\\b(true|false|null)\\b)"
                    + "|(?<NUMBER>-?\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?\\b)");
    private static final Pattern XML = Pattern.compile(
            "(?<COMMENT><!--(.|\\R)*?-->)"
                    + "|(?<TAG></?[\\w:.-]+|/?>)"
                    + "|(?<STRING>\"[^\"]*\"|'[^']*')"
                    + "|(?<VAR>\\{\\{[^{}]+}})");
    private static final Pattern TEXT = Pattern.compile("(?<VAR>\\{\\{[^{}]+}})");

    private final CodeArea area = new CodeArea();
    private String language = "text";

    public CodeEditor() {
        this("text");
    }

    public CodeEditor(String language) {
        getStyleClass().add("code-editor");
        area.getStyleClass().add("code-area-pfx");
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.setWrapText(false);
        this.language = language;
        area.multiPlainChanges().successionEnds(Duration.ofMillis(120)).subscribe(ignore -> highlight());
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        getChildren().add(new VirtualizedScrollPane<>(area));
    }

    private void onKey(KeyEvent e) {
        if (!area.isEditable()) return;
        if (e.getCode() == KeyCode.TAB && !e.isControlDown()) {
            area.replaceSelection("    ");
            e.consume();
        } else if (e.getCode() == KeyCode.ENTER && !e.isControlDown() && !e.isShiftDown()) {
            int par = area.getCurrentParagraph();
            String line = area.getParagraph(par).getText();
            String before = line.substring(0, Math.min(area.getCaretColumn(), line.length()));
            Matcher m = Pattern.compile("^\\s*").matcher(before);
            String indent = m.find() ? m.group() : "";
            String trimmed = before.stripTrailing();
            if (trimmed.endsWith("{") || trimmed.endsWith("[") || trimmed.endsWith("(")) indent += "    ";
            area.replaceSelection("\n" + indent);
            e.consume();
        }
    }

    public CodeArea area() {
        return area;
    }

    public void setLanguage(String language) {
        this.language = language == null ? "text" : language;
        highlight();
    }

    public String getText() {
        return area.getText();
    }

    public void setText(String text) {
        area.replaceText(text == null ? "" : text);
        area.moveTo(0);
        area.requestFollowCaret();
        highlight();
    }

    public void setEditable(boolean editable) {
        area.setEditable(editable);
    }

    public void setWrap(boolean wrap) {
        area.setWrapText(wrap);
    }

    public void onChange(Consumer<String> listener) {
        area.textProperty().addListener((o, a, b) -> listener.accept(b));
    }

    public void insert(String text) {
        int pos = area.getCaretPosition();
        String current = area.getText();
        String prefix = pos > 0 && current.charAt(pos - 1) != '\n' ? "\n" : "";
        area.insertText(pos, prefix + text + "\n");
        area.requestFocus();
    }

    private void highlight() {
        String text = area.getText();
        if (text.length() > 400_000) return; // too large to highlight cheaply
        area.setStyleSpans(0, compute(text, language));
    }

    static StyleSpans<Collection<String>> compute(String text, String language) {
        Pattern p = switch (language) {
            case "javascript", "js" -> JS;
            case "json" -> JSON;
            case "xml", "html" -> XML;
            default -> TEXT;
        };
        Matcher m = p.matcher(text);
        int last = 0;
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        while (m.find()) {
            String cls = group(m, "COMMENT", "comment") != null ? "comment"
                    : group(m, "KEY", "key") != null ? "key"
                    : group(m, "STRING", "string") != null ? "string"
                    : group(m, "VAR", "variable") != null ? "variable"
                    : group(m, "KEYWORD", "keyword") != null ? "keyword"
                    : group(m, "LITERAL", "literal") != null ? "literal"
                    : group(m, "PM", "pm") != null ? "pm"
                    : group(m, "TAG", "tag") != null ? "tag"
                    : group(m, "NUMBER", "number") != null ? "number" : null;
            b.add(Collections.emptyList(), m.start() - last);
            b.add(cls == null ? Collections.emptyList() : Collections.singleton(cls), m.end() - m.start());
            last = m.end();
        }
        b.add(Collections.emptyList(), text.length() - last);
        return b.create();
    }

    private static String group(Matcher m, String name, String cls) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
