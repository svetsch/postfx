package io.postfx.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.postfx.engine.JsonUtil;
import io.postfx.engine.UrlUtil;
import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.BodyMode;
import io.postfx.model.Check;
import io.postfx.model.CheckOperator;
import io.postfx.model.CheckSource;
import io.postfx.model.Collection;
import io.postfx.model.Folder;
import io.postfx.model.KeyValue;
import io.postfx.model.RequestItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports OpenAPI 3.x and Swagger 2.0 specifications (JSON or YAML).
 * One request per operation, grouped by tag, with example bodies generated from schemas, a status code check for
 * the documented success response and (optionally) a JSON schema test.
 */
public final class OpenApiImporter {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete", "head", "options", "trace");

    private final JsonNode root;
    private final boolean swagger2;
    private final List<String> warnings;
    private final boolean schemaTests;

    private OpenApiImporter(JsonNode root, List<String> warnings, boolean schemaTests) {
        this.root = root;
        this.swagger2 = root.has("swagger");
        this.warnings = warnings;
        this.schemaTests = schemaTests;
    }

    public static boolean isOpenApi(JsonNode n) {
        return n.isObject() && (n.has("openapi") || n.has("swagger")) && n.has("paths");
    }

    public static Collection importSpec(JsonNode spec, List<String> warnings) {
        return new OpenApiImporter(spec, warnings, true).run();
    }

    public static Collection importSpec(JsonNode spec, List<String> warnings, boolean schemaTests) {
        return new OpenApiImporter(spec, warnings, schemaTests).run();
    }

    private Collection run() {
        JsonNode info = root.path("info");
        Collection c = new Collection(info.path("title").asText("OpenAPI import"));
        String version = info.path("version").asText("");
        StringBuilder desc = new StringBuilder();
        if (!version.isEmpty()) desc.append("Version ").append(version).append("\n\n");
        desc.append(info.path("description").asText(""));
        c.description = desc.toString().strip();
        c.variables.add(new KeyValue("baseUrl", baseUrl()));

        // security
        Map<String, Auth> schemes = securitySchemes(c);
        Auth global = authFor(root.get("security"), schemes);
        if (global != null) c.auth = global;

        Map<String, Folder> folders = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> paths = root.path("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> p = paths.next();
            JsonNode pathItem = deref(p.getValue());
            for (String m : METHODS) {
                JsonNode op = pathItem.get(m);
                if (op == null) continue;
                RequestItem r = operation(p.getKey(), m, op, pathItem.path("parameters"), schemes);
                String tag = op.path("tags").path(0).asText(null);
                if (tag == null) c.items.add(r);
                else folders.computeIfAbsent(tag, t -> {
                    Folder f = new Folder(t);
                    for (JsonNode tn : root.path("tags")) {
                        if (t.equals(tn.path("name").asText())) f.description = tn.path("description").asText(null);
                    }
                    return f;
                }).items.add(r);
            }
        }
        c.items.addAll(0, folders.values());
        return c;
    }

