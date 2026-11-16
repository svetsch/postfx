package io.postfx.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.model.KeyValue;
import io.postfx.model.OAuth2;
import io.postfx.model.Settings;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * OAuth 2.0 token acquisition: client credentials, password, authorization code (with or without PKCE),
 * implicit, and refresh. Tokens are stored in the {@link OAuth2} configuration object.
 */
public class OAuth2Client {
    /** Seconds to wait for the user to complete the login in the browser. */
    public static final long BROWSER_TIMEOUT_SECONDS = 300;

    /** How the client talks to the user for interactive grants. */
    public interface Interaction {
        /** Opens the authorization URL in a browser. */
        void openBrowser(String url) throws Exception;

        /**
         * Called when the redirect URI is not on this machine: asks the user to paste the URL the browser was
         * redirected to after login. Returns null when the user cancels.
         */
        String askRedirectedUrl(String authorizeUrl, String redirectUri);
    }

    public static class OAuth2Exception extends Exception {
        public OAuth2Exception(String message) {
            super(message);
        }

        public OAuth2Exception(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final HttpEngine http;
    private final Settings settings;
    private final AtomicReference<LoopbackReceiver> pending = new AtomicReference<>();

    /** OpenID Connect mode: discovery, "openid" scope, nonce and ID token checks. */
    private boolean openId;
    /** Nonce sent in the current authorization request (checked against the ID token). */
    private String expectedNonce;

    public OAuth2Client(HttpEngine http, Settings settings) {
        this.http = http;
        this.settings = settings;
    }

    /** Enables OpenID Connect behaviour; returns this client. */
    public OAuth2Client openId(boolean enabled) {
        this.openId = enabled;
        return this;
    }

    public static OAuth2Client forAuth(HttpEngine http, Settings settings, io.postfx.model.Auth auth) {
        return new OAuth2Client(http, settings).openId(auth != null && auth.type == io.postfx.model.AuthType.OIDC);
    }

    // ------------------------------------------------------------------ public API

    /** Obtains a new token with the configured grant and stores it into {@code cfg}. */
    public void obtain(OAuth2 cfg, VariableContext vars, Interaction interaction, Consumer<String> log) throws OAuth2Exception {
        discoverIfNeeded(cfg, vars, log);
        expectedNonce = null;
        String grant = cfg.grantType == null ? OAuth2.CLIENT_CREDENTIALS : cfg.grantType;
        switch (grant) {
            case OAuth2.CLIENT_CREDENTIALS -> {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("grant_type", "client_credentials");
                common(cfg, vars, form);
                tokenRequest(cfg, vars, form, url(cfg.accessTokenUrl, vars, "Access Token URL"), log, false);
            }
            case OAuth2.PASSWORD -> {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("grant_type", "password");
                form.put("username", vars.resolve(cfg.username));
                form.put("password", vars.resolve(cfg.password));
                common(cfg, vars, form);
                tokenRequest(cfg, vars, form, url(cfg.accessTokenUrl, vars, "Access Token URL"), log, false);
            }
            case OAuth2.AUTHORIZATION_CODE, OAuth2.AUTHORIZATION_CODE_PKCE, OAuth2.IMPLICIT -> interactive(cfg, vars, interaction, log);
            default -> throw new OAuth2Exception("Unsupported grant type: " + grant);
        }
    }

    /** Uses the refresh token to get a new access token. */
    public void refresh(OAuth2 cfg, VariableContext vars, Consumer<String> log) throws OAuth2Exception {
        if (cfg.refreshToken == null || cfg.refreshToken.isBlank()) throw new OAuth2Exception("No refresh token available");
        discoverIfNeeded(cfg, vars, log);
        expectedNonce = null;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", cfg.refreshToken);
        String scope = effectiveScope(cfg, vars);
        if (scope != null && !scope.isBlank()) form.put("scope", scope);
        String url = cfg.refreshTokenUrl != null && !cfg.refreshTokenUrl.isBlank() ? cfg.refreshTokenUrl : cfg.accessTokenUrl;
        tokenRequest(cfg, vars, form, url(url, vars, "Access Token URL"), log, true);
    }

    /**
     * Makes sure the configuration holds a usable token when that is possible without user interaction:
     * refresh token first, then a new token for client credentials / password grants. Never throws.
     */
    public void ensureToken(OAuth2 cfg, VariableContext vars, Consumer<String> log) {
        if (!cfg.autoRefresh) return;
        synchronized (cfg) {
            if (cfg.hasToken() && !cfg.isExpired()) return;
            String why = cfg.hasToken() ? "expired" : "missing";
            if (cfg.refreshToken != null && !cfg.refreshToken.isBlank()) {
                try {
                    log.accept("OAuth 2.0 token " + why + ": refreshing it");
                    refresh(cfg, vars, log);
                    return;
                } catch (OAuth2Exception e) {
                    log.accept("OAuth 2.0 refresh failed: " + e.getMessage());
                }
            }
            if (!cfg.isInteractive()) {
                try {
                    log.accept("OAuth 2.0 token " + why + ": requesting a new one (" + OAuth2.label(cfg.grantType) + ")");
                    obtain(cfg, vars, null, log);
                } catch (OAuth2Exception e) {
                    log.accept("OAuth 2.0 token request failed: " + e.getMessage());
                }
            } else {
                log.accept("OAuth 2.0 token " + why + ": use 'Get New Access Token' in the Auth tab (" + OAuth2.label(cfg.grantType) + " needs a browser login)");
            }
        }
    }

    /** Cancels a pending browser login. */
    public void cancelPending() {
        LoopbackReceiver r = pending.getAndSet(null);
        if (r != null) {
            r.cancel();
            r.close();
        }
    }

    // ------------------------------------------------------------------ interactive grants

    private void interactive(OAuth2 cfg, VariableContext vars, Interaction interaction, Consumer<String> log) throws OAuth2Exception {
        if (interaction == null) {
            throw new OAuth2Exception(OAuth2.label(cfg.grantType) + " requires a browser login: use 'Get New Access Token' in the Auth tab");
        }
        boolean implicit = OAuth2.IMPLICIT.equals(cfg.grantType);
        String authUrl = url(cfg.authUrl, vars, "Auth URL");
        String redirect = vars.resolve(cfg.redirectUri == null ? "" : cfg.redirectUri).strip();
        if (redirect.isEmpty()) throw new OAuth2Exception("The Callback URL is required");
        String state = vars.resolve(cfg.state);
        if (state == null || state.isBlank()) state = random(16);
        String verifier = cfg.isPkce() ? random(48) : null;
        String nonce = openId ? random(16) : null;

        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", implicit ? (openId ? "id_token token" : "token") : "code");
        q.put("client_id", vars.resolve(cfg.clientId));
        q.put("redirect_uri", redirect);
        String scope = effectiveScope(cfg, vars);
        if (scope != null && !scope.isBlank()) q.put("scope", scope);
        q.put("state", state);
        if (nonce != null) q.put("nonce", nonce);
        String audience = vars.resolve(cfg.audience);
        if (audience != null && !audience.isBlank()) q.put("audience", audience);
        if (verifier != null) {
            boolean plain = "plain".equalsIgnoreCase(cfg.challengeAlgorithm);
            q.put("code_challenge", plain ? verifier : s256(verifier));
            q.put("code_challenge_method", plain ? "plain" : "S256");
        }
        String authorizeUrl = authUrl + (authUrl.contains("?") ? "&" : "?") + form(q);

        Map<String, String> params;
        URI redirectUri;
        try {
            redirectUri = URI.create(redirect);
        } catch (IllegalArgumentException e) {
            throw new OAuth2Exception("Invalid Callback URL: " + redirect);
        }
        if (LoopbackReceiver.isLoopback(redirectUri)) {
            try (LoopbackReceiver receiver = new LoopbackReceiver(redirectUri)) {
                pending.set(receiver);
                log.accept("Opening the browser for the OAuth 2.0 login (waiting for " + redirect + ")");
                interaction.openBrowser(authorizeUrl);
                params = receiver.await(BROWSER_TIMEOUT_SECONDS);
            } catch (TimeoutException e) {
                throw new OAuth2Exception("No answer from the browser within " + BROWSER_TIMEOUT_SECONDS + " s");
            } catch (CancellationException e) {
                throw new OAuth2Exception("Login cancelled");
            } catch (OAuth2Exception e) {
                throw e;
            } catch (Exception e) {
                throw new OAuth2Exception(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            } finally {
                pending.set(null);
            }
        } else {
            try {
                interaction.openBrowser(authorizeUrl);
            } catch (Exception e) {
                throw new OAuth2Exception("Could not open the browser: " + e.getMessage(), e);
            }
            String back = interaction.askRedirectedUrl(authorizeUrl, redirect);
            if (back == null || back.isBlank()) throw new OAuth2Exception("Login cancelled");
            params = paramsFromUrl(back.strip());
        }

        if (params.containsKey("error")) {
            throw new OAuth2Exception("Authorization server returned '" + params.get("error") + "'"
                    + (params.containsKey("error_description") ? ": " + params.get("error_description") : ""));
        }
        if (params.containsKey("state") && !state.equals(params.get("state"))) {
            throw new OAuth2Exception("State mismatch in the OAuth 2.0 callback (possible CSRF): expected " + state);
        }
        expectedNonce = nonce;
        if (implicit) {
            if (!params.containsKey("access_token")) throw new OAuth2Exception("No access_token in the callback URL");
            validateIdToken(params.get("id_token"), cfg, vars.resolve(cfg.clientId));
            store(cfg, params, false);
            log.accept("OAuth 2.0 token received (implicit)");
            return;
        }
        String code = params.get("code");
        if (code == null || code.isBlank()) throw new OAuth2Exception("No authorization code in the callback URL");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirect);
        if (verifier != null) form.put("code_verifier", verifier);
        tokenRequest(cfg, vars, form, url(cfg.accessTokenUrl, vars, "Access Token URL"), log, false);
    }

    static Map<String, String> paramsFromUrl(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        if (q >= 0) out.putAll(LoopbackReceiver.parse(url.substring(q + 1, h > q ? h : url.length())));
        if (h >= 0) out.putAll(LoopbackReceiver.parse(url.substring(h + 1)));
        return out;
    }

    // ------------------------------------------------------------------ token endpoint

    private void common(OAuth2 cfg, VariableContext vars, Map<String, String> form) {
        String scope = effectiveScope(cfg, vars);
        if (scope != null && !scope.isBlank()) form.put("scope", scope);
        String audience = vars.resolve(cfg.audience);
        if (audience != null && !audience.isBlank()) form.put("audience", audience);
    }

    private void tokenRequest(OAuth2 cfg, VariableContext vars, Map<String, String> form, String tokenUrl,
                              Consumer<String> log, boolean refreshing) throws OAuth2Exception {
        String clientId = vars.resolve(cfg.clientId);
        String secret = vars.resolve(cfg.clientSecret);
        ResolvedRequest r = new ResolvedRequest();
        r.method = "POST";
        r.url = tokenUrl;
        r.headers.add(new KeyValue("Accept", "application/json"));
        r.headers.add(new KeyValue("Content-Type", "application/x-www-form-urlencoded"));
        boolean basic = !"body".equalsIgnoreCase(cfg.clientAuthentication) && secret != null && !secret.isEmpty();
        if (basic) {
            String cred = (clientId == null ? "" : clientId) + ":" + secret;
            r.headers.add(new KeyValue("Authorization", "Basic " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8))));
        } else {
            if (clientId != null && !clientId.isEmpty()) form.put("client_id", clientId);
            if (secret != null && !secret.isEmpty()) form.put("client_secret", secret);
        }
        String body = form(form);
        r.body = body.getBytes(StandardCharsets.UTF_8);
        r.bodyPreview = body;
        r.headers.add(new KeyValue("User-Agent", RequestPreparer.USER_AGENT));
        HttpResult res = http.send(r, settings);
        if (res.failed()) throw new OAuth2Exception("Token request to " + tokenUrl + " failed: " + res.error);
        Map<String, String> values = parseTokenResponse(res.body);
        if (res.status >= 400 || values.containsKey("error")) {
            String err = values.containsKey("error")
                    ? values.get("error") + (values.containsKey("error_description") ? ": " + values.get("error_description") : "")
                    : abbreviate(res.body);
            throw new OAuth2Exception("Token endpoint answered HTTP " + res.status + " (" + err + ")");
        }
        if (!values.containsKey("access_token")) {
            throw new OAuth2Exception("No access_token in the token response: " + abbreviate(res.body));
        }
        validateIdToken(values.get("id_token"), cfg, clientId);
        store(cfg, values, refreshing);
        long secs = cfg.expiresAt > 0 ? Math.max(0, (cfg.expiresAt - System.currentTimeMillis()) / 1000) : -1;
        log.accept("OAuth 2.0 token received from " + tokenUrl + (secs >= 0 ? " (expires in " + secs + " s)" : ""));
    }

