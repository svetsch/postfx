package io.postfx.io;

import io.postfx.model.Collection;
import io.postfx.model.Environment;
import io.postfx.model.KeyValue;

import java.util.ArrayList;
import java.util.List;

public class ImportResult {
    public String format = "";
    public final List<Collection> collections = new ArrayList<>();
    public final List<Environment> environments = new ArrayList<>();
    public final List<KeyValue> globals = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public String summary() {
        StringBuilder sb = new StringBuilder(format);
        if (!collections.isEmpty()) sb.append(": ").append(collections.size()).append(" collection(s)");
        if (!environments.isEmpty()) sb.append(", ").append(environments.size()).append(" environment(s)");
        if (!globals.isEmpty()) sb.append(", ").append(globals.size()).append(" global variable(s)");
        return sb.toString();
    }
}
