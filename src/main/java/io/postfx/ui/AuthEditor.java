package io.postfx.ui;

import io.postfx.engine.OAuth2Client;
import io.postfx.engine.VariableContext;
import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.OAuth2;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Editor for request / folder / collection authorization, including OAuth 2.0 token management. */
public class AuthEditor extends ScrollPane {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final List<String> GRANTS = List.of(OAuth2.AUTHORIZATION_CODE, OAuth2.AUTHORIZATION_CODE_PKCE, OAuth2.IMPLICIT,
            OAuth2.PASSWORD, OAuth2.CLIENT_CREDENTIALS);

    private final VBox content = new VBox(10);
    private final ComboBox<AuthType> type = new ComboBox<>();
    private final GridPane form = new GridPane();
    private final Label explanation = new Label();
    private Auth auth = new Auth();
    private Runnable onChange = () -> {};
    private final boolean allowInherit;
    private Supplier<String> inheritedDescription = () -> "";
    private AppContext ctx;
    private Supplier<VariableContext> variables = VariableContext::new;

    // OAuth 2.0 token widgets (kept to update them after a token request)
    private final TextArea tokenArea = new TextArea();
    private final Label tokenInfo = new Label();
    private final Label oauthStatus = new Label();
    private final Button getToken = new Button("Get New Access Token");
    private final Button refreshToken = new Button("Refresh");
    private final Button clearToken = new Button("Clear");
    private final Button cancelLogin = new Button("Cancel login");
    private final TextArea claimsArea = new TextArea();
    private final Label discoveryInfo = new Label();
    private OAuth2Client pendingClient;

