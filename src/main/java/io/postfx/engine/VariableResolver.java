package io.postfx.engine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces {{name}} placeholders (and Postman dynamic variables such as {{$guid}}). */
public final class VariableResolver {
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final int MAX_DEPTH = 10;

    private VariableResolver() {}

    public static String resolve(String text, VariableContext ctx) {
        if (text == null || text.indexOf("{{") < 0) return text;
        String current = text;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            String next = resolveOnce(current, ctx);
            if (next.equals(current)) break;
            current = next;
        }
        return current;
    }

    private static String resolveOnce(String text, VariableContext ctx) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value;
            if (name.startsWith("$")) {
                value = DynamicVariables.value(name);
                if (value == null && ctx != null) value = ctx.get(name);
            } else {
                value = ctx == null ? null : ctx.get(name);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** True if the text still contains unresolved placeholders. */
    public static boolean hasPlaceholders(String text) {
        return text != null && PLACEHOLDER.matcher(text).find();
    }
}
