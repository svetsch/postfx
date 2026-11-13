package io.postfx.io;

import com.fasterxml.jackson.databind.JsonNode;
import io.postfx.engine.JsonUtil;
import io.postfx.model.Auth;
import io.postfx.model.AuthType;
import io.postfx.model.BodyMode;
import io.postfx.model.Check;
import io.postfx.model.CheckOperator;
import io.postfx.model.CheckSource;
import io.postfx.model.Collection;
import io.postfx.model.KeyValue;
import io.postfx.model.RequestItem;

import java.util.Set;

/** Imports HTTP Archive (.har) files recorded by browsers / proxies. Each entry becomes a request. */
public final class HarImporter {
    private static final Set<String> SKIP_HEADERS = Set.of("content-length", "host", "connection", "accept-encoding");

    private HarImporter() {}

    public static boolean isHar(JsonNode n) {
        return n.has("log") && n.path("log").has("entries");
    }

    public static Collection importHar(JsonNode n, String name) {
        Collection c = new Collection(name == null ? "HAR import" : name);
        c.description = "Imported from HAR (" + n.path("log").path("creator").path("name").asText("unknown tool") + ")";
        for (JsonNode e : n.path("log").path("entries")) {
            JsonNode req = e.path("request");
            RequestItem r = new RequestItem();
            r.request.auth = new Auth(AuthType.NONE);
            r.request.method = req.path("method").asText("GET");
            r.request.url = req.path("url").asText("");
            for (JsonNode h : req.path("headers")) {
                String key = h.path("name").asText("");
                if (key.startsWith(":") || SKIP_HEADERS.contains(key.toLowerCase())) continue;
                r.request.headers.add(new KeyValue(key, h.path("value").asText("")));
            }
            JsonNode post = req.path("postData");
            if (!post.isMissingNode()) {
                String mime = post.path("mimeType").asText("");
                if (post.path("params").size() > 0) {
                    boolean multipart = mime.contains("multipart");
                    r.request.body.mode = multipart ? BodyMode.FORMDATA : BodyMode.URLENCODED;
                    for (JsonNode p : post.path("params")) {
                        KeyValue kv = new KeyValue(p.path("name").asText(""), p.path("value").asText(""));
                        if (p.has("fileName")) {
                            kv.type = "file";
                            kv.value = p.path("fileName").asText();
                        }
                        (multipart ? r.request.body.formdata : r.request.body.urlencoded).add(kv);
                    }
                    if (multipart) r.request.headers.removeIf(h -> h.key.equalsIgnoreCase("Content-Type"));
                } else if (post.has("text")) {
                    r.request.body.mode = BodyMode.RAW;
                    r.request.body.raw = post.path("text").asText("");
                    r.request.body.rawLanguage = mime.contains("json") || JsonUtil.tryParse(r.request.body.raw) != null ? "json"
                            : mime.contains("xml") ? "xml" : "text";
                }
            }
            r.name = CurlCodec.defaultName(r.request);
            int status = e.path("response").path("status").asInt(0);
            if (status > 0) r.checks.add(new Check(CheckSource.STATUS, "", CheckOperator.EQUALS, String.valueOf(status)));
            c.items.add(r);
        }
        return c;
    }
}
