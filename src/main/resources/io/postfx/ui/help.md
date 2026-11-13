# Getting started
PostFX is an API client and test runner compatible with Postman collections. Everything you edit is saved automatically in your workspace.

## A first request in 30 seconds
1. Click **New request** (Ctrl+T), type a URL such as `https://postman-echo.com/get?hello=world` and press **Send** (Ctrl+Enter).
2. The response appears below: status, time, size, body (Pretty, Raw or Tree), headers and test results.
3. Click **Save** to store it in a collection.

## Explore the examples
The **PostFX Examples** collection (created at first start) is a guided tour. Select the **Public demo APIs** environment in the selector at the top right, then open the requests in order. Each request has a **Docs** tab explaining what it demonstrates:
- **1 - Basics**: query params, JSON and form bodies, headers, basic auth, path variables, dynamic variables.
- **2 - Checks & extractors**: a test chain built without code.
- **3 - Scripts**: Postman compatible pre-request and test scripts.
- **4 - Flow control**: loops with `pm.execution.setNextRequest()` in the runner.

Use **Help > Restore example collection** if you delete it.

## Screen layout
- **Left**: Collections, Environments and History.
- **Center**: one tab per open request, folder, environment or runner.
- **Top right**: active environment selector and quick look at its variables.
- **Bottom**: status bar (last result, save status).

# Requests
## URL, method and params
The **Params** tab edits the query string of the URL: both stay in sync. Untick a param to keep it without sending it. Path variables written as `/users/:id` get their own table in the Params tab.

Under the URL bar PostFX shows the **resolved URL** (variables replaced). Hover the URL field to see the value of every variable used.

Tip: paste a **cURL command** directly into the URL field to import it.

## Headers
Headers support `{{variables}}`. PostFX adds `User-Agent`, `Accept`, `Content-Type` (for bodies) and auth headers when you did not set them.

## Body
- **raw**: JSON, text, XML, HTML or JavaScript. The language sets the default Content-Type. **Beautify** formats JSON.
- **x-www-form-urlencoded**: key/value pairs, encoded for you.
- **form-data**: multipart; switch a row to **File** to upload a file.
- **GraphQL**: query + variables (sent as JSON).
- **binary**: the content of a file.

## Auth
Basic, Bearer token, API key (header or query) and **OAuth 2.0** (see the OAuth 2.0 page). New requests use **Inherit auth from parent**: configure auth once on the collection or a folder (double-click it in the tree) and every request below inherits it.

## Bulk edit
Key/value tables have a **Bulk edit** toggle: one `key: value` per line, prefix a line with `//` to disable it.

## Code generation
**</> Code** shows the request as a cURL command, a Java (JUnit + REST Assured) test, or Postman JSON.

# OAuth 2.0
Select **OAuth 2.0** in the Auth tab of a collection, folder or request. Configuring it on the collection is recommended: every request inherits it and they share one token.

## Grant types
- **Client Credentials**: machine-to-machine. Needs the Access Token URL, Client ID and Client Secret.
- **Password Credentials**: adds Username and Password (legacy, avoid for new APIs).
- **Authorization Code**: interactive login in your browser. Needs the Auth URL, Access Token URL, Client ID, Client Secret and Callback URL.
- **Authorization Code (With PKCE)**: same with a code challenge (`S256` or `plain`), the recommended flow for public clients; the Client Secret is optional.
- **Implicit**: legacy browser flow returning the token directly.

Scope, Audience and State are optional. Every field accepts `{{variables}}`: keep secrets such as `{{clientSecret}}` in an environment.

## Getting a token
Click **Get New Access Token**. For Client Credentials and Password the token is requested immediately. For the browser flows, PostFX opens your browser on the Auth URL:
- with a Callback URL like `http://localhost:8765/callback` (register it at your provider), PostFX listens on that port and captures the redirect automatically;
- with any other Callback URL (for example `https://oauth.pstmn.io/v1/callback` already registered for Postman), copy the address shown by the browser after login and paste it into the dialog.

The **state** parameter is checked to protect against CSRF. **Cancel login** stops waiting for the browser.

## Using and renewing the token
The token is sent as `Authorization: Bearer <token>` (**Header Prefix** is configurable) or as a query parameter. You can also paste a token into the Access Token field.

