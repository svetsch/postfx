package io.postfx.ui;

import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Editor for request / folder / collection authorization. */
public class AuthEditor extends VBox {
    private final ComboBox<AuthType> type = new ComboBox<>();
    private final GridPane form = new GridPane();
    private final Label explanation = new Label();
    private Auth auth = new Auth();
    private Runnable onChange = () -> {};
    private final boolean allowInherit;
    private Supplier<String> inheritedDescription = () -> "";

    public AuthEditor(boolean allowInherit) {
        super(10);
        this.allowInherit = allowInherit;
        setPadding(new Insets(10));
        for (AuthType t : AuthType.values()) if (allowInherit || t != AuthType.INHERIT) type.getItems().add(t);
        explanation.getStyleClass().add("hint");
        explanation.setWrapText(true);
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints c1 = new ColumnConstraints(120);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(c1, c2);
        type.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            auth.type = b;
            render();
            onChange.run();
        });
        getChildren().addAll(new Label("Type"), type, explanation, form);
    }

    public void setInheritedDescription(Supplier<String> s) {
        this.inheritedDescription = s;
    }

    public void setOnChange(Runnable r) {
        this.onChange = r;
    }

    public void setAuth(Auth a) {
        this.auth = a;
        if (!allowInherit && a.type == AuthType.INHERIT) a.type = AuthType.NONE;
        type.setValue(a.type);
        render();
    }

    private void render() {
        form.getChildren().clear();
        switch (auth.type) {
            case INHERIT -> explanation.setText("This request uses the authorization of its parent folder or collection. " + inheritedDescription.get());
            case NONE -> explanation.setText("No Authorization header is added.");
            case BASIC -> {
                explanation.setText("Sends 'Authorization: Basic base64(username:password)'. Values may contain {{variables}}.");
                field(0, "Username", new TextField(auth.username), v -> auth.username = v);
                field(1, "Password", new PasswordField(), v -> auth.password = v, auth.password);
            }
            case BEARER -> {
                explanation.setText("Sends 'Authorization: Bearer <token>'. Tip: use {{token}} and set it with an extractor or a script.");
                field(0, "Token", new TextField(auth.token), v -> auth.token = v);
            }
            case APIKEY -> {
                explanation.setText("Adds a key/value pair to the headers or to the query string.");
                field(0, "Key", new TextField(auth.apiKey), v -> auth.apiKey = v);
                field(1, "Value", new TextField(auth.apiValue), v -> auth.apiValue = v);
                ComboBox<String> in = new ComboBox<>();
                in.getItems().addAll("header", "query");
                in.setValue(auth.apiIn == null ? "header" : auth.apiIn);
                in.valueProperty().addListener((o, a, b) -> {
                    auth.apiIn = b;
                    onChange.run();
                });
                form.add(new Label("Add to"), 0, 2);
                form.add(in, 1, 2);
            }
        }
    }

    private void field(int row, String label, TextInputControl f, Consumer<String> setter) {
        field(row, label, f, setter, null);
    }

    private void field(int row, String label, TextInputControl f, Consumer<String> setter, String initial) {
        if (initial != null) f.setText(initial);
        f.setPromptText(label);
        f.textProperty().addListener((o, a, b) -> {
            setter.accept(b);
            onChange.run();
        });
        form.add(new Label(label), 0, row);
        form.add(f, 1, row);
    }
}
