package io.postfx.engine;

import io.postfx.model.KeyValue;

import java.util.ArrayList;
import java.util.List;

/** A request with all variables resolved and auth applied, ready to be sent. */
public class ResolvedRequest {
    public String method = "GET";
    public String url = "";
    public List<KeyValue> headers = new ArrayList<>();
    @com.fasterxml.jackson.annotation.JsonIgnore
    public byte[] body = new byte[0];
    /** Human readable body (multipart file content is summarised). */
    public String bodyPreview = "";
    public List<String> warnings = new ArrayList<>();

    public String header(String name) {
        for (KeyValue h : headers) {
            if (h.key.equalsIgnoreCase(name)) return h.value;
        }
        return null;
    }

    public void setHeaderIfAbsent(String name, String value) {
        if (header(name) == null) headers.add(new KeyValue(name, value));
    }

    public void setHeader(String name, String value) {
        headers.removeIf(h -> h.key.equalsIgnoreCase(name));
        headers.add(new KeyValue(name, value));
    }
}
