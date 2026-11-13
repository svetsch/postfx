package io.postfx.ui;

import java.util.List;

/** Ready-made code snippets shown next to the script editors. */
public final class Snippets {
    public record Snippet(String title, String description, String code) {
        @Override
        public String toString() {
            return title;
        }
    }

    private Snippets() {}

    public static final List<Snippet> PRE_REQUEST = List.of(
            new Snippet("Set an environment variable", "Stores a value in the active environment",
                    "pm.environment.set(\"variable_key\", \"variable_value\");"),
            new Snippet("Set a collection variable", "Stores a value in the collection scope",
                    "pm.collectionVariables.set(\"variable_key\", \"variable_value\");"),
            new Snippet("Set a local variable", "Only visible during this request (or the current run)",
                    "pm.variables.set(\"variable_key\", \"variable_value\");"),
            new Snippet("Get a variable", "Resolves local > data > environment > collection > global",
                    "const value = pm.variables.get(\"variable_key\");"),
            new Snippet("Timestamp variable", "Current time in milliseconds",
                    "pm.variables.set(\"timestamp\", Date.now().toString());"),
            new Snippet("Random id", "Random UUID",
                    "pm.variables.set(\"requestId\", require('uuid').v4());"),
            new Snippet("Add a header", "Adds a header to the request about to be sent",
                    "pm.request.headers.add({ key: \"X-Custom\", value: \"value\" });"),
            new Snippet("Add a query parameter", "Adds a query parameter to the URL",
                    "pm.request.url.query.add({ key: \"param\", value: \"value\" });"),
            new Snippet("HMAC signature", "Signs the body with HMAC-SHA256 (CryptoJS is built in)",
                    """
                    const secret = pm.environment.get("apiSecret");
                    const payload = Date.now() + (pm.request.body.raw || "");
                    const signature = CryptoJS.HmacSHA256(payload, secret).toString(CryptoJS.enc.Base64);
                    pm.request.headers.upsert({ key: "X-Signature", value: signature });"""),
            new Snippet("Basic auth header", "Builds an Authorization header with btoa",
                    "pm.request.headers.upsert({ key: \"Authorization\", value: \"Basic \" + btoa(pm.environment.get(\"user\") + \":\" + pm.environment.get(\"password\")) });"),
            new Snippet("Fetch a token (sendRequest)", "Calls a login endpoint and stores the token",
                    """
                    pm.sendRequest({
                        url: pm.variables.replaceIn("{{baseUrl}}/login"),
                        method: "POST",
                        header: { "Content-Type": "application/json" },
                        body: { mode: "raw", raw: JSON.stringify({ user: pm.environment.get("user"), password: pm.environment.get("password") }) }
                    }, function (err, res) {
                        if (err) { console.error(err); return; }
                        pm.environment.set("token", res.json().token);
                    });"""),
            new Snippet("Skip this request", "Skips the request in the runner when a condition is met",
                    """
                    if (!pm.environment.get("featureEnabled")) {
                        pm.execution.skipRequest();
                    }"""),
            new Snippet("Log to console", "Writes to the Console tab", "console.log(\"value:\", pm.variables.get(\"variable_key\"));")
    );

    public static final List<Snippet> TESTS = List.of(
            new Snippet("Status code is 200", "Asserts the HTTP status code",
                    """
                    pm.test("Status code is 200", function () {
                        pm.response.to.have.status(200);
                    });"""),
            new Snippet("Status code is 2xx", "Any success code",
                    """
                    pm.test("Successful response", function () {
                        pm.response.to.be.success;
                    });"""),
            new Snippet("Response time below 500 ms", "Performance check",
                    """
                    pm.test("Response time is less than 500ms", function () {
                        pm.expect(pm.response.responseTime).to.be.below(500);
                    });"""),
            new Snippet("JSON value check", "Compares a field of the JSON body",
                    """
                    pm.test("Value is correct", function () {
                        const json = pm.response.json();
                        pm.expect(json.value).to.eql(100);
                    });"""),
            new Snippet("Body contains string", "Searches the body text",
                    """
                    pm.test("Body contains string", function () {
                        pm.expect(pm.response.text()).to.include("string_you_want_to_search");
                    });"""),
            new Snippet("Header is present", "Checks a response header",
                    """
                    pm.test("Content-Type is present", function () {
                        pm.response.to.have.header("Content-Type");
                    });"""),
            new Snippet("Array length and content", "Checks a JSON array",
                    """
                    pm.test("Returns items", function () {
                        const items = pm.response.json();
                        pm.expect(items).to.be.an("array").that.is.not.empty;
                        pm.expect(items[0]).to.have.property("id");
                    });"""),
            new Snippet("JSON schema", "Validates the body against a JSON schema",
                    """
                    const schema = {
                        type: "object",
                        required: ["id", "name"],
                        properties: {
                            id: { type: "integer" },
                            name: { type: "string" }
                        }
                    };
                    pm.test("Schema is valid", function () {
                        pm.response.to.have.jsonSchema(schema);
                    });"""),
            new Snippet("Save a value for the next request", "Chains requests through a variable",
                    """
                    const json = pm.response.json();
                    pm.environment.set("token", json.token);"""),
            new Snippet("Next request (runner flow)", "Jumps to another request in the collection runner",
                    "pm.execution.setNextRequest(\"Request name\");"),
            new Snippet("Stop the run", "Ends the runner iteration after this request",
                    "pm.execution.setNextRequest(null);"),
            new Snippet("Retry until a condition", "Polls the same request until the job is done",
                    """
                    const status = pm.response.json().status;
                    if (status !== "DONE") {
                        pm.execution.setNextRequest(pm.info.requestName);
                    }"""),
            new Snippet("Use iteration data", "Reads a column of the runner data file",
                    """
                    pm.test("Matches data file", function () {
                        pm.expect(pm.response.json().name).to.eql(pm.iterationData.get("name"));
                    });"""),
            new Snippet("Parse XML", "Converts an XML body to JSON",
                    """
                    const data = xml2Json(pm.response.text());
                    console.log(data);"""),
            new Snippet("Cookie is present", "Checks a cookie set by the response",
                    """
                    pm.test("Session cookie is set", function () {
                        pm.expect(pm.cookies.has("session")).to.be.true;
                    });""")
    );
}
