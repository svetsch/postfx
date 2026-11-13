package io.postfx.engine;

public class ConsoleEntry {
    public String level;
    public String message;
    public String source;
    public long timestamp = System.currentTimeMillis();

    public ConsoleEntry() {}

    public ConsoleEntry(String level, String message, String source) {
        this.level = level;
        this.message = message;
        this.source = source;
    }

    @Override
    public String toString() {
        return "[" + level + "] " + (source == null ? "" : source + ": ") + message;
    }
}
