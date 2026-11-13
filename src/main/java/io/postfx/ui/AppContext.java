package io.postfx.ui;

import io.postfx.engine.CollectionRunner;
import io.postfx.engine.HttpEngine;
import io.postfx.engine.RequestExecutor;
import io.postfx.engine.VariableContext;
import io.postfx.io.WorkspaceStore;
import io.postfx.model.Collection;
import io.postfx.model.Environment;
import io.postfx.model.Folder;
import io.postfx.model.Item;
import io.postfx.model.Items;
import io.postfx.model.Workspace;
import io.postfx.script.ScriptExecutor;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Application state shared by all views. */
public class AppContext {
    public final Workspace workspace;
    public final WorkspaceStore store;
    public final HttpEngine http = new HttpEngine();
    public final ScriptExecutor scripts = new ScriptExecutor();
    public final RequestExecutor executor = new RequestExecutor(http, scripts);
    public final CollectionRunner runner = new CollectionRunner(executor);
    public final ExecutorService background = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "postfx-worker");
        t.setDaemon(true);
        return t;
    });
    public final ObjectProperty<Environment> activeEnvironment = new SimpleObjectProperty<>();
    public final StringProperty status = new SimpleStringProperty("Ready");

    private final List<Runnable> treeListeners = new ArrayList<>();
    private final List<Runnable> environmentListeners = new ArrayList<>();
    private final List<Runnable> historyListeners = new ArrayList<>();
    private final PauseTransition autosave = new PauseTransition(Duration.seconds(1));
    private MainWindow main;

    public AppContext(Workspace workspace, WorkspaceStore store) {
        this.workspace = workspace;
        this.store = store;
        activeEnvironment.set(workspace.activeEnvironment());
        activeEnvironment.addListener((o, a, b) -> {
            workspace.activeEnvironmentId = b == null ? null : b.id;
            changed();
        });
        autosave.setOnFinished(e -> saveNow());
        // warm up the JavaScript engine so the first send is fast
        background.submit(() -> scripts.run("pm.test('warm-up', () => pm.expect(1).to.eql(1));", new io.postfx.script.ScriptContext()));
    }

    /** Set by the application; used to open the system browser (OAuth 2.0 login). */
    public javafx.application.HostServices hostServices;

    public void openBrowser(String url) {
        try {
            if (hostServices != null) {
                hostServices.showDocument(url);
                return;
            }
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            status.set("Could not open the browser: " + e.getMessage());
        }
    }

    void setMain(MainWindow main) {
        this.main = main;
    }

    public MainWindow main() {
        return main;
    }

    /** Marks the workspace dirty; it is saved shortly after. */
    public void changed() {
        if (Platform.isFxApplicationThread()) autosave.playFromStart();
        else Platform.runLater(autosave::playFromStart);
    }

    public void saveNow() {
        try {
            store.save(workspace);
            status.set("Saved");
        } catch (Exception e) {
            status.set("Could not save workspace: " + e.getMessage());
        }
    }

    public void onTreeChanged(Runnable r) {
        treeListeners.add(r);
    }

    public void onEnvironmentsChanged(Runnable r) {
        environmentListeners.add(r);
    }

    public void onHistoryChanged(Runnable r) {
        historyListeners.add(r);
    }

    public void fireTreeChanged() {
        treeListeners.forEach(Runnable::run);
        changed();
    }

    public void fireEnvironmentsChanged() {
        environmentListeners.forEach(Runnable::run);
        changed();
    }

    public void fireHistoryChanged() {
        historyListeners.forEach(Runnable::run);
        changed();
    }

    /** Collection containing the item, or null for unsaved requests. */
    public Collection collectionOf(Item item) {
        for (Collection c : workspace.collections) {
            if (c == item) return c;
            if (Items.ancestors(c, item).isPresent()) return c;
        }
        return null;
    }

    public List<Folder> ancestorsOf(Item item) {
        Collection c = collectionOf(item);
        if (c == null || c == item) return new ArrayList<>();
        return Items.ancestors(c, item).orElseGet(ArrayList::new);
    }

    /** Variables for an execution in the given collection with the active environment. */
    public VariableContext variables(Collection c) {
        return VariableContext.of(workspace, c, activeEnvironment.get());
    }

    public void writeBack(VariableContext vars, Collection c) {
        vars.writeBack(workspace, c, activeEnvironment.get());
        fireEnvironmentsChanged();
    }

    public void shutdown() {
        autosave.stop();
        saveNow();
        background.shutdownNow();
        scripts.close();
    }
}
