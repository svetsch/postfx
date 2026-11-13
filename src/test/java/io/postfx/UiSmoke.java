package io.postfx;

import io.postfx.io.WorkspaceStore;
import io.postfx.model.Collection;
import io.postfx.model.Folder;
import io.postfx.model.Items;
import io.postfx.model.KeyValue;
import io.postfx.model.RequestItem;
import io.postfx.model.Workspace;
import io.postfx.ui.AppContext;
import io.postfx.ui.HelpWindow;
import io.postfx.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manual UI smoke run: starts the real window against a local test server, sends a request, runs a chain and writes
 * screenshots. Run with: mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass=io.postfx.UiSmoke -Dexec.args=outDir
 */
public class UiSmoke extends Application {
    static Path out;
    static TestServer server;

    public static void main(String[] args) throws Exception {
        out = Path.of(args.length > 0 ? args[0] : "target/ui-smoke");
        Files.createDirectories(out);
        server = new TestServer();
        launch(UiSmoke.class, args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        Path home = Files.createTempDirectory("postfx-smoke");
        System.setProperty("postfx.home", home.toString());
        WorkspaceStore store = WorkspaceStore.defaultStore();
        Workspace ws = store.load(); // sample workspace
        Collection local = new Collection("Local API");
        local.variables.add(new KeyValue("baseUrl", server.url()));
        RequestItem items = new RequestItem("List items", "GET", "{{baseUrl}}/items?page=1");
        items.checks.add(new io.postfx.model.Check(io.postfx.model.CheckSource.STATUS, "", io.postfx.model.CheckOperator.EQUALS, "200"));
        items.checks.add(new io.postfx.model.Check(io.postfx.model.CheckSource.JSON_PATH, "$[0].name", io.postfx.model.CheckOperator.EQUALS, "a"));
        items.testScript = "pm.test('two items', () => pm.expect(pm.response.json()).to.have.lengthOf(2));\npm.test('fails on purpose', () => pm.expect(1).to.eql(2));";
        local.items.add(items);
        ws.collections.add(0, local);
        AppContext ctx = new AppContext(ws, store);
        MainWindow w = new MainWindow(ctx, stage, true);
        stage.show();

        step(1500, () -> snap(stage.getScene(), "1-welcome.png"));
        step(2500, () -> w.openRequest(items));
        step(3500, () -> sendAccelerator(stage.getScene()));
        step(6500, () -> snap(stage.getScene(), "2-request.png"));
        step(7000, () -> {
            Collection ex = ws.collections.get(1);
            Folder scripts = (Folder) ex.items.get(2);
            w.openRequest((RequestItem) scripts.items.get(1));
        });
        step(8000, () -> snap(stage.getScene(), "3-script-request.png"));
        step(8500, () -> w.openRunner(local, null));
        step(9500, () -> clickRun(stage.getScene()));
        step(13000, () -> snap(stage.getScene(), "4-runner.png"));
        step(13500, () -> {
            w.setDark(true);
            w.openRequest(items);
        });
        step(14500, () -> snap(stage.getScene(), "5-dark.png"));
        step(15000, () -> {
            w.setDark(false);
            HelpWindow.show(stage, "Checks and extractors");
        });
        step(16500, () -> {
            for (Window win : java.util.List.copyOf(Window.getWindows())) {
                if (win instanceof Stage s && "PostFX Help".equals(s.getTitle())) snap(s.getScene(), "6-help.png");
            }
        });
        step(17000, () -> io.postfx.ui.AboutDialog.show(ctx));
        step(18500, () -> {
            for (Window win : java.util.List.copyOf(Window.getWindows())) {
                if (win instanceof Stage s && "About PostFX".equals(s.getTitle())) {
                    snap(s.getScene(), "7-about.png");
                    s.close();
                }
            }
        });
        step(19500, () -> {
            System.out.println("Items in sample: " + Items.flatten(ws.collections.get(1)).size());
            System.out.println("Active environment: " + ctx.activeEnvironment.get());
            System.out.println("SMOKE OK");
            ctx.shutdown();
            server.close();
            Platform.exit();
            System.exit(0);
        });
    }

    private static void sendAccelerator(Scene scene) {
        scene.getAccelerators().entrySet().stream()
                .filter(e -> e.getKey().getName().contains("Enter"))
                .findFirst().ifPresent(e -> e.getValue().run());
    }

    private static void clickRun(Scene scene) {
        scene.getRoot().lookupAll(".send-button").stream()
                .filter(n -> n instanceof javafx.scene.control.Button b && b.getText().contains("Run") && b.isVisible() && b.getScene() != null)
                .reduce((a, b) -> b)
                .ifPresent(n -> ((javafx.scene.control.Button) n).fire());
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

    private static void snap(Scene scene, String name) {
        try {
            WritableImage img = scene.snapshot(null);
            File f = out.resolve(name).toFile();
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", f);
            System.out.println("wrote " + f);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
