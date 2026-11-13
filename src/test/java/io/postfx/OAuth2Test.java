package io.postfx;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.engine.ExecutionResult;
import io.postfx.engine.HttpEngine;
import io.postfx.engine.JsonUtil;
import io.postfx.engine.OAuth2Client;
import io.postfx.engine.RequestExecutor;
import io.postfx.engine.VariableContext;
import io.postfx.io.Importer;
import io.postfx.io.JavaTestExporter;
import io.postfx.io.PostmanExporter;
import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.Check;
import io.postfx.model.CheckOperator;
import io.postfx.model.CheckSource;
import io.postfx.model.Collection;
import io.postfx.model.KeyValue;
import io.postfx.model.OAuth2;
import io.postfx.model.RequestItem;
import io.postfx.model.Settings;
import io.postfx.script.ScriptExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OAuth2Test {
    static TestServer server;
    static ScriptExecutor scripts;
    static RequestExecutor executor;
    static final Settings settings = new Settings();
    static final HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer();
        scripts = new ScriptExecutor();
        executor = new RequestExecutor(new HttpEngine(), scripts);
    }

    @AfterAll
    static void stop() {
        server.close();
        scripts.close();
    }

    static Collection collection(OAuth2 o) {
        Collection c = new Collection("OAuth");
        c.variables.add(new KeyValue("baseUrl", server.url()));
        c.auth = new Auth(AuthType.OAUTH2);
        c.auth.oauth2 = o;
        RequestItem secure = new RequestItem("Secure", "GET", "{{baseUrl}}/secure");
        secure.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "200"));
        c.items.add(secure);
        return c;
    }

    static String failures(ExecutionResult r) {
        StringBuilder sb = new StringBuilder();
        r.errors.forEach(e -> sb.append("ERROR ").append(e).append('\n'));
        r.tests.stream().filter(t -> !t.passed).forEach(t -> sb.append("FAIL ").append(t.name).append(": ").append(t.error).append('\n'));
        r.console.forEach(c -> sb.append(c).append('\n'));
        return sb.toString();
    }

    @Test
    void clientCredentialsAreFetchedAutomaticallyWithBasicOrBodyClientAuth() {
        for (String clientAuth : List.of("header", "body")) {
            OAuth2 o = new OAuth2();
            o.grantType = OAuth2.CLIENT_CREDENTIALS;
            o.accessTokenUrl = "{{baseUrl}}/oauth/token";
            o.clientId = "app";
            o.clientSecret = "{{secret}}";
            o.scope = "read";
            o.clientAuthentication = clientAuth;
            Collection c = collection(o);
            c.variables.add(new KeyValue("secret", "s3cret"));
            VariableContext vars = VariableContext.of(null, c, null);
            ExecutionResult r = executor.execute((RequestItem) c.items.get(0), c, vars, settings, 0, 1);
            assertTrue(r.passed(), clientAuth + "\n" + failures(r));
            assertEquals("cc-token-read", o.accessToken, "token is stored on the collection auth");
            assertTrue(o.expiresAt > System.currentTimeMillis());
            assertEquals("Bearer cc-token-read", r.sent.header("Authorization"));
        }
    }

    @Test
    void expiredTokenIsRefreshedWithTheRefreshToken() {
        OAuth2 o = new OAuth2();
        o.grantType = OAuth2.PASSWORD;
        o.accessTokenUrl = "{{baseUrl}}/oauth/token";
        o.clientId = "app";
        o.username = "ada";
        o.password = "pw";
        o.clientAuthentication = "body";
        Collection c = collection(o);
        VariableContext vars = VariableContext.of(null, c, null);
        ExecutionResult first = executor.execute((RequestItem) c.items.get(0), c, vars, settings, 0, 1);
        assertTrue(first.passed(), failures(first));
        assertEquals("pw-token", o.accessToken);
        assertEquals("rt-1", o.refreshToken);
        assertTrue(o.isExpired(), "expires_in=1 is inside the 30 s safety margin");

        ExecutionResult second = executor.execute((RequestItem) c.items.get(0), c, vars, settings, 0, 1);
        assertTrue(second.passed(), failures(second));
        assertEquals("refreshed-token", o.accessToken);
        assertEquals("rt-2", o.refreshToken);
        assertTrue(second.console.stream().anyMatch(e -> e.message.contains("refreshing")), failures(second));
    }

    @Test
    void tokenInQueryAndCustomPrefix() {
        OAuth2 o = new OAuth2();
        o.accessToken = "imp-token";
        o.addTokenTo = "query";
        o.autoRefresh = false;
        Collection c = collection(o);
        ExecutionResult r = executor.execute((RequestItem) c.items.get(0), c, VariableContext.of(null, c, null), settings, 0, 1);
        assertTrue(r.passed(), failures(r));
        assertTrue(r.sent.url.endsWith("?access_token=imp-token"), r.sent.url);
    }

    @Test
    void missingTokenForInteractiveGrantIsReportedNotFetched() {
        OAuth2 o = new OAuth2();
        o.grantType = OAuth2.AUTHORIZATION_CODE;
        Collection c = collection(o);
        ExecutionResult r = executor.execute((RequestItem) c.items.get(0), c, VariableContext.of(null, c, null), settings, 0, 1);
        assertFalse(r.passed());
        assertTrue(r.console.stream().anyMatch(e -> e.message.contains("Get New Access Token")), failures(r));
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** Simulates a browser: calls the authorize URL and follows the redirect to the callback. */
    static OAuth2Client.Interaction fakeBrowser(List<String> opened) {
        return new OAuth2Client.Interaction() {
            @Override
            public void openBrowser(String url) throws Exception {
                opened.add(url);
                HttpResponse<String> res = browser.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
                String location = res.headers().firstValue("Location").orElseThrow();
                int hash = location.indexOf('#');
                // a real browser runs the callback page script to post the fragment back; do the same
                String target = hash < 0 ? location : location.substring(0, hash) + "?" + location.substring(hash + 1) + "&__fragment=1";
                browser.send(HttpRequest.newBuilder(URI.create(target)).build(), HttpResponse.BodyHandlers.ofString());
            }

            @Override
            public String askRedirectedUrl(String authorizeUrl, String redirectUri) {
                try {
                    HttpResponse<String> res = browser.send(HttpRequest.newBuilder(URI.create(authorizeUrl)).build(), HttpResponse.BodyHandlers.ofString());
                    return res.headers().firstValue("Location").orElseThrow();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    @Test
    void authorizationCodeWithPkceThroughTheLoopbackCallback() throws Exception {
        OAuth2 o = new OAuth2();
        o.grantType = OAuth2.AUTHORIZATION_CODE_PKCE;
        o.authUrl = server.url() + "/oauth/authorize";
        o.accessTokenUrl = server.url() + "/oauth/token";
        o.clientId = "app";
        o.redirectUri = "http://127.0.0.1:" + freePort() + "/callback";
        o.scope = "openid profile";
        List<String> opened = new ArrayList<>();
        List<String> log = new ArrayList<>();
        new OAuth2Client(new HttpEngine(), settings).obtain(o, new VariableContext(), fakeBrowser(opened), log::add);
        assertEquals("ac-token", o.accessToken, String.join("\n", log));
        String url = opened.get(0);
        assertTrue(url.contains("response_type=code") && url.contains("code_challenge_method=S256") && url.contains("scope=openid+profile"), url);
        assertNotNull(server.lastChallenge);
    }

    @Test
    void implicitGrantReadsTheTokenFromTheFragment() throws Exception {
        OAuth2 o = new OAuth2();
        o.grantType = OAuth2.IMPLICIT;
        o.authUrl = server.url() + "/oauth/authorize";
        o.clientId = "app";
        o.redirectUri = "http://localhost:" + freePort() + "/cb";
        new OAuth2Client(new HttpEngine(), settings).obtain(o, new VariableContext(), fakeBrowser(new ArrayList<>()), m -> {});
        assertEquals("imp-token", o.accessToken);
        assertTrue(o.expiresAt > System.currentTimeMillis());
    }

    @Test
    void nonLoopbackRedirectAsksForThePastedUrl() throws Exception {
        OAuth2 o = new OAuth2();
        o.grantType = OAuth2.AUTHORIZATION_CODE;
        o.authUrl = server.url() + "/oauth/authorize";
        o.accessTokenUrl = server.url() + "/oauth/token";
        o.clientId = "app";
        o.clientSecret = "s3cret";
        o.redirectUri = "https://oauth.pstmn.io/v1/callback";
        server.lastChallenge = null;
        List<String> opened = new ArrayList<>();
        OAuth2Client.Interaction base = fakeBrowser(opened);
        OAuth2Client.Interaction noBrowser = new OAuth2Client.Interaction() {
            @Override
            public void openBrowser(String url) {
                opened.add(url);
            }

            @Override
            public String askRedirectedUrl(String authorizeUrl, String redirectUri) {
                return base.askRedirectedUrl(authorizeUrl, redirectUri);
            }
        };
        new OAuth2Client(new HttpEngine(), settings).obtain(o, new VariableContext(), noBrowser, m -> {});
        assertEquals("ac-token", o.accessToken);
    }

    @Test
    void tokenEndpointErrorsAreExplained() {
        OAuth2 o = new OAuth2();
        o.grantType = OAuth2.CLIENT_CREDENTIALS;
        o.accessTokenUrl = server.url() + "/oauth/token";
        o.clientId = "app";
        o.clientSecret = "wrong";
        OAuth2Client.OAuth2Exception e = assertThrows(OAuth2Client.OAuth2Exception.class,
                () -> new OAuth2Client(new HttpEngine(), settings).obtain(o, new VariableContext(), null, m -> {}));
        assertTrue(e.getMessage().contains("HTTP 400") && e.getMessage().contains("invalid_grant"), e.getMessage());

        o.accessTokenUrl = "{{undefinedUrl}}";
        e = assertThrows(OAuth2Client.OAuth2Exception.class,
                () -> new OAuth2Client(new HttpEngine(), settings).obtain(o, new VariableContext(), null, m -> {}));
        assertTrue(e.getMessage().contains("undefined variables"), e.getMessage());
    }

    @Test
    void postmanRoundTrip() {
        String postman = """
                {"info": {"name": "P", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "auth": {"type": "oauth2", "oauth2": [
                   {"key": "grant_type", "value": "authorization_code_with_pkce"},
                   {"key": "authUrl", "value": "https://id.example.com/authorize"},
                   {"key": "accessTokenUrl", "value": "https://id.example.com/token"},
                   {"key": "clientId", "value": "{{clientId}}"},
                   {"key": "scope", "value": "openid"},
                   {"key": "redirect_uri", "value": "https://oauth.pstmn.io/v1/callback"},
                   {"key": "addTokenTo", "value": "header"},
                   {"key": "client_authentication", "value": "body"},
                   {"key": "authRequestParams", "value": [{"key": "audience", "value": "api://x", "enabled": true}]}]},
                 "item": []}""";
        Collection c = Importer.importText(postman, null).collections.get(0);
        assertEquals(AuthType.OAUTH2, c.auth.type);
        OAuth2 o = c.auth.oauth2;
        assertEquals(OAuth2.AUTHORIZATION_CODE_PKCE, o.grantType);
        assertEquals("https://id.example.com/authorize", o.authUrl);
        assertEquals("{{clientId}}", o.clientId);
        assertEquals("body", o.clientAuthentication);
        assertEquals("api://x", o.audience);

        JsonNode exported = PostmanExporter.exportCollection(c);
        assertEquals("oauth2", exported.at("/auth/type").asText());
        Collection back = Importer.importText(JsonUtil.toJson(exported), null).collections.get(0);
        assertEquals(o.authUrl, back.auth.oauth2.authUrl);
        assertEquals(o.grantType, back.auth.oauth2.grantType);
        assertEquals("api://x", back.auth.oauth2.audience);
    }

    @Test
    void openApiOAuth2Schemes() {
        String spec = """
                openapi: 3.0.3
                info: {title: Secured, version: "1"}
                servers: [{url: "https://api.example.com"}]
                security: [{oauth: [read]}]
                components:
                  securitySchemes:
                    oauth:
                      type: oauth2
                      flows:
                        clientCredentials:
                          tokenUrl: /oauth/token
                          scopes: {read: Read, write: Write}
                paths:
                  /x: {get: {responses: {'200': {description: ok}}}}
                """;
        Collection c = Importer.importText(spec, null).collections.get(0);
        assertEquals(AuthType.OAUTH2, c.auth.type);
        assertEquals(OAuth2.CLIENT_CREDENTIALS, c.auth.oauth2.grantType);
        assertEquals("https://api.example.com/oauth/token", c.auth.oauth2.accessTokenUrl);
        assertEquals("read write", c.auth.oauth2.scope);
        assertEquals("{{clientId}}", c.auth.oauth2.clientId);
    }

    @Test
    void javaExportFetchesClientCredentialTokens() {
        OAuth2 o = new OAuth2();
        o.grantType = OAuth2.CLIENT_CREDENTIALS;
        o.accessTokenUrl = "{{baseUrl}}/oauth/token";
        o.clientId = "app";
        o.clientSecret = "{{secret}}";
        String code = JavaTestExporter.generate(collection(o), null, new JavaTestExporter.Options());
        assertTrue(code.contains("oauthToken(v(\"{{baseUrl}}/oauth/token\"), \"client_credentials\""), code);
        assertTrue(code.contains("static String oauthToken("), code);
    }
}
