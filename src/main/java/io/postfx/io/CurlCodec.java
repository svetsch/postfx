package io.postfx.io;

import io.postfx.engine.JsonUtil;
import io.postfx.engine.RequestPreparer;
import io.postfx.engine.UrlUtil;
import io.postfx.engine.VariableContext;
import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.BodyMode;
import io.postfx.model.KeyValue;
import io.postfx.model.Request;
import io.postfx.model.RequestItem;

import java.util.ArrayList;
import java.util.List;

/** Parses cURL command lines into requests and generates cURL commands from requests. */
public final class CurlCodec {
    private CurlCodec() {}

    public static boolean looksLikeCurl(String text) {
        return text != null && text.stripLeading().toLowerCase().startsWith("curl ");
    }

    /** Splits a shell command line (POSIX quotes, backslash and ^ line continuations). */
    static List<String> tokenize(String cmd) {
        String s = cmd.replaceAll("\\\\\r?\n", " ").replaceAll("\\^\r?\n", " ").replaceAll("`\r?\n", " ");
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (c == '\\' && quote == '"' && i + 1 < s.length() && "\"\\$`".indexOf(s.charAt(i + 1)) >= 0) cur.append(s.charAt(++i));
                else cur.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
            } else if (c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                // $'...' ANSI-C quoting: treat like single quotes with \n escapes
                int end = s.indexOf('\'', i + 2);
                if (end < 0) end = s.length();
                cur.append(s.substring(i + 2, end).replace("\\n", "\n").replace("\\t", "\t"));
                i = end;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else if (c == '\\' && i + 1 < s.length()) {
                cur.append(s.charAt(++i));
                inToken = true;
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) out.add(cur.toString());
        return out;
    }

    public static RequestItem parse(String command) {
        List<String> t = tokenize(command.strip());
        if (t.isEmpty() || !t.get(0).equalsIgnoreCase("curl")) throw new IllegalArgumentException("Not a cURL command");
        Request r = new Request();
        r.auth = new Auth(AuthType.NONE);
        String method = null;
        String url = null;
        List<String> data = new ArrayList<>();
        boolean urlencodeData = false;
        boolean get = false;
        for (int i = 1; i < t.size(); i++) {
            String a = t.get(i);
            String next = i + 1 < t.size() ? t.get(i + 1) : "";
            // support --opt=value
            if (a.startsWith("--") && a.contains("=")) {
                next = a.substring(a.indexOf('=') + 1);
                a = a.substring(0, a.indexOf('='));
                t.add(i + 1, next);
            }
            switch (a) {
                case "-X", "--request" -> { method = next.toUpperCase(); i++; }
                case "-H", "--header" -> {
                    int c = next.indexOf(':');
                    if (c > 0) r.headers.add(new KeyValue(next.substring(0, c).trim(), next.substring(c + 1).trim()));
                    i++;
                }
                case "-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--json" -> {
                    data.add(next);
                    if (a.equals("--json")) {
                        r.headers.add(new KeyValue("Content-Type", "application/json"));
                        r.headers.add(new KeyValue("Accept", "application/json"));
                    }
                    i++;
                }
                case "--data-urlencode" -> {
                    int eq = next.indexOf('=');
                    data.add(eq < 0 ? UrlUtil.formEncode(next) : next.substring(0, eq + 1) + UrlUtil.formEncode(next.substring(eq + 1)));
                    urlencodeData = true;
                    i++;
                }
                case "-F", "--form", "--form-string" -> {
                    int eq = next.indexOf('=');
                    KeyValue kv = eq < 0 ? new KeyValue(next, "") : new KeyValue(next.substring(0, eq), next.substring(eq + 1));
                    if (kv.value.startsWith("@") && !a.equals("--form-string")) {
                        kv.type = "file";
                        kv.value = kv.value.substring(1).replaceAll(";type=.*$", "");
                    }
                    r.body.mode = BodyMode.FORMDATA;
                    r.body.formdata.add(kv);
                    i++;
                }
                case "-u", "--user" -> {
                    int c = next.indexOf(':');
                    r.auth = new Auth(AuthType.BASIC);
                    r.auth.username = c < 0 ? next : next.substring(0, c);
                    r.auth.password = c < 0 ? "" : next.substring(c + 1);
                    i++;
                }
                case "-A", "--user-agent" -> { r.headers.add(new KeyValue("User-Agent", next)); i++; }
                case "-b", "--cookie" -> { r.headers.add(new KeyValue("Cookie", next)); i++; }
                case "-e", "--referer" -> { r.headers.add(new KeyValue("Referer", next)); i++; }
                case "--url" -> { url = next; i++; }
                case "-G", "--get" -> get = true;
                case "-I", "--head" -> method = "HEAD";
                case "-o", "--output", "-m", "--max-time", "--connect-timeout", "-w", "--write-out", "--retry", "-x", "--proxy",
                     "--cacert", "--cert", "--key", "-T", "--upload-file" -> i++;
                default -> {
                    if (!a.startsWith("-") && url == null) url = a;
                }
            }
        }
        if (url == null) throw new IllegalArgumentException("No URL found in the cURL command");
        r.url = url;
        if (!data.isEmpty()) {
            String joined = String.join("&", data);
            if (get) {
                r.url = r.url + (r.url.contains("?") ? "&" : "?") + joined;
            } else {
                String ct = header(r, "Content-Type");
                if (JsonUtil.tryParse(joined) != null && (ct == null || ct.contains("json"))) {
                    r.body.mode = BodyMode.RAW;
                    r.body.rawLanguage = "json";
                    r.body.raw = JsonUtil.prettyOrSame(joined);
                } else if (ct == null || ct.contains("x-www-form-urlencoded") || urlencodeData) {
                    r.body.mode = BodyMode.URLENCODED;
                    for (String pair : joined.split("&")) {
                        if (pair.isEmpty()) continue;
                        int eq = pair.indexOf('=');
                        r.body.urlencoded.add(eq < 0 ? new KeyValue(UrlUtil.decode(pair), "")
                                : new KeyValue(UrlUtil.decode(pair.substring(0, eq)), UrlUtil.decode(pair.substring(eq + 1))));
                    }
                    r.headers.removeIf(h -> h.key.equalsIgnoreCase("Content-Type") && h.value.contains("x-www-form-urlencoded"));
                } else {
                    r.body.mode = BodyMode.RAW;
                    r.body.rawLanguage = ct.contains("xml") ? "xml" : "text";
                    r.body.raw = joined;
                }
            }
        }
        if (r.body.mode == BodyMode.FORMDATA) {
            r.headers.removeIf(h -> h.key.equalsIgnoreCase("Content-Type") && h.value.startsWith("multipart/form-data"));
        }
        if (method == null) method = (!data.isEmpty() && !get) || r.body.mode == BodyMode.FORMDATA ? "POST" : "GET";
        r.method = method;
        RequestItem item = new RequestItem(defaultName(r));
        item.request = r;
        return item;
    }

