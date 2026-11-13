package io.postfx.model;

public class Auth {
    public AuthType type = AuthType.INHERIT;
    public String username = "";
    public String password = "";
    public String token = "";
    public String apiKey = "";
    public String apiValue = "";
    /** "header" or "query" */
    public String apiIn = "header";
    public OAuth2 oauth2 = new OAuth2();

    public Auth() {}

    public Auth(AuthType type) {
        this.type = type;
    }
}
