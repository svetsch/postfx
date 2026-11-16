package io.postfx;

import io.postfx.engine.CollectionRunner;
import io.postfx.engine.HttpEngine;
import io.postfx.engine.RequestExecutor;
import io.postfx.engine.RunOptions;
import io.postfx.engine.RunReport;
import io.postfx.engine.VariableContext;
import io.postfx.io.MarkdownExporter;
import io.postfx.io.SampleWorkspace;
import io.postfx.model.Check;
import io.postfx.model.CheckOperator;
import io.postfx.model.CheckSource;
import io.postfx.model.Collection;
import io.postfx.model.Folder;
import io.postfx.model.KeyValue;
import io.postfx.model.RequestItem;
import io.postfx.model.Settings;
import io.postfx.model.Workspace;
import io.postfx.script.ScriptExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownExportTest {

    @Test
    void testPlanFromTheExampleCollection() {
        Workspace ws = SampleWorkspace.create();
        Collection c = ws.collections.get(0);
        MarkdownExporter.Options o = new MarkdownExporter.Options();
        o.environment = ws.environments.get(0);
        o.globals = ws.globals;
        String md = MarkdownExporter.generate(c, null, o);

        assertTrue(md.startsWith("# Test document: PostFX Examples"), md.substring(0, 80));
        assertTrue(md.contains("## Summary"));
        assertTrue(md.contains("| [TC-01](#tc-01--get-with-query-params) | 1 - Basics › GET with query params | `GET` |"), md);
        assertTrue(md.contains("### 1 - Basics"), "folders become sections");
        assertTrue(md.contains("#### TC-01 · GET with query params"));
        assertTrue(md.contains("| Resolved URL | `https://postman-echo.com/get?search=postfx&page=1` |"), "variables resolved with the environment");
        assertTrue(md.contains("- [ ] Status code equals 200"), "checks become expected results");
        assertTrue(md.contains("- [ ] Status and headers"), "pm.test names become expected results");
        assertTrue(md.contains("Every test case also expects: *Response time is acceptable*"), "collection tests are listed once");
        assertTrue(md.contains("| `userId` | JSON path | `$[0].id` | environment |"), "extractors document the chain");
        assertTrue(md.contains("Basic, user `postman`, password ••••••"), "literal secrets are masked");
        assertFalse(md.contains("password` | `password"), "the basic auth password is not printed");
        assertTrue(md.contains("```json"), "JSON bodies are fenced and pretty printed");
        assertTrue(md.contains("<details><summary>Pre-request script (setup)</summary>"));
        assertTrue(md.contains("**Result:** ☐ Pass ☐ Fail"));
    }

    @Test
    void reportWithActualResults() throws Exception {
        try (TestServer server = new TestServer(); ScriptExecutor scripts = new ScriptExecutor()) {
            Collection c = new Collection("Local");
            c.variables.add(new KeyValue("baseUrl", server.url()));
            Folder f = new Folder("Items");
            RequestItem items = new RequestItem("List | items", "GET", "{{baseUrl}}/items");
            items.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, "200"));
            items.checks.add(new Check(CheckSource.JSON_PATH, "$[0].name", CheckOperator.EQUALS, "zzz"));
            items.testScript = "pm.test('has two items', () => pm.expect(pm.response.json()).to.have.lengthOf(2));";
            f.items.add(items);
            c.items.add(f);
            RunReport report = new CollectionRunner(new RequestExecutor(new HttpEngine(), scripts))
                    .run(c, VariableContext.of(null, c, null), new Settings(), new RunOptions(), null, null);
            MarkdownExporter.Options o = new MarkdownExporter.Options();
            o.report = report;
            String md = MarkdownExporter.generate(c, null, o);
            assertTrue(md.startsWith("# Test report: Local"));
            assertTrue(md.contains("## Run summary"));
            assertTrue(md.contains("**❌ Failed**"));
            assertTrue(md.contains("- ✅ Status code equals 200"), md);
            assertTrue(md.contains("- ❌ JSON path $[0].name equals zzz — expected 'a' to equal 'zzz'"), md);
            assertTrue(md.contains("- ✅ has two items"), md);
            assertTrue(md.contains("| 1 | 200 OK |"), md);
            assertTrue(md.contains("Items › List \\| items"), "pipes are escaped in tables");
            assertFalse(md.contains("☐ Pass"), "no manual checklist in a report");
        }
    }

    @Test
    void commandLine(@TempDir Path dir) throws Exception {
        Path coll = dir.resolve("c.json");
        Files.writeString(coll, io.postfx.engine.JsonUtil.toJson(
                io.postfx.io.PostmanExporter.exportCollection(SampleWorkspace.examples())));
        Path out = dir.resolve("doc.md");
        assertEquals(0, io.postfx.cli.Cli.main(new String[]{"export-md", coll.toString(), "-o", out.toString(), "--folder", "2 - Checks & extractors"}));
        String md = Files.readString(out);
        assertTrue(md.startsWith("# Test document: 2 - Checks & extractors"));
        assertTrue(md.contains("TC-04 · Create a comment"));
        assertFalse(md.contains("TC-05"), "only the folder");
    }
}
