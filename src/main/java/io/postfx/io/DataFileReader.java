package io.postfx.io;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.engine.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads iteration data files (CSV with a header row, or a JSON array of objects) like the Postman runner. */
public final class DataFileReader {
    private DataFileReader() {}

    public static List<Map<String, String>> read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".json") || text.stripLeading().startsWith("[")) return json(text);
        return csv(text);
    }

    static List<Map<String, String>> json(String text) {
        JsonNode n = JsonUtil.tryParse(text);
        if (n == null || !n.isArray()) throw new IllegalArgumentException("JSON data file must contain an array of objects");
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode o : n) {
            Map<String, String> row = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = o.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                row.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asText() : JsonUtil.toJson(e.getValue()));
            }
            rows.add(row);
        }
        return rows;
    }

    static List<Map<String, String>> csv(String text) {
        List<List<String>> records = parseCsv(text);
        List<Map<String, String>> rows = new ArrayList<>();
        if (records.isEmpty()) return rows;
        List<String> header = records.get(0);
        for (int i = 1; i < records.size(); i++) {
            List<String> rec = records.get(i);
            if (rec.size() == 1 && rec.get(0).isEmpty()) continue;
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) row.put(header.get(c).strip(), c < rec.size() ? rec.get(c) : "");
            rows.add(row);
        }
        return rows;
    }

    /** RFC 4180 parser (quoted fields, escaped quotes, newlines inside quotes); ',' or ';' separator. */
    static List<List<String>> parseCsv(String text) {
        String firstLine = text.lines().findFirst().orElse("");
        char sep = firstLine.chars().filter(ch -> ch == ';').count() > firstLine.chars().filter(ch -> ch == ',').count() ? ';' : ',';
        List<List<String>> out = new ArrayList<>();
        List<String> rec = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == sep) {
                rec.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                rec.add(field.toString());
                field.setLength(0);
                out.add(rec);
                rec = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !rec.isEmpty()) {
            rec.add(field.toString());
            out.add(rec);
        }
        return out;
    }
}
