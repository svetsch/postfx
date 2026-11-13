package io.postfx.io;

import io.postfx.engine.JsonUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort translation of common Postman script statements into JUnit / REST Assured code.
 * Anything not understood is kept as a {@code // TODO} comment so nothing is silently lost.
 */
final class ScriptToJava {
    enum Kind { INT, LONG, STRING, JSON, BOOL, NUMBER }

    record Expr(String code, Kind kind) {}

    private static final Pattern TEST_START = Pattern.compile(
            "^pm\\.test\\(\\s*(['\"`])(.*?)\\1\\s*,\\s*(?:async\\s+)?(?:function\\s*\\w*\\s*\\(\\s*\\)|\\(\\s*\\)\\s*=>)\\s*(\\{?)(.*)$");
    private static final Pattern DECL = Pattern.compile("^(?:var|let|const)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(.+?);?$");
    private static final Pattern SET_VAR = Pattern.compile(
            "^(?:pm\\.(?:environment|globals|collectionVariables|variables)\\.set|postman\\.set(?:Environment|Global)Variable)\\(\\s*(['\"`])(.+?)\\1\\s*,\\s*(.+)\\)\\s*;?$");
    private static final Pattern UNSET_VAR = Pattern.compile(
            "^(?:pm\\.(?:environment|globals|collectionVariables|variables)\\.unset|postman\\.clear(?:Environment|Global)Variable)\\(\\s*(['\"`])(.+?)\\1\\s*\\)\\s*;?$");
    private static final Pattern GET_VAR = Pattern.compile(
            "^(?:pm\\.(?:environment|globals|collectionVariables|variables|iterationData)\\.get|postman\\.get(?:Environment|Global)Variable)\\(\\s*(['\"`])(.+?)\\1\\s*\\)$");
    private static final Pattern STATUS = Pattern.compile("^pm\\.response\\.to\\.(not\\.)?(?:have|be)\\.status\\(\\s*(\\d{3})\\s*\\)\\s*;?$");
    private static final Pattern HEADER = Pattern.compile("^pm\\.response\\.to\\.(not\\.)?(?:have|be)\\.header\\(\\s*(['\"`])(.+?)\\2\\s*(?:,\\s*(.+?))?\\)\\s*;?$");
    private static final Pattern RESPONSE_PROP = Pattern.compile("^pm\\.response\\.to\\.(not\\.)?(?:be|have)\\.(ok|success|json|clientError|serverError|error|notFound|unauthorized|forbidden|badRequest|withBody)\\s*;?$");
    private static final Pattern BODY = Pattern.compile("^pm\\.response\\.to\\.(not\\.)?have\\.body\\((.+)\\)\\s*;?$");
    private static final Pattern JSON_BODY = Pattern.compile("^pm\\.response\\.to\\.(not\\.)?have\\.jsonBody\\(\\s*(['\"`])(.+?)\\2\\s*\\)\\s*;?$");
    private static final Pattern LEGACY_TEST = Pattern.compile("^tests\\[\\s*(['\"`])(.+?)\\1\\s*]\\s*=\\s*(.+?);?$");
    private static final Pattern JSON_ROOT = Pattern.compile("^(?:pm\\.response\\.json\\(\\)|JSON\\.parse\\(\\s*(?:responseBody|pm\\.response\\.text\\(\\))\\s*\\))");
    private static final Pattern ACCESSOR = Pattern.compile("\\.([A-Za-z_$][\\w$]*)|\\[(\\d+)]|\\[\\s*(['\"])(.+?)\\3\\s*]");

    private final Set<String> jsonAliases = new HashSet<>();
    private final Map<String, Kind> locals = new HashMap<>();
    private String currentTest;

    /** Translates a whole script; returns Java statements (without indentation). */
    List<String> translate(String script) {
        List<String> out = new ArrayList<>();
        if (script == null || script.isBlank()) return out;
        for (String raw : script.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("//")) {
                out.add(line);
                continue;
            }
            if (line.startsWith("/*") || line.startsWith("*")) {
                out.add("// " + line.replaceFirst("^/?\\*+/?", "").strip());
                continue;
            }
            translateLine(line, out);
        }
        return out;
    }

    private void translateLine(String line, List<String> out) {
        Matcher m = TEST_START.matcher(line);
        if (m.find()) {
            currentTest = m.group(2);
            out.add("// Test: " + currentTest);
            String rest = m.group(4).strip();
            boolean braced = !m.group(3).isEmpty();
            // single line test: pm.test("x", () => expr);   or   pm.test("x", function () { a; b; });
            String inner = rest.replaceFirst("\\s*}?\\s*\\)\\s*;?\\s*$", "");
            if (!inner.isBlank()) {
                for (String stmt : splitStatements(inner)) statement(stmt.strip(), out);
            }
            if (!braced || rest.matches(".*}\\s*\\)\\s*;?\\s*$")) currentTest = null;
            return;
        }
        if (line.matches("^}\\s*\\)?\\s*;?$") || line.equals("{")) {
            currentTest = null;
            return;
        }
        for (String stmt : splitStatements(line)) statement(stmt.strip(), out);
    }

    private void statement(String s, List<String> out) {
        if (s.isEmpty()) return;
        if (s.startsWith("//")) {
            out.add(s);
            return;
        }
        String st = s.endsWith(";") ? s.substring(0, s.length() - 1).strip() : s;
        String msg = currentTest == null ? null : str(currentTest);
        Matcher m;

        if ((m = STATUS.matcher(st)).matches()) {
            out.add(assertCall(m.group(1) != null ? "assertNotEquals" : "assertEquals", m.group(2), "response.statusCode()", msg));
            return;
        }
        if ((m = RESPONSE_PROP.matcher(st)).matches()) {
            boolean neg = m.group(1) != null;
            String cond = switch (m.group(2)) {
                case "ok", "success" -> "response.statusCode() >= 200 && response.statusCode() < 300";
                case "json" -> "String.valueOf(response.contentType()).contains(\"json\")";
                case "clientError" -> "response.statusCode() >= 400 && response.statusCode() < 500";
                case "serverError" -> "response.statusCode() >= 500";
                case "error" -> "response.statusCode() >= 400";
                case "notFound" -> "response.statusCode() == 404";
                case "unauthorized" -> "response.statusCode() == 401";
                case "forbidden" -> "response.statusCode() == 403";
                case "badRequest" -> "response.statusCode() == 400";
                default -> "!response.asString().isEmpty()";
            };
            out.add(assertCall(neg ? "assertFalse" : "assertTrue", cond, null, msg));
            return;
        }
        if ((m = HEADER.matcher(st)).matches()) {
            boolean neg = m.group(1) != null;
            String h = "response.header(" + str(m.group(3)) + ")";
            if (m.group(4) != null) {
                Expr v = value(m.group(4).strip());
                if (v == null) { todo(s, out); return; }
                out.add(assertCall(neg ? "assertNotEquals" : "assertEquals", asString(v), h, msg));
            } else {
                out.add(assertCall(neg ? "assertNull" : "assertNotNull", h, null, msg));
            }
            return;
        }
        if ((m = BODY.matcher(st)).matches()) {
            Expr v = value(m.group(2).strip());
            if (v == null) { todo(s, out); return; }
            out.add(assertCall(m.group(1) != null ? "assertNotEquals" : "assertEquals", asString(v), "response.asString()", msg));
            return;
        }
        if ((m = JSON_BODY.matcher(st)).matches()) {
            String path = gpath(m.group(3).replaceAll("^\\$\\.?", ""));
            out.add(assertCall(m.group(1) != null ? "assertNull" : "assertNotNull", "json(response, " + str(path) + ")", null, msg));
            return;
        }
        if (st.startsWith("pm.expect(")) {
            expectStatement(s, st, msg, out);
            return;
        }
        if ((m = SET_VAR.matcher(st)).matches()) {
            Expr v = value(m.group(3).strip());
            if (v == null) { todo(s, out); return; }
            out.add("vars.put(" + str(m.group(2)) + ", " + asString(v) + ");");
            return;
        }
        if ((m = UNSET_VAR.matcher(st)).matches()) {
            out.add("vars.remove(" + str(m.group(2)) + ");");
            return;
        }
        if ((m = DECL.matcher(st)).matches()) {
            String name = m.group(1);
            String rhs = m.group(2).strip();
            if (JSON_ROOT.matcher(rhs).matches() && rhs.replaceFirst(JSON_ROOT.pattern(), "").isEmpty()) {
                jsonAliases.add(name);
                out.add("// '" + name + "' = response JSON, read with json(response, path)");
                return;
            }
            Expr v = value(rhs);
            if (v == null) { todo(s, out); return; }
            locals.put(name, v.kind());
            String type = switch (v.kind()) {
                case INT -> "int";
                case LONG -> "long";
                case BOOL -> "boolean";
                case NUMBER -> "double";
                case STRING -> "String";
                case JSON -> "Object";
            };
            out.add(type + " " + name + " = " + v.code() + ";");
            return;
        }
        if ((m = LEGACY_TEST.matcher(st)).matches()) {
            String name = str(m.group(2));
            String cond = m.group(3).strip();
            Matcher c = Pattern.compile("^responseCode\\.code\\s*={2,3}\\s*(\\d{3})$").matcher(cond);
            Matcher b = Pattern.compile("^responseBody\\.(?:has|includes|indexOf)\\(\\s*(['\"])(.+?)\\1\\s*\\)(?:\\s*!==?\\s*-1)?$").matcher(cond);
            if (c.matches()) out.add("assertEquals(" + c.group(1) + ", response.statusCode(), " + name + ");");
            else if (b.matches()) out.add("assertTrue(response.asString().contains(" + str(b.group(2)) + "), " + name + ");");
            else todo(s, out);
            return;
        }
        if (st.startsWith("console.")) {
            out.add("// " + st);
            return;
        }
        if (st.contains("setNextRequest") || st.contains("skipRequest")) {
            out.add("// flow control is not available in JUnit (tests always run in order): " + st);
            return;
        }
        todo(s, out);
    }

    private void expectStatement(String original, String st, String msg, List<String> out) {
        int open = "pm.expect(".length() - 1;
        int close = matchingParen(st, open);
        if (close < 0) { todo(original, out); return; }
        String[] args = splitArgs(st.substring(open + 1, close));
        Expr actual = value(args[0].strip());
        if (args.length > 1) msg = args[1].strip().matches("^(['\"`]).*\\1$") ? str(args[1].strip().substring(1, args[1].strip().length() - 1)) : msg;
        if (actual == null) { todo(original, out); return; }
        String chain = st.substring(close + 1);
        boolean neg = false;
        List<String> parts = new ArrayList<>();
        // split ".to.not.equal(x)" into words, keeping call arguments
        int i = 0;
        while (i < chain.length()) {
            if (chain.charAt(i) != '.') { todo(original, out); return; }
            int j = i + 1;
            while (j < chain.length() && (Character.isLetterOrDigit(chain.charAt(j)) || chain.charAt(j) == '_')) j++;
            String word = chain.substring(i + 1, j);
            if (j < chain.length() && chain.charAt(j) == '(') {
                int end = matchingParen(chain, j);
                if (end < 0) { todo(original, out); return; }
                parts.add(word + chain.substring(j, end + 1));
                i = end + 1;
            } else {
                parts.add(word);
                i = j;
            }
        }
        List<String> generated = new ArrayList<>();
        int assertions = 0;
        for (String p : parts) {
            switch (p) {
                case "to", "be", "been", "is", "that", "which", "and", "has", "have", "with", "at", "of", "same", "deep", "does", "but", "also", "still" -> { }
                case "not" -> neg = !neg;
                default -> {
                    // chai flags apply to the assertions that follow them: a("string").and.not.be.empty
                    if (!assertion(p, neg, actual, msg, generated)) {
                        todo(original, out);
                        return;
                    }
                    assertions++;
                }
            }
        }
        if (assertions == 0) {
            todo(original, out);
            return;
        }
        out.addAll(generated);
    }

    /** Emits one chai assertion; returns false when it cannot be translated. */
    private boolean assertion(String op, boolean neg, Expr actual, String msg, List<String> out) {
        String name = op.contains("(") ? op.substring(0, op.indexOf('(')) : op;
        String arg = op.contains("(") ? op.substring(op.indexOf('(') + 1, op.length() - 1).strip() : "";
        String a = actual.code();
        switch (name) {
            case "eql", "equal", "equals", "eq", "eqls" -> {
                Expr exp = value(arg);
                if (exp == null) { return false; }
                if (actual.kind() == Kind.JSON) {
                    out.add("check(" + nz(msg) + ", " + a + ", " + str(neg ? "NOT_EQUALS" : "EQUALS") + ", " + asString(exp) + ");");
                } else if (isNumeric(actual.kind()) && isNumeric(exp.kind())) {
                    out.add(assertCall(neg ? "assertNotEquals" : "assertEquals", exp.code(), a, msg));
                } else {
                    out.add(assertCall(neg ? "assertNotEquals" : "assertEquals", asString(exp), asString(actual), msg));
                }
            }
            case "include", "includes", "contain", "contains", "string" -> {
                Expr exp = value(arg);
                if (exp == null) { return false; }
                out.add("check(" + nz(msg) + ", " + a + ", " + str(neg ? "NOT_CONTAINS" : "CONTAINS") + ", " + asString(exp) + ");");
            }
            case "above", "gt", "greaterThan", "least", "gte", "greaterThanOrEqual", "below", "lt", "lessThan", "most", "lte", "lessThanOrEqual" -> {
                String n = numArg(arg);
                if (n == null) { return false; }
                String op2 = switch (name) {
                    case "above", "gt", "greaterThan" -> " > ";
                    case "least", "gte", "greaterThanOrEqual" -> " >= ";
                    case "below", "lt", "lessThan" -> " < ";
                    default -> " <= ";
                };
                out.add(assertCall(neg ? "assertFalse" : "assertTrue", "num(" + a + ")" + op2 + n, null, msg));
            }
            case "a", "an" -> {
                Expr exp = value(arg);
                if (exp == null) { return false; }
                out.add(assertCall(neg ? "assertNotEquals" : "assertEquals", exp.code(), "typeOf(" + a + ")", msg));
            }
            case "true", "false" -> out.add(assertCall(neg ? "assertNotEquals" : "assertEquals", "Boolean." + name.toUpperCase(), a, msg));
            case "null", "undefined" -> out.add(assertCall(neg ? "assertNotNull" : "assertNull", a, null, msg));
            case "exist", "exists", "ok" -> out.add(assertCall(neg ? "assertNull" : "assertNotNull", a, null, msg));
            case "empty" -> out.add(assertCall(neg ? "assertFalse" : "assertTrue", "isEmpty(" + a + ")", null, msg));
            case "lengthOf", "length" -> {
                if (!arg.matches("\\d+")) { return false; }
                out.add(assertCall(neg ? "assertNotEquals" : "assertEquals", arg, "size(" + a + ")", msg));
            }
            case "match", "matches" -> {
                Matcher re = Pattern.compile("^/(.*)/([gimsuy]*)$").matcher(arg);
                if (!re.matches()) { return false; }
                String flags = re.group(2).contains("i") ? ", Pattern.CASE_INSENSITIVE" : "";
                out.add(assertCall(neg ? "assertFalse" : "assertTrue", "Pattern.compile(" + str(re.group(1)) + flags + ").matcher(text(" + a + ")).find()", null, msg));
            }
            case "property" -> {
                String[] pa = splitArgs(arg);
                Expr key = value(pa[0].strip());
                if (key == null || key.kind() != Kind.STRING || !key.code().startsWith("\"") || actual.kind() != Kind.JSON || !a.startsWith("json(response, ")) {
                    return false;
                }
                String basePath = a.substring("json(response, ".length(), a.length() - 1);
                String base = (String) JsonUtil.fromJson(basePath, String.class);
                String keyName = (String) JsonUtil.fromJson(key.code(), String.class);
                String full = base.equals("$") ? keyName : base + "." + keyName;
                String target = "json(response, " + str(full) + ")";
                if (pa.length > 1) {
                    Expr exp = value(pa[1].strip());
                    if (exp == null) { return false; }
                    out.add("check(" + nz(msg) + ", " + target + ", " + str(neg ? "NOT_EQUALS" : "EQUALS") + ", " + asString(exp) + ");");
                } else {
                    out.add(assertCall(neg ? "assertNull" : "assertNotNull", target, null, msg));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ values

    /** Java expression for a JavaScript value expression, or null if not understood. */
    Expr value(String js) {
        String e = js.strip();
        if (e.endsWith(";")) e = e.substring(0, e.length() - 1).strip();
        if (e.isEmpty()) return null;
        // literals
        if (e.matches("^'(?:[^'\\\\]|\\\\.)*'$") || e.matches("^\"(?:[^\"\\\\]|\\\\.)*\"$") || e.matches("^`[^`$]*`$")) {
            String inner = e.substring(1, e.length() - 1).replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\");
            return new Expr(str(inner), Kind.STRING);
        }
        if (e.matches("^-?\\d+$")) return new Expr(e, e.length() > 9 ? Kind.LONG : Kind.INT);
        if (e.matches("^-?\\d*\\.\\d+$")) return new Expr(e, Kind.NUMBER);
        if (e.equals("true") || e.equals("false")) return new Expr(e, Kind.BOOL);
        if (e.equals("null") || e.equals("undefined")) return new Expr("null", Kind.JSON);
        // response values
        switch (e) {
            case "pm.response.code", "responseCode.code" -> { return new Expr("response.statusCode()", Kind.INT); }
            case "pm.response.status", "responseCode.name" -> { return new Expr("response.statusLine()", Kind.STRING); }
            case "pm.response.responseTime", "responseTime" -> { return new Expr("response.time()", Kind.LONG); }
            case "pm.response.text()", "responseBody" -> { return new Expr("response.asString()", Kind.STRING); }
            case "pm.response.responseSize" -> { return new Expr("response.asByteArray().length", Kind.INT); }
            case "Date.now()", "new Date().getTime()" -> { return new Expr("System.currentTimeMillis()", Kind.LONG); }
            case "new Date().toISOString()" -> { return new Expr("java.time.Instant.now().toString()", Kind.STRING); }
            default -> { }
        }
        Matcher m = Pattern.compile("^(?:pm\\.response\\.headers\\.get|postman\\.getResponseHeader)\\(\\s*(['\"`])(.+?)\\1\\s*\\)$").matcher(e);
        if (m.matches()) return new Expr("response.header(" + str(m.group(2)) + ")", Kind.STRING);
        m = GET_VAR.matcher(e);
        if (m.matches()) return new Expr("vars.get(" + str(m.group(2)) + ")", Kind.STRING);
        m = Pattern.compile("^pm\\.variables\\.replaceIn\\(\\s*(['\"`])(.+?)\\1\\s*\\)$").matcher(e);
        if (m.matches()) return new Expr("v(" + str(m.group(2)) + ")", Kind.STRING);
        if (locals.containsKey(e)) return new Expr(e, locals.get(e));
        // JSON access: pm.response.json().a.b[0] / alias.a.b / JSON.parse(responseBody).x
        String path = jsonPath(e);
        if (path != null) {
            if (path.endsWith(".size()")) return new Expr("json(response, " + str(path) + ")", Kind.JSON);
            return new Expr("json(response, " + str(path.isEmpty() ? "$" : path) + ")", Kind.JSON);
        }
        // Number(x) / parseInt(x) / parseFloat(x)
        m = Pattern.compile("^(?:Number|parseInt|parseFloat)\\((.+?)(?:,\\s*10)?\\)$").matcher(e);
        if (m.matches()) {
            Expr inner = value(m.group(1));
            return inner == null ? null : isNumeric(inner.kind()) ? inner : new Expr("num(" + inner.code() + ")", Kind.NUMBER);
        }
        // String(x) / x.toString()
        m = Pattern.compile("^String\\((.+)\\)$").matcher(e);
        if (m.matches()) {
            Expr inner = value(m.group(1));
            return inner == null ? null : new Expr(asString(inner), Kind.STRING);
        }
        return null;
    }

    private String jsonPath(String e) {
        String rest;
        Matcher root = JSON_ROOT.matcher(e);
        if (root.find()) {
            rest = e.substring(root.end());
        } else {
            Matcher alias = Pattern.compile("^([A-Za-z_$][\\w$]*)").matcher(e);
            if (!alias.find() || !jsonAliases.contains(alias.group(1))) return null;
            rest = e.substring(alias.end());
        }
        StringBuilder path = new StringBuilder();
        Matcher a = ACCESSOR.matcher(rest);
        int pos = 0;
        while (a.find()) {
            if (a.start() != pos) return null;
            pos = a.end();
            if (a.group(1) != null) {
                if (a.group(1).equals("length") && a.end() == rest.length()) {
                    path.append(path.length() == 0 ? "size()" : ".size()");
                } else {
                    if (path.length() > 0) path.append('.');
                    path.append(a.group(1));
                }
            } else if (a.group(2) != null) {
                path.append('[').append(a.group(2)).append(']');
            } else {
                if (path.length() > 0) path.append('.');
                path.append('\'').append(a.group(4)).append('\'');
            }
        }
        if (pos != rest.length()) return null;
        return path.toString();
    }

    /** Converts a JS-ish path ("a.b[0]", "$.a") to a REST Assured GPath. */
    static String gpath(String p) {
        return p.replace("[*]", "");
    }

    // ------------------------------------------------------------------ helpers

    /** Java numeric expression for a comparison argument, or null if not understood. */
    private String numArg(String js) {
        String t = js.strip();
        if (t.matches("-?\\d+(\\.\\d+)?")) return t;
        Expr e = value(t);
        if (e == null) return null;
        return isNumeric(e.kind()) ? e.code() : "num(" + e.code() + ")";
    }

    private static boolean isNumeric(Kind k) {
        return k == Kind.INT || k == Kind.LONG || k == Kind.NUMBER;
    }

    static String asString(Expr e) {
        return switch (e.kind()) {
            case STRING -> e.code();
            case JSON -> "text(" + e.code() + ")";
            default -> "String.valueOf(" + e.code() + ")";
        };
    }

    private static String assertCall(String fn, String a, String b, String msg) {
        StringBuilder sb = new StringBuilder(fn).append('(').append(a);
        if (b != null) sb.append(", ").append(b);
        if (msg != null) sb.append(", ").append(msg);
        return sb.append(");").toString();
    }

    private static String nz(String msg) {
        return msg == null ? "null" : msg;
    }

    private static void todo(String s, List<String> out) {
        out.add("// TODO translate: " + s);
    }

    static String str(String s) {
        return JsonUtil.toJson(s);
    }

    static int matchingParen(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** Splits on top-level commas. */
    static String[] splitArgs(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out.toArray(new String[0]);
    }

    /** Splits on top-level semicolons. */
    static List<String> splitStatements(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ';' && depth == 0) {
                out.add(s.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < s.length() && !s.substring(start).isBlank()) out.add(s.substring(start));
        return out;
    }
}
