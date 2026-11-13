package io.postfx.ui;

import io.postfx.io.SampleWorkspace;
import io.postfx.io.WorkspaceStore;
import io.postfx.model.Workspace;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;

/** JavaFX application. */
public class PostFxApp extends Application {
    private AppContext ctx;

    public static void launchApp(String[] args) {
        launch(PostFxApp.class, args);
    }

    @Override
    public void start(Stage stage) {
        WorkspaceStore store = WorkspaceStore.defaultStore();
        boolean firstRun = !store.exists();
        Workspace ws;
        String loadError = null;
        try {
            ws = store.load();
        } catch (Exception e) {
            loadError = e.getMessage();
            ws = SampleWorkspace.create();
            try {
                Path broken = store.file().resolveSibling("workspace.broken-" + System.currentTimeMillis() + ".json");
                Files.move(store.file(), broken);
                loadError += "\n\nThe unreadable file was moved to " + broken;
            } catch (Exception ignored) {
                // keep the original file untouched
            }
        }
        ctx = new AppContext(ws, store);
        ctx.hostServices = getHostServices();
        new MainWindow(ctx, stage, firstRun);
        stage.setOnCloseRequest(e -> ctx.shutdown());
        stage.show();
        if (firstRun) ctx.saveNow();
        if (loadError != null) {
            String msg = loadError;
            Platform.runLater(() -> {
                Alert a = new Alert(Alert.AlertType.WARNING, msg);
                a.setHeaderText("The workspace could not be loaded; a new one was created.");
                a.initOwner(stage);
                a.showAndWait();
            });
        }
    }

    @Override
    public void stop() {
        if (ctx != null) ctx.saveNow();
    }
}
