package io.postfx;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.engine.ExecutionResult;
import io.postfx.engine.HttpEngine;
import io.postfx.engine.Jwt;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OidcTest {
    static TestServer server;
    static ScriptExecutor scripts;
    static RequestExecutor executor;
    static final Settings settings = new Settings();

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

    @BeforeEach
    void reset() {
        server.badNonce = false;
        server.lastChallenge = null;
    }

    static Auth oidc(String grant) {
        Auth a = new Auth(AuthType.OIDC);
        a.oauth2.grantType = grant;
        a.oauth2.discoveryUrl = server.url(); // issuer: /.well-known/openid-configuration is appended
        a.oauth2.clientId = "app";
        a.oauth2.clientSecret = "s3cret";
        a.oauth2.redirectUri = "http://127.0.0.1:" + freePort() + "/callback";
        a.oauth2.scope = "";
        return a;
    }

    static int freePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static OAuth2Client client(Auth a) {
        return OAuth2Client.forAuth(new HttpEngine(), settings, a);
    }

    @Test
    void discoveryFillsEndpointsAndOptions() throws Exception {
        Auth a = oidc(OAuth2.AUTHORIZATION_CODE_PKCE);
        String summary = client(a).discover(a.oauth2, new VariableContext(), m -> {});
        OAuth2 o = a.oauth2;
        assertEquals(server.url(), o.issuer);
        assertEquals(server.url() + "/oauth/authorize", o.authUrl);
        assertEquals(server.url() + "/oauth/token", o.accessTokenUrl);
        assertEquals(server.url() + "/userinfo", o.userInfoUrl);
        assertEquals("body", o.clientAuthentication, "only client_secret_post is supported by the provider");
        assertEquals("S256", o.challengeAlgorithm);
        assertEquals("openid profile email", o.scope);
        assertTrue(summary.contains("issuer"));
        assertEquals(server.url() + "/.well-known/openid-configuration", OAuth2Client.wellKnownUrl(server.url() + "/"));
        assertEquals("https://x/.well-known/openid-configuration", OAuth2Client.wellKnownUrl("https://x/.well-known/openid-configuration"));
    }

    @Test
    void authorizationCodeFlowDiscoversValidatesNonceAndKeepsTheIdToken() throws Exception {
        Auth a = oidc(OAuth2.AUTHORIZATION_CODE_PKCE);
        List<String> opened = new ArrayList<>();
        client(a).obtain(a.oauth2, new VariableContext(), OAuth2Test.fakeBrowser(opened), m -> {});
        OAuth2 o = a.oauth2;
        assertEquals("ac-token", o.accessToken);
        JsonNode claims = Jwt.payload(o.idToken);
        assertNotNull(claims, "ID token stored");
        assertEquals("ada", claims.get("sub").asText());
        String url = opened.get(0);
        assertTrue(url.contains("scope=openid"), "openid scope is always sent: " + url);
        assertTrue(url.contains("nonce="), url);
        assertEquals(claims.get("nonce").asText(), server.lastNonce);
    }

    @Test
    void forgedNonceIsRejected() {
        server.badNonce = true;
        Auth a = oidc(OAuth2.AUTHORIZATION_CODE_PKCE);
        OAuth2Client.OAuth2Exception e = assertThrows(OAuth2Client.OAuth2Exception.class,
                () -> client(a).obtain(a.oauth2, new VariableContext(), OAuth2Test.fakeBrowser(new ArrayList<>()), m -> {}));
        assertTrue(e.getMessage().contains("nonce mismatch"), e.getMessage());
        assertFalse(a.oauth2.hasToken(), "no token stored when the ID token is invalid");
    }

    @Test
    void wrongAudienceIsRejected() {
        Auth a = oidc(OAuth2.AUTHORIZATION_CODE_PKCE);
        a.oauth2.clientId = "app-other"; // the provider accepts it (startsWith "app") but the ID token is for "app"
        OAuth2Client.OAuth2Exception e = assertThrows(OAuth2Client.OAuth2Exception.class,
                () -> client(a).obtain(a.oauth2, new VariableContext(), OAuth2Test.fakeBrowser(new ArrayList<>()), m -> {}));
        assertTrue(e.getMessage().contains("audience"), e.getMessage());
    }

    @Test
    void implicitFlowRequestsAnIdToken() throws Exception {
        Auth a = oidc(OAuth2.IMPLICIT);
        List<String> opened = new ArrayList<>();
        client(a).obtain(a.oauth2, new VariableContext(), OAuth2Test.fakeBrowser(opened), m -> {});
        assertTrue(opened.get(0).contains("response_type=id_token+token"), opened.get(0));
        assertEquals("imp-token", a.oauth2.accessToken);
        assertNotNull(Jwt.payload(a.oauth2.idToken));
    }

    @Test
    void clientCredentialsAreDiscoveredAndFetchedAutomaticallyBeforeARequest() {
        Collection c = new Collection("OIDC");
        c.auth = oidc(OAuth2.CLIENT_CREDENTIALS);
        c.variables.add(new KeyValue("baseUrl", server.url()));
        RequestItem secure = new RequestItem("Secure", "GET", "{{baseUrl}}/secure");
        secure.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "200"));
        c.items.add(secure);
        ExecutionResult r = executor.execute(secure, c, VariableContext.of(null, c, null), settings, 0, 1);
        assertTrue(r.passed(), OAuth2Test.failures(r));
        assertEquals("cc-token-openid profile email", c.auth.oauth2.accessToken, "openid scope added by discovery");
        assertTrue(r.console.stream().anyMatch(e -> e.message.contains("discovery")), OAuth2Test.failures(r));
    }

    @Test
    void idTokenCanBeSentInsteadOfTheAccessToken() throws Exception {
        Collection c = new Collection("OIDC");
        c.auth = oidc(OAuth2.AUTHORIZATION_CODE_PKCE);
        client(c.auth).obtain(c.auth.oauth2, new VariableContext(), OAuth2Test.fakeBrowser(new ArrayList<>()), m -> {});
        c.auth.oauth2.tokenToSend = "id_token";
        RequestItem secure = new RequestItem("Secure", "GET", server.url() + "/secure");
        c.items.add(secure);
        ExecutionResult r = executor.execute(secure, c, VariableContext.of(null, c, null), settings, 0, 1);
        assertEquals("Bearer " + c.auth.oauth2.idToken, r.sent.header("Authorization"));
        assertEquals(200, r.response.status);
    }

    @Test
    void userInfo() throws Exception {
        Auth a = oidc(OAuth2.AUTHORIZATION_CODE_PKCE);
        OAuth2Client cl = client(a);
        cl.obtain(a.oauth2, new VariableContext(), OAuth2Test.fakeBrowser(new ArrayList<>()), m -> {});
        String body = cl.userInfo(a.oauth2, new VariableContext());
        assertTrue(body.contains("\"email\":\"ada@example.com\""), body);
    }

    @Test
    void openApiOpenIdConnectSchemeAndExports() {
        String spec = """
                openapi: 3.0.3
                info: {title: OIDC API, version: "1"}
                servers: [{url: "https://api.example.com"}]
                security: [{oidc: []}]
                components:
                  securitySchemes:
                    oidc: {type: openIdConnect, openIdConnectUrl: "https://id.example.com/.well-known/openid-configuration"}
                paths:
                  /me: {get: {responses: {'200': {description: ok}}}}
                """;
        Collection c = Importer.importText(spec, null).collections.get(0);
        assertEquals(AuthType.OIDC, c.auth.type);
        assertEquals("https://id.example.com/.well-known/openid-configuration", c.auth.oauth2.discoveryUrl);

        // Postman has no OpenID Connect type: exported as OAuth 2.0
        assertEquals("oauth2", PostmanExporter.exportCollection(c).at("/auth/type").asText());

        c.auth.oauth2.grantType = OAuth2.CLIENT_CREDENTIALS;
        String java = JavaTestExporter.generate(c, null, new JavaTestExporter.Options());
        assertTrue(java.contains("oauthToken(discoverTokenEndpoint(\"https://id.example.com/.well-known/openid-configuration\")"), java);
    }
}
