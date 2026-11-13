# Changelog

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
