package io.postfx.model;

import java.util.ArrayList;
import java.util.List;

/** Root folder with its own variable scope. */
public class Collection extends Folder {
    public List<KeyValue> variables = new ArrayList<>();

    public Collection() {
        auth = new Auth(AuthType.NONE);
    }

    public Collection(String name) {
        this();
        this.name = name;
    }
}
