package io.postfx.model;

import java.util.ArrayList;
import java.util.List;

/** A saved request with its scripts, declarative checks and variable extractors. */
public class RequestItem extends Item {
    public Request request = new Request();
    public String preRequestScript = "";
    public String testScript = "";
    public List<Check> checks = new ArrayList<>();
    public List<Extractor> extractors = new ArrayList<>();

    public RequestItem() {}

    public RequestItem(String name) {
        this.name = name;
    }

    public RequestItem(String name, String method, String url) {
        this.name = name;
        this.request.method = method;
        this.request.url = url;
    }
}