With **Fetch / refresh the token automatically** enabled, before each request (single send, runner and command line):
1. an expired token is renewed with the refresh token when there is one;
2. otherwise Client Credentials and Password grants request a new token;
3. browser flows need **Get New Access Token** again (a message in the Console says so).

Token requests appear in the response Console. The token is saved with the workspace and exported to Postman.

## Client authentication
**Send as Basic Auth header** (default) sends `client_id:client_secret` as HTTP Basic; **Send client credentials in body** sends `client_id` and `client_secret` form fields instead. Public clients without a secret always send `client_id` in the body.

## Import and export
- Postman collections keep their OAuth 2.0 configuration both ways.
- OpenAPI / Swagger `oauth2` security schemes become OAuth 2.0 auth (client credentials, authorization code with PKCE, password or implicit, with the scopes of the flow).
- Java export: Client Credentials and Password grants fetch their token in the generated test (`oauthToken(...)`); for browser flows run the tests with `-Dvar.accessToken=<token>`.

# Variables and environments
Write `{{name}}` anywhere: URL, params, headers, body, auth, checks, extractors.

## Scopes and precedence
From the highest to the lowest priority:
1. **Local** – set by scripts with `pm.variables.set()`; live for one request (or one runner run).
2. **Iteration data** – columns of the runner data file (CSV/JSON).
3. **Environment** – the active environment (top right selector).
4. **Collection** – collection variables (double-click a collection > Variables).
5. **Global** – Environments > Globals.

## Dynamic variables
Generated at each send: `{{$guid}}`, `{{$randomUUID}}`, `{{$timestamp}}`, `{{$isoTimestamp}}`, `{{$randomInt}}`, `{{$randomBoolean}}`, `{{$randomFirstName}}`, `{{$randomLastName}}`, `{{$randomFullName}}`, `{{$randomUserName}}`, `{{$randomEmail}}`, `{{$randomPassword}}`, `{{$randomWord}}`, `{{$randomCity}}`, `{{$randomCountry}}`, `{{$randomColor}}`, `{{$randomHexColor}}`, `{{$randomIP}}`, `{{$randomPhoneNumber}}`, `{{$randomPrice}}`, `{{$randomDatePast}}`, `{{$randomDateFuture}}`, `{{$randomUrl}}`, `{{$randomLoremSentence}}`.

## Environments
Create environments (Dev, Staging, Prod…) with the same variable names and switch between them with the selector. Double-click an environment in the list to make it active. Values changed by scripts and extractors are written back to the environment.

# Checks and extractors
Checks and extractors let you build tests and request chains **without writing code**.

## Checks
A check compares a part of the response with an expected value:
- **Source**: Status code, Response time, Header, JSON path, Body text, Body size.
- **Operator**: equals, not equals, contains, does not contain, >, >=, <, <=, exists, does not exist, matches regex, is empty, is not empty, type is.
- **Expected**: may contain `{{variables}}`, e.g. `{{userId}}`.

Each check appears in **Test Results** next to the `pm.test` results, and a ✔ / ✘ is shown next to the check itself.

## JSON paths
`$.data.items[0].id`, `data.items[0].id`, `$[0].name`, `items[-1]` (last), `items[*].id` (all ids, combine with contains), `items.length`, `$['key with spaces']`.

## Extractors
An extractor copies a value of the response into a variable after each response:
- `token` = JSON path `$.access_token` into **environment**
- `location` = Header `Location`
- `orderId` = Body regex `"id":\s*(\d+)` (first group)

The next requests use them as `{{token}}`, `{{orderId}}`…

## Without typing paths
Send the request, open **Body > Tree** in the response, right-click any value and choose **Add check** or **Extract into a variable**.

## Example chain
1. `POST /login` – extractor `token` = `$.token`
2. Collection auth = Bearer `{{token}}`
3. `GET /me` – check `JSON path id exists`
4. Run the collection in the **Runner**: login runs first and every following request is authenticated.

# Scripts
Pre-request and test scripts are JavaScript (ES2022) with the Postman `pm` API, so most Postman scripts run unchanged. Scripts run in an isolated sandbox without file or Java access.

## Order of execution
For each request:
1. Collection pre-request → folder pre-request(s) → request pre-request
2. The request is sent
3. Collection tests → folder tests → request tests
4. Declarative checks, then extractors

