package io.postfx.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.postfx.engine.JsonUtil;
import io.postfx.model.Check;
import io.postfx.model.Extractor;
import io.postfx.model.RequestItem;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Converts declarative checks/extractors to Postman test script code (so the exported collection still works in
 * Postman) and back. The generated block carries a machine readable copy of the definitions so a re-import restores
 * them as checks instead of code.
 */
public final class ChecksCodec {
    static final String BEGIN = "// ---- PostFX checks & extractors (generated, edit them in PostFX) ----";
    static final String DATA = "// postfx:";
    static final String END = "// ---- end PostFX ----";
    private static final String HELPER;

    static {
        try (InputStream in = ChecksCodec.class.getResourceAsStream("/io/postfx/script/postman-checks.js")) {
            HELPER = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ChecksCodec() {}

    /** Test script = user script + generated block (if the request has checks or extractors). */
    public static String testScriptForExport(RequestItem r) {
        List<Check> checks = r.checks.stream().filter(c -> c.enabled).toList();
        List<Extractor> extractors = r.extractors.stream().filter(e -> e.enabled && e.variable != null && !e.variable.isBlank()).toList();
        String user = r.testScript == null ? "" : r.testScript.stripTrailing();
        if (checks.isEmpty() && extractors.isEmpty()) return user;

        ObjectNode data = JsonUtil.MAPPER.createObjectNode();
        data.set("checks", JsonUtil.MAPPER.valueToTree(r.checks));
        data.set("extractors", JsonUtil.MAPPER.valueToTree(r.extractors));
        StringBuilder sb = new StringBuilder(user);
        if (!user.isEmpty()) sb.append("\n\n");
        sb.append(BEGIN).append('\n');
        sb.append(DATA).append(JsonUtil.toJson(data)).append('\n');
        sb.append(HELPER).append('\n');
        for (Check c : checks) {
            sb.append("pm.test(").append(js(c.describe())).append(", function () { __pfx.check(")
                    .append(js(c.source.name())).append(", ").append(js(c.property == null ? "" : c.property)).append(", ")
                    .append(js(c.operator.name())).append(", pm.variables.replaceIn(").append(js(c.expected == null ? "" : c.expected))
                    .append(")); });\n");
        }
        for (Extractor e : extractors) {
            String setter = switch (e.scope) {
                case GLOBAL -> "pm.globals";
                case COLLECTION -> "pm.collectionVariables";
                case ENVIRONMENT -> "pm.environment";
            };
            sb.append("(function () { var v = __pfx.extract(").append(js(e.source.name())).append(", pm.variables.replaceIn(")
                    .append(js(e.expression == null ? "" : e.expression)).append(")); if (v !== undefined) ").append(setter)
                    .append(".set(").append(js(e.variable)).append(", v); })();\n");
        }
        sb.append(END);
        return sb.toString();
    }

    /** Parsed result of an imported test script. */
    public record Decoded(String script, List<Check> checks, List<Extractor> extractors) {}

    public static Decoded decode(String script) {
        if (script == null || !script.contains(BEGIN)) return new Decoded(script == null ? "" : script, new ArrayList<>(), new ArrayList<>());
        List<String> lines = new ArrayList<>(Arrays.asList(script.split("\r?\n", -1)));
        int begin = -1, end = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (begin < 0 && lines.get(i).trim().equals(BEGIN)) begin = i;
            else if (begin >= 0 && lines.get(i).trim().equals(END)) {
                end = i;
                break;
            }
        }
        if (begin < 0 || end < 0) return new Decoded(script, new ArrayList<>(), new ArrayList<>());
        List<Check> checks = new ArrayList<>();
        List<Extractor> extractors = new ArrayList<>();
        for (int i = begin; i <= end; i++) {
            String l = lines.get(i).trim();
            if (l.startsWith(DATA)) {
                JsonNode n = JsonUtil.tryParse(l.substring(DATA.length()));
                if (n != null) {
                    for (JsonNode c : n.path("checks")) checks.add(JsonUtil.MAPPER.convertValue(c, Check.class));
                    for (JsonNode e : n.path("extractors")) extractors.add(JsonUtil.MAPPER.convertValue(e, Extractor.class));
                }
            }
        }
        List<String> kept = new ArrayList<>(lines.subList(0, begin));
        kept.addAll(lines.subList(end + 1, lines.size()));
        return new Decoded(String.join("\n", kept).strip(), checks, extractors);
    }

    /** JavaScript string literal. */
    static String js(String s) {
        return JsonUtil.toJson(s);
    }
}
