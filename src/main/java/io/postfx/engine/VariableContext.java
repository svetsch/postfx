package io.postfx.engine;

import io.postfx.model.Collection;
import io.postfx.model.Environment;
import io.postfx.model.KeyValue;
import io.postfx.model.VarScope;
import io.postfx.model.Workspace;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime variable scopes, same precedence as Postman:
 * local &gt; iteration data &gt; environment &gt; collection &gt; global.
 */
public class VariableContext {
    public final Map<String, String> globals = new LinkedHashMap<>();
    public final Map<String, String> collection = new LinkedHashMap<>();
    public final Map<String, String> environment = new LinkedHashMap<>();
    public final Map<String, String> data = new LinkedHashMap<>();
    public final Map<String, String> local = new LinkedHashMap<>();
    public String environmentName = "";

    public static VariableContext of(Workspace ws, Collection c, Environment env) {
        VariableContext ctx = new VariableContext();
        if (ws != null) load(ws.globals, ctx.globals);
        if (c != null) load(c.variables, ctx.collection);
        if (env != null) {
            load(env.values, ctx.environment);
            ctx.environmentName = env.name;
        }
        return ctx;
    }

    private static void load(List<KeyValue> list, Map<String, String> target) {
        for (KeyValue kv : list) {
            if (kv.enabled && kv.key != null && !kv.key.isBlank()) {
                target.put(kv.key, kv.value == null ? "" : kv.value);
            }
        }
    }

    public String get(String key) {
        if (local.containsKey(key)) return local.get(key);
        if (data.containsKey(key)) return data.get(key);
        if (environment.containsKey(key)) return environment.get(key);
        if (collection.containsKey(key)) return collection.get(key);
        return globals.get(key);
    }

    public boolean has(String key) {
        return get(key) != null;
    }

    /** All variables merged by precedence. */
    public Map<String, String> merged() {
        Map<String, String> m = new LinkedHashMap<>(globals);
        m.putAll(collection);
        m.putAll(environment);
        m.putAll(data);
        m.putAll(local);
        return m;
    }

    public Map<String, String> scope(String name) {
        return switch (name) {
            case "global", "globals" -> globals;
            case "collection", "collectionVariables" -> collection;
            case "environment" -> environment;
            case "data", "iterationData" -> data;
            case "local", "variables" -> local;
            default -> throw new IllegalArgumentException("Unknown variable scope: " + name);
        };
    }

    public Map<String, String> scope(VarScope s) {
        return switch (s) {
            case GLOBAL -> globals;
            case COLLECTION -> collection;
            case ENVIRONMENT -> environment;
        };
    }

    public String resolve(String text) {
        return VariableResolver.resolve(text, this);
    }

    /** Persists the runtime values back into the workspace model objects. */
    public void writeBack(Workspace ws, Collection c, Environment env) {
        if (ws != null) apply(ws.globals, globals);
        if (c != null) apply(c.variables, collection);
        if (env != null) apply(env.values, environment);
    }

    private static void apply(List<KeyValue> list, Map<String, String> values) {
        Map<String, String> remaining = new LinkedHashMap<>(values);
        Iterator<KeyValue> it = list.iterator();
        List<KeyValue> keepDisabled = new ArrayList<>();
        while (it.hasNext()) {
            KeyValue kv = it.next();
            if (!kv.enabled) {
                keepDisabled.add(kv);
                continue;
            }
            if (remaining.containsKey(kv.key)) {
                kv.value = remaining.remove(kv.key);
            } else if (kv.key != null && !kv.key.isBlank()) {
                it.remove(); // unset by a script
            }
        }
        remaining.forEach((k, v) -> list.add(new KeyValue(k, v)));
    }
}