    private String baseUrl() {
        if (swagger2) {
            String scheme = root.path("schemes").path(0).asText("https");
            String host = root.path("host").asText("localhost");
            String basePath = root.path("basePath").asText("");
            return scheme + "://" + host + (basePath.equals("/") ? "" : basePath);
        }
        JsonNode server = root.path("servers").path(0);
        String url = server.path("url").asText("/");
        Iterator<Map.Entry<String, JsonNode>> vars = server.path("variables").fields();
        while (vars.hasNext()) {
            Map.Entry<String, JsonNode> v = vars.next();
            url = url.replace("{" + v.getKey() + "}", v.getValue().path("default").asText(""));
        }
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.startsWith("/") || url.isEmpty()) {
            warnings.add("The specification uses a relative server URL; set the 'baseUrl' collection variable to the API host");
            url = "http://localhost" + url;
        }
        return url;
    }

    private Map<String, Auth> securitySchemes(Collection c) {
        Map<String, Auth> out = new LinkedHashMap<>();
        JsonNode defs = swagger2 ? root.path("securityDefinitions") : root.path("components").path("securitySchemes");
        Iterator<Map.Entry<String, JsonNode>> it = defs.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode s = deref(e.getValue());
            String type = s.path("type").asText();
            Auth a = null;
            switch (type) {
                case "http" -> {
                    String scheme = s.path("scheme").asText().toLowerCase();
                    if (scheme.equals("basic")) a = basic(c);
                    else if (scheme.equals("bearer")) a = bearer(c, "bearerToken");
                }
                case "basic" -> a = basic(c);
                case "apiKey" -> {
                    a = new Auth(AuthType.APIKEY);
                    a.apiKey = s.path("name").asText("X-API-Key");
                    a.apiIn = "query".equals(s.path("in").asText()) ? "query" : "header";
                    a.apiValue = "{{apiKey}}";
                    addVar(c, "apiKey");
                    if ("cookie".equals(s.path("in").asText())) warnings.add("API key in cookie (" + e.getKey() + ") was mapped to a header");
                }
                case "oauth2", "openIdConnect" -> a = bearer(c, "accessToken");
                default -> warnings.add("Security scheme '" + e.getKey() + "' of type " + type + " is not supported");
            }
            if (a != null) out.put(e.getKey(), a);
        }
        return out;
    }

    private static Auth basic(Collection c) {
        Auth a = new Auth(AuthType.BASIC);
        a.username = "{{username}}";
        a.password = "{{password}}";
        addVar(c, "username");
        addVar(c, "password");
        return a;
    }

    private static Auth bearer(Collection c, String var) {
        Auth a = new Auth(AuthType.BEARER);
        a.token = "{{" + var + "}}";
        addVar(c, var);
        return a;
    }

    private static void addVar(Collection c, String name) {
        if (c.variables.stream().noneMatch(v -> v.key.equals(name))) c.variables.add(new KeyValue(name, ""));
    }

    /** Auth of a "security" requirement list; null when not specified. */
    private static Auth authFor(JsonNode security, Map<String, Auth> schemes) {
        if (security == null || !security.isArray()) return null;
        if (security.isEmpty()) return new Auth(AuthType.NONE);
        for (JsonNode req : security) {
            if (req.isEmpty()) return new Auth(AuthType.NONE);
            Iterator<String> names = req.fieldNames();
            while (names.hasNext()) {
                Auth a = schemes.get(names.next());
                if (a != null) return JsonUtil.copy(a);
            }
        }
        return null;
    }

    private RequestItem operation(String path, String method, JsonNode op, JsonNode pathParams, Map<String, Auth> schemes) {
        String name = op.path("summary").asText("");
        if (name.isBlank()) name = op.path("operationId").asText("");
        if (name.isBlank()) name = method.toUpperCase() + " " + path;
        RequestItem r = new RequestItem(name);
        r.request.method = method.toUpperCase();
        String desc = op.path("description").asText("");
        if (op.path("deprecated").asBoolean(false)) desc = "DEPRECATED\n\n" + desc;
        if (!desc.isBlank()) r.description = desc.strip();

        String url = "{{baseUrl}}" + path.replaceAll("\\{([^}/]+)}", ":$1");
        List<KeyValue> query = new ArrayList<>();
        List<JsonNode> params = new ArrayList<>();
        pathParams.forEach(params::add);
        op.path("parameters").forEach(params::add);
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        for (JsonNode p : params) {
            JsonNode d = deref(p);
            byKey.put(d.path("in").asText() + ":" + d.path("name").asText(), d); // operation level overrides path level
        }
        JsonNode bodyParam = null;
        List<JsonNode> formParams = new ArrayList<>();
        for (JsonNode p : byKey.values()) {
            String in = p.path("in").asText();
            String pname = p.path("name").asText();
            String value = paramExample(p);
            boolean required = p.path("required").asBoolean(false);
            KeyValue kv = new KeyValue(pname, value);
            kv.description = p.path("description").asText(null);
            switch (in) {
                case "path" -> r.request.pathVariables.add(kv);
                case "query" -> {
                    if (required) query.add(kv);
                    else {
                        kv.enabled = false;
                        r.request.disabledParams.add(kv);
                    }
                }
                case "header" -> {
                    kv.enabled = required;
                    r.request.headers.add(kv);
                }
                case "body" -> bodyParam = p;
                case "formData" -> formParams.add(p);
                default -> { }
            }
        }
        r.request.url = UrlUtil.withQuery(url, query);

        // ---- body
        if (swagger2) {
            List<String> consumes = list(op.has("consumes") ? op.get("consumes") : root.get("consumes"));
            if (bodyParam != null) {
                String ct = consumes.isEmpty() ? "application/json" : consumes.get(0);
                setBody(r, ct, bodyParam.get("schema"), null);
            } else if (!formParams.isEmpty()) {
                boolean multipart = consumes.contains("multipart/form-data") || formParams.stream().anyMatch(p -> "file".equals(p.path("type").asText()));
                for (JsonNode p : formParams) {
                    KeyValue kv = new KeyValue(p.path("name").asText(), "file".equals(p.path("type").asText()) ? "" : paramExample(p));
                    if ("file".equals(p.path("type").asText())) kv.type = "file";
                    kv.enabled = p.path("required").asBoolean(true);
                    if (multipart) r.request.body.formdata.add(kv);
                    else r.request.body.urlencoded.add(kv);
                }
                r.request.body.mode = multipart ? BodyMode.FORMDATA : BodyMode.URLENCODED;
            }
        } else {
            JsonNode rb = deref(op.path("requestBody"));
            JsonNode content = rb.path("content");
            Iterator<String> types = content.fieldNames();
            String chosen = null;
            List<String> all = new ArrayList<>();
            types.forEachRemaining(all::add);
            for (String t : all) if (t.contains("json")) { chosen = t; break; }
            if (chosen == null && !all.isEmpty()) chosen = all.get(0);
            if (chosen != null) {
                JsonNode media = content.get(chosen);
                setBody(r, chosen, media.get("schema"), mediaExample(media));
            }
        }

        // ---- accept header & success response
        String successCode = null;
        JsonNode successResponse = null;
        Iterator<Map.Entry<String, JsonNode>> responses = op.path("responses").fields();
        while (responses.hasNext()) {
            Map.Entry<String, JsonNode> e = responses.next();
            if (e.getKey().matches("2\\d\\d")) {
                successCode = e.getKey();
                successResponse = deref(e.getValue());
                break;
            }
        }
        if (successCode != null) {
            r.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, successCode));
            JsonNode schema = null;
            String accept = null;
            if (swagger2) {
                schema = successResponse.get("schema");
                List<String> produces = list(op.has("produces") ? op.get("produces") : root.get("produces"));
                if (!produces.isEmpty()) accept = produces.get(0);
            } else {
                Iterator<Map.Entry<String, JsonNode>> it = successResponse.path("content").fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (accept == null || e.getKey().contains("json")) {
                        accept = e.getKey();
                        schema = e.getValue().get("schema");
                    }
                }
            }
            if (accept != null && r.request.headers.stream().noneMatch(h -> h.key.equalsIgnoreCase("Accept"))) {
                r.request.headers.add(new KeyValue("Accept", accept));
            }
            if (schemaTests && schema != null && accept != null && accept.contains("json")) {
                JsonNode resolved = jsonSchema(schema, 0, new HashSet<>());
                r.testScript = "// Generated from the OpenAPI response schema (" + successCode + ")\n"
                        + "const schema = " + JsonUtil.toPrettyJson(resolved) + ";\n\n"
                        + "pm.test(\"Response matches the documented schema\", function () {\n"
                        + "    pm.response.to.have.jsonSchema(schema);\n"
                        + "});";
            }
        }

        // ---- auth
        Auth opAuth = authFor(op.get("security"), schemes);
        if (opAuth != null) r.request.auth = opAuth;
        return r;
    }

    private void setBody(RequestItem r, String contentType, JsonNode schema, JsonNode example) {
        String ct = contentType.toLowerCase();
        if (ct.contains("x-www-form-urlencoded") || ct.contains("multipart/form-data")) {
            boolean multipart = ct.contains("multipart");
            JsonNode s = resolveAllOf(deref(schema == null ? F.objectNode() : schema));
            Iterator<Map.Entry<String, JsonNode>> props = s.path("properties").fields();
            while (props.hasNext()) {
                Map.Entry<String, JsonNode> p = props.next();
                JsonNode ps = deref(p.getValue());
                KeyValue kv = new KeyValue(p.getKey(), "");
                if ("binary".equals(ps.path("format").asText())) kv.type = "file";
                else kv.value = exampleText(example(ps, 0, new HashSet<>()));
                if (multipart) r.request.body.formdata.add(kv);
                else r.request.body.urlencoded.add(kv);
            }
            r.request.body.mode = multipart ? BodyMode.FORMDATA : BodyMode.URLENCODED;
            return;
        }
        r.request.body.mode = BodyMode.RAW;
        JsonNode ex = example != null ? example : (schema != null ? example(schema, 0, new HashSet<>()) : null);
        if (ct.contains("json")) {
            r.request.body.rawLanguage = "json";
            r.request.body.raw = ex == null ? "{}" : JsonUtil.toPrettyJson(ex);
        } else if (ct.contains("xml")) {
            r.request.body.rawLanguage = "xml";
            r.request.body.raw = ex != null && ex.isTextual() ? ex.asText() : "<root/>";
        } else {
            r.request.body.rawLanguage = "text";
            r.request.body.raw = ex == null ? "" : exampleText(ex);
        }
        if (r.request.headers.stream().noneMatch(h -> h.key.equalsIgnoreCase("Content-Type"))) {
            r.request.headers.add(new KeyValue("Content-Type", contentType));
        }
    }

    private static String exampleText(JsonNode n) {
        if (n == null || n.isNull()) return "";
        return n.isValueNode() ? n.asText() : JsonUtil.toJson(n);
    }

    private JsonNode mediaExample(JsonNode media) {
        if (media.has("example")) return media.get("example");
        JsonNode examples = media.path("examples");
        if (examples.isObject() && examples.size() > 0) {
            JsonNode first = deref(examples.elements().next());
            if (first.has("value")) return first.get("value");
        }
        return null;
    }

    private String paramExample(JsonNode p) {
        if (p.has("example")) return exampleText(p.get("example"));
        JsonNode examples = p.path("examples");
        if (examples.isObject() && examples.size() > 0) {
            JsonNode first = deref(examples.elements().next());
            if (first.has("value")) return exampleText(first.get("value"));
        }
        JsonNode schema = p.has("schema") ? p.get("schema") : p; // swagger 2 has type on the parameter itself
        JsonNode s = deref(schema);
        if (s.has("example")) return exampleText(s.get("example"));
        if (s.has("default")) return exampleText(s.get("default"));
        if (s.has("enum") && s.get("enum").size() > 0) return exampleText(s.get("enum").get(0));
        String type = s.path("type").asText("string");
        return "<" + type + ">";
    }

    // ------------------------------------------------------------------ schema helpers

    private JsonNode deref(JsonNode n) {
        int guard = 0;
        while (n != null && n.has("$ref") && guard++ < 20) {
            String ref = n.get("$ref").asText();
            if (!ref.startsWith("#/")) {
                warnings.add("External reference not supported: " + ref);
                return F.objectNode();
            }
            JsonNode target = root.at(ref.substring(1).replace("~1", "/"));
            if (target.isMissingNode()) {
                warnings.add("Unresolved reference: " + ref);
                return F.objectNode();
            }
            n = target;
        }
        return n == null ? F.missingNode() : n;
    }

    private JsonNode resolveAllOf(JsonNode s) {
        if (!s.has("allOf")) return s;
        ObjectNode merged = F.objectNode();
        ObjectNode props = merged.putObject("properties");
        ArrayNode required = merged.putArray("required");
        for (JsonNode part : s.get("allOf")) {
            JsonNode p = resolveAllOf(deref(part));
            if (p.has("properties")) props.setAll((ObjectNode) p.get("properties"));
            p.path("required").forEach(required::add);
        }
        merged.put("type", "object");
        return merged;
    }

    /** Builds an example value from a schema. */
    JsonNode example(JsonNode schemaRef, int depth, Set<String> seen) {
        if (schemaRef == null || depth > 8) return F.nullNode();
        String ref = schemaRef.has("$ref") ? schemaRef.get("$ref").asText() : null;
        if (ref != null) {
            if (seen.contains(ref)) return F.objectNode();
            seen = new HashSet<>(seen);
            seen.add(ref);
        }
        JsonNode s = deref(schemaRef);
        if (s.has("example")) return s.get("example");
        if (s.has("default")) return s.get("default");
        if (s.has("const")) return s.get("const");
        if (s.has("enum") && s.get("enum").size() > 0) return s.get("enum").get(0);
        if (s.has("allOf")) {
            ObjectNode merged = F.objectNode();
            for (JsonNode part : s.get("allOf")) {
                JsonNode v = example(part, depth + 1, seen);
                if (v.isObject()) merged.setAll((ObjectNode) v);
            }
            return merged;
        }
        if (s.has("oneOf")) return example(s.get("oneOf").get(0), depth + 1, seen);
        if (s.has("anyOf")) return example(s.get("anyOf").get(0), depth + 1, seen);
        String type = s.path("type").isArray() ? s.path("type").path(0).asText() : s.path("type").asText(s.has("properties") ? "object" : "");
        switch (type) {
            case "object" -> {
                ObjectNode o = F.objectNode();
                Iterator<Map.Entry<String, JsonNode>> props = s.path("properties").fields();
                while (props.hasNext()) {
                    Map.Entry<String, JsonNode> p = props.next();
                    if (deref(p.getValue()).path("readOnly").asBoolean(false)) continue;
                    o.set(p.getKey(), example(p.getValue(), depth + 1, seen));
                }
                if (o.isEmpty() && s.path("additionalProperties").isObject()) {
                    o.set("key", example(s.get("additionalProperties"), depth + 1, seen));
                }
                return o;
            }
            case "array" -> {
                ArrayNode a = F.arrayNode();
                a.add(example(s.get("items"), depth + 1, seen));
                return a;
            }
            case "integer" -> {
                return F.numberNode(s.has("minimum") ? s.get("minimum").asLong() : 0);
            }
            case "number" -> {
                return F.numberNode(s.has("minimum") ? s.get("minimum").asDouble() : 0.0);
            }
            case "boolean" -> {
                return F.booleanNode(true);
            }
            case "string" -> {
                return F.textNode(switch (s.path("format").asText("")) {
                    case "date-time" -> "2024-01-01T12:00:00Z";
                    case "date" -> "2024-01-01";
                    case "time" -> "12:00:00";
                    case "email" -> "user@example.com";
                    case "uuid" -> "3fa85f64-5717-4562-b3fc-2c963f66afa6";
                    case "uri", "url" -> "https://example.com";
                    case "hostname" -> "example.com";
                    case "ipv4" -> "192.168.0.1";
                    case "password" -> "secret";
                    case "byte" -> "U3dhZ2dlciByb2Nrcw==";
                    default -> "string";
                });
            }
            default -> {
                return F.nullNode();
            }
        }
    }

    /** Self-contained JSON schema (refs inlined, OpenAPI specifics converted) for pm.response.to.have.jsonSchema. */
    JsonNode jsonSchema(JsonNode schemaRef, int depth, Set<String> seen) {
        if (schemaRef == null || depth > 10) return F.objectNode();
        String ref = schemaRef.has("$ref") ? schemaRef.get("$ref").asText() : null;
        if (ref != null) {
            if (seen.contains(ref)) return F.objectNode(); // recursive structure: accept anything
            seen = new HashSet<>(seen);
            seen.add(ref);
        }
        JsonNode s = deref(schemaRef);
        ObjectNode out = F.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = s.fields();
        boolean nullable = s.path("nullable").asBoolean(false) || s.path("x-nullable").asBoolean(false);
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            String k = f.getKey();
            JsonNode v = f.getValue();
            switch (k) {
                case "type", "required", "enum", "minimum", "maximum", "minLength", "maxLength", "pattern", "minItems", "maxItems", "const" -> out.set(k, v);
                case "items" -> out.set(k, jsonSchema(v, depth + 1, seen));
                case "properties" -> {
                    ObjectNode props = out.putObject("properties");
                    Iterator<Map.Entry<String, JsonNode>> it = v.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> p = it.next();
                        props.set(p.getKey(), jsonSchema(p.getValue(), depth + 1, seen));
                    }
                }
                case "allOf", "anyOf", "oneOf" -> {
                    ArrayNode arr = out.putArray(k.equals("oneOf") ? "anyOf" : k);
                    for (JsonNode part : v) arr.add(jsonSchema(part, depth + 1, seen));
                }
                default -> { }
            }
        }
        if (nullable && out.has("type")) {
            ArrayNode types = F.arrayNode();
            if (out.get("type").isArray()) out.get("type").forEach(types::add);
            else types.add(out.get("type"));
            types.add("null");
            out.set("type", types);
            out.remove("enum");
        }
        return out;
    }

    private static List<String> list(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null) n.forEach(x -> out.add(x.asText()));
        return out;
    }
}
