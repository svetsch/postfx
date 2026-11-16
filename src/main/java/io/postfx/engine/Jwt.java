package io.postfx.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Decodes JSON Web Tokens (header and claims). The signature is not verified. */
public final class Jwt {
    private Jwt() {}

    public static JsonNode header(String jwt) {
        return part(jwt, 0);
    }

    public static JsonNode payload(String jwt) {
        return part(jwt, 1);
    }

    private static JsonNode part(String jwt, int index) {
        if (jwt == null) return null;
        String[] parts = jwt.strip().split("\\.");
        if (parts.length < 2 || index >= parts.length) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(parts[index].replace('+', '-').replace('/', '_').replace("=", ""));
            JsonNode n = JsonUtil.tryParse(new String(bytes, StandardCharsets.UTF_8));
            return n != null && n.isObject() ? n : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