    static String defaultName(Request r) {
        String base = UrlUtil.baseOf(r.url).replaceFirst("(?i)^[a-z]+://[^/]*", "");
        return r.method + " " + (base.isEmpty() ? "/" : base);
    }

    private static String header(Request r, String name) {
        for (KeyValue h : r.headers) if (h.enabled && h.key.equalsIgnoreCase(name)) return h.value;
        return null;
    }

    /** Generates a cURL command for the request, with variables resolved from the given context. */
    public static String toCurl(Request request, Auth effectiveAuth, VariableContext vars) {
        VariableContext v = vars == null ? new VariableContext() : vars;
        StringBuilder sb = new StringBuilder("curl --location");
        String method = v.resolve(request.method).toUpperCase();
        if (!method.equals("GET")) sb.append(" --request ").append(method);
        String url = v.resolve(RequestPreparer.applyPathVariables(request.url, request.pathVariables));
        if (effectiveAuth != null && effectiveAuth.type == AuthType.APIKEY && "query".equals(effectiveAuth.apiIn)) {
            url = UrlUtil.appendQueryParam(url, v.resolve(effectiveAuth.apiKey), v.resolve(effectiveAuth.apiValue));
        }
        sb.append(" ").append(q(url));
        for (KeyValue h : request.headers) {
            if (h.enabled && !h.key.isBlank()) sb.append(" \\\n  --header ").append(q(v.resolve(h.key) + ": " + v.resolve(h.value)));
        }
        if (effectiveAuth != null) {
            switch (effectiveAuth.type) {
                case BASIC -> sb.append(" \\\n  --user ").append(q(v.resolve(effectiveAuth.username) + ":" + v.resolve(effectiveAuth.password)));
                case BEARER -> sb.append(" \\\n  --header ").append(q("Authorization: Bearer " + v.resolve(effectiveAuth.token)));
                case OAUTH2, OIDC -> {
                    String token = v.resolve(effectiveAuth.oauth2.tokenForRequests());
                    if (token != null && !token.isBlank()) {
                        if ("query".equals(effectiveAuth.oauth2.addTokenTo)) {
                            sb.append(" \\\n  --url-query ").append(q(effectiveAuth.oauth2.queryParamName + "=" + token));
                        } else {
                            String prefix = effectiveAuth.oauth2.headerPrefix == null ? "" : effectiveAuth.oauth2.headerPrefix.strip();
                            sb.append(" \\\n  --header ").append(q("Authorization: " + (prefix.isEmpty() ? token : prefix + " " + token)));
                        }
                    }
                }
                case APIKEY -> {
                    if (!"query".equals(effectiveAuth.apiIn)) {
                        sb.append(" \\\n  --header ").append(q(v.resolve(effectiveAuth.apiKey) + ": " + v.resolve(effectiveAuth.apiValue)));
                    }
                }
                default -> { }
            }
        }
        switch (request.body.mode) {
            case RAW -> {
                if (header(request, "Content-Type") == null && request.body.raw != null && !request.body.raw.isEmpty()) {
                    sb.append(" \\\n  --header ").append(q("Content-Type: " + request.body.contentTypeForRaw()));
                }
                sb.append(" \\\n  --data-raw ").append(q(v.resolve(request.body.raw)));
            }
            case URLENCODED -> {
                for (KeyValue kv : request.body.urlencoded) {
                    if (kv.enabled && !kv.key.isBlank()) sb.append(" \\\n  --data-urlencode ").append(q(v.resolve(kv.key) + "=" + v.resolve(kv.value)));
                }
            }
            case FORMDATA -> {
                for (KeyValue kv : request.body.formdata) {
                    if (kv.enabled && !kv.key.isBlank()) {
                        sb.append(" \\\n  --form ").append(q(v.resolve(kv.key) + "=" + (kv.isFile() ? "@" : "") + v.resolve(kv.value)));
                    }
                }
            }
            case GRAPHQL -> {
                String body = "{\"query\":" + JsonUtil.toJson(v.resolve(request.body.graphqlQuery))
                        + (request.body.graphqlVariables == null || request.body.graphqlVariables.isBlank() ? "" : ",\"variables\":" + v.resolve(request.body.graphqlVariables))
                        + "}";
                sb.append(" \\\n  --header 'Content-Type: application/json'");
                sb.append(" \\\n  --data-raw ").append(q(body));
            }
            case BINARY -> sb.append(" \\\n  --data-binary ").append(q("@" + v.resolve(request.body.binaryFile)));
            default -> { }
        }
        return sb.toString();
    }

    private static String q(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
    }
}
