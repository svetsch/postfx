package io.postfx.model;

public enum BodyMode {
    NONE("none"), RAW("raw"), URLENCODED("x-www-form-urlencoded"), FORMDATA("form-data"),
    GRAPHQL("GraphQL"), BINARY("binary");

    public final String label;

    BodyMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
