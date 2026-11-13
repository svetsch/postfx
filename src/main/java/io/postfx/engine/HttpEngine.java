package io.postfx.engine;

import io.postfx.model.KeyValue;
import io.postfx.model.Settings;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sends {@link ResolvedRequest}s with java.net.http. */
public class HttpEngine {
    /** Headers the JDK client refuses to set; they are silently managed by the client. */
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "upgrade");
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([\\w.:-]+)", Pattern.CASE_INSENSITIVE);

    static {
        // Allow overriding the Host header like Postman does.
        if (System.getProperty("jdk.httpclient.allowRestrictedHeaders") == null) {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        }
    }

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    public CookieManager cookies() {
        return cookies;
    }

    private HttpClient client(Settings s) {
        String key = s.followRedirects + "|" + s.verifySsl;
        return clients.computeIfAbsent(key, k -> {
            HttpClient.Builder b = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofMillis(Math.max(1000, s.timeoutMs)))
                    .cookieHandler(cookies)
                    .followRedirects(s.followRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);
            if (!s.verifySsl) {
                b.sslContext(trustAll());
                SSLParameters p = new SSLParameters();
                p.setEndpointIdentificationAlgorithm(null);
                b.sslParameters(p);
            }
            return b.build();
        });
    }

    public HttpResult send(ResolvedRequest r, Settings s) {
        try {
            return sendAsync(r, s).join();
        } catch (CancellationException e) {
            HttpResult res = new HttpResult();
            res.error = "Request cancelled";
            return res;
        }
    }

    public CompletableFuture<HttpResult> sendAsync(ResolvedRequest r, Settings s) {
        HttpRequest req;
        try {
            req = build(r, s);
        } catch (Exception e) {
            HttpResult res = new HttpResult();
            res.error = "Invalid request: " + e.getMessage();
            return CompletableFuture.completedFuture(res);
        }
        long start = System.nanoTime();
        return client(s).sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .handle((resp, ex) -> {
                    HttpResult res = new HttpResult();
                    res.timeMs = (System.nanoTime() - start) / 1_000_000;
                    if (ex != null) {
                        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                        res.error = describe(cause);
                        return res;
                    }
                    res.status = resp.statusCode();
                    res.statusText = HttpResult.reasonPhrase(res.status);
                    res.httpVersion = resp.version() == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
                    resp.headers().map().forEach((k, values) -> {
                        if (k.startsWith(":")) return;
                        for (String v : values) res.headers.add(new KeyValue(k, v));
                    });
                    res.bodyBytes = resp.body() == null ? new byte[0] : resp.body();
                    res.body = new String(res.bodyBytes, charsetOf(res.header("Content-Type")));
                    return res;
                });
    }

    private HttpRequest build(ResolvedRequest r, Settings s) {
        URI uri = UrlUtil.toUri(r.url);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(Math.max(1000, s.timeoutMs)));
        for (KeyValue h : r.headers) {
            if (h.key == null || h.key.isBlank()) continue;
            if (RESTRICTED.contains(h.key.toLowerCase())) {
                r.warnings.add("Header '" + h.key + "' is managed by the HTTP client and was not sent as-is");
                continue;
            }
            b.header(h.key.trim(), h.value == null ? "" : h.value);
        }
        HttpRequest.BodyPublisher pub = r.body == null || r.body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(r.body);
        b.method(r.method.toUpperCase(), pub);
        return b.build();
    }

    private static String describe(Throwable t) {
        String name = t.getClass().getSimpleName();
        String msg = t.getMessage();
        if (t instanceof java.net.http.HttpConnectTimeoutException || t instanceof java.net.http.HttpTimeoutException) {
            return "Request timed out";
        }
        if (t instanceof java.net.ConnectException) {
            return "Connection refused" + (msg != null && !msg.isBlank() ? ": " + msg : "");
        }
        if (t instanceof java.nio.channels.UnresolvedAddressException || t instanceof java.net.UnknownHostException) {
            return "Could not resolve host";
        }
        if (t instanceof javax.net.ssl.SSLException) {
            return "SSL error: " + msg + " (you can disable SSL verification in Settings)";
        }
        return name + (msg != null ? ": " + msg : "");
    }

    static Charset charsetOf(String contentType) {
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static SSLContext trustAll() {
        try {
            TrustManager[] tm = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void clearCookies() {
        cookies.getCookieStore().removeAll();
    }

    public List<java.net.HttpCookie> allCookies() {
        return cookies.getCookieStore().getCookies();
    }
}
