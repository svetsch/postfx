package io.postfx.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.model.KeyValue;

import java.util.ArrayList;
import java.util.List;

public class HttpResult {
    public int status;
    public String statusText = "";
    public String httpVersion = "";
    public List<KeyValue> headers = new ArrayList<>();
    @JsonIgnore
    public byte[] bodyBytes = new byte[0];
    public String body = "";
    public long timeMs;
    /** Set when the request could not be sent (DNS, connection refused, timeout...). */
    public String error;

    @JsonIgnore
    private JsonNode json;
    @JsonIgnore
    private boolean jsonParsed;

    public boolean failed() {
        return error != null;
    }

    public String header(String name) {
        for (KeyValue h : headers) {
            if (h.key.equalsIgnoreCase(name)) return h.value;
        }
        return null;
    }

    public List<String> headers(String name) {
        List<String> out = new ArrayList<>();
        for (KeyValue h : headers) {
            if (h.key.equalsIgnoreCase(name)) out.add(h.value);
        }
        return out;
    }

    public long size() {
        return bodyBytes.length;
    }

    /** Parsed JSON body, or null if the body is not JSON. */
    @JsonIgnore
    public JsonNode json() {
        if (!jsonParsed) {
            json = JsonUtil.tryParse(body);
            jsonParsed = true;
        }
        return json;
    }

    public String contentType() {
        String ct = header("Content-Type");
        return ct == null ? "" : ct.toLowerCase();
    }

    public static String reasonPhrase(int code) {
        return switch (code) {
            case 100 -> "Continue";
            case 101 -> "Switching Protocols";
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 203 -> "Non-Authoritative Information";
            case 204 -> "No Content";
            case 205 -> "Reset Content";
            case 206 -> "Partial Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 402 -> "Payment Required";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case 413 -> "Payload Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 418 -> "I'm a teapot";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }
}