## Pre-request script
```
// sign the request
const ts = Date.now().toString();
const sig = CryptoJS.HmacSHA256(ts + pm.request.body.raw, pm.environment.get("secret"))
                    .toString(CryptoJS.enc.Hex);
pm.request.headers.upsert({ key: "X-Signature", value: sig });
pm.variables.set("timestamp", ts);
```

## Test script
```
pm.test("Status code is 200", () => pm.response.to.have.status(200));

const json = pm.response.json();
pm.test("User is valid", () => {
    pm.expect(json).to.have.property("id");
    pm.expect(json.email).to.match(/@/);
    pm.expect(json.roles).to.include("admin");
});

pm.environment.set("userId", json.id);   // used by the next request
```

## Snippets
The list next to each script editor inserts common code at the cursor. Hover a snippet to preview it.

## Console
`console.log()`, `console.warn()` and `console.error()` output, script errors, extractor activity and the sent request appear in the **Console** tab of the response.

# pm API reference
## Variables
- `pm.variables.get/set/has/unset/toObject/replaceIn` – resolved value / local scope
- `pm.environment.*`, `pm.collectionVariables.*`, `pm.globals.*` – `get`, `set`, `unset`, `has`, `clear`, `toObject`
- `pm.environment.name`
- `pm.iterationData.get(name)` – runner data file

## Request (pre-request scripts can modify it)
- `pm.request.method`, `pm.request.url` (`toString()`, `query.add/remove/get`, `getHost()`, `getPath()`), `pm.request.url = "…"`
- `pm.request.headers.add/upsert/remove/get/has/toObject`
- `pm.request.body.raw`, `pm.request.body.update("…")`, `pm.request.body.mode`

## Response (test scripts)
- `pm.response.code`, `status`, `responseTime`, `responseSize`
- `pm.response.json()`, `pm.response.text()`
- `pm.response.headers.get(name)`, `pm.cookies.get(name)`

## Assertions
- `pm.test(name, fn)`, `pm.test.skip(name, fn)`
- `pm.response.to.have.status(200)` / `.header(name[, value])` / `.body(text)` / `.jsonBody(path[, value])` / `.jsonSchema(schema)`
- `pm.response.to.be.ok` / `.success` / `.json` / `.clientError` / `.serverError` / `.notFound` …
- `pm.expect(x)` (chai): `.to.equal / eql / deep.equal`, `.include / contain`, `.above / below / least / most / within`, `.a("string") / an("array")`, `.lengthOf(n)`, `.property(name[, value])`, `.nested.property("a.b")`, `.keys(...)`, `.members([...])`, `.match(/re/)`, `.oneOf([...])`, `.true / false / null / undefined / exist / empty / ok`, `.not`, `.satisfy(fn)`, `.closeTo(n, delta)`, `.throw()`
- Legacy: `tests["name"] = condition`, `responseBody`, `responseCode.code`, `postman.setEnvironmentVariable()`…

## Flow control (runner)
- `pm.execution.setNextRequest("name")` – jump to a request of the run
- `pm.execution.setNextRequest(null)` – end the current iteration
- `pm.execution.skipRequest()` – in a pre-request script: do not send this request
- `pm.info.requestName`, `pm.info.iteration`, `pm.info.iterationCount`, `pm.info.eventName`

## Calling other endpoints
```
pm.sendRequest({ url: "https://…/token", method: "POST",
                 header: { "Content-Type": "application/json" },
                 body: { mode: "raw", raw: JSON.stringify({ id: 1 }) } },
  (err, res) => pm.environment.set("token", res.json().token));
```

## Built-in libraries
`CryptoJS` (MD5, SHA1, SHA256, SHA512, HmacSHA1/256/512, enc.Hex/Base64/Utf8), `btoa` / `atob`, `require("uuid").v4()`, `require("lodash")` / `_` (common functions), `xml2Json(xml)`, `setTimeout`.

# Runner and test chains
The Runner executes a collection or folder as a **chain**: variables set by one request (extractors, `pm.environment.set`) are visible to the next ones.

## Starting a run
Right-click a collection or folder > **Run…**, or use the **▶ Run** button in the toolbar or in a folder tab.

