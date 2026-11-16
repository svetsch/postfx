package io.postfx.io;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.postfx.engine.JsonUtil;
import io.postfx.engine.UrlUtil;
import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.Body;
import io.postfx.model.Collection;
import io.postfx.model.Environment;
import io.postfx.model.Folder;
import io.postfx.model.Item;
import io.postfx.model.KeyValue;
import io.postfx.model.Request;
import io.postfx.model.RequestItem;

import java.util.List;

/** Exports to Postman collection v2.1 / environment format. */
public final class PostmanExporter {
    public static final String SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    private PostmanExporter() {}

    public static ObjectNode exportCollection(Collection c) {
        ObjectNode root = JsonUtil.MAPPER.createObjectNode();
        ObjectNode info = root.putObject("info");
        info.put("_postman_id", c.id);
        info.put("name", c.name);
        if (c.description != null) info.put("description", c.description);
        info.put("schema", SCHEMA);
        ArrayNode items = root.putArray("item");
        for (Item i : c.items) items.add(item(i));
        events(root, c.preRequestScript, c.testScript);
        putAuth(root, c.auth);
        if (!c.variables.isEmpty()) {
            ArrayNode vars = root.putArray("variable");
            for (KeyValue kv : c.variables) {
                ObjectNode v = vars.addObject();
                v.put("key", kv.key);
                v.put("value", kv.value);
                v.put("type", "string");
                if (!kv.enabled) v.put("disabled", true);
            }
        }
        return root;
    }

    public static ObjectNode exportEnvironment(Environment e) {
        ObjectNode root = JsonUtil.MAPPER.createObjectNode();
        root.put("id", e.id);
        root.put("name", e.name);
        ArrayNode values = root.putArray("values");
        for (KeyValue kv : e.values) {
            ObjectNode v = values.addObject();
            v.put("key", kv.key);
            v.put("value", kv.value);
            v.put("type", "secret".equals(kv.type) ? "secret" : "default");
            v.put("enabled", kv.enabled);
        }
        root.put("_postman_variable_scope", "environment");
        root.put("_postman_exported_using", "PostFX/" + io.postfx.BuildInfo.version());
        return root;
    }

    public static ObjectNode exportGlobals(List<KeyValue> globals) {
        Environment e = new Environment("Globals");
        e.values.addAll(globals);
        ObjectNode n = exportEnvironment(e);
        n.put("_postman_variable_scope", "globals");
        return n;
    }

    private static ObjectNode item(Item i) {
        ObjectNode n = JsonUtil.MAPPER.createObjectNode();
        n.put("name", i.name);
        if (i instanceof Folder f) {
            if (f.description != null) n.put("description", f.description);
            ArrayNode items = n.putArray("item");
            for (Item child : f.items) items.add(item(child));
            events(n, f.preRequestScript, f.testScript);
            putAuth(n, f.auth);
            return n;
        }
        RequestItem r = (RequestItem) i;
        events(n, r.preRequestScript, ChecksCodec.testScriptForExport(r));
        n.set("request", request(r.request, r.description));
        n.putArray("response");
        return n;
    }

    static ObjectNode request(Request r, String description) {
        ObjectNode req = JsonUtil.MAPPER.createObjectNode();
        putAuth(req, r.auth);
        req.put("method", r.method);
        ArrayNode headers = req.putArray("header");
        for (KeyValue h : r.headers) {
            ObjectNode o = headers.addObject();
            o.put("key", h.key);
            o.put("value", h.value);
            o.put("type", "text");
            if (!h.enabled) o.put("disabled", true);
            if (h.description != null) o.put("description", h.description);
        }
        ObjectNode body = body(r.body);
        if (body != null) req.set("body", body);
        req.set("url", url(r));
        if (description != null) req.put("description", description);
        return req;
    }

