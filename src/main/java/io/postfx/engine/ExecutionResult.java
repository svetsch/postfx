package io.postfx.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.postfx.model.Request;

import java.util.ArrayList;
import java.util.List;

/** Outcome of one request execution (scripts + HTTP call + checks + extractors). */
public class ExecutionResult {
    public String requestId;
    public String name;
    public int iteration;
    /** The request after pre-request scripts ran (unresolved). */
    @JsonIgnore
    public Request finalRequest;
    public ResolvedRequest sent;
    public HttpResult response;
    public final List<TestResult> tests = new ArrayList<>();
    public final List<ConsoleEntry> console = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public boolean skipped;
    /** setNextRequest() was called. */
    public boolean nextRequestSet;
    public String nextRequest;

    public long passedCount() {
        return tests.stream().filter(t -> t.passed && !t.skipped).count();
    }

    public long failedCount() {
        return tests.stream().filter(t -> !t.passed).count();
    }

    public boolean passed() {
        return errors.isEmpty() && failedCount() == 0;
    }
}
