package io.postfx;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.engine.CollectionRunner;
import io.postfx.engine.ExecutionResult;
import io.postfx.engine.HttpEngine;
import io.postfx.engine.JsonPathLite;
import io.postfx.engine.JsonUtil;
import io.postfx.engine.RequestExecutor;
import io.postfx.engine.RunOptions;
import io.postfx.engine.RunReport;
import io.postfx.engine.TestResult;
import io.postfx.engine.VariableContext;
import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.BodyMode;
import io.postfx.model.Check;
import io.postfx.model.CheckOperator;
import io.postfx.model.CheckSource;
import io.postfx.model.Collection;
import io.postfx.model.ExtractSource;
import io.postfx.model.Extractor;
import io.postfx.model.Folder;
import io.postfx.model.KeyValue;
import io.postfx.model.RequestItem;
import io.postfx.model.Settings;
import io.postfx.model.VarScope;
import io.postfx.script.ScriptExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {
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

    static String failures(ExecutionResult r) {
        StringBuilder sb = new StringBuilder();
        r.errors.forEach(e -> sb.append("ERROR ").append(e).append('\n'));
        r.tests.stream().filter(t -> !t.passed).forEach(t -> sb.append("FAIL ").append(t.name).append(": ").append(t.error).append('\n'));
        r.console.forEach(c -> sb.append(c).append('\n'));
        return sb.toString();
    }

    @Test
    void variablesResolveByPrecedence() {
        VariableContext v = new VariableContext();
        v.globals.put("a", "global");
        v.environment.put("a", "env");
        v.collection.put("b", "{{a}}-coll");
        assertEquals("env env-coll", v.resolve("{{a}} {{b}}"));
        assertEquals("{{missing}}", v.resolve("{{missing}}"));
        assertTrue(v.resolve("{{$guid}}").matches("[0-9a-f-]{36}"));
    }

    @Test
    void jsonPath() {
        JsonNode n = JsonUtil.tryParse("{\"a\":{\"b\":[{\"c\":1},{\"c\":2}]},\"odd key\":true}");
        assertEquals("2", JsonPathLite.asText(JsonPathLite.read(n, "$.a.b[1].c")));
        assertEquals("1", JsonPathLite.asText(JsonPathLite.read(n, "a.b[0].c")));
        assertEquals("2", JsonPathLite.asText(JsonPathLite.read(n, "a.b[-1].c")));
        assertEquals("[1,2]", JsonPathLite.asText(JsonPathLite.read(n, "$.a.b[*].c")));
        assertEquals("2", JsonPathLite.asText(JsonPathLite.read(n, "a.b.length")));
        assertEquals("true", JsonPathLite.asText(JsonPathLite.read(n, "$['odd key']")));
        assertNull(JsonPathLite.read(n, "a.x.y"));
    }

    @Test
    void scriptsTestsAndChai() {
        RequestItem item = new RequestItem("Items", "GET", server.url() + "/items");
        item.testScript = """
                pm.test("status", () => pm.response.to.have.status(200));
                pm.test("ok", function () { pm.response.to.be.ok; pm.response.to.be.json; });
                pm.test("header", () => pm.response.to.have.header("X-Test", "yes"));
                const data = pm.response.json();
                pm.test("chai", () => {
                    pm.expect(data).to.be.an('array').that.has.lengthOf(2);
                    pm.expect(data[0]).to.have.property('name', 'a');
                    pm.expect(data[0]).to.deep.equal({id: 1, name: 'a'});
                    pm.expect(data.map(d => d.id)).to.include(2);
                    pm.expect(data[1].name).to.not.equal('a');
                    pm.expect(pm.response.responseTime).to.be.below(10000);
                    pm.expect(data).to.have.length.above(1);
                    pm.expect({a: {b: 1}}).to.have.nested.property('a.b', 1);
                    pm.expect('hello').to.match(/^he/);
                    pm.expect(3).to.be.oneOf([1, 2, 3]);
                    pm.expect({a: 1, b: 2}).to.have.all.keys('a', 'b');
                    pm.expect([]).to.be.empty;
                });
                pm.test("jsonSchema", () => pm.response.to.have.jsonSchema({type: 'array', items: {type: 'object', required: ['id', 'name']}}));
                pm.test("must fail", () => pm.expect(data.length).to.eql(3));
                tests["legacy"] = responseCode.code === 200;
                pm.environment.set("firstName", data[0].name);
                pm.collectionVariables.set("count", data.length);
                console.log("done", data.length);
                const hmac = CryptoJS.HmacSHA256("message", "key").toString(CryptoJS.enc.Hex);
                pm.test("crypto", () => pm.expect(hmac).to.eql("6e9ef29b75fffc5b7abae527d58fdadb2fe42e7219011976917343065f58ed4a"));
                pm.test("btoa", () => pm.expect(atob(btoa("hi"))).to.eql("hi"));
                """;
        VariableContext vars = new VariableContext();
        ExecutionResult r = executor.execute(item, null, vars, settings, 0, 1);
        assertTrue(r.errors.isEmpty(), failures(r));
        Map<String, TestResult> byName = new java.util.HashMap<>();
        r.tests.forEach(t -> byName.put(t.name, t));
        for (String ok : List.of("status", "ok", "header", "chai", "jsonSchema", "legacy", "crypto", "btoa")) {
            assertTrue(byName.get(ok).passed, ok + ": " + byName.get(ok).error);
        }
        assertFalse(byName.get("must fail").passed);
        assertEquals("AssertionError: expected 2 to deeply equal 3", byName.get("must fail").error);
        assertEquals("a", vars.environment.get("firstName"));
        assertEquals("2", vars.collection.get("count"));
        assertTrue(r.console.stream().anyMatch(c -> c.message.equals("done 2")));
    }

    @Test
    void preRequestScriptModifiesRequest() {
        RequestItem item = new RequestItem("Echo", "GET", server.url() + "/echo?a=1");
        item.preRequestScript = """
                pm.request.headers.add({key: 'X-Custom', value: pm.variables.replaceIn('{{who}}')});
                pm.request.url.query.add({key: 'b', value: '2'});
                pm.request.method = 'POST';
                pm.request.body.update(JSON.stringify({ts: 1}));
                pm.variables.set('local1', 'L');
                """;
        item.testScript = """
                const d = pm.response.json();
                pm.test("modified", () => {
                    pm.expect(d.method).to.eql('POST');
                    pm.expect(d.query).to.eql('a=1&b=2');
                    pm.expect(d.headers['x-custom']).to.eql('world');
                    pm.expect(d.body).to.eql('{"ts":1}');
                    pm.expect(pm.variables.get('local1')).to.eql('L');
                });
                """;
        VariableContext vars = new VariableContext();
        vars.environment.put("who", "world");
        ExecutionResult r = executor.execute(item, null, vars, settings, 0, 1);
        assertTrue(r.passed(), failures(r));
    }

    @Test
    void scriptErrorsAndTimeoutsAreReported() {
        Settings s = new Settings();
        s.scriptTimeoutMs = 500;
        RequestItem item = new RequestItem("Items", "GET", server.url() + "/items");
        item.testScript = "while(true) {}";
        ExecutionResult r = executor.execute(item, null, new VariableContext(), s, 0, 1);
        assertTrue(r.errors.get(0).contains("timed out"), r.errors.toString());

        item.testScript = "pm.test('x', () => {});\nundefinedFn();";
        r = executor.execute(item, null, new VariableContext(), settings, 0, 1);
        assertTrue(r.errors.get(0).contains("undefinedFn"), r.errors.toString());
        assertTrue(r.errors.get(0).contains("line 2"), r.errors.toString());
        assertEquals(1, r.tests.size());
    }

    @Test
    void scriptsCannotAccessJava() {
        RequestItem item = new RequestItem("Items", "GET", server.url() + "/items");
        item.testScript = """
                let threw = false;
                try { Java.type('java.lang.System'); } catch (e) { threw = true; }
                pm.test('type lookup refused', () => pm.expect(threw).to.be.true);
                """;
        ExecutionResult r = executor.execute(item, null, new VariableContext(), settings, 0, 1);
        assertTrue(r.passed(), failures(r));
    }

    @Test
    void chainWithExtractorsChecksAuthInheritanceAndSetNextRequest() {
        Collection c = new Collection("API");
        c.variables.add(new KeyValue("baseUrl", server.url()));
        c.auth = new Auth(AuthType.BEARER);
        c.auth.token = "{{token}}";

        RequestItem login = new RequestItem("Login", "POST", "{{baseUrl}}/login");
        login.request.auth = new Auth(AuthType.NONE);
        login.request.body.mode = BodyMode.RAW;
        login.request.body.raw = "{\"password\":\"{{password}}\"}";
        login.extractors.add(new Extractor("token", ExtractSource.JSON_PATH, "$.token", VarScope.ENVIRONMENT));
        login.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "200"));
        login.checks.add(new Check(CheckSource.JSON_PATH, "user.id", CheckOperator.EQUALS, "7"));
        login.checks.add(new Check(CheckSource.HEADER, "content-type", CheckOperator.CONTAINS, "json"));
        login.checks.add(new Check(CheckSource.RESPONSE_TIME, "", CheckOperator.LESS_THAN, "10000"));

        Folder users = new Folder("Users");
        RequestItem me = new RequestItem("Me", "GET", "{{baseUrl}}/me");
        me.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "200"));
        me.checks.add(new Check(CheckSource.JSON_PATH, "roles", CheckOperator.CONTAINS, "admin"));
        me.checks.add(new Check(CheckSource.JSON_PATH, "name", CheckOperator.TYPE_IS, "string"));
        me.testScript = "pm.execution.setNextRequest('Final');";
        RequestItem skipped = new RequestItem("Never", "GET", "{{baseUrl}}/nothing");
        RequestItem fin = new RequestItem("Final", "GET", "{{baseUrl}}/items");
        fin.checks.add(new Check(CheckSource.JSON_PATH, "$[1].name", CheckOperator.EQUALS, "b"));
        users.items.addAll(List.of(me, skipped, fin));
        c.items.addAll(List.of(login, users));

        VariableContext vars = VariableContext.of(null, c, null);
        RunOptions opts = new RunOptions();
        opts.data = List.of(Map.of("password", "secret"), Map.of("password", "secret"));
        RunReport report = new CollectionRunner(executor).run(c, vars, settings, opts, null, null);

        assertEquals(2, report.iterations);
        assertEquals(6, report.results.size(), "Login, Me, Final x 2 iterations");
        assertTrue(report.results.stream().noneMatch(r -> r.name.equals("Never")));
        for (ExecutionResult r : report.results) assertTrue(r.passed(), r.name + "\n" + failures(r));
        assertEquals("abc123", vars.environment.get("token"));
        assertTrue(report.success());
    }

    @Test
    void failingCheckIsReported() {
        RequestItem item = new RequestItem("Login", "POST", server.url() + "/login");
        item.request.body.mode = BodyMode.RAW;
        item.request.body.raw = "{}";
        item.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "200"));
        ExecutionResult r = executor.execute(item, null, new VariableContext(), settings, 0, 1);
        assertFalse(r.passed());
        assertEquals("expected '401' to equal '200'", r.tests.get(0).error);
    }

    @Test
    void sendRequestFromScript() {
        RequestItem item = new RequestItem("Items", "GET", server.url() + "/items");
        item.preRequestScript = """
                pm.sendRequest({url: pm.variables.get('base') + '/login', method: 'POST',
                                header: {'Content-Type': 'application/json'},
                                body: {mode: 'raw', raw: JSON.stringify({password: 'secret'})}}, (err, res) => {
                    pm.environment.set('tok', res.json().token);
                });
                """;
        VariableContext vars = new VariableContext();
        vars.environment.put("base", server.url());
        ExecutionResult r = executor.execute(item, null, vars, settings, 0, 1);
        assertTrue(r.passed(), failures(r));
        assertEquals("abc123", vars.environment.get("tok"));
    }

    @Test
    void formBodies() {
        RequestItem item = new RequestItem("Echo", "POST", server.url() + "/echo");
        item.request.body.mode = BodyMode.URLENCODED;
        item.request.body.urlencoded.add(new KeyValue("a b", "c&d"));
        item.checks.add(new Check(CheckSource.JSON_PATH, "body", CheckOperator.EQUALS, "a+b=c%26d"));
        item.checks.add(new Check(CheckSource.JSON_PATH, "headers.content-type", CheckOperator.EQUALS, "application/x-www-form-urlencoded"));
        ExecutionResult r = executor.execute(item, null, new VariableContext(), settings, 0, 1);
        assertTrue(r.passed(), failures(r));

        item.request.body.mode = BodyMode.FORMDATA;
        item.request.body.formdata.add(new KeyValue("field", "value"));
        item.checks.clear();
        item.checks.add(new Check(CheckSource.JSON_PATH, "body", CheckOperator.CONTAINS, "name=\"field\""));
        item.checks.add(new Check(CheckSource.JSON_PATH, "headers.content-type", CheckOperator.MATCHES, "^multipart/form-data; boundary="));
        r = executor.execute(item, null, new VariableContext(), settings, 0, 1);
        assertTrue(r.passed(), failures(r));
    }
}