    static ObjectNode url(Request r) {
        ObjectNode u = JsonUtil.MAPPER.createObjectNode();
        String raw = r.url == null ? "" : r.url;
        u.put("raw", raw);
        String base = UrlUtil.baseOf(raw);
        String rest = base;
        int scheme = base.indexOf("://");
        if (scheme > 0 && !base.substring(0, scheme).contains("{{")) {
            u.put("protocol", base.substring(0, scheme));
            rest = base.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        String hostPart = slash < 0 ? rest : rest.substring(0, slash);
        String pathPart = slash < 0 ? "" : rest.substring(slash + 1);
        int colon = hostPart.lastIndexOf(':');
        if (colon > 0 && hostPart.substring(colon + 1).matches("\\d+")) {
            u.put("port", hostPart.substring(colon + 1));
            hostPart = hostPart.substring(0, colon);
        }
        ArrayNode host = u.putArray("host");
        if (hostPart.contains("{{")) host.add(hostPart);
        else for (String h : hostPart.split("\\.")) host.add(h);
        ArrayNode path = u.putArray("path");
        if (!pathPart.isEmpty()) for (String p : pathPart.split("/", -1)) path.add(p);
        List<KeyValue> query = UrlUtil.parseQuery(raw);
        if (!query.isEmpty() || !r.disabledParams.isEmpty()) {
            ArrayNode q = u.putArray("query");
            for (KeyValue kv : query) {
                ObjectNode o = q.addObject();
                o.put("key", kv.key);
                o.put("value", kv.value);
            }
            for (KeyValue kv : r.disabledParams) {
                ObjectNode o = q.addObject();
                o.put("key", kv.key);
                o.put("value", kv.value);
                o.put("disabled", true);
            }
        }
        if (!r.pathVariables.isEmpty()) {
            ArrayNode vars = u.putArray("variable");
            for (KeyValue kv : r.pathVariables) {
                ObjectNode o = vars.addObject();
                o.put("key", kv.key);
                o.put("value", kv.value);
                if (kv.description != null) o.put("description", kv.description);
            }
        }
        return u;
    }

    private static ObjectNode body(Body b) {
        if (b == null) return null;
        ObjectNode n = JsonUtil.MAPPER.createObjectNode();
        switch (b.mode) {
            case RAW -> {
                n.put("mode", "raw");
                n.put("raw", b.raw);
                n.putObject("options").putObject("raw").put("language", b.rawLanguage == null ? "text" : b.rawLanguage);
            }
            case URLENCODED -> {
                n.put("mode", "urlencoded");
                ArrayNode a = n.putArray("urlencoded");
                for (KeyValue kv : b.urlencoded) {
                    ObjectNode o = a.addObject();
                    o.put("key", kv.key);
                    o.put("value", kv.value);
                    o.put("type", "text");
                    if (!kv.enabled) o.put("disabled", true);
                }
            }
            case FORMDATA -> {
                n.put("mode", "formdata");
                ArrayNode a = n.putArray("formdata");
                for (KeyValue kv : b.formdata) {
                    ObjectNode o = a.addObject();
                    o.put("key", kv.key);
                    if (kv.isFile()) {
                        o.put("type", "file");
                        o.put("src", kv.value);
                    } else {
                        o.put("value", kv.value);
                        o.put("type", "text");
                    }
                    if (!kv.enabled) o.put("disabled", true);
                }
            }
            case GRAPHQL -> {
                n.put("mode", "graphql");
                ObjectNode g = n.putObject("graphql");
                g.put("query", b.graphqlQuery);
                g.put("variables", b.graphqlVariables == null ? "" : b.graphqlVariables);
            }
            case BINARY -> {
                n.put("mode", "file");
                n.putObject("file").put("src", b.binaryFile);
            }
            default -> {
                return null;
            }
        }
        return n;
    }

    private static void events(ObjectNode n, String pre, String test) {
        ArrayNode events = JsonUtil.MAPPER.createArrayNode();
        addEvent(events, "prerequest", pre);
        addEvent(events, "test", test);
        if (!events.isEmpty()) n.set("event", events);
    }

    private static void addEvent(ArrayNode events, String listen, String script) {
        if (script == null || script.isBlank()) return;
        ObjectNode e = events.addObject();
        e.put("listen", listen);
        ObjectNode s = e.putObject("script");
        s.put("type", "text/javascript");
        ArrayNode exec = s.putArray("exec");
        for (String line : script.split("\r?\n", -1)) exec.add(line);
    }

    private static void putAuth(ObjectNode n, Auth a) {
        if (a == null || a.type == AuthType.INHERIT) return;
        ObjectNode o = n.putObject("auth");
        switch (a.type) {
            case NONE -> o.put("type", "noauth");
            case BASIC -> {
                o.put("type", "basic");
                ArrayNode p = o.putArray("basic");
                kv(p, "username", a.username);
                kv(p, "password", a.password);
            }
            case BEARER -> {
                o.put("type", "bearer");
                kv(o.putArray("bearer"), "token", a.token);
            }
            case APIKEY -> {
                o.put("type", "apikey");
                ArrayNode p = o.putArray("apikey");
                kv(p, "key", a.apiKey);
                kv(p, "value", a.apiValue);
                kv(p, "in", a.apiIn);
            }
            case OAUTH2, OIDC -> {
                // Postman has no OpenID Connect type: export the discovered endpoints as OAuth 2.0
                o.put("type", "oauth2");
                ArrayNode p = o.putArray("oauth2");
                io.postfx.model.OAuth2 c = a.oauth2;
                kv(p, "grant_type", c.grantType);
                kv(p, "accessToken", c.accessToken);
                kv(p, "tokenType", c.tokenType);
                kv(p, "addTokenTo", "query".equals(c.addTokenTo) ? "queryParams" : "header");
                kv(p, "headerPrefix", c.headerPrefix);
                kv(p, "authUrl", c.authUrl);
                kv(p, "accessTokenUrl", c.accessTokenUrl);
                if (c.refreshTokenUrl != null && !c.refreshTokenUrl.isBlank()) kv(p, "refreshTokenUrl", c.refreshTokenUrl);
                kv(p, "clientId", c.clientId);
                kv(p, "clientSecret", c.clientSecret);
                kv(p, "scope", c.scope);
                kv(p, "redirect_uri", c.redirectUri);
                kv(p, "useBrowser", "false");
                kv(p, "username", c.username);
                kv(p, "password", c.password);
                kv(p, "client_authentication", c.clientAuthentication);
                kv(p, "challengeAlgorithm", c.challengeAlgorithm);
                kv(p, "state", c.state);
                if (c.audience != null && !c.audience.isBlank()) {
                    for (String list : new String[]{"authRequestParams", "tokenRequestParams"}) {
                        ObjectNode e = p.addObject();
                        e.put("key", list);
                        ArrayNode arr = e.putArray("value");
                        ObjectNode aud = arr.addObject();
                        aud.put("key", "audience");
                        aud.put("value", c.audience);
                        aud.put("enabled", true);
                        aud.put("send_as", list.startsWith("auth") ? "request_url" : "request_body");
                        e.put("type", "any");
                    }
                }
            }
            default -> { }
        }
    }

    private static void kv(ArrayNode arr, String key, String value) {
        ObjectNode o = arr.addObject();
        o.put("key", key);
        o.put("value", value == null ? "" : value);
        o.put("type", "string");
    }
}
