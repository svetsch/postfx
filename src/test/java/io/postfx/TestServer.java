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
        server.start();
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
