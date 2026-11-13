package io.postfx.engine;

public class TestResult {
    public String name;
    public boolean passed;
    public boolean skipped;
    public String error;
    /** "script" (pm.test), "check" (declarative check) */
    public String origin = "script";

    public TestResult() {}

    public TestResult(String name, boolean passed, String error, boolean skipped, String origin) {
        this.name = name;
        this.passed = passed;
        this.error = error;
        this.skipped = skipped;
        this.origin = origin;
    }
}
