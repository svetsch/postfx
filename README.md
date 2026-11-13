# PostFX

An API client and test runner built with **Java 21 + OpenJFX**, compatible with Postman collections and scripts.

- **Requests**: all methods, query/path params, headers, raw / form / multipart / GraphQL / binary bodies, Basic / Bearer / API key / **OAuth 2.0** auth (all grant types, PKCE, automatic refresh) with inheritance from folders and collections, cookies, history, cURL paste.
- **Variables**: global, collection, environment, iteration data and local scopes (Postman precedence), dynamic variables (`{{$guid}}`, `{{$timestamp}}`…), resolved URL preview.
- **Scripts**: pre-request and test scripts in JavaScript (GraalJS, sandboxed) with the Postman `pm` API: `pm.test`, `pm.expect` (chai), `pm.response.to.have…`, `pm.environment/collectionVariables/globals/variables`, `pm.sendRequest`, `pm.execution.setNextRequest/skipRequest`, `CryptoJS`, lodash subset, `xml2Json`, JSON schema, legacy `tests[]` / `postman.*`.
- **No-code checks and extractors**: assert on status / time / headers / JSON paths / body, and extract response values into variables to chain requests. Right-click a value in the response JSON tree to create them.
- **Runner (test chains)**: ordered selection, iterations, CSV/JSON data files, delay, stop on failure, `setNextRequest` loops; JUnit XML / JSON / text reports.
- **Import**: Postman collection v2.0/v2.1, environments and globals, OpenAPI 3.x and Swagger 2.0 (JSON/YAML, from a file or a URL), Insomnia v4, HAR, cURL. OpenAPI imports generate example bodies, status checks and JSON schema tests.
- **Export**: Postman v2.1 (checks are converted to script code and restored on re-import), and **Java tests** (JUnit 5 + REST Assured, single class or complete Maven project).
- **Command line** for CI, **help** window with a full guide, snippet library, example collection, light and dark themes, About box with version and commit.

## Build and run

Requirements: JDK 21 and Maven 3.9.

```bash
mvn package                 # build + tests -> target/postfx-<version>.jar and target/lib/
mvn exec:java               # start the desktop application from the sources
java -jar target/postfx-<version>.jar            # start the desktop application
java -jar target/postfx-<version>.jar --version  # version, git commit, build date
```

The workspace is stored in `~/.postfx/workspace.json` (override the folder with `-Dpostfx.home=...`).
On first start an example collection and the "Public demo APIs" environment are created: open **Welcome › Open the examples** or press **F1** for the guide.

## Native executable

```bash
mvn -Pnative package                         # target/native/PostFX/  (PostFX.exe + postfx-cli.exe, bundled runtime)
mvn -Pnative package -Djpackage.type=msi     # Windows installer (needs WiX Toolset 3 on the PATH)
mvn -Pnative package -Djpackage.type=deb     # Linux package (deb / rpm)
mvn -Pnative package -Djpackage.type=dmg     # macOS image (dmg / pkg)
```

The `native` profile uses `jpackage` and a jlink-trimmed Java runtime: the target machine needs no Java installation.
Two launchers are produced: `PostFX` (desktop application) and `postfx-cli` (console launcher for the command line).
Build on the operating system you are targeting.

## Command line

```bash
postfx-cli run collection.json -e dev.postman_environment.json --reporter-junit report.xml
postfx-cli run openapi.yaml --env-var baseUrl=http://localhost:8080 --bail
postfx-cli run collection.json -d data.csv -n 3 --folder "Smoke tests"
postfx-cli convert openapi.yaml -o api.postman_collection.json
postfx-cli export-java collection.json -o ./api-tests --package com.acme.tests -e dev.postman_environment.json
postfx-cli --version
```

Use `java -jar target/postfx-<version>.jar <command>` when running from the jar. The exit code is `0` when every test passed.

## Version and commit

The About box (**Help › About PostFX**) and `--version` show the version, git commit (with an "uncommitted changes" marker), branch, commit date, build date and runtime versions.
The commit is recorded by `git-commit-id-maven-plugin` once the repository has at least one commit; before that it shows "unknown".

## Project layout

| Package | Content |
|---|---|
| `io.postfx.model` | Collections, folders, requests, environments, checks, extractors (JSON workspace model) |
| `io.postfx.engine` | Variables, HTTP engine, request preparation, checks, extractors, request executor, collection runner |
| `io.postfx.script` | GraalJS sandbox (`sandbox.js`) and the Java bridge behind the `pm` API |
| `io.postfx.io` | Importers (Postman, OpenAPI/Swagger, Insomnia, HAR, cURL), Postman and Java exporters, reports, data files |
| `io.postfx.cli` | Command line |
| `io.postfx.ui` | JavaFX user interface, help (`help.md`), styles |

## Tests

```bash
mvn test
mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass=io.postfx.UiSmoke -Dexec.args=target/ui-smoke   # UI smoke run with screenshots
```

## Continuous integration and releases

`.github/workflows/build.yml` runs the tests and builds the Windows native image on every push to `main` (downloadable as a workflow artifact). Linux and macOS entries are present in the build matrix, commented out.
To publish a release, set the version in `pom.xml`, add a section to `CHANGELOG.md`, then push a matching tag:

```bash
git tag v0.6.0
git push origin v0.6.0
```

The workflow checks that the tag matches the pom version and creates the GitHub Release with the release notes from the changelog and the Windows native archive (`postfx-<version>-windows-x64.zip`).
