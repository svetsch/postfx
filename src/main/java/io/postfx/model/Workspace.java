package io.postfx.model;

import java.util.ArrayList;
import java.util.List;

/** Everything persisted by the application. */
public class Workspace {
    public List<Collection> collections = new ArrayList<>();
    public List<Environment> environments = new ArrayList<>();
    public List<KeyValue> globals = new ArrayList<>();
    public String activeEnvironmentId;
    public List<HistoryEntry> history = new ArrayList<>();
    public Settings settings = new Settings();

    public Environment activeEnvironment() {
        if (activeEnvironmentId == null) return null;
        return environments.stream().filter(e -> activeEnvironmentId.equals(e.id)).findFirst().orElse(null);
    }
}
