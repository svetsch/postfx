package io.postfx.io;

import io.postfx.engine.ExecutionResult;
import io.postfx.engine.JsonUtil;
import io.postfx.engine.RunReport;
import io.postfx.engine.TestResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Writes run reports as JSON, JUnit XML (for CI servers) or plain text. */
public final class ReportWriter {
    private ReportWriter() {}

    public static void writeJson(RunReport r, Path file) throws IOException {
        Files.writeString(file, JsonUtil.toPrettyJson(r), StandardCharsets.UTF_8);
    }

    public static void writeJUnit(RunReport r, Path file) throws IOException {
        Files.writeString(file, junit(r), StandardCharsets.UTF_8);
    }

    public static String junit(RunReport r) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        long tests = r.results.stream().mapToLong(x -> Math.max(1, x.tests.size())).sum();
        long failures = r.results.stream().mapToLong(x -> x.tests.isEmpty() ? (x.errors.isEmpty() ? 0 : 1) : x.failedCount()).sum();
        sb.append("<testsuites name=\"").append(x(r.collectionName)).append("\" tests=\"").append(tests).append("\" failures=\"")
                .append(failures).append("\" time=\"").append(r.durationMs / 1000.0).append("\">\n");
        for (ExecutionResult e : r.results) {
            long t = e.response != null ? e.response.timeMs : 0;
            sb.append("  <testsuite name=\"").append(x(e.name)).append(" (iteration ").append(e.iteration + 1).append(")\" tests=\"")
                    .append(Math.max(1, e.tests.size())).append("\" failures=\"").append(e.failedCount()).append("\" errors=\"")
                    .append(e.errors.size()).append("\" time=\"").append(t / 1000.0).append("\" timestamp=\"")
                    .append(Instant.ofEpochMilli(r.startedAt)).append("\">\n");
            if (e.tests.isEmpty()) {
                sb.append("    <testcase name=\"").append(x(e.name)).append("\" classname=\"").append(x(r.collectionName)).append("\" time=\"").append(t / 1000.0).append("\"");
                if (e.errors.isEmpty()) sb.append("/>\n");
                else sb.append(">\n      <error message=\"").append(x(String.join("; ", e.errors))).append("\"/>\n    </testcase>\n");
            }
            for (TestResult tr : e.tests) {
                sb.append("    <testcase name=\"").append(x(tr.name)).append("\" classname=\"").append(x(r.collectionName + "." + e.name)).append("\"");
                if (tr.skipped) sb.append(">\n      <skipped/>\n    </testcase>\n");
                else if (!tr.passed) sb.append(">\n      <failure message=\"").append(x(tr.error)).append("\" type=\"AssertionFailure\"/>\n    </testcase>\n");
                else sb.append("/>\n");
            }
            if (!e.errors.isEmpty() && !e.tests.isEmpty()) {
                sb.append("    <system-err>").append(x(String.join("\n", e.errors))).append("</system-err>\n");
            }
            sb.append("  </testsuite>\n");
        }
        return sb.append("</testsuites>\n").toString();
    }

    public static String text(RunReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Collection : ").append(r.collectionName).append('\n');
        if (r.environmentName != null && !r.environmentName.isEmpty()) sb.append("Environment: ").append(r.environmentName).append('\n');
        int iteration = -1;
        for (ExecutionResult e : r.results) {
            if (e.iteration != iteration) {
                iteration = e.iteration;
                sb.append("\nIteration ").append(iteration + 1).append('/').append(r.iterations).append('\n');
            }
            sb.append(e.passed() ? "  ✔ " : "  ✘ ").append(e.name);
            if (e.response != null && !e.response.failed()) {
                sb.append("  [").append(e.response.status).append(' ').append(e.response.statusText).append(", ").append(e.response.timeMs).append(" ms]");
            }
            if (e.skipped) sb.append("  (skipped)");
            sb.append('\n');
            for (TestResult t : e.tests) {
                sb.append(t.skipped ? "      - " : t.passed ? "      ✔ " : "      ✘ ").append(t.name);
                if (!t.passed && t.error != null) sb.append("\n          ").append(t.error);
                sb.append('\n');
            }
            for (String err : e.errors) sb.append("      ! ").append(err).append('\n');
        }
        sb.append("\nRequests: ").append(r.totalRequests()).append(" (").append(r.failedRequests()).append(" failed)")
                .append("   Tests: ").append(r.passedTests() + r.failedTests()).append(" (").append(r.failedTests()).append(" failed)")
                .append("   Avg response: ").append(r.averageResponseTime()).append(" ms")
                .append("   Duration: ").append(r.durationMs).append(" ms\n");
        for (String err : r.errors) sb.append("! ").append(err).append('\n');
        return sb.toString();
    }

    private static String x(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "&#10;");
    }
}
