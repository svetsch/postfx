package io.postfx.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.Body;
import io.postfx.model.Folder;
import io.postfx.model.KeyValue;
import io.postfx.model.Request;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Turns a model {@link Request} into a {@link ResolvedRequest}: variables, auth, body encoding. */
public final class RequestPreparer {
    public static final String USER_AGENT = "PostFX/" + io.postfx.BuildInfo.version();

    private RequestPreparer() {}

    /** Effective auth: the request's own auth unless it inherits, then the closest folder that defines one. */
    public static Auth effectiveAuth(Request request, List<Folder> ancestors) {
        if (request.auth != null && request.auth.type != AuthType.INHERIT) return request.auth;
        if (ancestors != null) {
            for (int i = ancestors.size() - 1; i >= 0; i--) {
                Auth a = ancestors.get(i).auth;
                if (a != null && a.type != AuthType.INHERIT) return a;
            }
        }
        return new Auth(AuthType.NONE);
    }

    public static ResolvedRequest prepare(Request request, Auth auth, VariableContext vars) {
        ResolvedRequest r = new ResolvedRequest();
        r.method = vars.resolve(request.method == null || request.method.isBlank() ? "GET" : request.method.trim()).toUpperCase();
        r.url = vars.resolve(applyPathVariables(request.url == null ? "" : request.url.trim(), request.pathVariables));
        if (!r.url.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) r.url = "http://" + r.url;

        for (KeyValue h : request.headers) {
            if (!h.enabled || h.key == null || h.key.isBlank()) continue;
            r.headers.add(new KeyValue(vars.resolve(h.key.trim()), vars.resolve(h.value)));
        }

        applyAuth(r, auth, vars);
        applyBody(r, request.body, vars);

        r.setHeaderIfAbsent("User-Agent", USER_AGENT);
        r.setHeaderIfAbsent("Accept", "*/*");
        r.setHeaderIfAbsent("Cache-Control", "no-cache");

        if (VariableResolver.hasPlaceholders(r.url)) {
            r.warnings.add("URL contains unresolved variables: " + r.url);
        }
        return r;
    }

    private static final java.util.regex.Pattern PATH_VAR = java.util.regex.Pattern.compile("(?<=/):([A-Za-z_][\\w.-]*)");

    /** Replaces {@code /:name} path segments with the matching path variable values. */
    public static String applyPathVariables(String url, List<KeyValue> pathVariables) {
        if (pathVariables == null || pathVariables.isEmpty() || url.indexOf("/:") < 0) return url;
        String base = UrlUtil.baseOf(url);
        String rest = url.substring(base.length());
        java.util.regex.Matcher m = PATH_VAR.matcher(base);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = null;
            for (KeyValue kv : pathVariables) {
                if (kv.enabled && m.group(1).equals(kv.key)) value = kv.value;
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb + rest;
    }

    /** Names of the {@code :name} path variables found in a URL. */
    public static List<String> pathVariableNames(String url) {
        List<String> out = new java.util.ArrayList<>();
        // skip the scheme and port ("http://host:8080")
        String base = UrlUtil.baseOf(url == null ? "" : url).replaceFirst("(?i)^[a-z][a-z0-9+.-]*://[^/]*", "");
        java.util.regex.Matcher m = PATH_VAR.matcher(base);
        while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    private static void applyAuth(ResolvedRequest r, Auth auth, VariableContext vars) {
        if (auth == null) return;
        switch (auth.type) {
            case BASIC -> {
                String cred = vars.resolve(auth.username) + ":" + vars.resolve(auth.password);
                r.setHeaderIfAbsent("Authorization", "Basic " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8)));
            }
            case BEARER -> r.setHeaderIfAbsent("Authorization", "Bearer " + vars.resolve(auth.token));
            case APIKEY -> {
                String k = vars.resolve(auth.apiKey);
                String v = vars.resolve(auth.apiValue);
                if (k == null || k.isBlank()) return;
                if ("query".equalsIgnoreCase(auth.apiIn)) r.url = UrlUtil.appendQueryParam(r.url, k, v);
                else r.setHeaderIfAbsent(k, v);
            }
            case OAUTH2, OIDC -> {
                var o = auth.oauth2;
                String token = o == null ? "" : vars.resolve(o.tokenForRequests());
                if (token == null || token.isBlank()) {
                    r.warnings.add(auth.type.label + ": no token (Auth tab › Get New Access Token)");
                    return;
                }
                if ("query".equalsIgnoreCase(o.addTokenTo)) {
                    String name = o.queryParamName == null || o.queryParamName.isBlank() ? "access_token" : o.queryParamName;
                    r.url = UrlUtil.appendQueryParam(r.url, name, token);
                } else {
                    String prefix = o.headerPrefix == null ? "" : vars.resolve(o.headerPrefix).strip();
                    r.setHeaderIfAbsent("Authorization", prefix.isEmpty() ? token : prefix + " " + token);
                }
            }
            default -> { }
        }
    }

