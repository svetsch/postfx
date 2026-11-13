package io.postfx.model;

public enum VarScope {
    ENVIRONMENT("environment"), COLLECTION("collection"), GLOBAL("global");

    public final String label;

    VarScope(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
