package io.postfx.model;

import java.util.ArrayList;
import java.util.List;

public class Environment {
    public String id = Ids.newId();
    public String name = "";
    public List<KeyValue> values = new ArrayList<>();

    public Environment() {}

    public Environment(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