    private static void applyBody(ResolvedRequest r, Body body, VariableContext vars) {
        if (body == null) return;
        switch (body.mode) {
            case RAW -> {
                String text = vars.resolve(body.raw == null ? "" : body.raw);
                r.body = text.getBytes(StandardCharsets.UTF_8);
                r.bodyPreview = text;
                if (!text.isEmpty()) r.setHeaderIfAbsent("Content-Type", body.contentTypeForRaw());
            }
            case URLENCODED -> {
                StringBuilder sb = new StringBuilder();
                for (KeyValue kv : body.urlencoded) {
                    if (!kv.enabled || kv.key == null || kv.key.isBlank()) continue;
                    if (sb.length() > 0) sb.append('&');
                    sb.append(UrlUtil.formEncode(vars.resolve(kv.key))).append('=').append(UrlUtil.formEncode(vars.resolve(kv.value)));
                }
                r.body = sb.toString().getBytes(StandardCharsets.UTF_8);
                r.bodyPreview = sb.toString();
                r.setHeaderIfAbsent("Content-Type", "application/x-www-form-urlencoded");
            }
            case FORMDATA -> multipart(r, body.formdata, vars);
            case GRAPHQL -> {
                ObjectNode node = JsonUtil.MAPPER.createObjectNode();
                node.put("query", vars.resolve(body.graphqlQuery == null ? "" : body.graphqlQuery));
                String varsText = vars.resolve(body.graphqlVariables);
                JsonNode parsed = JsonUtil.tryParse(varsText);
                if (parsed != null) node.set("variables", parsed);
                String json = JsonUtil.toJson(node);
                r.body = json.getBytes(StandardCharsets.UTF_8);
                r.bodyPreview = json;
                r.setHeaderIfAbsent("Content-Type", "application/json");
            }
            case BINARY -> {
                String file = vars.resolve(body.binaryFile);
                if (file == null || file.isBlank()) return;
                try {
                    r.body = Files.readAllBytes(Path.of(file));
                    r.bodyPreview = "<file " + file + ", " + r.body.length + " bytes>";
                    r.setHeaderIfAbsent("Content-Type", guessMime(file));
                } catch (IOException e) {
                    r.warnings.add("Could not read file " + file + ": " + e.getMessage());
                }
            }
            default -> { }
        }
    }

    private static void multipart(ResolvedRequest r, List<KeyValue> fields, VariableContext vars) {
        String boundary = "----PostFXBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder preview = new StringBuilder();
        for (KeyValue kv : fields) {
            if (!kv.enabled || kv.key == null || kv.key.isBlank()) continue;
            String name = vars.resolve(kv.key);
            write(out, "--" + boundary + "\r\n");
            if (kv.isFile()) {
                String file = vars.resolve(kv.value);
                Path p = Path.of(file == null ? "" : file);
                write(out, "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + p.getFileName() + "\"\r\n");
                write(out, "Content-Type: " + guessMime(file) + "\r\n\r\n");
                try {
                    byte[] bytes = Files.readAllBytes(p);
                    out.writeBytes(bytes);
                    preview.append(name).append(" = <file ").append(file).append(", ").append(bytes.length).append(" bytes>\n");
                } catch (IOException e) {
                    r.warnings.add("Could not read file " + file + ": " + e.getMessage());
                }
            } else {
                String value = vars.resolve(kv.value);
                write(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
                write(out, value == null ? "" : value);
                preview.append(name).append(" = ").append(value).append('\n');
            }
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");
        r.body = out.toByteArray();
        r.bodyPreview = preview.toString();
        r.setHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    static String guessMime(String file) {
        try {
            String t = Files.probeContentType(Path.of(file));
            if (t != null) return t;
        } catch (Exception ignored) {
            // ignore
        }
        return "application/octet-stream";
    }
}
