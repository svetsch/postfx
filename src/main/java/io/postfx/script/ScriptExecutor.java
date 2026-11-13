package io.postfx.script;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs Postman style scripts with GraalJS. Every script gets a fresh, isolated context (no Java access other than
 * the {@link ScriptBridge}); the engine is shared so parsed sources are cached.
 */
public class ScriptExecutor implements AutoCloseable {
    private static final Source SANDBOX;

    static {
        try (InputStream in = ScriptExecutor.class.getResourceAsStream("/io/postfx/script/sandbox.js")) {
            if (in == null) throw new IllegalStateException("sandbox.js missing from classpath");
            String code = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            SANDBOX = Source.newBuilder("js", code, "postfx-sandbox.js").cached(true).buildLiteral();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final Engine engine;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "postfx-script-watchdog");
        t.setDaemon(true);
        return t;
    });

    public ScriptExecutor() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    /** Version of the embedded GraalJS engine. */
    public String engineVersion() {
        return engine.getVersion();
    }

    public ScriptResult run(String script, ScriptContext sc) {
        ScriptResult result = new ScriptResult();
        if (script == null || script.isBlank()) return result;
        ScriptBridge bridge = new ScriptBridge(sc, result);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Context ctx = Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowHostClassLookup(c -> false)
                .allowIO(org.graalvm.polyglot.io.IOAccess.NONE)
                .allowCreateThread(false)
                .build();
        ScheduledFuture<?> killer = watchdog.schedule(() -> {
            timedOut.set(true);
            ctx.close(true);
        }, Math.max(100, sc.timeoutMs), TimeUnit.MILLISECONDS);
        try {
            ctx.getBindings("js").putMember("__bridge", bridge);
            ctx.eval(SANDBOX);
            try {
                ctx.eval(Source.newBuilder("js", script, sc.scriptName).buildLiteral());
            } catch (PolyglotException e) {
                result.error = timedOut.get() ? "Script timed out after " + sc.timeoutMs + " ms" : format(e);
            }
            if (!timedOut.get()) {
                try {
                    ctx.eval("js", "__finish()");
                } catch (PolyglotException e) {
                    if (result.error == null) result.error = format(e);
                }
            }
        } catch (PolyglotException e) {
            result.error = timedOut.get() ? "Script timed out after " + sc.timeoutMs + " ms" : "Sandbox error: " + format(e);
        } catch (IllegalStateException e) {
            result.error = timedOut.get() ? "Script timed out after " + sc.timeoutMs + " ms" : e.getMessage();
        } finally {
            killer.cancel(false);
            try {
                ctx.close();
            } catch (Exception ignored) {
                // already closed (timeout)
            }
        }
        return result;
    }

    static String format(PolyglotException e) {
        String msg = e.getMessage();
        if (e.isHostException() && e.asHostException() != null) {
            msg = e.asHostException().getMessage();
        }
        SourceSection loc = e.getSourceLocation();
        if (loc != null && loc.getSource() != null && !loc.getSource().getName().startsWith("postfx-sandbox")) {
            msg += " (line " + loc.getStartLine() + ")";
        }
        return msg;
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        engine.close();
    }
}
