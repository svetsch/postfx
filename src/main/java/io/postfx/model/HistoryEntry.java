package io.postfx.model;

public class HistoryEntry {
    public long timestamp;
    public String name;
    public int status;
    public long timeMs;
    public Request request;

    public HistoryEntry() {}
}
