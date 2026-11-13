package io.postfx.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.model.Check;
import io.postfx.model.CheckOperator;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Evaluates declarative {@link Check}s against a response. */
public final class CheckEvaluator {
    private CheckEvaluator() {}

    public static TestResult evaluate(Check check, HttpResult res, VariableContext vars) {
        String name = check.describe();
        try {
            String expected = vars.resolve(check.expected == null ? "" : check.expected);
            Actual actual = actual(check, res, vars);
            String failure = compare(actual, check.operator, expected);
            return new TestResult(name, failure == null, failure, false, "check");
        } catch (Exception e) {
            return new TestResult(name, false, e.getMessage(), false, "check");
        }
    }

    /** Actual value: either JSON node, text or number (both kept as text/node). */
    record Actual(JsonNode node, String text, boolean present) {
        static Actual ofText(String s) { return new Actual(null, s, s != null); }
        static Actual ofNode(JsonNode n) { return new Actual(n, JsonPathLite.asText(n), n != null); }
    }

    static Actual actual(Check check, HttpResult res, VariableContext vars) {
        return switch (check.source) {
            case STATUS -> Actual.ofText(String.valueOf(res.status));
            case RESPONSE_TIME -> Actual.ofText(String.valueOf(res.timeMs));
            case BODY_SIZE -> Actual.ofText(String.valueOf(res.size()));
            case BODY -> Actual.ofText(res.body);
            case HEADER -> Actual.ofText(res.header(vars.resolve(check.property)));
            case JSON_PATH -> {
                JsonNode root = res.json();
                if (root == null) throw new IllegalStateException("Response body is not valid JSON");
                yield Actual.ofNode(JsonPathLite.read(root, vars.resolve(check.property)));
            }
        };
    }

    /** Returns null if the check passes, otherwise the failure message. */
    static String compare(Actual a, CheckOperator op, String expected) {
        String shown = a.present() ? quote(a.text()) : "<missing>";
        return switch (op) {
            case EXISTS -> a.present() ? null : "expected value to exist";
            case NOT_EXISTS -> a.present() ? "expected value not to exist but was " + shown : null;
            case EQUALS -> equalsValue(a, expected) ? null : "expected " + shown + " to equal " + quote(expected);
            case NOT_EQUALS -> !equalsValue(a, expected) ? null : "expected " + shown + " to not equal " + quote(expected);
            case CONTAINS -> contains(a, expected) ? null : "expected " + shown + " to contain " + quote(expected);
            case NOT_CONTAINS -> !contains(a, expected) ? null : "expected " + shown + " to not contain " + quote(expected);
            case GREATER_THAN -> numeric(a, expected, c -> c > 0) ? null : "expected " + shown + " to be > " + expected;
            case GREATER_OR_EQUAL -> numeric(a, expected, c -> c >= 0) ? null : "expected " + shown + " to be >= " + expected;
            case LESS_THAN -> numeric(a, expected, c -> c < 0) ? null : "expected " + shown + " to be < " + expected;
            case LESS_OR_EQUAL -> numeric(a, expected, c -> c <= 0) ? null : "expected " + shown + " to be <= " + expected;
            case MATCHES -> a.present() && Pattern.compile(expected).matcher(a.text()).find() ? null
                    : "expected " + shown + " to match /" + expected + "/";
            case IS_EMPTY -> isEmpty(a) ? null : "expected " + shown + " to be empty";
            case IS_NOT_EMPTY -> !isEmpty(a) ? null : "expected value to not be empty";
            case TYPE_IS -> typeOf(a).equalsIgnoreCase(expected.trim()) ? null
                    : "expected type " + expected + " but was " + typeOf(a);
        };
    }

    static boolean equalsValue(Actual a, String expected) {
        if (!a.present()) return false;
        JsonNode n = a.node();
        if (n != null) {
            if (n.isNumber()) return numEq(n.asText(), expected);
            if (n.isBoolean()) return n.asText().equalsIgnoreCase(expected.trim());
            if (n.isNull()) return "null".equals(expected.trim());
            if (n.isTextual()) {
                if (n.asText().equals(expected)) return true;
                JsonNode e = JsonUtil.tryParse(expected);
                return e != null && e.isTextual() && e.asText().equals(n.asText());
            }
            JsonNode e = JsonUtil.tryParse(expected);
            return e != null && e.equals(n);
        }
        if (a.text().equals(expected)) return true;
        return numEq(a.text(), expected);
    }

    private static boolean numEq(String a, String b) {
        BigDecimal x = toNumber(a), y = toNumber(b);
        return x != null && y != null && x.compareTo(y) == 0;
    }

    static boolean contains(Actual a, String expected) {
        if (!a.present()) return false;
        JsonNode n = a.node();
        if (n != null && n.isArray()) {
            for (JsonNode el : n) if (equalsValue(Actual.ofNode(el), expected)) return true;
            return false;
        }
        if (n != null && n.isObject()) return n.has(expected);
        return a.text().contains(expected);
    }

    static boolean numeric(Actual a, String expected, java.util.function.IntPredicate cmp) {
        if (!a.present()) return false;
        BigDecimal x = toNumber(a.text()), y = toNumber(expected);
        if (x == null || y == null) return false;
        return cmp.test(x.compareTo(y));
    }

    static boolean isEmpty(Actual a) {
        if (!a.present()) return true;
        JsonNode n = a.node();
        if (n != null) {
            if (n.isNull()) return true;
            if (n.isContainerNode()) return n.isEmpty();
            return n.asText().isEmpty();
        }
        return a.text().isEmpty();
    }

    static String typeOf(Actual a) {
        if (!a.present()) return "undefined";
        JsonNode n = a.node();
        if (n == null) return toNumber(a.text()) != null ? "number" : "string";
        if (n.isArray()) return "array";
        if (n.isObject()) return "object";
        if (n.isNumber()) return "number";
        if (n.isBoolean()) return "boolean";
        if (n.isNull()) return "null";
        return "string";
    }

    static BigDecimal toNumber(String s) {
        if (s == null) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String quote(String s) {
        if (s == null) return "null";
        String t = s.length() > 200 ? s.substring(0, 200) + "..." : s;
        return "'" + t + "'";
    }
}
