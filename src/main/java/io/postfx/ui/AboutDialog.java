package io.postfx.ui;

import io.postfx.BuildInfo;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;

/** About box: version, git commit, build date and runtime versions, with a "Copy" button for bug reports. */
public final class AboutDialog {
    private AboutDialog() {}

    public static void show(AppContext ctx) {
        Map<String, String> info = new LinkedHashMap<>(BuildInfo.details());
        info.put("JavaScript", "GraalJS " + io.postfx.BuildInfo.graalJsVersion());
        info.put("Workspace", ctx.store.file().toString());

        Label name = new Label("PostFX");
        name.getStyleClass().add("welcome-title");
        Label version = new Label("Version " + BuildInfo.shortVersion());
        version.getStyleClass().add("about-version");
        Label tagline = new Label("API client and test runner — Postman compatible collections and scripts,\n"
                + "OpenAPI / Swagger / Insomnia / HAR / cURL import, Java (JUnit 5 + REST Assured) export.");
        tagline.getStyleClass().add("hint");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(6);
        int row = 0;
        for (Map.Entry<String, String> e : info.entrySet()) {
            Label k = new Label(e.getKey());
            k.getStyleClass().add("section-title");
            Label v = new Label(e.getValue());
            v.getStyleClass().add("mono");
            v.setWrapText(true);
            v.setMaxWidth(520);
            grid.add(k, 0, row);
            grid.add(v, 1, row++);
        }

        VBox box = new VBox(8, name, version, tagline, new Separator(), grid);
        box.setPadding(new Insets(16, 20, 8, 20));

        Dialog<ButtonType> d = new Dialog<>();
        if (Dialogs.owner != null) d.initOwner(Dialogs.owner);
        d.setTitle("About PostFX");
        d.getDialogPane().setContent(box);
        ButtonType copy = new ButtonType("Copy details", ButtonBar.ButtonData.LEFT);
        d.getDialogPane().getButtonTypes().addAll(copy, ButtonType.CLOSE);
        d.getDialogPane().lookupButton(copy).addEventFilter(ActionEvent.ACTION, e -> {
            StringBuilder sb = new StringBuilder("PostFX ").append(BuildInfo.shortVersion()).append('\n');
            info.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
            ClipboardContent c = new ClipboardContent();
            c.putString(sb.toString());
            Clipboard.getSystemClipboard().setContent(c);
            ctx.status.set("Version details copied to the clipboard");
            e.consume();
        });
        d.showAndWait();
    }
}
