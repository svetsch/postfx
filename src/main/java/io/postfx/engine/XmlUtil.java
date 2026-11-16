package io.postfx.engine;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** XML helpers: detection and pretty printing (DTDs and external entities are refused). */
public final class XmlUtil {
    private static final Pattern DECLARATION = Pattern.compile("^\\s*(<\\?xml[^?]*\\?>)");

    private XmlUtil() {}

    /** True when the text looks like an XML document (starts with a tag or an XML declaration). */
    public static boolean looksLikeXml(String text) {
        if (text == null) return false;
        String t = text.stripLeading();
        return t.startsWith("<") && !t.regionMatches(true, 0, "<!doctype html", 0, 14) && !t.regionMatches(true, 0, "<html", 0, 5);
    }

    /** Parses the text; returns null when it is not well-formed XML. */
    public static Document tryParse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new InputSource(new StringReader(text.strip())));
        } catch (Exception e) {
            return null;
        }
    }

    /** Element being written by the pretty printer. */
    private static final class Frame {
        final String name;
        boolean children;
        final StringBuilder text = new StringBuilder();

        Frame(String name) {
            this.name = name;
        }
    }

    /**
     * Indented XML (2 spaces), or the input unchanged when it is not well-formed XML. Attribute order, namespace
     * declarations, comments, CDATA sections and the XML declaration are kept as written; only whitespace between
     * elements changes. Mixed content (text and elements in the same element) is left untouched.
     */
    public static String prettyOrSame(String text) {
        if (tryParse(text) == null) return text; // also refuses DOCTYPE / external entities
        try {
            javax.xml.stream.XMLInputFactory f = javax.xml.stream.XMLInputFactory.newFactory();
            f.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            f.setProperty(javax.xml.stream.XMLInputFactory.IS_COALESCING, false);
            f.setProperty(javax.xml.stream.XMLInputFactory.IS_NAMESPACE_AWARE, true);
            try {
                // the JDK parser reports CDATA sections as plain text unless asked to keep them
                f.setProperty("http://java.sun.com/xml/stream/properties/report-cdata-event", Boolean.TRUE);
            } catch (IllegalArgumentException ignored) {
                // other StAX implementations report CDATA events by default
            }
            javax.xml.stream.XMLStreamReader r = f.createXMLStreamReader(new StringReader(text.strip()));
            StringBuilder out = new StringBuilder();
            Matcher decl = DECLARATION.matcher(text);
            if (decl.find()) out.append(decl.group(1));
            java.util.ArrayDeque<Frame> stack = new java.util.ArrayDeque<>();
            boolean openTag = false;
            while (r.hasNext()) {
                int ev = r.next();
                switch (ev) {
                    case javax.xml.stream.XMLStreamConstants.START_ELEMENT -> {
                        Frame parent = stack.peek();
                        if (parent != null) {
                            if (!parent.text.toString().isBlank()) return text; // mixed content
                            parent.text.setLength(0);
                            parent.children = true;
                        }
                        if (openTag) out.append('>');
                        newline(out, stack.size());
                        String name = qname(r.getPrefix(), r.getLocalName());
                        out.append('<').append(name);
                        for (int i = 0; i < r.getNamespaceCount(); i++) {
                            String p = r.getNamespacePrefix(i);
                            out.append(p == null || p.isEmpty() ? " xmlns" : " xmlns:" + p)
                                    .append("=\"").append(escapeAttr(r.getNamespaceURI(i))).append('"');
                        }
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            out.append(' ').append(qname(r.getAttributePrefix(i), r.getAttributeLocalName(i)))
                                    .append("=\"").append(escapeAttr(r.getAttributeValue(i))).append('"');
                        }
                        stack.push(new Frame(name));
                        openTag = true;
                    }
                    case javax.xml.stream.XMLStreamConstants.CHARACTERS, javax.xml.stream.XMLStreamConstants.SPACE -> {
                        Frame top = stack.peek();
                        if (top == null) break;
                        if (top.children && !r.getText().isBlank()) return text; // mixed content
                        if (!top.children) top.text.append(escapeText(r.getText()));
                    }
                    case javax.xml.stream.XMLStreamConstants.CDATA -> {
                        Frame top = stack.peek();
                        if (top == null) break;
                        if (top.children) return text;
                        top.text.append("<![CDATA[").append(r.getText()).append("]]>");
                    }
                    case javax.xml.stream.XMLStreamConstants.END_ELEMENT -> {
                        Frame f2 = stack.pop();
                        if (openTag) {
                            if (f2.text.length() == 0) out.append("/>");
                            else out.append('>').append(f2.text).append("</").append(f2.name).append('>');
                            openTag = false;
                        } else {
                            newline(out, stack.size());
                            out.append("</").append(f2.name).append('>');
                        }
                    }
                    case javax.xml.stream.XMLStreamConstants.COMMENT, javax.xml.stream.XMLStreamConstants.PROCESSING_INSTRUCTION -> {
                        Frame top = stack.peek();
                        if (top != null) {
                            if (!top.text.toString().isBlank()) return text;
                            top.text.setLength(0);
                            top.children = true;
                        }
                        if (openTag) {
                            out.append('>');
                            openTag = false;
                        }
                        newline(out, stack.size());
                        if (ev == javax.xml.stream.XMLStreamConstants.COMMENT) out.append("<!--").append(r.getText()).append("-->");
                        else out.append("<?").append(r.getPITarget()).append(r.getPIData() == null || r.getPIData().isEmpty() ? "" : " " + r.getPIData()).append("?>");
                    }
                    case javax.xml.stream.XMLStreamConstants.DTD, javax.xml.stream.XMLStreamConstants.ENTITY_REFERENCE -> {
                        return text;
                    }
                    default -> { }
                }
            }
            r.close();
            return out.toString().strip();
        } catch (Exception e) {
            return text;
        }
    }

    private static void newline(StringBuilder out, int depth) {
        if (out.length() > 0) out.append('\n');
        out.append("  ".repeat(depth));
    }

    private static String qname(String prefix, String local) {
        return prefix == null || prefix.isEmpty() ? local : prefix + ":" + local;
    }

    private static String escapeText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
                .replace("\n", "&#10;").replace("\r", "&#13;").replace("\t", "&#9;");
    }

    /** Pretty prints JSON or XML according to the content type / content; returns the input otherwise. */
    public static String prettyBody(String body, String contentType) {
        if (body == null || body.isBlank()) return body;
        String ct = contentType == null ? "" : contentType.toLowerCase();
        String t = body.stripLeading();
        if (ct.contains("json") || t.startsWith("{") || t.startsWith("[")) {
            String p = JsonUtil.prettyOrSame(body);
            if (!p.equals(body) || ct.contains("json")) return p;
        }
        if (ct.contains("xml") || looksLikeXml(body)) return prettyOrSame(body);
        return body;
    }
}
