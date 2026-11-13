package io.postfx.engine;

import io.postfx.model.RequestItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RunOptions {
    public int iterations = 1;
    /** Iteration data rows (CSV/JSON data file); when present and iterations == 1, one iteration per row. */
    public List<Map<String, String>> data = new ArrayList<>();
    public long delayMs;
    public boolean stopOnFailure;
    /** Requests to run, in order. Null means every request of the collection. */
    public List<RequestItem> selection;
    /** Safety net against endless setNextRequest() loops. */
    public int maxStepsPerIteration = 10000;
}
