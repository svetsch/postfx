package io.postfx.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** OAuth 2.0 configuration and current token (same fields as Postman's oauth2 auth). */
public class OAuth2 {
    public static final String AUTHORIZATION_CODE = "authorization_code";
    public static final String AUTHORIZATION_CODE_PKCE = "authorization_code_with_pkce";
    public static final String IMPLICIT = "implicit";
    public static final String PASSWORD = "password_credentials";
    public static final String CLIENT_CREDENTIALS = "client_credentials";

    // ---- configuration (all fields may contain {{variables}})
    public String grantType = CLIENT_CREDENTIALS;
    public String authUrl = "";
    public String accessTokenUrl = "";
    /** Token URL used for refresh_token requests; empty = access token URL. */
    public String refreshTokenUrl = "";
    public String clientId = "";
    public String clientSecret = "";
    public String scope = "";
    public String audience = "";
    public String redirectUri = "http://localhost:8765/callback";
    public String username = "";
    public String password = "";
    /** "header" (HTTP Basic with client id/secret) or "body" (client_id/client_secret form fields). */
    public String clientAuthentication = "header";
    public String state = "";
    /** PKCE code challenge method: "S256" or "plain". */
    public String challengeAlgorithm = "S256";
    /** Prefix of the Authorization header value, usually "Bearer". */
    public String headerPrefix = "Bearer";
    /** "header" or "query". */
    public String addTokenTo = "header";
    public String queryParamName = "access_token";
    /** Fetch / refresh the token automatically before a request when it is missing or expired. */
    public boolean autoRefresh = true;

    // ---- OpenID Connect
    /** Issuer URL or full ".well-known/openid-configuration" URL (OpenID Connect only). */
    public String discoveryUrl = "";
    /** Token added to requests: "access_token" (default) or "id_token". */
    public String tokenToSend = "access_token";
    /** Values read from the discovery document. */
    public String issuer = "";
    public String userInfoUrl = "";
    public String endSessionUrl = "";
    public String jwksUri = "";

    // ---- current token
    public String accessToken = "";
    public String refreshToken = "";
    public String tokenType = "";
    public String idToken = "";
    /** Expiry time in epoch milliseconds; 0 when unknown. */
    public long expiresAt;

    @JsonIgnore
    public boolean isInteractive() {
        return !(CLIENT_CREDENTIALS.equals(grantType) || PASSWORD.equals(grantType));
    }

    @JsonIgnore
    public boolean isPkce() {
        return AUTHORIZATION_CODE_PKCE.equals(grantType);
    }

    /** True when the token is known to be expired (30 s safety margin). */
    @JsonIgnore
    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt - 30_000;
    }

    @JsonIgnore
    public boolean hasToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    /** The token to put on requests (access token, or ID token for OpenID Connect when chosen). */
    @JsonIgnore
    public String tokenForRequests() {
        if ("id_token".equals(tokenToSend) && idToken != null && !idToken.isBlank()) return idToken;
        return accessToken;
    }

    public void clearToken() {
        accessToken = "";
        refreshToken = "";
        tokenType = "";
        idToken = "";
        expiresAt = 0;
    }

    public static String label(String grant) {
        return switch (grant == null ? "" : grant) {
            case AUTHORIZATION_CODE -> "Authorization Code";
            case AUTHORIZATION_CODE_PKCE -> "Authorization Code (With PKCE)";
            case IMPLICIT -> "Implicit";
            case PASSWORD -> "Password Credentials";
            case CLIENT_CREDENTIALS -> "Client Credentials";
            default -> grant;
        };
    }
}
