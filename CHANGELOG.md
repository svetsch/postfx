# Changelog

## Unreleased
- Application icon: window title bars, welcome page, About box, and the Windows / macOS / Linux native launchers

## 0.6.0

### OAuth 2.0
- New **OAuth 2.0** auth type for requests, folders and collections
- Grant types: Authorization Code, Authorization Code with PKCE (S256 / plain), Implicit, Password Credentials, Client Credentials
- "Get New Access Token" opens the system browser; localhost callback URLs are captured automatically, other callback URLs (e.g. Postman's) by pasting the redirected address; state is verified
- Token sent as `Authorization` header (configurable prefix) or query parameter; client authentication as Basic header or in the body; scope, audience, state, refresh token URL
- Automatic renewal before requests (also in the runner and the command line): refresh token first, then a new token for client credentials / password grants
- Postman import/export of OAuth 2.0 settings, OpenAPI / Swagger `oauth2` security schemes, Java export fetches client credentials / password tokens, cURL export includes the token

### Fixes
- About box: the branch is no longer shown as a commit id for builds made from a tag

## 0.5.0

First public release of PostFX, a JavaFX API client and test runner compatible with Postman.

### Requests
- All HTTP methods, query and path variables (`/users/:id`), headers, bulk edit
- Bodies: raw (JSON, text, XML, HTML, JavaScript), x-www-form-urlencoded, multipart form-data with files, GraphQL, binary
- Auth: Basic, Bearer token, API key (header or query), inherited from folders and collections
- Cookies, history, resolved URL preview, paste a cURL command into the URL field, code generation (cURL, Java, Postman JSON)

### Variables
- Global, collection, environment, iteration data and local scopes with Postman precedence
- Dynamic variables (`{{$guid}}`, `{{$timestamp}}`, `{{$randomEmail}}`...)

### Scripts and tests
- Sandboxed JavaScript (GraalJS) pre-request and test scripts with the Postman `pm` API: `pm.test`, `pm.expect` (chai), `pm.response.to.have...`, variables, `pm.sendRequest`, `pm.execution.setNextRequest/skipRequest`, `CryptoJS`, lodash subset, `xml2Json`, JSON schema, legacy `tests[]` / `postman.*`
- No-code checks (status, time, headers, JSON path, body) and extractors to chain requests, created from the response JSON tree with a right-click
- Collection runner: run order, iterations, CSV/JSON data files, delay, stop on failure, `setNextRequest` loops, JUnit XML / JSON / text reports

### Import and export
- Import: Postman collection v2.0/v2.1, environments and globals, OpenAPI 3.x and Swagger 2.0 (JSON/YAML, file or URL), Insomnia v4, HAR, cURL
- OpenAPI import generates example bodies, status checks and JSON schema tests
- Export: Postman collection v2.1 (checks round-trip), Java tests (JUnit 5 + REST Assured, class or Maven project)

### Tooling
- Command line runner for CI (`run`, `convert`, `export-java`, `--version`)
- User guide, snippet library, example collection, light and dark themes, About box with version and git commit
- Native application images built with jpackage (bundled Java runtime)
