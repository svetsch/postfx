package io.postfx.engine;

import java.util.ArrayList;
import java.util.List;

public class RunReport {
    public String collectionName;
    public String environmentName;
    public long startedAt;
    public long durationMs;
    public int iterations;
    public boolean cancelled;
    public boolean stoppedOnFailure;
    public final List<ExecutionResult> results = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();

    public long totalRequests() {
        return results.stream().filter(r -> !r.skipped).count();
    }

    public long failedRequests() {
        return results.stream().filter(r -> !r.passed()).count();
    }

    public long passedTests() {
        return results.stream().mapToLong(ExecutionResult::passedCount).sum();
    }

    public long failedTests() {
        return results.stream().mapToLong(ExecutionResult::failedCount).sum();
    }

    public long averageResponseTime() {
        return (long) results.stream().filter(r -> r.response != null && !r.response.failed())
                .mapToLong(r -> r.response.timeMs).average().orElse(0);
    }

    public boolean success() {
        return errors.isEmpty() && failedRequests() == 0 && !cancelled;
    }
}
