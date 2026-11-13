package io.postfx.model;

import java.util.ArrayList;
import java.util.List;

public class Body {
    public BodyMode mode = BodyMode.NONE;
    public String raw = "";
    /** json, text, xml, html, javascript */
    public String rawLanguage = "json";
    public List<KeyValue> urlencoded = new ArrayList<>();
    public List<KeyValue> formdata = new ArrayList<>();
    public String graphqlQuery = "";
    public String graphqlVariables = "";
    public String binaryFile = "";

    public String contentTypeForRaw() {
        return switch (rawLanguage == null ? "text" : rawLanguage) {
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "html" -> "text/html";
            case "javascript" -> "application/javascript";
            default -> "text/plain";
        };
    }
}
