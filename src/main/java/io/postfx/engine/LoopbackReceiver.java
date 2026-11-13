package io.postfx.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Temporary HTTP server on the loopback interface that receives the OAuth 2.0 redirect
 * (authorization code in the query, or implicit token in the URL fragment, posted back by a small script).
 */
final class LoopbackReceiver implements AutoCloseable {
    private static final String DONE_PAGE = """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>PostFX</title>
            <style>body{font-family:sans-serif;margin:60px;color:#333}h1{color:#ff6c37}</style></head>
            <body><h1>PostFX</h1><p id="m">%s</p></body></html>""";
    private static final String FRAGMENT_PAGE = """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>PostFX</title>
            <style>body{font-family:sans-serif;margin:60px;color:#333}h1{color:#ff6c37}</style></head>
            <body><h1>PostFX</h1><p id="m">Completing sign-in…</p>
            <script>
              var h = window.location.hash ? window.location.hash.substring(1) : "";
              fetch(window.location.pathname + "?" + h + (h ? "&" : "") + "__fragment=1")
                .then(function () { document.getElementById("m").textContent = "Authorization complete. You can close this window and return to PostFX."; });
            </script></body></html>""";

    private final List<HttpServer> servers = new ArrayList<>();
    private final CompletableFuture<Map<String, String>> result = new CompletableFuture<>();

    static boolean isLoopback(URI redirect) {
        String host = redirect.getHost();
        return "http".equalsIgnoreCase(redirect.getScheme()) && host != null
                && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
    }

    LoopbackReceiver(URI redirect) throws IOException {
        int port = redirect.getPort() < 0 ? 80 : redirect.getPort();
        String path = redirect.getPath() == null || redirect.getPath().isEmpty() ? "/" : redirect.getPath();
        List<InetAddress> addresses = new ArrayList<>();
        String host = redirect.getHost();
        if (host.equalsIgnoreCase("localhost")) {
            // browsers may resolve localhost to IPv4 or IPv6: listen on both when possible
            addresses.add(InetAddress.getByName("127.0.0.1"));
            addresses.add(InetAddress.getByName("::1"));
        } else {
            addresses.add(InetAddress.getByName(host.replace("[", "").replace("]", "")));
        }
        IOException first = null;
        for (InetAddress a : addresses) {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress(a, port), 0);
                s.createContext(path, this::handle);
                s.setExecutor(Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "postfx-oauth-callback");
                    t.setDaemon(true);
                    return t;
                }));
                s.start();
                servers.add(s);
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        if (servers.isEmpty()) {
            throw new IOException("Cannot listen on " + redirect + " for the OAuth callback: "
                    + (first == null ? "unknown error" : first.getMessage()) + " (is the port already in use?)", first);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        String page;
        if (query == null || query.isEmpty()) {
            page = FRAGMENT_PAGE; // implicit flow: the token is in the fragment, the page posts it back
        } else {
            Map<String, String> params = parse(query);
            params.remove("__fragment");
            if (params.containsKey("error")) {
                page = DONE_PAGE.formatted("Authorization failed: " + escape(params.get("error"))
                        + (params.containsKey("error_description") ? " — " + escape(params.get("error_description")) : ""));
            } else {
                page = DONE_PAGE.formatted("Authorization complete. You can close this window and return to PostFX.");
            }
            if (!params.isEmpty()) result.complete(params);
        }
        byte[] b = page.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static Map<String, String> parse(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null) return out;
        for (String part : query.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = UrlUtil.decode(eq < 0 ? part : part.substring(0, eq));
            String v = eq < 0 ? "" : UrlUtil.decode(part.substring(eq + 1));
            out.put(k, v);
        }
        return out;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    Map<String, String> await(long timeoutSeconds) throws Exception {
        return result.get(timeoutSeconds, TimeUnit.SECONDS);
    }

    void cancel() {
        result.cancel(true);
    }

    @Override
    public void close() {
        for (HttpServer s : servers) s.stop(0);
    }
}
