package io.postfx.io;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.engine.JsonUtil;
import io.postfx.engine.UrlUtil;
import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.Body;
import io.postfx.model.BodyMode;
import io.postfx.model.Collection;
import io.postfx.model.Environment;
import io.postfx.model.Folder;
import io.postfx.model.Item;
import io.postfx.model.KeyValue;
import io.postfx.model.Request;
import io.postfx.model.RequestItem;

import java.util.ArrayList;
import java.util.List;

/** Imports Postman collections (v2.0 / v2.1), environments and globals. */
public final class PostmanImporter {
    private PostmanImporter() {}

    public static boolean isCollection(JsonNode n) {
        return n.has("info") && n.has("item");
    }

    public static boolean isEnvironment(JsonNode n) {
        return n.has("values") && n.get("values").isArray() && !n.has("item");
    }

    public static Collection importCollection(JsonNode n, List<String> warnings) {
        JsonNode info = n.path("info");
        if (info.path("schema").asText("").contains("v1.0")) {
            throw new IllegalArgumentException("Postman collection format v1 is not supported, re-export it as v2.1 from Postman");
        }
        Collection c = new Collection(info.path("name").asText("Imported collection"));
        c.description = description(info.get("description"));
        c.auth = auth(n.get("auth"), AuthType.NONE, warnings);
        readEvents(n.get("event"), c);
        for (JsonNode v : n.path("variable")) c.variables.add(variable(v));
        for (JsonNode child : n.path("item")) c.items.add(item(child, warnings));
        return c;
    }

    public static Environment importEnvironment(JsonNode n) {
        Environment e = new Environment(n.path("name").asText("Imported environment"));
        for (JsonNode v : n.path("values")) e.values.add(variable(v));
        return e;
    }

    static KeyValue variable(JsonNode v) {
        KeyValue kv = new KeyValue(v.path("key").asText(""), text(v.get("value")));
        kv.enabled = v.has("enabled") ? v.path("enabled").asBoolean(true) : !v.path("disabled").asBoolean(false);
        if ("secret".equals(v.path("type").asText())) kv.type = "secret";
        if (v.hasNonNull("description")) kv.description = description(v.get("description"));
        return kv;
    }

    private static Item item(JsonNode n, List<String> warnings) {
        String name = n.path("name").asText("Untitled");
        if (n.has("item")) {
            Folder f = new Folder(name);
            f.description = description(n.get("description"));
            f.auth = auth(n.get("auth"), AuthType.INHERIT, warnings);
            readEvents(n.get("event"), f);
            for (JsonNode child : n.path("item")) f.items.add(item(child, warnings));
            return f;
        }
        RequestItem r = new RequestItem(name);
        JsonNode req = n.get("request");
        if (req != null && req.isTextual()) {
            r.request.url = req.asText();
        } else if (req != null) {
            readRequest(req, r.request, warnings);
            if (r.description == null) r.description = description(req.get("description"));
        }
        if (n.hasNonNull("description")) r.description = description(n.get("description"));
        for (JsonNode ev : n.path("event")) {
            String script = script(ev);
            switch (ev.path("listen").asText()) {
                case "prerequest" -> r.preRequestScript = script;
                case "test" -> {
                    ChecksCodec.Decoded d = ChecksCodec.decode(script);
                    r.testScript = d.script();
                    r.checks.addAll(d.checks());
                    r.extractors.addAll(d.extractors());
                }
                default -> { }
            }
        }
        return r;
    }

    private static void readEvents(JsonNode events, Folder f) {
        if (events == null) return;
        for (JsonNode ev : events) {
            switch (ev.path("listen").asText()) {
                case "prerequest" -> f.preRequestScript = script(ev);
                case "test" -> f.testScript = script(ev);
                default -> { }
            }
        }
    }

    private static String script(JsonNode ev) {
        JsonNode exec = ev.path("script").path("exec");
        if (exec.isTextual()) return exec.asText();
        List<String> lines = new ArrayList<>();
        for (JsonNode l : exec) lines.add(l.asText());
        return String.join("\n", lines);
    }

    static void readRequest(JsonNode req, Request r, List<String> warnings) {
        r.method = req.path("method").asText("GET").toUpperCase();
        readUrl(req.get("url"), r);
        JsonNode headers = req.get("header");
        if (headers != null && headers.isArray()) {
            for (JsonNode h : headers) {
                KeyValue kv = new KeyValue(h.path("key").asText(""), text(h.get("value")));
                kv.enabled = !h.path("disabled").asBoolean(false);
                if (h.hasNonNull("description")) kv.description = description(h.get("description"));
                r.headers.add(kv);
            }
        } else if (headers != null && headers.isTextual()) {
            for (String line : headers.asText().split("\r?\n")) {
                int i = line.indexOf(':');
                if (i > 0) r.headers.add(new KeyValue(line.substring(0, i).trim(), line.substring(i + 1).trim()));
            }
        }
        readBody(req.get("body"), r.body);
        r.auth = auth(req.get("auth"), AuthType.INHERIT, warnings);
    }

    private static void readUrl(JsonNode url, Request r) {
        if (url == null || url.isNull()) return;
        if (url.isTextual()) {
            r.url = url.asText();
            return;
        }
        String raw = url.path("raw").asText(null);
        if (raw == null) raw = buildRaw(url);
        List<KeyValue> enabled = new ArrayList<>();
        for (JsonNode q : url.path("query")) {
            KeyValue kv = new KeyValue(q.path("key").asText(""), text(q.get("value")));
            kv.enabled = !q.path("disabled").asBoolean(false);
            if (kv.enabled) enabled.add(kv);
            else r.disabledParams.add(kv);
        }
        if (!raw.contains("?") && !enabled.isEmpty()) raw = UrlUtil.withQuery(raw, enabled);
        r.url = raw;
        for (JsonNode v : url.path("variable")) {
            KeyValue kv = new KeyValue(v.path("key").asText(""), text(v.get("value")));
            if (v.hasNonNull("description")) kv.description = description(v.get("description"));
            r.pathVariables.add(kv);
        }
    }

