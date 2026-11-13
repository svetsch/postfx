package io.postfx.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Generic key/value row used for headers, query params, form fields and variables. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KeyValue {
    public String key = "";
    public String value = "";
    public boolean enabled = true;
    public String description;
    /** "text" (default), "file" (form-data file part) or "secret" (masked variable). */
    public String type;

    public KeyValue() {}

    public KeyValue(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public KeyValue(String key, String value, boolean enabled) {
        this(key, value);
        this.enabled = enabled;
    }

    @JsonIgnore
    public boolean isFile() {
        return "file".equals(type);
    }

    @JsonIgnore
    public boolean isBlank() {
        return (key == null || key.isBlank()) && (value == null || value.isBlank());
    }

    @Override
    public String toString() {
        return key + "=" + value;
    }
}
