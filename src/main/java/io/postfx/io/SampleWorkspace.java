package io.postfx.io;

import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.BodyMode;
import io.postfx.model.Check;
import io.postfx.model.CheckOperator;
import io.postfx.model.CheckSource;
import io.postfx.model.Collection;
import io.postfx.model.Environment;
import io.postfx.model.ExtractSource;
import io.postfx.model.Extractor;
import io.postfx.model.Folder;
import io.postfx.model.KeyValue;
import io.postfx.model.RequestItem;
import io.postfx.model.VarScope;
import io.postfx.model.Workspace;

/** The example workspace created on first start: a guided tour of every feature using public demo APIs. */
public final class SampleWorkspace {
    private SampleWorkspace() {}

    public static Workspace create() {
        Workspace ws = new Workspace();
        Environment env = new Environment("Public demo APIs");
        env.values.add(new KeyValue("echoUrl", "https://postman-echo.com"));
        env.values.add(new KeyValue("jsonUrl", "https://jsonplaceholder.typicode.com"));
        ws.environments.add(env);
        ws.activeEnvironmentId = env.id;
        ws.globals.add(new KeyValue("appName", "PostFX"));
        ws.collections.add(examples());
        return ws;
    }

    public static Collection examples() {
        Collection c = new Collection("PostFX Examples");
        c.description = """
                A guided tour of PostFX. Select the "Public demo APIs" environment, then open the requests in order.
                Each request has a Docs tab explaining what it shows.

                1 - Basics: methods, params, headers, bodies, auth, path variables, dynamic variables
                2 - Checks & extractors: no-code assertions and variable extraction to chain requests
                3 - Scripts: Postman compatible pm.* scripts (pre-request and tests)
                4 - Flow control: pm.execution.setNextRequest() loops in the collection runner

                Run everything with the Runner (Run button on the collection) to see the chain in action.""";
        c.variables.add(new KeyValue("defaultTimeoutMs", "5000"));
        c.testScript = """
                // Collection level test script: runs after EVERY request of this collection.
                pm.test("Response time is acceptable", function () {
                    pm.expect(pm.response.responseTime).to.be.below(Number(pm.variables.get("defaultTimeoutMs")));
                });""";

        // ------------------------------------------------------------------ 1 - Basics
        Folder basics = new Folder("1 - Basics");
        basics.description = "The building blocks of a request.";

        RequestItem get = new RequestItem("GET with query params", "GET", "{{echoUrl}}/get?search=postfx&page=1");
        get.request.disabledParams.add(new KeyValue("debug", "true", false));
        get.description = """
                The Params tab edits the query string of the URL. Unticked params are kept but not sent.
                {{echoUrl}} comes from the active environment; hover a variable in the URL bar to see its value.""";
        get.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "200"));
        get.checks.add(new Check(CheckSource.JSON_PATH, "args.search", CheckOperator.EQUALS, "postfx"));

        RequestItem post = new RequestItem("POST JSON body", "POST", "{{echoUrl}}/post");
        post.request.body.mode = BodyMode.RAW;
        post.request.body.rawLanguage = "json";
        post.request.body.raw = """
                {
                  "id": "{{$guid}}",
                  "createdAt": "{{$isoTimestamp}}",
                  "user": "{{$randomFullName}}",
                  "app": "{{appName}}"
                }""";
        post.description = """
                Raw JSON body. Content-Type: application/json is added automatically.
                {{$guid}}, {{$isoTimestamp}} and {{$randomFullName}} are dynamic variables, generated for each send.
                {{appName}} is a global variable (Environments > Globals).""";
        post.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "200"));
        post.checks.add(new Check(CheckSource.JSON_PATH, "json.app", CheckOperator.EQUALS, "{{appName}}"));
        post.checks.add(new Check(CheckSource.JSON_PATH, "json.id", CheckOperator.MATCHES, "^[0-9a-f-]{36}$"));

        RequestItem form = new RequestItem("POST form (urlencoded)", "POST", "{{echoUrl}}/post");
        form.request.body.mode = BodyMode.URLENCODED;
        form.request.body.urlencoded.add(new KeyValue("username", "ada"));
        form.request.body.urlencoded.add(new KeyValue("language", "Java & JavaScript"));
        form.description = "x-www-form-urlencoded body: values are encoded for you.";
        form.checks.add(new Check(CheckSource.JSON_PATH, "form.language", CheckOperator.EQUALS, "Java & JavaScript"));

        RequestItem headers = new RequestItem("Custom headers", "GET", "{{echoUrl}}/headers");
        headers.request.headers.add(new KeyValue("X-Request-Id", "{{$guid}}"));
        headers.request.headers.add(new KeyValue("X-Client", "{{appName}}"));
        headers.description = "Headers support variables too. postman-echo returns the headers it received.";
        headers.checks.add(new Check(CheckSource.JSON_PATH, "headers.x-client", CheckOperator.EQUALS, "PostFX"));

        RequestItem basic = new RequestItem("Basic auth", "GET", "{{echoUrl}}/basic-auth");
        basic.request.auth = new Auth(AuthType.BASIC);
        basic.request.auth.username = "postman";
        basic.request.auth.password = "password";
        basic.description = """
                The Auth tab builds the Authorization header. Requests can also inherit auth from their folder or
                collection ("Inherit auth from parent"), which is the default for new requests.""";
        basic.checks.add(new Check(CheckSource.JSON_PATH, "authenticated", CheckOperator.EQUALS, "true"));

        RequestItem status = new RequestItem("Path variable", "GET", "{{echoUrl}}/status/:code");
        status.request.pathVariables.add(new KeyValue("code", "201"));
        status.description = "':code' in the URL is a path variable. Its value is set in the Params tab.";
        status.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "201"));

        basics.items.add(get);
        basics.items.add(post);
        basics.items.add(form);
        basics.items.add(headers);
        basics.items.add(basic);
        basics.items.add(status);

        // ------------------------------------------------------------------ 2 - Checks & extractors
        Folder chain = new Folder("2 - Checks & extractors");
        chain.description = """
                Build test chains without code: Checks assert on the response, Extract copies response values
                into variables used by the next requests. Run the folder with the Runner to see the chain.""";

        RequestItem users = new RequestItem("List users", "GET", "{{jsonUrl}}/users");
        users.description = """
                Checks tab: the status, the array length and the type of a field.
                Extract tab: '$[0].id' is saved into the environment variable 'userId',
                then used by the next requests as {{userId}}.""";
        users.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "200"));
        users.checks.add(new Check(CheckSource.JSON_PATH, "$.length", CheckOperator.GREATER_OR_EQUAL, "5"));
        users.checks.add(new Check(CheckSource.JSON_PATH, "$[0].email", CheckOperator.TYPE_IS, "string"));
        users.checks.add(new Check(CheckSource.HEADER, "Content-Type", CheckOperator.CONTAINS, "application/json"));
        users.extractors.add(new Extractor("userId", ExtractSource.JSON_PATH, "$[0].id", VarScope.ENVIRONMENT));
        users.extractors.add(new Extractor("userName", ExtractSource.JSON_PATH, "$[0].username", VarScope.ENVIRONMENT));

        RequestItem user = new RequestItem("Get the user", "GET", "{{jsonUrl}}/users/:id");
        user.request.pathVariables.add(new KeyValue("id", "{{userId}}"));
        user.description = "Uses {{userId}} extracted by 'List users' as a path variable.";
        user.checks.add(new Check(CheckSource.JSON_PATH, "id", CheckOperator.EQUALS, "{{userId}}"));
        user.checks.add(new Check(CheckSource.JSON_PATH, "username", CheckOperator.EQUALS, "{{userName}}"));
        user.checks.add(new Check(CheckSource.JSON_PATH, "address.city", CheckOperator.IS_NOT_EMPTY, ""));

        RequestItem posts = new RequestItem("Posts of the user", "GET", "{{jsonUrl}}/posts?userId={{userId}}");
        posts.description = "Filters posts by the extracted user id and extracts the first post id.";
        posts.checks.add(new Check(CheckSource.JSON_PATH, "$[*].userId", CheckOperator.CONTAINS, "{{userId}}"));
        posts.checks.add(new Check(CheckSource.RESPONSE_TIME, "", CheckOperator.LESS_THAN, "5000"));
        posts.extractors.add(new Extractor("postId", ExtractSource.JSON_PATH, "$[0].id", VarScope.ENVIRONMENT));

        RequestItem create = new RequestItem("Create a comment", "POST", "{{jsonUrl}}/comments");
        create.request.body.mode = BodyMode.RAW;
        create.request.body.raw = """
                {
                  "postId": {{postId}},
                  "name": "Written by {{userName}}",
                  "body": "Created by the PostFX example chain"
                }""";
        create.description = "Uses the two extracted variables in a JSON body. jsonplaceholder answers 201 Created.";
        create.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "201"));
        create.checks.add(new Check(CheckSource.JSON_PATH, "postId", CheckOperator.EQUALS, "{{postId}}"));
        create.checks.add(new Check(CheckSource.JSON_PATH, "id", CheckOperator.EXISTS, ""));

        chain.items.add(users);
        chain.items.add(user);
        chain.items.add(posts);
        chain.items.add(create);

        // ------------------------------------------------------------------ 3 - Scripts
        Folder scripts = new Folder("3 - Scripts");
        scripts.description = "Postman compatible JavaScript. Use the snippets list next to the editors to insert common code.";

        RequestItem signed = new RequestItem("Pre-request: signature", "POST", "{{echoUrl}}/post");
        signed.request.body.mode = BodyMode.RAW;
        signed.request.body.raw = "{\"amount\": 42, \"currency\": \"CHF\"}";
        signed.request.headers.add(new KeyValue("X-Timestamp", "{{timestamp}}"));
        signed.request.headers.add(new KeyValue("X-Signature", "{{signature}}"));
        signed.preRequestScript = """
                // Runs before the request is sent.
                const timestamp = Date.now().toString();
                const body = pm.request.body.raw;
                // HMAC-SHA256 with CryptoJS (built in)
                const signature = CryptoJS.HmacSHA256(timestamp + body, "my-secret").toString(CryptoJS.enc.Hex);

                pm.variables.set("timestamp", timestamp);   // local variable: only for this request/run
                pm.variables.set("signature", signature);

                // The request itself can be modified too
                pm.request.headers.add({ key: "X-Script", value: "added by the pre-request script" });
                console.log("Signed payload at", timestamp);""";
        signed.testScript = """
                const json = pm.response.json();

                pm.test("Status code is 200", function () {
                    pm.response.to.have.status(200);
                });

                pm.test("Signature header was sent", function () {
                    pm.expect(json.headers["x-signature"]).to.have.lengthOf(64);
                    pm.expect(json.headers["x-script"]).to.eql("added by the pre-request script");
                });

                pm.test("Body echoed back", function () {
                    pm.expect(json.json).to.deep.equal({ amount: 42, currency: "CHF" });
                });""";
        signed.description = "A pre-request script computes an HMAC signature, stores it in variables and adds a header.";

        RequestItem tests = new RequestItem("Tests: assertions tour", "GET", "{{jsonUrl}}/users/1");
        tests.testScript = """
                const user = pm.response.json();

                pm.test("Status and headers", () => {
                    pm.response.to.have.status(200);
                    pm.response.to.be.json;
                    pm.response.to.have.header("Content-Type");
                });

                pm.test("Chai style assertions", () => {
                    pm.expect(user).to.be.an("object");
                    pm.expect(user).to.have.property("id", 1);
                    pm.expect(user.email).to.match(/@/);
                    pm.expect(user.address).to.have.all.keys("street", "suite", "city", "zipcode", "geo");
                    pm.expect(user.name).to.be.a("string").and.not.be.empty;
                    pm.expect(user.id).to.be.oneOf([1, 2, 3]);
                });

                pm.test("JSON schema", () => {
                    pm.response.to.have.jsonSchema({
                        type: "object",
                        required: ["id", "name", "email"],
                        properties: {
                            id: { type: "integer" },
                            email: { type: "string", pattern: "@" }
                        }
                    });
                });

                // Save values for the next requests
                pm.environment.set("companyName", user.company.name);
                console.log("Company:", user.company.name);""";
        tests.description = "A tour of the assertion API: pm.response.to..., pm.expect(...) chains and JSON schema.";

        RequestItem send = new RequestItem("pm.sendRequest", "GET", "{{echoUrl}}/get?token={{authToken}}");
        send.preRequestScript = """
                // Fetch a value from another endpoint before sending this request
                pm.sendRequest({
                    url: pm.variables.replaceIn("{{echoUrl}}/post"),
                    method: "POST",
                    header: { "Content-Type": "application/json" },
                    body: { mode: "raw", raw: JSON.stringify({ token: "token-" + Date.now() }) }
                }, function (err, res) {
                    if (err) { console.error(err); return; }
                    pm.variables.set("authToken", res.json().json.token);
                });""";
        send.checks.add(new Check(CheckSource.JSON_PATH, "args.token", CheckOperator.MATCHES, "^token-\\d+$"));
        send.description = "pm.sendRequest() calls another endpoint from a script (here to obtain a token).";

        scripts.items.add(signed);
        scripts.items.add(tests);
        scripts.items.add(send);

        // ------------------------------------------------------------------ 4 - Flow control
        Folder flow = new Folder("4 - Flow control");
        flow.description = "pm.execution.setNextRequest() changes the order of the collection runner (polling, retries, branches).";
        RequestItem poll = new RequestItem("Poll until ready", "GET", "{{echoUrl}}/get?attempt={{attempt}}");
        poll.preRequestScript = """
                const attempt = Number(pm.collectionVariables.get("attempt") || 0) + 1;
                pm.collectionVariables.set("attempt", attempt);""";
        poll.testScript = """
                const attempt = Number(pm.collectionVariables.get("attempt"));
                pm.test("Attempt " + attempt + " answered", () => pm.response.to.have.status(200));

                if (attempt < 3) {
                    // run this same request again (only in the Runner)
                    pm.execution.setNextRequest(pm.info.requestName);
                } else {
                    pm.collectionVariables.unset("attempt");
                    pm.execution.setNextRequest("Done");
                }""";
        poll.description = "Run this folder with the Runner: the request repeats 3 times, then jumps to 'Done'.";
        RequestItem skipped = new RequestItem("Skipped by the loop", "GET", "{{echoUrl}}/status/500");
        skipped.description = "Never executed by the runner because 'Poll until ready' jumps over it.";
        RequestItem done = new RequestItem("Done", "GET", "{{echoUrl}}/get?done=true");
        done.checks.add(new Check(CheckSource.JSON_PATH, "args.done", CheckOperator.EQUALS, "true"));
        flow.items.add(poll);
        flow.items.add(skipped);
        flow.items.add(done);

        c.items.add(basics);
        c.items.add(chain);
        c.items.add(scripts);
        c.items.add(flow);
        return c;
    }
}
