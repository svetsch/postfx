package io.postfx.script;

import io.postfx.engine.HttpResult;
import io.postfx.engine.ResolvedRequest;
import io.postfx.engine.VariableContext;
import io.postfx.model.Request;

import java.util.function.Function;

/** Inputs of one script execution. */
public class ScriptContext {
    public static final String PRE_REQUEST = "prerequest";
    public static final String TEST = "test";

    public String event = PRE_REQUEST;
    /** Shown in error messages, e.g. "Login (pre-request)". */
    public String scriptName = "script";
    public VariableContext vars = new VariableContext();
    /** Mutable copy of the request; pre-request scripts may change it. */
    public Request request = new Request();
    public String requestId = "";
    public String requestName = "";
    /** Response, only for test scripts. */
    public HttpResult response;
    public int iteration;
    public int iterationCount = 1;
    /** Used by pm.sendRequest. */
    public Function<ResolvedRequest, HttpResult> sender;
    public int timeoutMs = 30000;
}
