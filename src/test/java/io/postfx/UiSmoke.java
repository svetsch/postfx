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
        RequestItem xmlOrders = new RequestItem("XML orders", "POST", "{{baseUrl}}/xml");
        xmlOrders.request.body.mode = io.postfx.model.BodyMode.RAW;
        xmlOrders.request.body.rawLanguage = "xml";
        xmlOrders.request.body.raw = "<query><customer id=\"42\"/><status>open</status><limit>10</limit></query>";
        local.items.add(xmlOrders);
        ws.collections.add(0, local);
        Collection oauth = new Collection("OAuth API");
        oauth.variables.add(new KeyValue("baseUrl", server.url()));
        oauth.variables.add(new KeyValue("clientSecret", "s3cret"));
        oauth.auth = new io.postfx.model.Auth(io.postfx.model.AuthType.OAUTH2);
        oauth.auth.oauth2.grantType = io.postfx.model.OAuth2.CLIENT_CREDENTIALS;
        oauth.auth.oauth2.accessTokenUrl = "{{baseUrl}}/oauth/token";
        oauth.auth.oauth2.clientId = "app";
        oauth.auth.oauth2.clientSecret = "{{clientSecret}}";
        oauth.auth.oauth2.scope = "read";
        RequestItem secure = new RequestItem("Protected resource", "GET", "{{baseUrl}}/secure");
        secure.checks.add(new io.postfx.model.Check(io.postfx.model.CheckSource.STATUS, "", io.postfx.model.CheckOperator.EQUALS, "200"));
        oauth.items.add(secure);
        ws.collections.add(oauth);
        Collection oidc = new Collection("OIDC API");
        oidc.auth = new io.postfx.model.Auth(io.postfx.model.AuthType.OIDC);
        oidc.auth.oauth2.grantType = io.postfx.model.OAuth2.AUTHORIZATION_CODE_PKCE;
        oidc.auth.oauth2.discoveryUrl = server.url();
        oidc.auth.oauth2.clientId = "app";
        oidc.auth.oauth2.scope = "";
        ws.collections.add(oidc);
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
        step(19000, () -> {
            w.openFolder(oauth);
            stage.getScene().getRoot().lookupAll(".tab-pane").forEach(n -> {
                var tp = (javafx.scene.control.TabPane) n;
                tp.getTabs().stream().filter(t -> t.getText().equals("Auth")).findFirst().ifPresent(t -> tp.getSelectionModel().select(t));
            });
        });
        step(20000, () -> stage.getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof javafx.scene.control.Button b && "Get New Access Token".equals(b.getText()) && b.isVisible() && b.getScene() != null)
                .findFirst().ifPresent(n -> ((javafx.scene.control.Button) n).fire()));
        step(22000, () -> snap(stage.getScene(), "8-oauth.png"));
        step(22500, () -> w.openRequest(secure));
        step(23000, () -> sendAccelerator(stage.getScene()));
        step(25000, () -> {
            snap(stage.getScene(), "9-oauth-request.png");
            System.out.println("OAuth token: " + oauth.auth.oauth2.accessToken);
        });
        step(25500, () -> {
            w.openFolder(oidc);
            stage.getScene().getRoot().lookupAll(".tab-pane").forEach(n -> {
                var tp = (javafx.scene.control.TabPane) n;
                tp.getTabs().stream().filter(t -> t.getText().equals("Auth")).findFirst().ifPresent(t -> tp.getSelectionModel().select(t));
            });
        });
        step(26500, () -> stage.getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof javafx.scene.control.Button b && "Discover".equals(b.getText()) && b.isVisible() && b.getScene() != null)
                .findFirst().ifPresent(n -> ((javafx.scene.control.Button) n).fire()));
        step(28000, () -> {
            snap(stage.getScene(), "10-oidc.png");
            System.out.println("OIDC token URL: " + oidc.auth.oauth2.accessTokenUrl);
        });
        step(28500, () -> {
            w.setDark(true);
            w.openRequest(secure);
        });
        step(29500, () -> snap(stage.getScene(), "11-dark-oauth-request.png"));
        step(30000, () -> {
            w.setDark(false);
            w.openRequest(xmlOrders);
            stage.getScene().getRoot().lookupAll(".tab-pane").forEach(n -> {
                var tp = (javafx.scene.control.TabPane) n;
                tp.getTabs().stream().filter(t -> t.getText().startsWith("Body")).findFirst().ifPresent(t -> tp.getSelectionModel().select(t));
            });
        });
        step(31000, () -> stage.getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof javafx.scene.control.Button b && "Beautify".equals(b.getText()) && b.isVisible() && b.getScene() != null)
                .findFirst().ifPresent(n -> ((javafx.scene.control.Button) n).fire()));
        step(31500, () -> sendAccelerator(stage.getScene()));
        step(33500, () -> {
            snap(stage.getScene(), "12-xml.png");
            System.out.println("XML body after Beautify: " + xmlOrders.request.body.raw.replace("\n", "\\n"));
        });
        step(34500, () -> {
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
