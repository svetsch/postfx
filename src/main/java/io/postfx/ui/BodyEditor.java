package io.postfx.ui;

import io.postfx.engine.JsonUtil;
import io.postfx.model.Body;
import io.postfx.model.BodyMode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;

/** Request body editor: none / raw (json, text, xml...) / urlencoded / form-data / GraphQL / binary. */
public class BodyEditor extends BorderPane {
    private final ToggleGroup modes = new ToggleGroup();
    private final ComboBox<String> language = new ComboBox<>();
    private final Button beautify = new Button("Beautify");
    private final Label beautifyStatus = new Label();
    private final CodeEditor raw = new CodeEditor("json");
    private final KeyValueTable urlencoded = new KeyValueTable(false, true);
    private final KeyValueTable formdata = new KeyValueTable(true, true);
    private final CodeEditor gqlQuery = new CodeEditor("text");
    private final CodeEditor gqlVars = new CodeEditor("json");
    private final TextField binary = new TextField();
    private final StackPane content = new StackPane();
    private final Label none = new Label("This request does not have a body");
    private Body body = new Body();
    private Runnable onChange = () -> {};
    private boolean loading;

    public BodyEditor() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));
        for (BodyMode m : BodyMode.values()) {
            RadioButton rb = new RadioButton(m.label);
            rb.setUserData(m);
            rb.setToggleGroup(modes);
            bar.getChildren().add(rb);
        }
        language.getItems().addAll("json", "text", "xml", "html", "javascript");
        language.setTooltip(new Tooltip("Sets the default Content-Type header"));
        beautify.setTooltip(new Tooltip("Pretty print the JSON or XML body"));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        beautifyStatus.getStyleClass().add("fail");
        bar.getChildren().addAll(grow, beautifyStatus, language, beautify);
        setTop(bar);
        none.getStyleClass().add("hint");

        Button browse = new Button("Select file…");
        browse.setOnAction(e -> {
            File f = new FileChooser().showOpenDialog(getScene().getWindow());
            if (f != null) binary.setText(f.getAbsolutePath());
        });
        binary.setPromptText("Path of the file sent as body");
        HBox.setHgrow(binary, Priority.ALWAYS);
        HBox binaryBox = new HBox(6, binary, browse);
        binaryBox.setPadding(new Insets(10));

        Label q = new Label("QUERY");
        q.getStyleClass().add("kv-header");
        Label v = new Label("GRAPHQL VARIABLES (JSON)");
        v.getStyleClass().add("kv-header");
        VBox qBox = new VBox(2, q, gqlQuery);
        VBox vBox = new VBox(2, v, gqlVars);
        VBox.setVgrow(gqlQuery, Priority.ALWAYS);
        VBox.setVgrow(gqlVars, Priority.ALWAYS);
        SplitPane gql = new SplitPane(qBox, vBox);

        modes.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) return;
            BodyMode m = (BodyMode) b.getUserData();
            show(m);
            if (!loading) {
                body.mode = m;
                onChange.run();
            }
        });
        language.valueProperty().addListener((o, a, b) -> {
            raw.setLanguage(b);
            beautify.setDisable(!"json".equals(b) && !"xml".equals(b));
            beautifyStatus.setText("");
            if (!loading && b != null) {
                body.rawLanguage = b;
                onChange.run();
            }
        });
        beautify.setOnAction(e -> beautify());
        raw.onChange(t -> { if (!loading) { body.raw = t; onChange.run(); } });
        gqlQuery.onChange(t -> { if (!loading) { body.graphqlQuery = t; onChange.run(); } });
        gqlVars.onChange(t -> { if (!loading) { body.graphqlVariables = t; onChange.run(); } });
        binary.textProperty().addListener((o, a, b) -> { if (!loading) { body.binaryFile = b; onChange.run(); } });
        urlencoded.setOnChange(() -> onChange.run());
        formdata.setOnChange(() -> onChange.run());

        content.getChildren().addAll(none, raw, urlencoded, formdata, gql, binaryBox);
        setCenter(content);
    }

    /** Pretty prints the raw body as JSON or XML; tells the user when it is not well-formed. */
    private void beautify() {
        String text = raw.getText();
        if (text == null || text.isBlank()) return;
        boolean xml = "xml".equals(language.getValue());
        boolean valid = xml ? io.postfx.engine.XmlUtil.tryParse(text) != null : JsonUtil.tryParse(text) != null;
        if (!valid) {
            beautifyStatus.setText(xml ? "Not well-formed XML" : "Not valid JSON");
            return;
        }
        beautifyStatus.setText("");
        raw.setText(xml ? io.postfx.engine.XmlUtil.prettyOrSame(text) : JsonUtil.prettyOrSame(text));
    }

    private void show(BodyMode m) {
        content.getChildren().forEach(n -> n.setVisible(false));
        int idx = switch (m) {
            case NONE -> 0;
            case RAW -> 1;
            case URLENCODED -> 2;
            case FORMDATA -> 3;
            case GRAPHQL -> 4;
            case BINARY -> 5;
        };
        content.getChildren().get(idx).setVisible(true);
        language.setVisible(m == BodyMode.RAW);
        beautify.setVisible(m == BodyMode.RAW);
    }

    public void setOnChange(Runnable r) {
        this.onChange = r;
    }

    public void setBody(Body b) {
        loading = true;
        this.body = b;
        modes.getToggles().stream().filter(t -> t.getUserData() == b.mode).findFirst().ifPresent(modes::selectToggle);
        language.setValue(b.rawLanguage == null ? "json" : b.rawLanguage);
        raw.setText(b.raw);
        raw.setLanguage(language.getValue());
        urlencoded.setData(b.urlencoded);
        formdata.setData(b.formdata);
        gqlQuery.setText(b.graphqlQuery);
        gqlVars.setText(b.graphqlVariables);
        binary.setText(b.binaryFile);
        show(b.mode);
        loading = false;
    }
}