    private static String buildRaw(JsonNode url) {
        StringBuilder sb = new StringBuilder();
        if (url.hasNonNull("protocol")) sb.append(url.get("protocol").asText()).append("://");
        JsonNode host = url.get("host");
        if (host != null && host.isArray()) {
            List<String> parts = new ArrayList<>();
            host.forEach(h -> parts.add(h.asText()));
            sb.append(String.join(".", parts));
        } else if (host != null) {
            sb.append(host.asText());
        }
        if (url.hasNonNull("port")) sb.append(':').append(url.get("port").asText());
        JsonNode path = url.get("path");
        if (path != null && path.isArray()) {
            for (JsonNode p : path) sb.append('/').append(p.isObject() ? p.path("value").asText() : p.asText());
        } else if (path != null) {
            String p = path.asText();
            sb.append(p.startsWith("/") ? p : "/" + p);
        }
        return sb.toString();
    }

    private static void readBody(JsonNode b, Body body) {
        if (b == null || b.isNull() || b.path("disabled").asBoolean(false)) return;
        switch (b.path("mode").asText("")) {
            case "raw" -> {
                body.mode = BodyMode.RAW;
                body.raw = text(b.get("raw"));
                String lang = b.path("options").path("raw").path("language").asText("");
                body.rawLanguage = !lang.isEmpty() ? lang : (JsonUtil.tryParse(body.raw) != null ? "json" : "text");
            }
            case "urlencoded" -> {
                body.mode = BodyMode.URLENCODED;
                for (JsonNode f : b.path("urlencoded")) {
                    KeyValue kv = new KeyValue(f.path("key").asText(""), text(f.get("value")));
                    kv.enabled = !f.path("disabled").asBoolean(false);
                    body.urlencoded.add(kv);
                }
            }
            case "formdata" -> {
                body.mode = BodyMode.FORMDATA;
                for (JsonNode f : b.path("formdata")) {
                    KeyValue kv = new KeyValue(f.path("key").asText(""), "");
                    kv.enabled = !f.path("disabled").asBoolean(false);
                    if ("file".equals(f.path("type").asText())) {
                        kv.type = "file";
                        JsonNode src = f.get("src");
                        kv.value = src == null ? "" : src.isArray() ? (src.isEmpty() ? "" : src.get(0).asText()) : src.asText();
                    } else {
                        kv.value = text(f.get("value"));
                    }
                    body.formdata.add(kv);
                }
            }
            case "graphql" -> {
                body.mode = BodyMode.GRAPHQL;
                body.graphqlQuery = b.path("graphql").path("query").asText("");
                JsonNode v = b.path("graphql").path("variables");
                body.graphqlVariables = v.isTextual() ? v.asText() : (v.isMissingNode() || v.isNull() ? "" : JsonUtil.toPrettyJson(v));
            }
            case "file" -> {
                body.mode = BodyMode.BINARY;
                body.binaryFile = b.path("file").path("src").asText("");
            }
            default -> { }
        }
    }

    static Auth auth(JsonNode a, AuthType whenMissing, List<String> warnings) {
        if (a == null || a.isNull()) return new Auth(whenMissing);
        String type = a.path("type").asText("noauth");
        Auth auth = new Auth(AuthType.NONE);
        switch (type) {
            case "noauth" -> auth.type = AuthType.NONE;
            case "inherit" -> auth.type = AuthType.INHERIT;
            case "basic" -> {
                auth.type = AuthType.BASIC;
                auth.username = param(a, "basic", "username");
                auth.password = param(a, "basic", "password");
            }
            case "bearer" -> {
                auth.type = AuthType.BEARER;
                auth.token = param(a, "bearer", "token");
            }
            case "apikey" -> {
                auth.type = AuthType.APIKEY;
                auth.apiKey = param(a, "apikey", "key");
                auth.apiValue = param(a, "apikey", "value");
                String in = param(a, "apikey", "in");
                auth.apiIn = in.isEmpty() ? "header" : in;
            }
            case "oauth2" -> {
                String token = param(a, "oauth2", "accessToken");
                auth.type = AuthType.BEARER;
                auth.token = token.isEmpty() ? "{{accessToken}}" : token;
                warnings.add("OAuth 2.0 auth was converted to a Bearer token (" + auth.token + ")");
            }
            default -> warnings.add("Auth type '" + type + "' is not supported and was replaced by 'No Auth'");
        }
        return auth;
    }

    /** Auth parameter in either v2.1 form ([{key, value}]) or v2.0 form ({key: value}). */
    private static String param(JsonNode auth, String type, String key) {
        JsonNode p = auth.get(type);
        if (p == null) return "";
        if (p.isArray()) {
            for (JsonNode kv : p) if (key.equals(kv.path("key").asText())) return text(kv.get("value"));
            return "";
        }
        return text(p.get(key));
    }

    static String description(JsonNode d) {
        if (d == null || d.isNull()) return null;
        if (d.isTextual()) return d.asText();
        if (d.has("content")) return d.path("content").asText();
        return null;
    }

    static String text(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return "";
        return v.isValueNode() ? v.asText() : JsonUtil.toJson(v);
    }
}
