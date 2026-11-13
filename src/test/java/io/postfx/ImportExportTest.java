package io.postfx;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.engine.CollectionRunner;
import io.postfx.engine.ExecutionResult;
import io.postfx.engine.HttpEngine;
import io.postfx.engine.JsonUtil;
import io.postfx.engine.RequestExecutor;
import io.postfx.engine.RunOptions;
import io.postfx.engine.RunReport;
import io.postfx.engine.VariableContext;
import io.postfx.io.ChecksCodec;
import io.postfx.io.CurlCodec;
import io.postfx.io.DataFileReader;
import io.postfx.io.ImportResult;
import io.postfx.io.Importer;
import io.postfx.io.JavaTestExporter;
import io.postfx.io.PostmanExporter;
import io.postfx.io.SampleWorkspace;
import io.postfx.model.AuthType;
import io.postfx.model.BodyMode;
import io.postfx.model.CheckOperator;
import io.postfx.model.CheckSource;
import io.postfx.model.Collection;
import io.postfx.model.Folder;
import io.postfx.model.Items;
import io.postfx.model.RequestItem;
import io.postfx.model.Settings;
import io.postfx.model.Workspace;
import io.postfx.script.ScriptExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImportExportTest {

    static final String POSTMAN = """
            {
              "info": {"_postman_id": "1", "name": "Demo", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{token}}", "type": "string"}]},
              "variable": [{"key": "baseUrl", "value": "http://localhost"}],
              "item": [
                {"name": "Users", "item": [
                  {"name": "Get user", "event": [{"listen": "test", "script": {"exec": ["pm.test('ok', () => pm.response.to.have.status(200));"], "type": "text/javascript"}}],
                   "request": {"method": "GET", "header": [{"key": "Accept", "value": "application/json"}, {"key": "X-Off", "value": "1", "disabled": true}],
                     "url": {"raw": "{{baseUrl}}/users/:id?expand=true", "host": ["{{baseUrl}}"], "path": ["users", ":id"],
                             "query": [{"key": "expand", "value": "true"}, {"key": "debug", "value": "1", "disabled": true}],
                             "variable": [{"key": "id", "value": "42"}]}}}
                ]},
                {"name": "Login", "request": {"auth": {"type": "noauth"}, "method": "POST",
                   "body": {"mode": "raw", "raw": "{\\"u\\":1}", "options": {"raw": {"language": "json"}}}, "url": "{{baseUrl}}/login"}},
                {"name": "Upload", "request": {"method": "POST", "body": {"mode": "formdata", "formdata": [
                   {"key": "f", "type": "file", "src": "/tmp/a.txt"}, {"key": "t", "value": "x", "type": "text"}]}, "url": "{{baseUrl}}/up"}}
              ]
            }""";

    @Test
    void postmanRoundTripKeepsChecksAndExtractors() {
        ImportResult r = Importer.importText(POSTMAN, null);
        assertEquals("Postman collection", r.format);
        Collection c = r.collections.get(0);
        assertEquals(AuthType.BEARER, c.auth.type);
        assertEquals("{{token}}", c.auth.token);
        RequestItem get = (RequestItem) ((Folder) c.items.get(0)).items.get(0);
        assertEquals("{{baseUrl}}/users/:id?expand=true", get.request.url);
        assertEquals("debug", get.request.disabledParams.get(0).key);
        assertEquals("42", get.request.pathVariables.get(0).value);
        assertFalse(get.request.headers.get(1).enabled);
        assertEquals(AuthType.INHERIT, get.request.auth.type);
        RequestItem login = (RequestItem) c.items.get(1);
        assertEquals(AuthType.NONE, login.request.auth.type);
        assertEquals(BodyMode.RAW, login.request.body.mode);
        RequestItem upload = (RequestItem) c.items.get(2);
        assertTrue(upload.request.body.formdata.get(0).isFile());

        // add declarative checks and export to Postman
        get.checks.add(new io.postfx.model.Check(CheckSource.JSON_PATH, "name", CheckOperator.EQUALS, "Ada"));
        get.extractors.add(new io.postfx.model.Extractor("name", io.postfx.model.ExtractSource.JSON_PATH, "name", io.postfx.model.VarScope.ENVIRONMENT));
        JsonNode exported = PostmanExporter.exportCollection(c);
        String text = JsonUtil.toPrettyJson(exported);
        assertTrue(text.contains("__pfx.check"));

        Collection back = Importer.importText(text, null).collections.get(0);
        RequestItem get2 = (RequestItem) ((Folder) back.items.get(0)).items.get(0);
        assertEquals(1, get2.checks.size());
        assertEquals(1, get2.extractors.size());
        assertEquals("pm.test('ok', () => pm.response.to.have.status(200));", get2.testScript);
        assertEquals("42", get2.request.pathVariables.get(0).value);
        assertEquals("debug", get2.request.disabledParams.get(0).key);
    }

    @Test
    void generatedPostmanCheckScriptsRunInTheSandbox() throws Exception {
        try (TestServer server = new TestServer(); ScriptExecutor scripts = new ScriptExecutor()) {
            RequestItem item = new RequestItem("Me", "GET", server.url() + "/items");
            item.checks.add(new io.postfx.model.Check(CheckSource.JSON_PATH, "$[1].name", CheckOperator.EQUALS, "b"));
            item.checks.add(new io.postfx.model.Check(CheckSource.JSON_PATH, "$[*].id", CheckOperator.CONTAINS, "2"));
            item.checks.add(new io.postfx.model.Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "500"));
            item.extractors.add(new io.postfx.model.Extractor("firstId", io.postfx.model.ExtractSource.JSON_PATH, "$[0].id", io.postfx.model.VarScope.ENVIRONMENT));
            // emulate Postman: the generated script replaces the declarative checks
            RequestItem asPostman = new RequestItem("Me", "GET", item.request.url);
            asPostman.testScript = ChecksCodec.testScriptForExport(item);
            VariableContext vars = new VariableContext();
            ExecutionResult r = new RequestExecutor(new HttpEngine(), scripts).execute(asPostman, null, vars, new Settings(), 0, 1);
            assertTrue(r.errors.isEmpty(), r.errors.toString());
            assertEquals(3, r.tests.size());
            assertTrue(r.tests.get(0).passed, r.tests.get(0).error);
            assertTrue(r.tests.get(1).passed, r.tests.get(1).error);
            assertFalse(r.tests.get(2).passed);
            assertEquals("Error: expected '200' to equal '500'", r.tests.get(2).error);
            assertEquals("1", vars.environment.get("firstId"));
        }
    }

    static final String OPENAPI = """
            openapi: 3.0.1
            info: {title: Pet Store, version: "1.0"}
            servers: [{url: "https://{env}.example.com/v1", variables: {env: {default: api}}}]
            security: [{bearer: []}]
            components:
              securitySchemes:
                bearer: {type: http, scheme: bearer}
                key: {type: apiKey, in: header, name: X-Key}
              schemas:
                Pet:
                  type: object
                  required: [name]
                  properties:
                    id: {type: integer, readOnly: true}
                    name: {type: string, example: Rex}
                    tags: {type: array, items: {type: string}}
                    owner: {$ref: '#/components/schemas/Owner'}
                Owner:
                  type: object
                  properties:
                    email: {type: string, format: email}
                    pets: {type: array, items: {$ref: '#/components/schemas/Pet'}}
            paths:
              /pets:
                get:
                  tags: [pets]
                  summary: List pets
                  parameters:
                    - {name: limit, in: query, required: true, schema: {type: integer, default: 10}}
                    - {name: q, in: query, schema: {type: string}}
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema: {type: array, items: {$ref: '#/components/schemas/Pet'}}
                post:
                  tags: [pets]
                  operationId: createPet
                  security: [{key: []}]
                  requestBody:
                    content:
                      application/json:
                        schema: {$ref: '#/components/schemas/Pet'}
                  responses:
                    '201': {description: created}
              /pets/{petId}:
                parameters:
                  - {name: petId, in: path, required: true, schema: {type: string}, example: "abc"}
                delete:
                  security: []
                  responses:
                    '204': {description: deleted}
            """;

    @Test
    void openApiImport() {
        ImportResult r = Importer.importText(OPENAPI, null);
        assertEquals("OpenAPI 3.0.1", r.format);
        Collection c = r.collections.get(0);
        assertEquals("Pet Store", c.name);
        assertEquals("https://api.example.com/v1", c.variables.get(0).value);
        assertEquals(AuthType.BEARER, c.auth.type);
        Folder pets = (Folder) c.items.get(0);
        assertEquals("pets", pets.name);
        RequestItem list = (RequestItem) pets.items.get(0);
        assertEquals("List pets", list.name);
        assertEquals("{{baseUrl}}/pets?limit=10", list.request.url);
        assertEquals("q", list.request.disabledParams.get(0).key);
        assertEquals("200", list.checks.get(0).expected);
        assertTrue(list.testScript.contains("jsonSchema"));
        RequestItem create = (RequestItem) pets.items.get(1);
        assertEquals("createPet", create.name);
        assertEquals(AuthType.APIKEY, create.request.auth.type);
        JsonNode body = JsonUtil.tryParse(create.request.body.raw);
        assertEquals("Rex", body.get("name").asText());
        assertFalse(body.has("id"), "readOnly properties are not part of request examples");
        assertEquals("user@example.com", body.get("owner").get("email").asText());
        RequestItem delete = (RequestItem) c.items.get(1);
        assertEquals("{{baseUrl}}/pets/:petId", delete.request.url);
        assertEquals("abc", delete.request.pathVariables.get(0).value);
        assertEquals(AuthType.NONE, delete.request.auth.type);
    }

    @Test
    void swagger2Import() {
        String spec = """
                {"swagger": "2.0", "info": {"title": "Old API", "version": "1"}, "host": "old.example.com", "basePath": "/api", "schemes": ["http"],
                 "paths": {"/login": {"post": {"consumes": ["application/x-www-form-urlencoded"],
                    "parameters": [{"name": "user", "in": "formData", "type": "string", "required": true}],
                    "responses": {"200": {"description": "ok"}}}}}}""";
        Collection c = Importer.importText(spec, null).collections.get(0);
        assertEquals("http://old.example.com/api", c.variables.get(0).value);
        RequestItem login = (RequestItem) c.items.get(0);
        assertEquals(BodyMode.URLENCODED, login.request.body.mode);
        assertEquals("user", login.request.body.urlencoded.get(0).key);
    }

    @Test
    void curlImportAndExport() {
        RequestItem r = CurlCodec.parse("""
                curl -X POST 'https://api.example.com/v1/items?x=1' \\
                  -H 'Content-Type: application/json' -H "X-Token: a b" \\
                  --data-raw '{"name":"it''s"}' -u admin:pw""");
        assertEquals("POST", r.request.method);
        assertEquals("https://api.example.com/v1/items?x=1", r.request.url);
        assertEquals(2, r.request.headers.size());
        assertEquals("a b", r.request.headers.get(1).value);
        assertEquals(BodyMode.RAW, r.request.body.mode);
        assertEquals(AuthType.BASIC, r.request.auth.type);
        assertEquals("pw", r.request.auth.password);

        RequestItem form = CurlCodec.parse("curl https://x.io/f -F 'file=@/tmp/a.png' -F name=bob");
        assertEquals("POST", form.request.method);
        assertTrue(form.request.body.formdata.get(0).isFile());

        RequestItem enc = CurlCodec.parse("curl https://x.io/f -d 'a=1&b=two%20words'");
        assertEquals(BodyMode.URLENCODED, enc.request.body.mode);
        assertEquals("two words", enc.request.body.urlencoded.get(1).value);

        String curl = CurlCodec.toCurl(r.request, r.request.auth, new VariableContext());
        RequestItem again = CurlCodec.parse(curl);
        assertEquals(r.request.url, again.request.url);
        assertEquals(r.request.method, again.request.method);
    }

    @Test
    void harAndInsomniaImport() {
        String har = """
                {"log": {"creator": {"name": "Chrome"}, "entries": [{"request": {"method": "GET", "url": "https://a.io/x?y=1",
                  "headers": [{"name": ":authority", "value": "a.io"}, {"name": "Accept", "value": "*/*"}]}, "response": {"status": 200}}]}}""";
        ImportResult r = Importer.importText(har, "rec");
        assertEquals("HAR", r.format);
        RequestItem item = (RequestItem) r.collections.get(0).items.get(0);
        assertEquals(1, item.request.headers.size());
        assertEquals("200", item.checks.get(0).expected);

        String insomnia = """
                {"_type": "export", "__export_format": 4, "resources": [
                  {"_id": "wrk_1", "_type": "workspace", "name": "Ins"},
                  {"_id": "fld_1", "_type": "request_group", "parentId": "wrk_1", "name": "F"},
                  {"_id": "req_1", "_type": "request", "parentId": "fld_1", "name": "R", "method": "GET", "url": "{{ _.base }}/x",
                   "headers": [], "body": {}, "authentication": {"type": "bearer", "token": "{{ _.tok }}"}},
                  {"_id": "env_1", "_type": "environment", "parentId": "wrk_1", "name": "Base", "data": {"base": "https://i.io"}}]}""";
        r = Importer.importText(insomnia, null);
        assertEquals("Insomnia export", r.format);
        RequestItem ir = (RequestItem) ((Folder) r.collections.get(0).items.get(0)).items.get(0);
        assertEquals("{{base}}/x", ir.request.url);
        assertEquals("{{tok}}", ir.request.auth.token);
        assertEquals("https://i.io", r.environments.get(0).values.get(0).value);
    }

    @Test
    void environmentsAndDataFiles(@TempDir Path dir) throws Exception {
        ImportResult r = Importer.importText("""
                {"name": "Dev", "values": [{"key": "a", "value": "1", "enabled": true}, {"key": "b", "value": "2", "enabled": false}],
                 "_postman_variable_scope": "environment"}""", null);
        assertEquals("Dev", r.environments.get(0).name);
        assertFalse(r.environments.get(0).values.get(1).enabled);

        Path csv = dir.resolve("data.csv");
        Files.writeString(csv, "user,comment\nada,\"hello, \"\"world\"\"\"\nbob,\"multi\nline\"\n");
        List<java.util.Map<String, String>> rows = DataFileReader.read(csv);
        assertEquals(2, rows.size());
        assertEquals("hello, \"world\"", rows.get(0).get("comment"));
        assertEquals("multi\nline", rows.get(1).get("comment"));
    }

    @Test
    void javaExportGeneratesCompilableTests(@TempDir Path dir) throws Exception {
        Collection c = SampleWorkspace.examples();
        Folder scripts = (Folder) c.items.get(2);
        RequestItem tour = (RequestItem) scripts.items.get(1);
        JavaTestExporter.Options o = new JavaTestExporter.Options();
        o.environment = SampleWorkspace.create().environments.get(0);
        String code = JavaTestExporter.generate(c, null, o);
        assertTrue(code.contains("class PostFXExamplesTest"));
        assertTrue(code.contains("@Order(1)"));
        assertTrue(code.contains("vars.put(\"userId\", text(json(response, \"[0].id\")));"), code);
        assertTrue(code.contains("assertEquals(200, response.statusCode(), \"Status and headers\");"), code);
        assertTrue(code.contains("vars.put(\"companyName\", text(json(response, \"company.name\")));"), code);
        assertTrue(code.contains(".auth().preemptive().basic(\"postman\", \"password\")"));
        Path file = JavaTestExporter.writeMavenProject(dir, c, null, o);
        assertTrue(Files.exists(dir.resolve("pom.xml")));
        assertTrue(Files.readString(file).contains("package com.example.api;"));
        assertNotNull(tour);
    }

    @Test
    void javaExportTranslatesChainsAndRootPaths() {
        Collection c = new Collection("Chains");
        RequestItem r = new RequestItem("R", "GET", "{{base}}/users");
        r.checks.add(new io.postfx.model.Check(CheckSource.JSON_PATH, "$.length", CheckOperator.GREATER_OR_EQUAL, "5"));
        r.testScript = """
                const user = pm.response.json();
                pm.test("chain", () => {
                    pm.expect(user.name).to.be.a("string").and.not.be.empty; // trailing comment
                    pm.expect(pm.response.responseTime).to.be.below(Number(pm.variables.get("max")));
                    pm.expect(user.tags).to.have.lengthOf(2);
                });
                pm.environment.set("id", user.id);
                """;
        c.items.add(r);
        String code = JavaTestExporter.generate(c, null, new JavaTestExporter.Options());
        assertTrue(code.contains("json(response, \"size()\")"), code);
        assertTrue(code.contains("assertEquals(\"string\", typeOf(json(response, \"name\")), \"chain\");"), code);
        assertTrue(code.contains("assertFalse(isEmpty(json(response, \"name\")), \"chain\");"), code);
        assertTrue(code.contains("assertTrue(num(response.time()) < num(vars.get(\"max\")), \"chain\");"), code);
        assertTrue(code.contains("assertEquals(2, size(json(response, \"tags\")), \"chain\");"), code);
        assertTrue(code.contains("vars.put(\"id\", text(json(response, \"id\")));"), code);
        assertTrue(code.contains("// trailing comment"), code);
        assertFalse(code.contains("TODO"), code);
    }

    @Test
    void sampleWorkspaceSerializes() {
        Workspace ws = SampleWorkspace.create();
        String json = JsonUtil.toPrettyJson(ws);
        Workspace back = JsonUtil.fromJson(json, Workspace.class);
        assertEquals(Items.flatten(ws.collections.get(0)).size(), Items.flatten(back.collections.get(0)).size());
        assertInstanceOf(Folder.class, back.collections.get(0).items.get(0));
    }

    @Test
    void cliRunnerOnLocalServer(@TempDir Path dir) throws Exception {
        try (TestServer server = new TestServer()) {
            Path coll = dir.resolve("c.json");
            String spec = POSTMAN.replace("http://localhost", server.url());
            Files.writeString(coll, spec);
            Path junit = dir.resolve("junit.xml");
            int code = io.postfx.cli.Cli.main(new String[]{"run", coll.toString(), "--reporter-junit", junit.toString(), "--folder", "Users"});
            assertEquals(1, code, "the /users endpoint does not exist on the test server -> failing test");
            assertTrue(Files.readString(junit).contains("<testsuites"));
        }
    }

    @Test
    void runnerHonoursStopOnFailure() throws Exception {
        try (TestServer server = new TestServer(); ScriptExecutor scripts = new ScriptExecutor()) {
            Collection c = new Collection("x");
            for (int i = 0; i < 3; i++) {
                RequestItem r = new RequestItem("r" + i, "GET", server.url() + "/me");
                r.checks.add(new io.postfx.model.Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "200"));
                c.items.add(r);
            }
            RunOptions o = new RunOptions();
            o.stopOnFailure = true;
            RunReport rep = new CollectionRunner(new RequestExecutor(new HttpEngine(), scripts)).run(c, new VariableContext(), new Settings(), o, null, null);
            assertEquals(1, rep.results.size());
            assertTrue(rep.stoppedOnFailure);
        }
    }
}
