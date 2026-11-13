package io.postfx.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.postfx.engine.ConsoleEntry;
import io.postfx.engine.HttpResult;
import io.postfx.engine.JsonUtil;
import io.postfx.engine.RequestPreparer;
import io.postfx.engine.ResolvedRequest;
import io.postfx.engine.TestResult;
import io.postfx.model.AuthType;
import io.postfx.model.Body;
import io.postfx.model.BodyMode;
import io.postfx.model.KeyValue;
import io.postfx.model.Request;
import org.graalvm.polyglot.HostAccess;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Object exposed to the JavaScript sandbox as {@code __bridge}. Only methods annotated with
 * {@link HostAccess.Export} are callable from scripts.
 */
public class ScriptBridge {
    private static final HexFormat HEX = HexFormat.of();
    private final ScriptContext sc;
    private final ScriptResult result;

    public ScriptBridge(ScriptContext sc, ScriptResult result) {
        this.sc = sc;
        this.result = result;
    }

    // ---------------------------------------------------------------- general

    @HostAccess.Export
    public String event() {
        return sc.event;
    }

    @HostAccess.Export
    public String environmentName() {
        return sc.vars.environmentName;
    }

    @HostAccess.Export
    public String infoJson() {
        ObjectNode n = JsonUtil.MAPPER.createObjectNode();
        n.put("eventName", sc.event);
        n.put("iteration", sc.iteration);
        n.put("iterationCount", sc.iterationCount);
        n.put("requestName", sc.requestName);
        n.put("requestId", sc.requestId);
        return JsonUtil.toJson(n);
    }

    @HostAccess.Export
    public void log(String level, String message) {
        result.console.add(new ConsoleEntry(level, message, sc.scriptName));
    }

    @HostAccess.Export
    public void addTest(String name, boolean passed, String error, boolean skipped) {
        result.tests.add(new TestResult(name, passed, error, skipped, "script"));
    }

    @HostAccess.Export
    public void setNextRequest(String name) {
        result.nextRequestSet = true;
        result.nextRequest = name;
    }

    @HostAccess.Export
    public void skipRequest() {
        result.skipRequest = true;
    }

    // ---------------------------------------------------------------- variables

    private Map<String, String> scope(String name) {
        return sc.vars.scope(name);
    }

    @HostAccess.Export
    public String getVar(String scope, String key) {
        if ("all".equals(scope)) return sc.vars.get(key);
        return scope(scope).get(key);
    }

    @HostAccess.Export
    public boolean hasVar(String scope, String key) {
        if ("all".equals(scope)) return sc.vars.has(key);
        return scope(scope).containsKey(key);
    }

    @HostAccess.Export
    public void setVar(String scope, String key, String value) {
        scope(scope).put(key, value == null ? "" : value);
    }

    @HostAccess.Export
    public void unsetVar(String scope, String key) {
        scope(scope).remove(key);
    }

    @HostAccess.Export
    public void clearScope(String scope) {
        scope(scope).clear();
    }

    @HostAccess.Export
    public String scopeJson(String scope) {
        return JsonUtil.toJson("all".equals(scope) ? sc.vars.merged() : scope(scope));
    }

    @HostAccess.Export
    public String replaceIn(String text) {
        return sc.vars.resolve(text);
    }

    // ---------------------------------------------------------------- request / response

    @HostAccess.Export
    public String requestJson() {
        Request r = sc.request;
        ObjectNode n = JsonUtil.MAPPER.createObjectNode();
        n.put("id", sc.requestId);
        n.put("name", sc.requestName);
        n.put("method", r.method);
        n.put("url", r.url);
        n.set("headers", kvArray(r.headers));
        ObjectNode body = n.putObject("body");
        Body b = r.body;
        body.put("mode", switch (b.mode) {
            case RAW -> "raw";
            case URLENCODED -> "urlencoded";
            case FORMDATA -> "formdata";
            case GRAPHQL -> "graphql";
            case BINARY -> "file";
            case NONE -> "none";
        });
        body.put("raw", b.raw);
        body.set("urlencoded", kvArray(b.urlencoded));
        body.set("formdata", kvArray(b.formdata));
        ObjectNode gql = body.putObject("graphql");
        gql.put("query", b.graphqlQuery);
        gql.put("variables", b.graphqlVariables);
        body.putObject("options").putObject("raw").put("language", b.rawLanguage);
        ObjectNode auth = n.putObject("auth");
        auth.put("type", r.auth == null ? "inherit" : r.auth.type.name().toLowerCase());
        return JsonUtil.toJson(n);
    }