    static Map<String, String> parseTokenResponse(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode n = JsonUtil.tryParse(body);
        if (n != null && n.isObject()) {
            n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString()));
        } else if (body != null && body.contains("=")) {
            out.putAll(LoopbackReceiver.parse(body.strip())); // e.g. GitHub without Accept: application/json
        }
        return out;
    }

    private static void store(OAuth2 cfg, Map<String, String> values, boolean refreshing) {
        cfg.accessToken = values.get("access_token");
        if (values.containsKey("refresh_token")) cfg.refreshToken = values.get("refresh_token");
        else if (!refreshing) cfg.refreshToken = "";
        cfg.tokenType = values.getOrDefault("token_type", "");
        cfg.idToken = values.getOrDefault("id_token", "");
        long expiresIn = 0;
        try {
            expiresIn = Long.parseLong(values.getOrDefault("expires_in", "0").strip());
        } catch (NumberFormatException ignored) {
            // unknown expiry
        }
        cfg.expiresAt = expiresIn > 0 ? System.currentTimeMillis() + expiresIn * 1000 : 0;
    }

    // ------------------------------------------------------------------ OpenID Connect

    /** The discovery document URL: the given URL, or issuer + "/.well-known/openid-configuration". */
    public static String wellKnownUrl(String issuerOrUrl) {
        String u = issuerOrUrl.strip();
        if (u.contains("/.well-known/")) return u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + "/.well-known/openid-configuration";
    }

    /**
     * Reads the OpenID Provider configuration and fills the endpoints, issuer, client authentication method and PKCE
     * method into {@code cfg}. Returns a short summary.
     */
    public String discover(OAuth2 cfg, VariableContext vars, Consumer<String> log) throws OAuth2Exception {
        String raw = vars.resolve(cfg.discoveryUrl == null ? "" : cfg.discoveryUrl).strip();
        if (raw.isEmpty()) throw new OAuth2Exception("The Discovery URL is required (issuer or .well-known/openid-configuration URL)");
        if (VariableResolver.hasPlaceholders(raw)) throw new OAuth2Exception("The Discovery URL contains undefined variables: " + raw);
        String url = wellKnownUrl(raw);
        ResolvedRequest r = new ResolvedRequest();
        r.method = "GET";
        r.url = url;
        r.headers.add(new KeyValue("Accept", "application/json"));
        r.headers.add(new KeyValue("User-Agent", RequestPreparer.USER_AGENT));
        HttpResult res = http.send(r, settings);
        if (res.failed()) throw new OAuth2Exception("Could not read " + url + ": " + res.error);
        if (res.status >= 400) throw new OAuth2Exception("Discovery document " + url + " answered HTTP " + res.status);
        JsonNode doc = JsonUtil.tryParse(res.body);
        if (doc == null || !doc.isObject() || !doc.has("issuer")) {
            throw new OAuth2Exception(url + " is not an OpenID Connect discovery document");
        }
        cfg.issuer = doc.path("issuer").asText("");
        cfg.authUrl = doc.path("authorization_endpoint").asText(cfg.authUrl);
        cfg.accessTokenUrl = doc.path("token_endpoint").asText(cfg.accessTokenUrl);
        cfg.userInfoUrl = doc.path("userinfo_endpoint").asText("");
        cfg.endSessionUrl = doc.path("end_session_endpoint").asText("");
        cfg.jwksUri = doc.path("jwks_uri").asText("");
        java.util.List<String> authMethods = texts(doc.path("token_endpoint_auth_methods_supported"));
        if (!authMethods.isEmpty()) {
            if (authMethods.contains("client_secret_basic")) cfg.clientAuthentication = "header";
            else if (authMethods.contains("client_secret_post")) cfg.clientAuthentication = "body";
        }
        java.util.List<String> challenges = texts(doc.path("code_challenge_methods_supported"));
        if (challenges.contains("S256")) cfg.challengeAlgorithm = "S256";
        else if (challenges.contains("plain")) cfg.challengeAlgorithm = "plain";
        if (cfg.scope == null || cfg.scope.isBlank()) {
            java.util.List<String> scopes = texts(doc.path("scopes_supported"));
            StringBuilder sb = new StringBuilder("openid");
            for (String s : java.util.List.of("profile", "email")) if (scopes.contains(s)) sb.append(' ').append(s);
            cfg.scope = sb.toString();
        }
        String summary = "OpenID Connect discovery: issuer " + cfg.issuer + ", token endpoint " + cfg.accessTokenUrl;
        log.accept(summary);
        return summary;
    }

    private void discoverIfNeeded(OAuth2 cfg, VariableContext vars, Consumer<String> log) throws OAuth2Exception {
        if (!openId || cfg.discoveryUrl == null || cfg.discoveryUrl.isBlank()) return;
        boolean needsAuthUrl = cfg.isInteractive() && (cfg.authUrl == null || cfg.authUrl.isBlank());
        boolean needsTokenUrl = !OAuth2.IMPLICIT.equals(cfg.grantType) && (cfg.accessTokenUrl == null || cfg.accessTokenUrl.isBlank());
        if (needsAuthUrl || needsTokenUrl) discover(cfg, vars, log);
    }

    /** Scope sent to the server; OpenID Connect always includes "openid". */
    private String effectiveScope(OAuth2 cfg, VariableContext vars) {
        String scope = vars.resolve(cfg.scope == null ? "" : cfg.scope).strip();
        if (openId && !java.util.Arrays.asList(scope.split("\\s+")).contains("openid")) {
            scope = scope.isEmpty() ? "openid" : "openid " + scope;
        }
        return scope;
    }

    /** Checks nonce, issuer and audience of an ID token (the signature is not verified). */
    private void validateIdToken(String idToken, OAuth2 cfg, String clientId) throws OAuth2Exception {
        if (!openId || idToken == null || idToken.isBlank()) return;
        JsonNode claims = Jwt.payload(idToken);
        if (claims == null) throw new OAuth2Exception("The ID token is not a valid JWT");
        if (expectedNonce != null && !expectedNonce.equals(claims.path("nonce").asText(null))) {
            throw new OAuth2Exception("ID token nonce mismatch (possible replay): expected " + expectedNonce
                    + ", got " + claims.path("nonce").asText("none"));
        }
        if (cfg.issuer != null && !cfg.issuer.isBlank() && claims.has("iss")
                && !trimSlash(cfg.issuer).equals(trimSlash(claims.get("iss").asText()))) {
            throw new OAuth2Exception("ID token issuer " + claims.get("iss").asText() + " does not match " + cfg.issuer);
        }
        JsonNode aud = claims.get("aud");
        if (aud != null && clientId != null && !clientId.isBlank()) {
            boolean ok = aud.isArray() ? texts(aud).contains(clientId) : clientId.equals(aud.asText());
            if (!ok) throw new OAuth2Exception("ID token audience " + aud + " does not contain the client id " + clientId);
        }
    }

    /** Calls the UserInfo endpoint with the access token and returns the response body. */
    public String userInfo(OAuth2 cfg, VariableContext vars) throws OAuth2Exception {
        if (!cfg.hasToken()) throw new OAuth2Exception("Get an access token first");
        if ((cfg.userInfoUrl == null || cfg.userInfoUrl.isBlank()) && cfg.discoveryUrl != null && !cfg.discoveryUrl.isBlank()) {
            discover(cfg, vars, m -> {});
        }
        if (cfg.userInfoUrl == null || cfg.userInfoUrl.isBlank()) throw new OAuth2Exception("The provider has no UserInfo endpoint");
        ResolvedRequest r = new ResolvedRequest();
        r.method = "GET";
        r.url = cfg.userInfoUrl;
        r.headers.add(new KeyValue("Accept", "application/json"));
        r.headers.add(new KeyValue("Authorization", "Bearer " + cfg.accessToken));
        HttpResult res = http.send(r, settings);
        if (res.failed()) throw new OAuth2Exception("UserInfo request failed: " + res.error);
        if (res.status >= 400) throw new OAuth2Exception("UserInfo endpoint answered HTTP " + res.status + ": " + abbreviate(res.body));
        return res.body;
    }

    private static java.util.List<String> texts(JsonNode arr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (arr != null && arr.isArray()) arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ------------------------------------------------------------------ helpers

    private static String url(String raw, VariableContext vars, String what) throws OAuth2Exception {
        String u = vars.resolve(raw == null ? "" : raw).strip();
        if (u.isEmpty()) throw new OAuth2Exception("The " + what + " is required");
        if (VariableResolver.hasPlaceholders(u)) throw new OAuth2Exception("The " + what + " contains undefined variables: " + u);
        return u;
    }

    private static String form(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(UrlUtil.formEncode(k)).append('=').append(UrlUtil.formEncode(v == null ? "" : v));
        });
        return sb.toString();
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String s256(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
