package io.postfx.model;

import java.util.ArrayList;
import java.util.List;

public class Request {
    public String method = "GET";
    /** Full URL including the query string; may contain {{variables}}. */
    public String url = "";
    /** Disabled query params (enabled ones live in the URL query string). */
    public List<KeyValue> disabledParams = new ArrayList<>();
    /** Values of Postman style path variables, e.g. {@code /users/:id}. */
    public List<KeyValue> pathVariables = new ArrayList<>();
    public List<KeyValue> headers = new ArrayList<>();
    public Body body = new Body();
    public Auth auth = new Auth(AuthType.INHERIT);
}