    private static ArrayNode kvArray(List<KeyValue> list) {
        ArrayNode a = JsonUtil.MAPPER.createArrayNode();
        for (KeyValue kv : list) {
            ObjectNode o = a.addObject();
            o.put("key", kv.key);
            o.put("value", kv.value);
            o.put("disabled", !kv.enabled);
            if (kv.type != null) o.put("type", kv.type);
        }
        return a;
    }

    private static List<KeyValue> kvList(JsonNode arr) {
        List<KeyValue> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode o : arr) {
            KeyValue kv = new KeyValue(o.path("key").asText(""), o.path("value").isNull() ? "" : o.path("value").asText(""));
            kv.enabled = !o.path("disabled").asBoolean(false);
            if (o.hasNonNull("type")) kv.type = o.get("type").asText();
            out.add(kv);
        }
        return out;
    }

    /** Called by the sandbox at the end of a pre-request script to apply changes to pm.request. */
    @HostAccess.Export
    public void updateRequest(String json) {
        JsonNode n = JsonUtil.tryParse(json);
        if (n == null) return;
        Request r = sc.request;
        if (n.hasNonNull("method")) r.method = n.get("method").asText();
        if (n.hasNonNull("url")) r.url = n.get("url").asText();
        if (n.has("headers")) r.headers = kvList(n.get("headers"));
        JsonNode b = n.get("body");
        if (b != null && b.isObject()) {
            String mode = b.path("mode").asText("none");
            switch (mode) {
                case "raw" -> {
                    r.body.mode = BodyMode.RAW;
                    r.body.raw = b.path("raw").isNull() ? "" : b.path("raw").asText("");
                }
                case "urlencoded" -> {
                    r.body.mode = BodyMode.URLENCODED;
                    r.body.urlencoded = kvList(b.get("urlencoded"));
                }
                case "formdata" -> {
                    r.body.mode = BodyMode.FORMDATA;
                    r.body.formdata = kvList(b.get("formdata"));
                }
                case "graphql" -> {
                    r.body.mode = BodyMode.GRAPHQL;
                    r.body.graphqlQuery = b.path("graphql").path("query").asText("");
                    JsonNode v = b.path("graphql").path("variables");
                    r.body.graphqlVariables = v.isTextual() ? v.asText() : (v.isMissingNode() || v.isNull() ? "" : JsonUtil.toJson(v));
                }
                case "file" -> r.body.mode = BodyMode.BINARY;
                default -> r.body.mode = BodyMode.NONE;
            }
        }
    }

    @HostAccess.Export
    public String responseJson() {
        HttpResult r = sc.response;
        if (r == null) return null;
        return JsonUtil.toJson(responseNode(r));
    }

    private static ObjectNode responseNode(HttpResult r) {
        ObjectNode n = JsonUtil.MAPPER.createObjectNode();
        n.put("code", r.status);
        n.put("status", r.statusText);
        n.set("headers", kvArray(r.headers));
        n.put("body", r.body);
        n.put("responseTime", r.timeMs);
        n.put("responseSize", r.size());
        return n;
    }

    /** pm.sendRequest: synchronous HTTP call from a script. */
    @HostAccess.Export
    public String sendRequest(String specJson) {
        ObjectNode out = JsonUtil.MAPPER.createObjectNode();
        if (sc.sender == null) {
            out.put("error", "pm.sendRequest is not available here");
            return JsonUtil.toJson(out);
        }
        JsonNode spec = JsonUtil.tryParse(specJson);
        Request req = new Request();
        req.method = spec.path("method").asText("GET");
        req.url = spec.path("url").asText("");
        req.headers = kvList(spec.get("headers"));
        req.auth.type = AuthType.NONE;
        JsonNode body = spec.get("body");
        if (body != null && body.isObject()) {
            switch (body.path("mode").asText("raw")) {
                case "urlencoded" -> {
                    req.body.mode = BodyMode.URLENCODED;
                    req.body.urlencoded = kvList(body.get("urlencoded"));
                }
                case "formdata" -> {
                    req.body.mode = BodyMode.FORMDATA;
                    req.body.formdata = kvList(body.get("formdata"));
                }
                default -> {
                    req.body.mode = BodyMode.RAW;
                    req.body.raw = body.path("raw").asText("");
                    req.body.rawLanguage = JsonUtil.tryParse(req.body.raw) != null ? "json" : "text";
                }
            }
        }
        ResolvedRequest resolved = RequestPreparer.prepare(req, req.auth, sc.vars);
        HttpResult res = sc.sender.apply(resolved);
        log("info", "pm.sendRequest " + resolved.method + " " + resolved.url + " -> "
                + (res.failed() ? res.error : res.status + " " + res.statusText));
        if (res.failed()) {
            out.put("error", res.error);
            return JsonUtil.toJson(out);
        }
        return JsonUtil.toJson(responseNode(res));
    }

    // ---------------------------------------------------------------- crypto & encoding helpers

    @HostAccess.Export
    public String uuid() {
        return UUID.randomUUID().toString();
    }

    @HostAccess.Export
    public String randomHex(int bytes) {
        byte[] b = new byte[Math.max(0, bytes)];
        new SecureRandom().nextBytes(b);
        return HEX.formatHex(b);
    }

    @HostAccess.Export
    public String hash(String algorithm, String hexData) {
        try {
            return HEX.formatHex(MessageDigest.getInstance(algorithm).digest(HEX.parseHex(hexData)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Hash failed: " + e.getMessage(), e);
        }
    }

    @HostAccess.Export
    public String hmac(String algorithm, String hexKey, String hexData) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            byte[] key = HEX.parseHex(hexKey);
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, algorithm));
            return HEX.formatHex(mac.doFinal(HEX.parseHex(hexData)));
        } catch (Exception e) {
            throw new IllegalArgumentException("HMAC failed: " + e.getMessage(), e);
        }
    }

    @HostAccess.Export
    public String utf8ToHex(String s) {
        return HEX.formatHex(s.getBytes(StandardCharsets.UTF_8));
    }

    @HostAccess.Export
    public String hexToUtf8(String hex) {
        return new String(HEX.parseHex(hex), StandardCharsets.UTF_8);
    }

    @HostAccess.Export
    public String latin1ToHex(String s) {
        return HEX.formatHex(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    @HostAccess.Export
    public String hexToLatin1(String hex) {
        return new String(HEX.parseHex(hex), StandardCharsets.ISO_8859_1);
    }

    @HostAccess.Export
    public String hexToBase64(String hex) {
        return Base64.getEncoder().encodeToString(HEX.parseHex(hex));
    }

    @HostAccess.Export
    public String base64ToHex(String b64) {
        return HEX.formatHex(Base64.getDecoder().decode(b64.trim()));
    }

    @HostAccess.Export
    public String btoa(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    @HostAccess.Export
    public String atob(String s) {
        return new String(Base64.getDecoder().decode(s.trim()), StandardCharsets.ISO_8859_1);
    }

    /** xml2Json: elements become objects, attributes go under "$", repeated elements become arrays. */
    @HostAccess.Export
    public String xmlToJson(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setNamespaceAware(false);
            Element root = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            ObjectNode out = JsonUtil.MAPPER.createObjectNode();
            out.set(root.getTagName(), elementToJson(root));
            return JsonUtil.toJson(out);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid XML: " + e.getMessage(), e);
        }
    }

    private static JsonNode elementToJson(Element e) {
        NamedNodeMap attrs = e.getAttributes();
        NodeList children = e.getChildNodes();
        boolean hasElements = false;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE) hasElements = true;
            else if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) text.append(c.getNodeValue());
        }
        if (!hasElements && attrs.getLength() == 0) {
            return JsonUtil.MAPPER.getNodeFactory().textNode(text.toString().trim());
        }
        ObjectNode o = JsonUtil.MAPPER.createObjectNode();
        if (attrs.getLength() > 0) {
            ObjectNode a = o.putObject("$");
            for (int i = 0; i < attrs.getLength(); i++) a.put(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
        }
        if (!text.toString().isBlank()) o.put("_", text.toString().trim());
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ce = (Element) c;
            JsonNode v = elementToJson(ce);
            JsonNode existing = o.get(ce.getTagName());
            if (existing == null) o.set(ce.getTagName(), v);
            else if (existing.isArray()) ((ArrayNode) existing).add(v);
            else {
                ArrayNode arr = JsonUtil.MAPPER.createArrayNode();
                arr.add(existing).add(v);
                o.set(ce.getTagName(), arr);
            }
        }
        return o;
    }
}
