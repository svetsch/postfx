package io.postfx.engine;

import io.postfx.model.Collection;
import io.postfx.model.Items;
import io.postfx.model.RequestItem;
import io.postfx.model.Settings;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a chain of requests: variables set by one request (scripts or extractors) are visible to the next ones,
 * and {@code pm.execution.setNextRequest()} can jump inside the chain or stop it.
 */
public class CollectionRunner {
    public interface Listener {
        default void onIterationStart(int iteration, int total) {}
        default void onRequestStart(int iteration, RequestItem item) {}
        default void onRequestDone(ExecutionResult result) {}
        default void onFinish(RunReport report) {}
    }

    private final RequestExecutor executor;

    public CollectionRunner(RequestExecutor executor) {
        this.executor = executor;
    }

    public RunReport run(Collection collection, VariableContext vars, Settings settings, RunOptions opts,
                         Listener listener, AtomicBoolean cancel) {
        Listener l = listener == null ? new Listener() {} : listener;
        AtomicBoolean stop = cancel == null ? new AtomicBoolean() : cancel;
        List<RequestItem> order = opts.selection != null ? opts.selection : Items.flatten(collection);

        RunReport report = new RunReport();
        report.collectionName = collection.name;
        report.environmentName = vars.environmentName;
        report.startedAt = System.currentTimeMillis();
        int iterations = Math.max(1, opts.iterations);
        if (!opts.data.isEmpty() && opts.iterations <= 1) iterations = opts.data.size();
        report.iterations = iterations;

        outer:
        for (int it = 0; it < iterations; it++) {
            vars.data.clear();
            if (!opts.data.isEmpty()) {
                Map<String, String> row = opts.data.get(it % opts.data.size());
                vars.data.putAll(row);
            }
            l.onIterationStart(it, iterations);
            int index = 0;
            int steps = 0;
            while (index < order.size()) {
                if (stop.get()) {
                    report.cancelled = true;
                    break outer;
                }
                if (++steps > opts.maxStepsPerIteration) {
                    report.errors.add("Stopped: more than " + opts.maxStepsPerIteration + " steps in iteration " + (it + 1) + " (setNextRequest loop?)");
                    break outer;
                }
                RequestItem item = order.get(index);
                l.onRequestStart(it, item);
                ExecutionResult r;
                try {
                    r = executor.execute(item, collection, vars, settings, it, iterations);
                } catch (Exception e) {
                    r = new ExecutionResult();
                    r.requestId = item.id;
                    r.name = item.name;
                    r.iteration = it;
                    r.errors.add("Internal error: " + e);
                }
                report.results.add(r);
                l.onRequestDone(r);

                if (opts.stopOnFailure && !r.passed()) {
                    report.stoppedOnFailure = true;
                    break outer;
                }
                if (r.nextRequestSet) {
                    if (r.nextRequest == null) break; // setNextRequest(null): end of this iteration
                    int target = indexOf(order, r.nextRequest);
                    if (target < 0) {
                        report.errors.add("setNextRequest(\"" + r.nextRequest + "\"): no request with that name in the run");
                        break;
                    }
                    index = target;
                } else {
                    index++;
                }
                if (opts.delayMs > 0 && index < order.size()) {
                    try {
                        Thread.sleep(opts.delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        report.cancelled = true;
                        break outer;
                    }
                }
            }
        }
        report.durationMs = System.currentTimeMillis() - report.startedAt;
        l.onFinish(report);
        return report;
    }

    private static int indexOf(List<RequestItem> order, String nameOrId) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).id.equals(nameOrId)) return i;
        }
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).name.equals(nameOrId)) return i;
        }
        return -1;
    }
}
