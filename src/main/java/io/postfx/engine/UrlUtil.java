package io.postfx.engine;

import io.postfx.model.KeyValue;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class UrlUtil {
    private static final String ALLOWED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;=%";

    private UrlUtil() {}

    /** Builds a URI from user text, percent-encoding characters that are not legal in a URI. */
    public static URI toUri(String url) {
        String u = url.trim();
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            u = "http://" + u;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c == '%' && !(i + 2 < u.length() && isHex(u.charAt(i + 1)) && isHex(u.charAt(i + 2)))) {
                sb.append("%25");
            } else if (ALLOWED.indexOf(c) >= 0) {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (Character.isHighSurrogate(c) && i + 1 < u.length()) {
                    bytes = u.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
                    i++;
                }
                for (byte b : bytes) sb.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return URI.create(sb.toString());
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    public static String baseOf(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    public static String queryOf(String url) {
        int q = url.indexOf('?');
        if (q < 0) return "";
        String qs = url.substring(q + 1);
        int h = qs.indexOf('#');
        return h < 0 ? qs : qs.substring(0, h);
    }

    /** Parses the query string of a URL into raw (not decoded) key/value pairs. */
    public static List<KeyValue> parseQuery(String url) {
        List<KeyValue> out = new ArrayList<>();
        String qs = queryOf(url);
        if (qs.isEmpty()) return out;
        for (String part : qs.split("&", -1)) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq < 0) out.add(new KeyValue(part, ""));
            else out.add(new KeyValue(part.substring(0, eq), part.substring(eq + 1)));
        }
        return out;
    }

    /** Rebuilds a URL from its base and enabled params (raw values, no encoding applied). */
    public static String withQuery(String url, List<KeyValue> params) {
        String base = baseOf(url);
        StringBuilder sb = new StringBuilder();
        for (KeyValue kv : params) {
            if (!kv.enabled || kv.isBlank()) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(kv.key == null ? "" : kv.key);
            if (kv.value != null && !kv.value.isEmpty()) sb.append('=').append(kv.value);
        }
        return sb.length() == 0 ? base : base + "?" + sb;
    }

    public static String appendQueryParam(String url, String key, String value) {
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + encode(key) + "=" + encode(value);
    }

    public static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String formEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    public static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
