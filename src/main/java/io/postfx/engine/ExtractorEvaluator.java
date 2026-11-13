package io.postfx.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.model.Extractor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies {@link Extractor}s: copies response values into variables for the next requests of a chain. */
public final class ExtractorEvaluator {
    private ExtractorEvaluator() {}

    /** Returns the extracted value or null when nothing matched. */
    public static String extract(Extractor ex, HttpResult res, VariableContext vars) {
        String expr = vars.resolve(ex.expression == null ? "" : ex.expression);
        return switch (ex.source) {
            case STATUS -> String.valueOf(res.status);
            case BODY -> res.body;
            case HEADER -> res.header(expr);
            case JSON_PATH -> {
                JsonNode root = res.json();
                yield root == null ? null : JsonPathLite.asText(JsonPathLite.read(root, expr));
            }
            case BODY_REGEX -> {
                Matcher m = Pattern.compile(expr).matcher(res.body == null ? "" : res.body);
                if (!m.find()) yield null;
                yield m.groupCount() >= 1 ? m.group(1) : m.group();
            }
        };
    }

    /** Applies the extractor; returns a console entry describing what happened. */
    public static ConsoleEntry apply(Extractor ex, HttpResult res, VariableContext vars) {
        if (ex.variable == null || ex.variable.isBlank()) return null;
        try {
            String value = extract(ex, res, vars);
            if (value == null) {
                return new ConsoleEntry("warn", "Extractor '" + ex.variable + "': no value found for " + ex.source.label + " " + ex.expression, "extract");
            }
            vars.scope(ex.scope).put(ex.variable, value);
            String shown = value.length() > 120 ? value.substring(0, 120) + "..." : value;
            return new ConsoleEntry("info", "Set " + ex.scope.label + " variable '" + ex.variable + "' = " + shown, "extract");
        } catch (Exception e) {
            return new ConsoleEntry("error", "Extractor '" + ex.variable + "' failed: " + e.getMessage(), "extract");
        }
    }
}
