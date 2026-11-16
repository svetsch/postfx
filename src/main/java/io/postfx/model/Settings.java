package io.postfx.model;

public class Settings {
    public int timeoutMs = 30000;
    public boolean followRedirects = true;
    public boolean verifySsl = true;
    public int scriptTimeoutMs = 30000;
    public int historyLimit = 100;
    public boolean darkTheme;
    /** Response tab shown after a request: body, tests, console, headers, auto (failures first) or keep. */
    public String responseTab = RESPONSE_TAB_BODY;

    public static final String RESPONSE_TAB_BODY = "body";
    public static final String RESPONSE_TAB_TESTS = "tests";
    public static final String RESPONSE_TAB_CONSOLE = "console";
    public static final String RESPONSE_TAB_HEADERS = "headers";
    public static final String RESPONSE_TAB_AUTO = "auto";
    public static final String RESPONSE_TAB_KEEP = "keep";
}
