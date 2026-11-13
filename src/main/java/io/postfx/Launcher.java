package io.postfx;

import io.postfx.cli.Cli;

/**
 * Entry point. Without arguments it starts the JavaFX application; with a command ("run", "convert",
 * "export-java") it runs headless. Kept free of JavaFX types so the jar also works from the class path.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        if (System.getProperty("polyglot.engine.WarnInterpreterOnly") == null) {
            System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        }
        if (args.length > 0) {
            System.exit(Cli.main(args));
        }
        io.postfx.ui.PostFxApp.launchApp(args);
    }
}
