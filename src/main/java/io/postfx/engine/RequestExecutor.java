package io.postfx.engine;

import io.postfx.model.Auth;
import io.postfx.model.Check;
import io.postfx.model.Collection;
import io.postfx.model.Extractor;
import io.postfx.model.Folder;
import io.postfx.model.Items;
import io.postfx.model.Request;
import io.postfx.model.RequestItem;
import io.postfx.model.Settings;
import io.postfx.script.ScriptContext;
import io.postfx.script.ScriptExecutor;
import io.postfx.script.ScriptResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Executes one request the way Postman does:
 * collection/folder/request pre-request scripts → send → collection/folder/request test scripts → checks → extractors.
 */
public class RequestExecutor {
    private final HttpEngine http;
    private final ScriptExecutor scripts;

    public RequestExecutor(HttpEngine http, ScriptExecutor scripts) {
        this.http = http;
        this.scripts = scripts;
    }

    public HttpEngine http() {
        return http;
    }

    public ScriptExecutor scripts() {
        return scripts;
    }

    /** Hook used to send the request so the UI can cancel it. */
    public interface Sender {
        HttpResult send(ResolvedRequest r);
    }

    public ExecutionResult execute(RequestItem item, Collection collection, VariableContext vars, Settings settings,
                                   int iteration, int iterationCount) {
        return execute(item, collection, vars, settings, iteration, iterationCount, r -> http.send(r, settings));
    }

    public ExecutionResult execute(RequestItem item, Collection collection, VariableContext vars, Settings settings,
                                   int iteration, int iterationCount, Sender sender) {
        ExecutionResult result = new ExecutionResult();
        result.requestId = item.id;
        result.name = item.name;
        result.iteration = iteration;

        List<Folder> chain = collection == null ? new ArrayList<>()
                : Items.ancestors(collection, item).orElseGet(() -> new ArrayList<>(List.of(collection)));
        Request request = JsonUtil.copy(item.request);
        result.finalRequest = request;

        // ---- pre-request scripts (collection → folders → request)
        for (Folder f : chain) {
            runScript(f.preRequestScript, ScriptContext.PRE_REQUEST, label(f, "pre-request"), item, request, null, vars, settings, iteration, iterationCount, result);
        }
        runScript(item.preRequestScript, ScriptContext.PRE_REQUEST, item.name + " (pre-request)", item, request, null, vars, settings, iteration, iterationCount, result);
        if (result.skipped) {
            result.console.add(new ConsoleEntry("info", "Request skipped by pm.execution.skipRequest()", item.name));
            return result;
        }

        // ---- send
        Auth auth = RequestPreparer.effectiveAuth(request, chain);
        if (auth.type.usesOAuth2()) {
            // tokens are kept in the saved model (request, folder or collection), not in the working copy
            Auth original = RequestPreparer.effectiveAuth(item.request, chain);
            if (original.type.usesOAuth2()) auth = original;
            OAuth2Client.forAuth(http, settings, auth).ensureToken(auth.oauth2, vars,
                    msg -> result.console.add(new ConsoleEntry(msg.contains("failed") ? "error" : "info", msg, "oauth2")));
        }
        ResolvedRequest resolved = RequestPreparer.prepare(request, auth, vars);
        result.sent = resolved;
        HttpResult response = sender.send(resolved);
        result.response = response;
        for (String w : resolved.warnings) result.console.add(new ConsoleEntry("warn", w, item.name));
        if (response.failed()) {
            result.errors.add(response.error);
            result.console.add(new ConsoleEntry("error", resolved.method + " " + resolved.url + " failed: " + response.error, item.name));
            return result;
        }
        result.console.add(new ConsoleEntry("log", resolved.method + " " + resolved.url + " → " + response.status + " "
                + response.statusText + " (" + response.timeMs + " ms)", item.name));

        // ---- test scripts (collection → folders → request)
        for (Folder f : chain) {
            runScript(f.testScript, ScriptContext.TEST, label(f, "tests"), item, request, response, vars, settings, iteration, iterationCount, result);
        }
        runScript(item.testScript, ScriptContext.TEST, item.name + " (tests)", item, request, response, vars, settings, iteration, iterationCount, result);

        // ---- declarative checks
        for (Check c : item.checks) {
            if (c.enabled) result.tests.add(CheckEvaluator.evaluate(c, response, vars));
        }
        // ---- extractors (variable chaining)
        for (Extractor ex : item.extractors) {
            if (!ex.enabled) continue;
            ConsoleEntry e = ExtractorEvaluator.apply(ex, response, vars);
            if (e != null) result.console.add(e);
        }
        return result;
    }

    private static String label(Folder f, String what) {
        return (f instanceof Collection ? "collection " : "folder ") + f.name + " (" + what + ")";
    }

    private void runScript(String code, String event, String name, RequestItem item, Request request, HttpResult response,
                           VariableContext vars, Settings settings, int iteration, int iterationCount, ExecutionResult result) {
        if (code == null || code.isBlank()) return;
        ScriptContext sc = new ScriptContext();
        sc.event = event;
        sc.scriptName = name;
        sc.vars = vars;
        sc.request = request;
        sc.requestId = item.id;
        sc.requestName = item.name;
        sc.response = response;
        sc.iteration = iteration;
        sc.iterationCount = iterationCount;
        sc.timeoutMs = settings.scriptTimeoutMs;
        sc.sender = r -> http.send(r, settings);
        ScriptResult sr = scripts.run(code, sc);
        result.tests.addAll(sr.tests);
        result.console.addAll(sr.console);
        if (sr.error != null) {
            String msg = "Error in " + name + ": " + sr.error;
            result.errors.add(msg);
            result.console.add(new ConsoleEntry("error", msg, name));
        }
        if (sr.nextRequestSet) {
            result.nextRequestSet = true;
            result.nextRequest = sr.nextRequest;
        }
        if (sr.skipRequest && ScriptContext.PRE_REQUEST.equals(event)) result.skipped = true;
    }

    /** Runs the execution asynchronously (used by the UI). */
    public static <T> CompletableFuture<T> async(Supplier<T> s) {
        return CompletableFuture.supplyAsync(s);
    }
}
