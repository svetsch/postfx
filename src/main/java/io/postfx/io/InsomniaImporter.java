package io.postfx.io;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.BodyMode;
import io.postfx.model.Collection;
import io.postfx.model.Environment;
import io.postfx.model.Folder;
import io.postfx.model.KeyValue;
import io.postfx.model.RequestItem;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Imports Insomnia v4 exports (requests, folders and environments). */
public final class InsomniaImporter {
    private InsomniaImporter() {}

    public static boolean isInsomnia(JsonNode n) {
        return "export".equals(n.path("_type").asText()) && n.has("resources");
    }

    /** Insomnia templates use {{ _.name }}; PostFX uses {{name}}. */
    static String vars(String s) {
        return s == null ? "" : s.replaceAll("\\{\\{\\s*_\\.([\\w.-]+)\\s*}}", "{{$1}}");
    }

    public static ImportResult importExport(JsonNode n) {
        ImportResult result = new ImportResult();
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode r : n.path("resources")) byId.put(r.path("_id").asText(), r);

        Map<String, Collection> workspaces = new LinkedHashMap<>();
        Map<String, Folder> folders = new LinkedHashMap<>();
        for (JsonNode r : byId.values()) {
            String type = r.path("_type").asText();
            if (type.equals("workspace")) workspaces.put(r.path("_id").asText(), new Collection(r.path("name").asText("Insomnia")));
            if (type.equals("request_group")) {
                Folder f = new Folder(r.path("name").asText("Folder"));
                f.description = r.path("description").asText(null);
                folders.put(r.path("_id").asText(), f);
            }
        }
        Collection fallback = null;
        List<JsonNode> ordered = new ArrayList<>(byId.values());
        ordered.sort((a, b) -> Double.compare(a.path("metaSortKey").asDouble(0), b.path("metaSortKey").asDouble(0)));
        for (JsonNode r : ordered) {
            String type = r.path("_type").asText();
            String parent = r.path("parentId").asText("");
            Folder target = folders.containsKey(parent) ? folders.get(parent) : workspaces.get(parent);
            if (target == null && (type.equals("request") || type.equals("request_group"))) {
                if (fallback == null) fallback = new Collection("Insomnia import");
                target = fallback;
            }
            switch (type) {
                case "request_group" -> target.items.add(folders.get(r.path("_id").asText()));
                case "request" -> target.items.add(request(r));
                case "environment" -> {
                    Environment e = new Environment(r.path("name").asText("Insomnia environment"));
                    Iterator<Map.Entry<String, JsonNode>> it = r.path("data").fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> d = it.next();
                        e.values.add(new KeyValue(d.getKey(), d.getValue().isValueNode() ? d.getValue().asText() : d.getValue().toString()));
                    }
                    if (!e.values.isEmpty()) result.environments.add(e);
                }
                default -> { }
            }
        }
        result.collections.addAll(workspaces.values());
        if (fallback != null) result.collections.add(fallback);
        return result;
    }

    private static RequestItem request(JsonNode r) {
        RequestItem item = new RequestItem(r.path("name").asText("Request"));
        item.description = r.path("description").asText(null);
        item.request.method = r.path("method").asText("GET");
        item.request.url = vars(r.path("url").asText(""));
        List<KeyValue> params = new ArrayList<>();
        for (JsonNode p : r.path("parameters")) {
            KeyValue kv = new KeyValue(p.path("name").asText(), vars(p.path("value").asText()));
            kv.enabled = !p.path("disabled").asBoolean(false);
            if (kv.enabled) params.add(kv);
            else item.request.disabledParams.add(kv);
        }
        if (!params.isEmpty()) item.request.url = io.postfx.engine.UrlUtil.withQuery(item.request.url,
                concat(io.postfx.engine.UrlUtil.parseQuery(item.request.url), params));
        for (JsonNode h : r.path("headers")) {
            KeyValue kv = new KeyValue(h.path("name").asText(), vars(h.path("value").asText()));
            kv.enabled = !h.path("disabled").asBoolean(false);
            item.request.headers.add(kv);
        }
        JsonNode body = r.path("body");
        String mime = body.path("mimeType").asText("");
        if (body.has("text")) {
            item.request.body.mode = BodyMode.RAW;
            item.request.body.raw = vars(body.path("text").asText());
            item.request.body.rawLanguage = mime.contains("json") ? "json" : mime.contains("xml") ? "xml" : "text";
        } else if (body.has("params")) {
            boolean multipart = mime.contains("multipart");
            item.request.body.mode = multipart ? BodyMode.FORMDATA : BodyMode.URLENCODED;
            for (JsonNode p : body.path("params")) {
                KeyValue kv = new KeyValue(p.path("name").asText(), vars(p.path("value").asText()));
                if ("file".equals(p.path("type").asText())) {
                    kv.type = "file";
                    kv.value = p.path("fileName").asText();
                }
                kv.enabled = !p.path("disabled").asBoolean(false);
                (multipart ? item.request.body.formdata : item.request.body.urlencoded).add(kv);
            }
        }
        JsonNode auth = r.path("authentication");
        switch (auth.path("type").asText("")) {
            case "basic" -> {
                item.request.auth = new Auth(AuthType.BASIC);
                item.request.auth.username = vars(auth.path("username").asText());
                item.request.auth.password = vars(auth.path("password").asText());
            }
            case "bearer" -> {
                item.request.auth = new Auth(AuthType.BEARER);
                item.request.auth.token = vars(auth.path("token").asText());
            }
            case "apikey" -> {
                item.request.auth = new Auth(AuthType.APIKEY);
                item.request.auth.apiKey = vars(auth.path("key").asText());
                item.request.auth.apiValue = vars(auth.path("value").asText());
                item.request.auth.apiIn = "queryParams".equals(auth.path("addTo").asText()) ? "query" : "header";
            }
            default -> { }
        }
        return item;
    }

    private static List<KeyValue> concat(List<KeyValue> a, List<KeyValue> b) {
        List<KeyValue> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
