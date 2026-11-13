package io.postfx.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.postfx.engine.JsonUtil;
import io.postfx.model.Collection;
import io.postfx.model.RequestItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects the format of a file / pasted text and imports it. Supported:
 * Postman collection v2.0/v2.1, Postman environment &amp; globals, OpenAPI 3.x, Swagger 2.0 (JSON or YAML),
 * Insomnia v4 export, HAR, cURL command, PostFX collection.
 */
public final class Importer {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private Importer() {}

    public static ImportResult importFile(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String base = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        return importText(text, base);
    }

    public static ImportResult importText(String text, String nameHint) {
        String t = text.strip();
        if (t.startsWith("﻿")) t = t.substring(1);
        ImportResult result = new ImportResult();
        if (CurlCodec.looksLikeCurl(t)) {
            result.format = "cURL";
            RequestItem item = CurlCodec.parse(t);
            Collection c = new Collection(nameHint == null || nameHint.isBlank() ? "cURL import" : nameHint);
            c.items.add(item);
            result.collections.add(c);
            return result;
        }
        JsonNode n = JsonUtil.tryParse(t);
        if (n == null) {
            try {
                n = YAML.readTree(t);
            } catch (Exception e) {
                throw new IllegalArgumentException("Unrecognized format: the content is neither JSON, YAML nor a cURL command");
            }
        }
        if (n == null || !n.isObject()) throw new IllegalArgumentException("Unrecognized format");

        if (PostmanImporter.isCollection(n)) {
            result.format = "Postman collection";
            result.collections.add(PostmanImporter.importCollection(n, result.warnings));
        } else if (PostmanImporter.isEnvironment(n)) {
            if ("globals".equals(n.path("_postman_variable_scope").asText())) {
                result.format = "Postman globals";
                for (JsonNode v : n.path("values")) result.globals.add(PostmanImporter.variable(v));
            } else {
                result.format = "Postman environment";
                result.environments.add(PostmanImporter.importEnvironment(n));
            }
        } else if (OpenApiImporter.isOpenApi(n)) {
            result.format = n.has("swagger") ? "Swagger 2.0" : "OpenAPI " + n.path("openapi").asText();
            result.collections.add(OpenApiImporter.importSpec(n, result.warnings));
        } else if (InsomniaImporter.isInsomnia(n)) {
            result.format = "Insomnia export";
            ImportResult r = InsomniaImporter.importExport(n);
            result.collections.addAll(r.collections);
            result.environments.addAll(r.environments);
        } else if (HarImporter.isHar(n)) {
            result.format = "HAR";
            result.collections.add(HarImporter.importHar(n, nameHint));
        } else if ("collection".equals(n.path("kind").asText()) && n.has("items")) {
            result.format = "PostFX collection";
            Collection c = JsonUtil.MAPPER.convertValue(n, Collection.class);
            result.collections.add(c);
        } else {
            throw new IllegalArgumentException("Unrecognized format. Supported: Postman collection/environment, OpenAPI 3, Swagger 2, Insomnia, HAR, cURL");
        }
        return result;
    }
}
