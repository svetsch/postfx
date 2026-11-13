package io.postfx.model;

public enum ExtractSource {
    JSON_PATH("JSON path"), HEADER("Header"), BODY_REGEX("Body regex (group 1)"), STATUS("Status code"), BODY("Whole body");

    public final String label;

    ExtractSource(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