    public AuthEditor(boolean allowInherit) {
        this.allowInherit = allowInherit;
        setFitToWidth(true);
        content.setPadding(new Insets(10));
        setContent(content);
        for (AuthType t : AuthType.values()) if (allowInherit || t != AuthType.INHERIT) type.getItems().add(t);
        explanation.getStyleClass().add("hint");
        explanation.setWrapText(true);
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints c1 = new ColumnConstraints(150);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(c1, c2);
        type.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            auth.type = b;
            render();
            onChange.run();
        });
        tokenArea.setWrapText(true);
        tokenArea.setPrefRowCount(3);
        tokenArea.getStyleClass().add("mono");
        tokenArea.setPromptText("No token yet: click 'Get New Access Token', or paste a token here");
        tokenInfo.getStyleClass().add("hint");
        oauthStatus.setWrapText(true);
        getToken.getStyleClass().add("send-button");
        getToken.setOnAction(e -> requestToken(false));
        refreshToken.setTooltip(new Tooltip("Use the refresh token to get a new access token"));
        refreshToken.setOnAction(e -> requestToken(true));
        clearToken.setOnAction(e -> {
            auth.oauth2.clearToken();
            showToken();
            onChange.run();
        });
        cancelLogin.setVisible(false);
        cancelLogin.setOnAction(e -> {
            if (pendingClient != null) pendingClient.cancelPending();
        });
        content.getChildren().addAll(new Label("Type"), type, explanation, form);
    }

    /** Gives access to the browser, background threads and variables (needed for OAuth 2.0). */
    public void setContext(AppContext ctx, Supplier<VariableContext> variables) {
        this.ctx = ctx;
        this.variables = variables;
    }

    public void setInheritedDescription(Supplier<String> s) {
        this.inheritedDescription = s;
    }

    public void setOnChange(Runnable r) {
        this.onChange = r;
    }

    public void setAuth(Auth a) {
        this.auth = a;
        if (a.oauth2 == null) a.oauth2 = new OAuth2();
        if (!allowInherit && a.type == AuthType.INHERIT) a.type = AuthType.NONE;
        type.setValue(a.type);
        render();
    }

    private void render() {
        form.getChildren().clear();
        switch (auth.type) {
            case INHERIT -> explanation.setText("This request uses the authorization of its parent folder or collection. " + inheritedDescription.get());
            case NONE -> explanation.setText("No Authorization header is added.");
            case BASIC -> {
                explanation.setText("Sends 'Authorization: Basic base64(username:password)'. Values may contain {{variables}}.");
                field(0, "Username", new TextField(auth.username), v -> auth.username = v);
                field(1, "Password", new PasswordField(), v -> auth.password = v, auth.password);
            }
            case BEARER -> {
                explanation.setText("Sends 'Authorization: Bearer <token>'. Tip: use {{token}} and set it with an extractor or a script.");
                field(0, "Token", new TextField(auth.token), v -> auth.token = v);
            }
            case APIKEY -> {
                explanation.setText("Adds a key/value pair to the headers or to the query string.");
                field(0, "Key", new TextField(auth.apiKey), v -> auth.apiKey = v);
                field(1, "Value", new TextField(auth.apiValue), v -> auth.apiValue = v);
                ComboBox<String> in = combo(List.of("header", "query"), auth.apiIn == null ? "header" : auth.apiIn, v -> auth.apiIn = v, null);
                form.add(new Label("Add to"), 0, 2);
                form.add(in, 1, 2);
            }
            case OAUTH2, OIDC -> renderOAuth2();
        }
    }

    // ------------------------------------------------------------------ OAuth 2.0

    private boolean isOidc() {
        return auth.type == AuthType.OIDC;
    }

    private void renderOAuth2() {
        OAuth2 o = auth.oauth2;
        int r = 0;
        if (isOidc()) {
            explanation.setText("OpenID Connect signs you in with your identity provider (Keycloak, Entra ID, Auth0, Okta, Google...). "
                    + "Enter the issuer or its .well-known/openid-configuration URL and click Discover: endpoints and options are filled in. "
                    + "The \"openid\" scope is always requested and the ID token's nonce, issuer and audience are checked. "
                    + "Configure it on the collection or a folder to share one token between requests.");
            r = section(r, "PROVIDER");
            TextField discovery = new TextField(o.discoveryUrl == null ? "" : o.discoveryUrl);
            discovery.setPromptText("https://login.example.com/realms/demo   or   .../.well-known/openid-configuration");
            discovery.textProperty().addListener((obs, a, b) -> {
                o.discoveryUrl = b;
                onChange.run();
            });
            Button discover = new Button("Discover");
            discover.setTooltip(new Tooltip("Read the provider's .well-known/openid-configuration and fill in the endpoints"));
            discover.setOnAction(e -> runDiscovery());
            HBox.setHgrow(discovery, Priority.ALWAYS);
            HBox discoveryBox = new HBox(8, discovery, discover);
            form.add(new Label("Discovery URL"), 0, r);
            form.add(discoveryBox, 1, r++);
            discoveryInfo.setWrapText(true);
            discoveryInfo.getStyleClass().setAll("label", "hint");
            discoveryInfo.setText(o.issuer == null || o.issuer.isBlank() ? "Not discovered yet"
                    : "Issuer " + o.issuer + (o.userInfoUrl == null || o.userInfoUrl.isBlank() ? "" : " · UserInfo " + o.userInfoUrl));
            form.add(discoveryInfo, 1, r++);
        } else {
            explanation.setText("PostFX gets an access token from your authorization server and adds it to every request using this auth. "
                    + "Expired tokens are refreshed automatically (refresh token, or a new token for Client Credentials / Password). "
                    + "All fields accept {{variables}}. Configure it on the collection or a folder to share one token between requests.");
        }
        r = section(r, "CURRENT TOKEN");
        form.add(topLabel("Access Token"), 0, r);
        tokenArea.textProperty().removeListener(tokenListener);
        tokenArea.setText(o.accessToken);
        tokenArea.textProperty().addListener(tokenListener);
        form.add(tokenArea, 1, r++);
        form.add(tokenInfo, 1, r++);
        HBox buttons = new HBox(8, getToken, refreshToken, clearToken, cancelLogin);
        buttons.setAlignment(Pos.CENTER_LEFT);
        form.add(buttons, 1, r++);
        form.add(oauthStatus, 1, r++);
        showToken();
        if (isOidc()) {
            form.add(topLabel("ID Token claims"), 0, r);
            claimsArea.setEditable(false);
            claimsArea.setPrefRowCount(6);
            claimsArea.getStyleClass().add("mono");
            claimsArea.setPromptText("Claims of the ID token appear here after sign-in (decoded, signature not verified)");
            form.add(claimsArea, 1, r++);
            Button userInfo = new Button("User info");
            userInfo.setTooltip(new Tooltip("Call the provider's UserInfo endpoint with the access token"));
            userInfo.setOnAction(e -> showUserInfo());
            ComboBox<String> send = combo(List.of("access_token", "id_token"), o.tokenToSend, v -> o.tokenToSend = v, new StringConverter<>() {
                @Override
                public String toString(String v) {
                    return "id_token".equals(v) ? "Send the ID token" : "Send the access token";
                }

                @Override
                public String fromString(String v) {
                    return v;
                }
            });
            HBox oidcBox = new HBox(8, send, userInfo);
            oidcBox.setAlignment(Pos.CENTER_LEFT);
            form.add(new Label("Token to send"), 0, r);
            form.add(oidcBox, 1, r++);
            showClaims();
        }

        r = textRow(r, "Header Prefix", o.headerPrefix, v -> o.headerPrefix = v, "Bearer");
        ComboBox<String> addTo = combo(List.of("header", "query"), o.addTokenTo, v -> {
            o.addTokenTo = v;
            render();
        }, null);
        form.add(new Label("Add token to"), 0, r);
        form.add(addTo, 1, r++);
        if ("query".equals(o.addTokenTo)) r = textRow(r, "Query parameter", o.queryParamName, v -> o.queryParamName = v, "access_token");
        CheckBox auto = new CheckBox("Fetch / refresh the token automatically when it is missing or expired");
        auto.setSelected(o.autoRefresh);
        auto.selectedProperty().addListener((obs, a, b) -> {
            o.autoRefresh = b;
            onChange.run();
        });
        form.add(auto, 1, r++);

        r = section(r, "CONFIGURE NEW TOKEN");
        ComboBox<String> grant = combo(GRANTS, o.grantType, v -> {
            o.grantType = v;
            render();
        }, new StringConverter<>() {
            @Override
            public String toString(String s) {
                return OAuth2.label(s);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        form.add(new Label("Grant Type"), 0, r);
        form.add(grant, 1, r++);

        boolean code = o.grantType.startsWith("authorization_code");
        boolean implicit = OAuth2.IMPLICIT.equals(o.grantType);
        boolean password = OAuth2.PASSWORD.equals(o.grantType);
        if (code || implicit) {
            r = textRow(r, "Callback URL", o.redirectUri, v -> o.redirectUri = v, "http://localhost:8765/callback");
            r = hint(r, "Register this redirect URL at your provider. A http://localhost URL is captured automatically; "
                    + "with any other URL you paste the address shown by the browser after login.");
            r = textRow(r, "Auth URL", o.authUrl, v -> o.authUrl = v, "https://id.example.com/oauth2/authorize");
        }
        if (!implicit) r = textRow(r, "Access Token URL", o.accessTokenUrl, v -> o.accessTokenUrl = v, "https://id.example.com/oauth2/token");
        r = textRow(r, "Client ID", o.clientId, v -> o.clientId = v, "{{clientId}}");
        if (!implicit) r = textRow(r, "Client Secret", o.clientSecret, v -> o.clientSecret = v, o.isPkce() ? "optional for public clients" : "{{clientSecret}}");
        if (o.isPkce()) {
            form.add(new Label("Code Challenge Method"), 0, r);
            form.add(combo(List.of("S256", "plain"), o.challengeAlgorithm, v -> o.challengeAlgorithm = v, null), 1, r++);
        }
        if (password) {
            r = textRow(r, "Username", o.username, v -> o.username = v, "{{username}}");
            r = textRow(r, "Password", o.password, v -> o.password = v, "{{password}}");
        }
        r = textRow(r, "Scope", o.scope, v -> o.scope = v, "e.g. openid profile read:items");
        r = textRow(r, "Audience", o.audience, v -> o.audience = v, "optional (Auth0, ...)");
        if (code || implicit) r = textRow(r, "State", o.state, v -> o.state = v, "random when empty");
        if (!implicit) {
            ComboBox<String> clientAuth = combo(List.of("header", "body"), o.clientAuthentication, v -> o.clientAuthentication = v,
                    new StringConverter<>() {
                        @Override
                        public String toString(String s) {
                            return "body".equals(s) ? "Send client credentials in body" : "Send as Basic Auth header";
                        }

                        @Override
                        public String fromString(String s) {
                            return s;
                        }
                    });
            form.add(new Label("Client Authentication"), 0, r);
            form.add(clientAuth, 1, r++);
            r = textRow(r, "Refresh Token URL", o.refreshTokenUrl, v -> o.refreshTokenUrl = v, "optional, defaults to the Access Token URL");
        }
        Button help = new Button("? OAuth 2.0 help");
        help.getStyleClass().add("link-button");
        help.setOnAction(e -> HelpWindow.show(getScene().getWindow(), "OAuth 2.0"));
        form.add(help, 1, r);
    }

    private final javafx.beans.value.ChangeListener<String> tokenListener = (obs, a, b) -> {
        if (auth.oauth2 == null || b.equals(auth.oauth2.accessToken)) return;
        auth.oauth2.accessToken = b.strip();
        auth.oauth2.expiresAt = 0; // pasted token: expiry unknown
        showToken();
        onChange.run();
    };

    private void showClaims() {
        com.fasterxml.jackson.databind.JsonNode claims = io.postfx.engine.Jwt.payload(auth.oauth2.idToken);
        claimsArea.setText(claims == null ? "" : io.postfx.engine.JsonUtil.toPrettyJson(claims));
    }

    private void runDiscovery() {
        if (ctx == null) return;
        OAuth2 o = auth.oauth2;
        VariableContext vars = variables.get();
        discoveryInfo.getStyleClass().setAll("label", "hint");
        discoveryInfo.setText("Reading the discovery document…");
        ctx.background.submit(() -> {
            try {
                String summary = OAuth2Client.forAuth(ctx.http, ctx.workspace.settings, auth).discover(o, vars, m -> {});
                Platform.runLater(() -> {
                    render();
                    onChange.run();
                    ctx.status.set(summary);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    discoveryInfo.setText("✘ " + ex.getMessage());
                    discoveryInfo.getStyleClass().setAll("label", "fail");
                });
            }
        });
    }

    private void showUserInfo() {
        if (ctx == null) return;
        VariableContext vars = variables.get();
        ctx.background.submit(() -> {
            try {
                String body = OAuth2Client.forAuth(ctx.http, ctx.workspace.settings, auth).userInfo(auth.oauth2, vars);
                Platform.runLater(() -> Dialogs.info("User info", io.postfx.engine.JsonUtil.prettyOrSame(body)));
            } catch (Exception ex) {
                Platform.runLater(() -> Dialogs.error("User info", ex.getMessage()));
            }
        });
    }

    private void showToken() {
        OAuth2 o = auth.oauth2;
        StringBuilder sb = new StringBuilder();
        if (!o.hasToken()) {
            sb.append("No access token");
        } else {
            sb.append(o.tokenType == null || o.tokenType.isBlank() ? "Token" : o.tokenType);
            if (o.expiresAt > 0) {
                long mins = (o.expiresAt - System.currentTimeMillis()) / 60000;
                sb.append(o.isExpired() ? " · expired at " : " · expires in " + Math.max(0, mins) + " min (")
                        .append(TIME.format(Instant.ofEpochMilli(o.expiresAt))).append(o.isExpired() ? "" : ")");
            } else {
                sb.append(" · expiry unknown");
            }
            if (o.refreshToken != null && !o.refreshToken.isBlank()) sb.append(" · refresh token available");
        }
        tokenInfo.setText(sb.toString());
        refreshToken.setDisable(o.refreshToken == null || o.refreshToken.isBlank());
        clearToken.setDisable(!o.hasToken());
        if (!tokenArea.getText().equals(o.accessToken == null ? "" : o.accessToken)) {
            tokenArea.textProperty().removeListener(tokenListener);
            tokenArea.setText(o.accessToken);
            tokenArea.textProperty().addListener(tokenListener);
        }
    }

    private void requestToken(boolean refresh) {
        if (ctx == null) return;
        OAuth2 o = auth.oauth2;
        VariableContext vars = variables.get();
        OAuth2Client client = OAuth2Client.forAuth(ctx.http, ctx.workspace.settings, auth);
        pendingClient = client;
        setBusy(true, refresh ? "Refreshing the token…" : o.isInteractive()
                ? "Complete the login in your browser… (" + OAuth2Client.BROWSER_TIMEOUT_SECONDS / 60 + " min timeout)" : "Requesting a token…");
        Consumer<String> log = msg -> Platform.runLater(() -> ctx.status.set(msg));
        ctx.background.submit(() -> {
            try {
                if (refresh) client.refresh(o, vars, log);
                else client.obtain(o, vars, interaction(), log);
                Platform.runLater(() -> {
                    setBusy(false, "✔ Token received");
                    oauthStatus.getStyleClass().setAll("label", "pass");
                    showToken();
                    if (isOidc()) render();
                    onChange.run();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setBusy(false, "✘ " + ex.getMessage());
                    oauthStatus.getStyleClass().setAll("label", "fail");
                });
            }
        });
    }

    private void setBusy(boolean busy, String message) {
        getToken.setDisable(busy);
        refreshToken.setDisable(busy || auth.oauth2.refreshToken == null || auth.oauth2.refreshToken.isBlank());
        cancelLogin.setVisible(busy && auth.oauth2.isInteractive());
        oauthStatus.getStyleClass().setAll("label");
        oauthStatus.setText(message);
    }

    private OAuth2Client.Interaction interaction() {
        return new OAuth2Client.Interaction() {
            @Override
            public void openBrowser(String url) {
                Platform.runLater(() -> ctx.openBrowser(url));
            }

            @Override
            public String askRedirectedUrl(String authorizeUrl, String redirectUri) {
                CompletableFuture<String> answer = new CompletableFuture<>();
                Platform.runLater(() -> {
                    TextInputDialog d = new TextInputDialog();
                    if (getScene() != null) d.initOwner(getScene().getWindow());
                    d.setTitle("OAuth 2.0 login");
                    d.setHeaderText("Log in in the browser window that just opened.\nYou are then redirected to " + redirectUri
                            + "\nCopy the full address from the browser's address bar and paste it here.");
                    d.setContentText("Redirected URL");
                    d.getEditor().setPrefColumnCount(50);
                    answer.complete(d.showAndWait().orElse(null));
                });
                return answer.join();
            }
        };
    }

    // ------------------------------------------------------------------ form helpers

    /** Label aligned with the first line of a multi-line field. */
    private static Label topLabel(String text) {
        Label l = new Label(text);
        GridPane.setValignment(l, javafx.geometry.VPos.TOP);
        l.setPadding(new Insets(6, 0, 0, 0));
        return l;
    }

    private int section(int row, String title) {
        Label l = new Label(title);
        l.getStyleClass().add("section-title");
        l.setPadding(new Insets(8, 0, 0, 0));
        form.add(l, 0, row, 2, 1);
        return row + 1;
    }

    private int hint(int row, String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        form.add(l, 1, row);
        return row + 1;
    }

    private int textRow(int row, String label, String value, Consumer<String> setter, String prompt) {
        TextField f = new TextField(value == null ? "" : value);
        f.setPromptText(prompt);
        f.textProperty().addListener((o, a, b) -> {
            setter.accept(b);
            onChange.run();
        });
        form.add(new Label(label), 0, row);
        form.add(f, 1, row);
        return row + 1;
    }

    private ComboBox<String> combo(List<String> values, String current, Consumer<String> setter, StringConverter<String> converter) {
        ComboBox<String> c = new ComboBox<>();
        c.getItems().addAll(values);
        if (converter != null) c.setConverter(converter);
        c.setValue(current == null || !values.contains(current) ? values.get(0) : current);
        c.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            setter.accept(b);
            onChange.run();
        });
        return c;
    }

    private void field(int row, String label, TextInputControl f, Consumer<String> setter) {
        field(row, label, f, setter, null);
    }

    private void field(int row, String label, TextInputControl f, Consumer<String> setter, String initial) {
        if (initial != null) f.setText(initial);
        f.setPromptText(label);
        f.textProperty().addListener((o, a, b) -> {
            setter.accept(b);
            onChange.run();
        });
        form.add(new Label(label), 0, row);
        form.add(f, 1, row);
    }
}
