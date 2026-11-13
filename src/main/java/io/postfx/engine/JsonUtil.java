package io.postfx.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.UncheckedIOException;

/** Shared Jackson configuration. */
public final class JsonUtil {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    private JsonUtil() {}

    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String toPrettyJson(Object o) {
        try {
            return MAPPER.writer(PRETTY).writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deep copy through JSON serialisation. */
    @SuppressWarnings("unchecked")
    public static <T> T copy(T o) {
        if (o == null) return null;
        return (T) fromJson(toJson(o), o.getClass());
    }

    /** Parses JSON or returns null if the text is not valid JSON. */
    public static JsonNode tryParse(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        try {
            return MAPPER.readTree(t);
        } catch (Exception e) {
            return null;
        }
    }

    /** Pretty prints JSON text, or returns the input unchanged if it is not JSON. */
    public static String prettyOrSame(String text) {
        JsonNode n = tryParse(text);
        if (n == null || !(n.isContainerNode())) return text;
        return toPrettyJson(n);
    }
}
