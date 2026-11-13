package io.postfx.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Small JSON path implementation: {@code $.a.b[0].c}, {@code a.b}, {@code $['odd key']}, {@code items[-1]},
 * {@code items[*].id} (returns an array) and {@code items.length}.
 */
public final class JsonPathLite {
    private JsonPathLite() {}

    /** A path segment: either a field name, an index, or a wildcard. */
    public record Segment(String field, Integer index, boolean wildcard) {
        static Segment ofField(String f) { return new Segment(f, null, false); }
        static Segment ofIndex(int i) { return new Segment(null, i, false); }
        static Segment ofWildcard() { return new Segment(null, null, true); }
    }

    public static List<Segment> parse(String path) {
        List<Segment> out = new ArrayList<>();
        if (path == null) return out;
        String p = path.trim();
        if (p.startsWith("$")) p = p.substring(1);
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '.') {
                i++;
            } else if (c == '[') {
                int j = i + 1;
                while (j < p.length() && p.charAt(j) == ' ') j++;
                if (j < p.length() && (p.charAt(j) == '\'' || p.charAt(j) == '"')) {
                    char q = p.charAt(j);
                    int close = p.indexOf(q, j + 1);
                    if (close < 0) throw new IllegalArgumentException("Unclosed quote in path: " + path);
                    int end = p.indexOf(']', close);
                    if (end < 0) throw new IllegalArgumentException("Unclosed [ in path: " + path);
                    out.add(Segment.ofField(p.substring(j + 1, close)));
                    i = end + 1;
                } else {
                    int end = p.indexOf(']', i);
                    if (end < 0) throw new IllegalArgumentException("Unclosed [ in path: " + path);
                    String inner = p.substring(i + 1, end).trim();
                    if (inner.equals("*")) out.add(Segment.ofWildcard());
                    else out.add(Segment.ofIndex(Integer.parseInt(inner)));
                    i = end + 1;
                }
            } else {
                int j = i;
                while (j < p.length() && p.charAt(j) != '.' && p.charAt(j) != '[') j++;
                String name = p.substring(i, j);
                out.add(name.equals("*") ? Segment.ofWildcard() : Segment.ofField(name));
                i = j;
            }
        }
        return out;
    }

    /** Evaluates the path; returns null when the path does not exist. */
    public static JsonNode read(JsonNode root, String path) {
        if (root == null) return null;
        List<Segment> segments = parse(path);
        return eval(root, segments, 0);
    }

    private static JsonNode eval(JsonNode node, List<Segment> segs, int pos) {
        if (node == null) return null;
        if (pos == segs.size()) return node;
        Segment s = segs.get(pos);
        if (s.wildcard()) {
            ArrayNode result = JsonUtil.MAPPER.createArrayNode();
            Iterable<JsonNode> children = node.isArray() || node.isObject() ? node : List.of();
            for (JsonNode child : children) {
                JsonNode v = eval(child, segs, pos + 1);
                if (v != null) {
                    if (v.isArray() && pos + 1 < segs.size() && hasWildcard(segs, pos + 1)) result.addAll((ArrayNode) v);
                    else result.add(v);
                }
            }
            return result;
        }
        JsonNode next;
        if (s.index() != null) {
            if (!node.isArray()) return null;
            int idx = s.index() < 0 ? node.size() + s.index() : s.index();
            next = idx >= 0 && idx < node.size() ? node.get(idx) : null;
        } else if (node.isObject()) {
            next = node.get(s.field());
        } else if (node.isArray()) {
            if ("length".equals(s.field()) || "size()".equals(s.field())) {
                next = IntNode.valueOf(node.size());
            } else if (s.field().matches("-?\\d+")) {
                int idx = Integer.parseInt(s.field());
                idx = idx < 0 ? node.size() + idx : idx;
                next = idx >= 0 && idx < node.size() ? node.get(idx) : null;
            } else {
                return null;
            }
        } else if (node.isTextual() && "length".equals(s.field())) {
            next = IntNode.valueOf(node.asText().length());
        } else {
            return null;
        }
        return eval(next, segs, pos + 1);
    }

    private static boolean hasWildcard(List<Segment> segs, int from) {
        for (int i = from; i < segs.size(); i++) if (segs.get(i).wildcard()) return true;
        return false;
    }

    /** Text form of a node: plain text for scalars, compact JSON for containers. */
    public static String asText(JsonNode n) {
        if (n == null) return null;
        if (n.isValueNode()) return n.isNull() ? "null" : n.asText();
        return JsonUtil.toJson(n);
    }
}
