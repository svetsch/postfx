package io.postfx;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Tiny local API used by the tests: /login, /me, /echo, /items. */
public class TestServer implements AutoCloseable {
    private final HttpServer server;

    public TestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"secret\"")) {
                send(ex, 200, "{\"token\":\"abc123\",\"user\":{\"id\":7,\"name\":\"Ada\"}}");
            } else {
                send(ex, 401, "{\"error\":\"bad credentials\"}");
            }
        });
        server.createContext("/me", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if ("Bearer abc123".equals(auth)) send(ex, 200, "{\"id\":7,\"name\":\"Ada\",\"roles\":[\"admin\",\"dev\"]}");
            else send(ex, 401, "{\"error\":\"unauthorized\"}");
        });
        server.createContext("/echo", ex -> {
            StringBuilder sb = new StringBuilder("{\"method\":\"").append(ex.getRequestMethod()).append("\",\"query\":\"")
                    .append(ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery()).append("\",\"headers\":{");
            boolean first = true;
            for (var e : ex.getRequestHeaders().entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey().toLowerCase()).append("\":\"").append(e.getValue().get(0).replace("\"", "\\\"")).append('"');
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            sb.append("},\"body\":\"").append(body.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n")).append("\"}");
            send(ex, 200, sb.toString());
        });
        server.createContext("/items", ex -> send(ex, 200, "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]"));
        oauth();
        server.start();
    }

    // ------------------------------------------------------------------ minimal OAuth 2.0 server

    /** Tokens accepted by /secure. */
    public final java.util.Set<String> validTokens = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public volatile String lastChallenge;
    public volatile int tokenRequests;

    private void oauth() {
        server.createContext("/oauth/authorize", ex -> {
            var q = io.postfx.engine.UrlUtil.parseQuery(ex.getRequestURI().toString());
            java.util.Map<String, String> p = new java.util.HashMap<>();
            q.forEach(kv -> p.put(kv.key, io.postfx.engine.UrlUtil.decode(kv.value)));
            lastChallenge = p.get("code_challenge");
            String redirect = p.get("redirect_uri");
            String location = "token".equals(p.get("response_type"))
                    ? redirect + "#access_token=imp-token&token_type=bearer&expires_in=600&state=" + p.get("state")
                    : redirect + "?code=code-123&state=" + p.get("state");
            ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        validTokens.add("imp-token");
        server.createContext("/oauth/token", ex -> {
            tokenRequests++;
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            java.util.Map<String, String> f = new java.util.HashMap<>();
            for (String part : body.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) f.put(io.postfx.engine.UrlUtil.decode(part.substring(0, eq)), io.postfx.engine.UrlUtil.decode(part.substring(eq + 1)));
            }
            String basic = ex.getRequestHeaders().getFirst("Authorization");
            String client = basic != null && basic.startsWith("Basic ")
                    ? new String(java.util.Base64.getDecoder().decode(basic.substring(6)), StandardCharsets.UTF_8)
                    : f.get("client_id") + ":" + f.getOrDefault("client_secret", "");
            String grant = f.getOrDefault("grant_type", "");
            String token = null, refresh = null;
            int expires = 3600;
            switch (grant) {
                case "client_credentials" -> token = "app:s3cret".equals(client) ? "cc-token-" + f.getOrDefault("scope", "") : null;
                case "password" -> {
                    if ("ada".equals(f.get("username")) && "pw".equals(f.get("password"))) {
                        token = "pw-token";
                        refresh = "rt-1";
                        expires = 1; // immediately "expired" for the client (30 s margin)
                    }
                }
                case "refresh_token" -> {
                    if ("rt-1".equals(f.get("refresh_token"))) {
                        token = "refreshed-token";
                        refresh = "rt-2";
                    }
                }
                case "authorization_code" -> {
                    String verifier = f.get("code_verifier");
                    boolean pkceOk = lastChallenge == null || (verifier != null && lastChallenge.equals(io.postfx.engine.OAuth2Client.s256(verifier)));
                    if ("code-123".equals(f.get("code")) && pkceOk && client.startsWith("app")) token = "ac-token";
                }
                default -> { }
            }
            if (token == null) {
                send(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"bad credentials for " + grant + "\"}");
                return;
            }
            validTokens.add(token);
            send(ex, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expires
                    + (refresh == null ? "" : ",\"refresh_token\":\"" + refresh + "\"") + "}");
        });
        server.createContext("/secure", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String q = ex.getRequestURI().getRawQuery();
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7)
                    : q != null && q.startsWith("access_token=") ? q.substring("access_token=".length()) : null;
            if (token != null && validTokens.contains(token)) send(ex, 200, "{\"ok\":true,\"token\":\"" + token + "\"}");
            else send(ex, 401, "{\"error\":\"invalid_token\"}");
        });
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("X-Test", "yes");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
