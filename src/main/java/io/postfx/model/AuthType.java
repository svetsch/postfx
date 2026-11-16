package io.postfx.model;

public enum AuthType {
    INHERIT("Inherit auth from parent"), NONE("No Auth"), BASIC("Basic Auth"), BEARER("Bearer Token"), APIKEY("API Key"),
    OAUTH2("OAuth 2.0"), OIDC("OpenID Connect");

    /** OAuth 2.0 and OpenID Connect share the token machinery (configuration in {@link Auth#oauth2}). */
    public boolean usesOAuth2() {
        return this == OAUTH2 || this == OIDC;
    }

    public final String label;

    AuthType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
