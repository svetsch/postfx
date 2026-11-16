package io.postfx.cli;

import io.postfx.engine.CollectionRunner;
import io.postfx.engine.ExecutionResult;
import io.postfx.engine.HttpEngine;
import io.postfx.engine.JsonUtil;
import io.postfx.engine.RequestExecutor;
import io.postfx.engine.RunOptions;
import io.postfx.engine.RunReport;
import io.postfx.engine.TestResult;
import io.postfx.engine.VariableContext;
import io.postfx.io.DataFileReader;
import io.postfx.io.ImportResult;
import io.postfx.io.Importer;
import io.postfx.io.JavaTestExporter;
import io.postfx.io.PostmanExporter;
import io.postfx.io.ReportWriter;
import io.postfx.model.Collection;
import io.postfx.model.Environment;
import io.postfx.model.Folder;
import io.postfx.model.Item;
import io.postfx.model.Items;
import io.postfx.model.KeyValue;
import io.postfx.model.RequestItem;
import io.postfx.model.Settings;
import io.postfx.script.ScriptExecutor;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Command line interface: run collections in CI, convert formats, export Java tests. */
public final class Cli {
    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);

    private Cli() {}

    public static final String USAGE = """
            PostFX command line

              postfx                                   start the desktop application
              postfx --version                         version, git commit and build date
              postfx run <collection> [options]        run a collection (Postman, OpenAPI, Insomnia, HAR... any importable file)
                  -e, --environment <file>             environment file (Postman format)
                  -g, --globals <file>                 globals file (Postman format)
                  -d, --data <file>                    iteration data (CSV or JSON)
                  -n, --iterations <n>                 number of iterations
                  --folder <name>                      run only this folder
                  --env-var <key=value>                set an environment variable (repeatable)
                  --delay <ms>                         delay between requests
                  --timeout <ms>                       request timeout (default 30000)
                  --bail                               stop on the first failure
                  -k, --insecure                       do not verify SSL certificates
                  --reporter-junit <file>              write a JUnit XML report
                  --reporter-json <file>               write a JSON report
                  --reporter-md <file>                 write a Markdown test report
                  --export-environment <file>          save the environment after the run
              postfx convert <file> -o <postman.json>  convert any supported format to a Postman v2.1 collection
              postfx export-md <file> -o <doc.md> [-e env] [--folder name] [--no-scripts]
                                                       write a Markdown test document (requests and expected results)
              postfx export-java <file> -o <dir> [--package p] [--class C] [-e env]
                                                       generate a Maven project with JUnit 5 + REST Assured tests

            Exit code: 0 when all tests passed, 1 on test failures, 2 on usage/IO errors.
            """;

    public static int main(String[] args) {
        try {
            if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help") || args[0].equals("help")) {
                OUT.print(USAGE);
                return 0;
            }
            return switch (args[0]) {
                case "-v", "--version", "version" -> {
                    OUT.print(io.postfx.BuildInfo.describe());
                    yield 0;
                }
                case "run" -> run(args);
                case "convert" -> convert(args);
                case "export-java" -> exportJava(args);
                case "export-md" -> exportMarkdown(args);
                default -> {
                    System.err.println("Unknown command: " + args[0] + "\n");
                    OUT.print(USAGE);
                    yield 2;
                }
            };
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static String value(String[] args, int i) {
        if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
        return args[i + 1];
    }

    private static Collection loadCollection(String file, List<String> warnings) throws Exception {
        ImportResult r = Importer.importFile(Path.of(file));
        warnings.addAll(r.warnings);
        if (r.collections.isEmpty()) throw new IllegalArgumentException(file + " does not contain a collection (" + r.format + ")");
        return r.collections.get(0);
    }

    private static Environment loadEnvironment(String file) throws Exception {
        ImportResult r = Importer.importFile(Path.of(file));
        if (!r.environments.isEmpty()) return r.environments.get(0);
        if (!r.globals.isEmpty()) {
            Environment e = new Environment("globals");
            e.values.addAll(r.globals);
            return e;
        }
        throw new IllegalArgumentException(file + " is not an environment file");
    }

    static int run(String[] args) throws Exception {
        String collectionFile = null;
        Environment env = null;
        List<KeyValue> globals = new ArrayList<>();
        RunOptions opts = new RunOptions();
        Settings settings = new Settings();
        String folder = null, junit = null, json = null, exportEnv = null, markdown = null;
        List<String> envVars = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-e", "--environment" -> env = loadEnvironment(value(args, i++));
                case "-g", "--globals" -> globals.addAll(loadEnvironment(value(args, i++)).values);
                case "-d", "--data", "--iteration-data" -> opts.data = DataFileReader.read(Path.of(value(args, i++)));
                case "-n", "--iterations", "--iteration-count" -> opts.iterations = Integer.parseInt(value(args, i++));
                case "--folder" -> folder = value(args, i++);
                case "--env-var" -> envVars.add(value(args, i++));
                case "--delay", "--delay-request" -> opts.delayMs = Long.parseLong(value(args, i++));
                case "--timeout", "--timeout-request" -> settings.timeoutMs = Integer.parseInt(value(args, i++));
                case "--bail" -> opts.stopOnFailure = true;
                case "-k", "--insecure" -> settings.verifySsl = false;
                case "--reporter-junit" -> junit = value(args, i++);
                case "--reporter-json" -> json = value(args, i++);
                case "--reporter-md" -> markdown = value(args, i++);
                case "--export-environment" -> exportEnv = value(args, i++);
                default -> {
                    if (args[i].startsWith("-")) throw new IllegalArgumentException("Unknown option " + args[i]);
                    collectionFile = args[i];
                }
            }
        }
        if (collectionFile == null) throw new IllegalArgumentException("Missing collection file");
        List<String> warnings = new ArrayList<>();
        Collection collection = loadCollection(collectionFile, warnings);
        warnings.forEach(w -> OUT.println("warning: " + w));
        if (env == null) env = new Environment("cli");
        for (String kv : envVars) {
            int eq = kv.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("--env-var expects key=value");
            env.values.add(new KeyValue(kv.substring(0, eq), kv.substring(eq + 1)));
        }
        if (folder != null) {
            String f = folder;
            Item found = findFolder(collection, f);
            if (found == null) throw new IllegalArgumentException("Folder not found: " + folder);
            opts.selection = Items.flatten(found);
        }
        io.postfx.model.Workspace ws = new io.postfx.model.Workspace();
        ws.globals.addAll(globals);
        VariableContext vars = VariableContext.of(ws, collection, env);

        RunReport report;
        try (ScriptExecutor scripts = new ScriptExecutor()) {
            RequestExecutor executor = new RequestExecutor(new HttpEngine(), scripts);
            OUT.println("PostFX run: " + collection.name);
            report = new CollectionRunner(executor).run(collection, vars, settings, opts, new CollectionRunner.Listener() {
                @Override
                public void onIterationStart(int iteration, int total) {
                    if (total > 1) OUT.println("\nIteration " + (iteration + 1) + "/" + total);
                }

                @Override
                public void onRequestDone(ExecutionResult r) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(r.passed() ? "  ✔ " : "  ✘ ").append(r.name);
                    if (r.sent != null) sb.append("  ").append(r.sent.method).append(' ').append(r.sent.url);
                    if (r.response != null && !r.response.failed()) {
                        sb.append("  [").append(r.response.status).append(' ').append(r.response.statusText).append(", ")
                                .append(r.response.timeMs).append(" ms]");
                    }
                    OUT.println(sb);
                    for (TestResult t : r.tests) {
                        OUT.println((t.skipped ? "      - " : t.passed ? "      ✔ " : "      ✘ ") + t.name
                                + (!t.passed && t.error != null ? "\n          " + t.error : ""));
                    }
                    for (String e : r.errors) OUT.println("      ! " + e);
                    r.console.stream().filter(c -> c.level.equals("log") && !c.source.equals(r.name) || c.level.equals("warn"))
                            .forEach(c -> OUT.println("      > " + c.message));
                }
            }, null);
        }
        vars.writeBack(ws, collection, env);
        OUT.println();
        OUT.println("Requests: " + report.totalRequests() + " (" + report.failedRequests() + " failed)   Tests: "
                + (report.passedTests() + report.failedTests()) + " (" + report.failedTests() + " failed)   Avg response: "
                + report.averageResponseTime() + " ms   Duration: " + report.durationMs + " ms");
        report.errors.forEach(e -> OUT.println("! " + e));
        if (junit != null) ReportWriter.writeJUnit(report, Path.of(junit));
        if (json != null) ReportWriter.writeJson(report, Path.of(json));
        if (markdown != null) {
            io.postfx.io.MarkdownExporter.Options mo = new io.postfx.io.MarkdownExporter.Options();
            mo.environment = env;
            mo.globals = globals;
            mo.report = report;
            Folder scopeFolder = folder == null ? null : (Folder) findFolder(collection, folder);
            Files.writeString(Path.of(markdown), io.postfx.io.MarkdownExporter.generate(collection, scopeFolder, mo), StandardCharsets.UTF_8);
        }
        if (exportEnv != null) Files.writeString(Path.of(exportEnv), JsonUtil.toPrettyJson(PostmanExporter.exportEnvironment(env)));
        return report.success() ? 0 : 1;
    }

    static Item findFolder(Folder root, String name) {
        for (Item i : root.items) {
            if (i instanceof Folder f) {
                if (f.name.equals(name)) return f;
                Item inner = findFolder(f, name);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    static int convert(String[] args) throws Exception {
        String in = null, out = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-o") || args[i].equals("--output")) out = value(args, i++);
            else in = args[i];
        }
        if (in == null || out == null) throw new IllegalArgumentException("Usage: postfx convert <file> -o <postman.json>");
        List<String> warnings = new ArrayList<>();
        Collection c = loadCollection(in, warnings);
        warnings.forEach(w -> OUT.println("warning: " + w));
        Files.writeString(Path.of(out), JsonUtil.toPrettyJson(PostmanExporter.exportCollection(c)), StandardCharsets.UTF_8);
        OUT.println("Wrote " + out + " (" + Items.flatten(c).size() + " requests)");
        return 0;
    }

    static int exportMarkdown(String[] args) throws Exception {
        String in = null, out = null, folder = null;
        io.postfx.io.MarkdownExporter.Options o = new io.postfx.io.MarkdownExporter.Options();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--output" -> out = value(args, i++);
                case "-e", "--environment" -> o.environment = loadEnvironment(value(args, i++));
                case "--folder" -> folder = value(args, i++);
                case "--no-scripts" -> o.includeScripts = false;
                default -> in = args[i];
            }
        }
        if (in == null || out == null) throw new IllegalArgumentException("Usage: postfx export-md <file> -o <doc.md>");
        Collection c = loadCollection(in, new ArrayList<>());
        Folder scope = null;
        if (folder != null) {
            scope = (Folder) findFolder(c, folder);
            if (scope == null) throw new IllegalArgumentException("Folder not found: " + folder);
        }
        Files.writeString(Path.of(out), io.postfx.io.MarkdownExporter.generate(c, scope, o), StandardCharsets.UTF_8);
        OUT.println("Wrote " + out + " (" + Items.flatten(scope == null ? c : scope).size() + " test cases)");
        return 0;
    }

    static int exportJava(String[] args) throws Exception {
        String in = null, out = null;
        JavaTestExporter.Options o = new JavaTestExporter.Options();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--output" -> out = value(args, i++);
                case "--package" -> o.packageName = value(args, i++);
                case "--class" -> o.className = value(args, i++);
                case "-e", "--environment" -> o.environment = loadEnvironment(value(args, i++));
                default -> in = args[i];
            }
        }
        if (in == null || out == null) throw new IllegalArgumentException("Usage: postfx export-java <file> -o <dir>");
        List<String> warnings = new ArrayList<>();
        Collection c = loadCollection(in, warnings);
        Path file = JavaTestExporter.writeMavenProject(Path.of(out), c, null, o);
        List<RequestItem> reqs = Items.flatten(c);
        OUT.println("Wrote " + file + " (" + reqs.size() + " tests). Run with: cd " + out + " && mvn test");
        return 0;
    }
}