## Options
- **Run order**: untick requests to skip them, use ↑ ↓ to reorder.
- **Environment**: environment used for this run.
- **Iterations**: repeat the whole chain.
- **Data file**: CSV (header row) or JSON array. One iteration per row; columns are variables (`{{column}}`, `pm.iterationData.get("column")`).
- **Delay**: pause between requests.
- **Stop on first failure**.
- **Keep variable values**: write the values set during the run back to the environment.

## Results
The result tree shows iterations, requests and every test. Select a request to see what was sent and received. **Export results** writes JUnit XML (for CI), JSON or a text summary.

## Loops and branches
```
// test script of "Poll status"
if (pm.response.json().state !== "DONE") {
    pm.execution.setNextRequest("Poll status");   // run it again
} else {
    pm.execution.setNextRequest("Download result");
}
```

# Import
**File > Import** (Ctrl+I) accepts files, **Import from text / link** accepts pasted content or a URL. The format is detected automatically:
- **Postman** collection v2.0 / v2.1, environment, globals
- **OpenAPI 3.x** and **Swagger 2.0**, JSON or YAML
- **Insomnia** v4 export
- **HAR** (browser / proxy recording)
- **cURL** command (also by pasting it into the URL field)

## OpenAPI import
- One request per operation, grouped in folders by tag.
- `baseUrl` collection variable from `servers` (or `host` + `basePath`).
- Path parameters become path variables (`/pets/:petId`), required query parameters are enabled, optional ones are disabled.
- Example bodies are generated from schemas (or taken from `example`).
- Security schemes become collection / request auth with variables (`{{bearerToken}}`, `{{apiKey}}`…).
- Each operation gets a **status code check** for its documented success response and, for JSON responses, a **JSON schema test** generated from the response schema: import a spec and you immediately have a contract test suite.

# Export
## Postman
Right-click a collection > **Export as Postman collection**. Environments can be exported from their tab or the environments list. Checks and extractors are converted into equivalent test script code so the collection still works in Postman, and they are restored as checks when the file is imported back into PostFX.

## Java test cases
Right-click a collection or folder > **Export as Java tests**. PostFX generates a JUnit 5 + REST Assured test class (optionally a complete Maven project):
- one ordered `@Test` per request, sharing a `vars` map: chains keep working;
- variables initialised from globals, collection and the chosen environment; override with `mvn test -Dvar.baseUrl=…`;
- checks become `assertEquals` / `assertTrue` / `check(...)` assertions;
- extractors become `vars.put("token", text(json(response, "token")))`;
- common Postman script statements (`pm.response.to.have.status`, `pm.expect(...)`, `pm.environment.set`…) are translated; the rest is kept as `// TODO` comments.

The **</> Code** button of a request shows the Java code for a single request.

# Command line
The same jar runs collections headless, e.g. in CI:
```
java -jar postfx.jar run collection.json -e dev.postman_environment.json --reporter-junit report.xml
java -jar postfx.jar run openapi.yaml --env-var baseUrl=http://localhost:8080
java -jar postfx.jar run collection.json -d data.csv --folder "Smoke tests" --bail
java -jar postfx.jar convert openapi.yaml -o api.postman_collection.json
java -jar postfx.jar export-java collection.json -o ./api-tests --package com.acme.tests
```
The exit code is 0 when every test passed and 1 otherwise. Run `java -jar postfx.jar help` for all options.

# Keyboard shortcuts
- **Ctrl+Enter** – send the current request
- **Ctrl+S** – save
- **Ctrl+T** – new request
- **Ctrl+W** – close the current tab
- **Ctrl+I** – import
- **Ctrl+Shift+R** – open the runner for the current collection
- **Ctrl+L** – focus the URL field
- **F1** – help
- **F2 / Del** – rename / delete the selected item in the tree

# Troubleshooting
## Variables are not replaced
The resolved URL under the URL bar shows "(undefined variables)". Check that the right environment is selected (top right) and hover the URL field to see each variable. Remember that disabled variables are ignored.

## SSL errors
For self-signed certificates disable **Settings > Verify SSL certificates** (or use `-k` on the command line).

## Script errors
Errors are listed in the response **Console** with the line number. Scripts are stopped after the script timeout (Settings).

## Where is my data?
Everything is stored in `~/.postfx/workspace.json` (a `.bak` copy of the previous version is kept next to it). Use the Postman export to share collections.
