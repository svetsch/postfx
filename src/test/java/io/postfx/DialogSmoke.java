package io.postfx;

import io.postfx.io.SampleWorkspace;
import io.postfx.io.WorkspaceStore;
import io.postfx.model.Collection;
import io.postfx.model.Folder;
import io.postfx.model.RequestItem;
import io.postfx.model.Workspace;
import io.postfx.ui.AppContext;
import io.postfx.ui.Dialogs;
import io.postfx.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Manual check of the export / code dialogs: opens each one, screenshots it at its default size and enlarged.
 * Run with: mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass=io.postfx.DialogSmoke -Dexec.args=outDir
 */
public class DialogSmoke extends Application {
    static Path out;

    public static void main(String[] args) throws Exception {
        out = Path.of(args.length > 0 ? args[0] : "target/dialog-smoke");
        Files.createDirectories(out);
        launch(DialogSmoke.class, args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        System.setProperty("postfx.home", Files.createTempDirectory("postfx-dialogs").toString());
        WorkspaceStore store = WorkspaceStore.defaultStore();
        Workspace ws = SampleWorkspace.create();
        AppContext ctx = new AppContext(ws, store);
        new MainWindow(ctx, stage, false);
        stage.show();
        Collection c = ws.collections.get(0);
        RequestItem tour = (RequestItem) ((Folder) c.items.get(2)).items.get(1);

        step(1000, () -> Dialogs.showCode(ctx, tour));
        step(2500, () -> snapWindow("Code: ", "1-code-default.png", null));
        step(3000, () -> snapWindow("Code: ", "2-code-enlarged.png", new double[]{1300, 900}));
        step(4500, () -> snapWindow("Code: ", "2-code-enlarged.png", null));
        step(5000, () -> closeWindow("Code: "));
        step(5500, () -> Dialogs.exportJava(ctx, c, null));
        step(7000, () -> snapWindow("Export Java tests", "3-export-java.png", null));
        step(7500, () -> closeWindow("Export Java tests"));
        step(8000, () -> Dialogs.importText());
        step(9500, () -> snapWindow("Import from text", "4-import-text.png", null));
        step(10000, () -> snapWindow("Import from text", "5-import-text-enlarged.png", new double[]{1200, 800}));
        step(11500, () -> snapWindow("Import from text", "5-import-text-enlarged.png", null));
        step(12000, () -> closeWindow("Import from text"));
        step(12500, () -> Dialogs.settings(ctx));
        step(14000, () -> snapWindow("Settings", "6-settings.png", null));
        step(14500, () -> closeWindow("Settings"));
        step(15500, () -> {
            System.out.println("DIALOGS OK");
            ctx.shutdown();
            Platform.exit();
            System.exit(0);
        });
    }

    private static void snapWindow(String titlePrefix, String file, double[] resize) {
        for (Window w : List.copyOf(Window.getWindows())) {
            if (w instanceof Stage s && s.getTitle() != null && s.getTitle().startsWith(titlePrefix)) {
                if (resize != null) {
                    s.setWidth(resize[0]);
                    s.setHeight(resize[1]);
                    return; // snapshot in a later step, after layout
                }
                try {
                    ImageIO.write(SwingFXUtils.fromFXImage(s.getScene().snapshot(null), null), "png", out.resolve(file).toFile());
                    System.out.println("wrote " + file + " (" + (int) s.getWidth() + "x" + (int) s.getHeight() + ")");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void closeWindow(String titlePrefix) {
        for (Window w : List.copyOf(Window.getWindows())) {
            if (w instanceof Stage s && s.getTitle() != null && s.getTitle().startsWith(titlePrefix)) s.close();
        }
    }

    private static void step(long delayMs, Runnable r) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
            }
            Platform.runLater(() -> {
                try {
                    r.run();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }
}
