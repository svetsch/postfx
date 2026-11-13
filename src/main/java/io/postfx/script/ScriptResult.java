package io.postfx.script;

import io.postfx.engine.ConsoleEntry;
import io.postfx.engine.TestResult;

import java.util.ArrayList;
import java.util.List;

public class ScriptResult {
    public final List<TestResult> tests = new ArrayList<>();
    public final List<ConsoleEntry> console = new ArrayList<>();
    /** Script error (syntax error, exception, timeout), null if the script completed. */
    public String error;
    public boolean nextRequestSet;
    /** Name or id of the next request; null means "stop the run". */
    public String nextRequest;
    public boolean skipRequest;
}
