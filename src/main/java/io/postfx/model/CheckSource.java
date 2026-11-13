package io.postfx.model;

public enum CheckSource {
    STATUS("Status code"), RESPONSE_TIME("Response time (ms)"), HEADER("Header"), JSON_PATH("JSON path"),
    BODY("Body text"), BODY_SIZE("Body size (bytes)");

    public final String label;

    CheckSource(String label) {
        this.label = label;
    }

    /** Whether the source needs a property (header name / JSON path). */
    public boolean needsProperty() {
        return this == HEADER || this == JSON_PATH;
    }

    @Override
    public String toString() {
        return label;
    }
}
