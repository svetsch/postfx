package io.postfx.model;

public enum AuthType {
    INHERIT("Inherit auth from parent"), NONE("No Auth"), BASIC("Basic Auth"), BEARER("Bearer Token"), APIKEY("API Key"),
    OAUTH2("OAuth 2.0");

    public final String label;

    AuthType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
