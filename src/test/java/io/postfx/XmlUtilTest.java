package io.postfx;

import io.postfx.engine.XmlUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class XmlUtilTest {

    @Test
    void indentsCompactXml() {
        String pretty = XmlUtil.prettyOrSame("<order id=\"7\"><item sku=\"a\">2</item><item sku=\"b\"/><note><![CDATA[x < y]]></note></order>");
        assertEquals("""
                <order id="7">
                  <item sku="a">2</item>
                  <item sku="b"/>
                  <note><![CDATA[x < y]]></note>
                </order>""", pretty.replace("\r", ""));
    }

    @Test
    void keepsTheDeclarationAndDoesNotAddBlankLines() {
        String in = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<a>\n\n   <b>1</b>\n\n</a>";
        String pretty = XmlUtil.prettyOrSame(in).replace("\r", "");
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<a>\n  <b>1</b>\n</a>", pretty);
        assertEquals(pretty, XmlUtil.prettyOrSame(pretty).replace("\r", ""), "formatting is stable");
    }

    @Test
    void namespacesAndCommentsAreKept() {
        String pretty = XmlUtil.prettyOrSame("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><!-- c --><s:Body><r>1</r></s:Body></s:Envelope>");
        assertTrue(pretty.contains("xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""), pretty);
        assertTrue(pretty.contains("<!-- c -->"), pretty);
        assertTrue(pretty.contains("\n    <r>1</r>"), pretty);
    }

    @Test
    void attributeOrderAndEscapingArePreserved() {
        String pretty = XmlUtil.prettyOrSame("<item sku=\"A-1\" qty=\"2\" note=\"a &amp; b &lt; c\">x &amp; y</item>");
        assertEquals("<item sku=\"A-1\" qty=\"2\" note=\"a &amp; b &lt; c\">x &amp; y</item>", pretty);
    }

    @Test
    void mixedContentIsLeftUnchanged() {
        String mixed = "<p>Hello <b>world</b>!</p>";
        assertSame(mixed, XmlUtil.prettyOrSame(mixed));
    }

    @Test
    void invalidXmlIsReturnedUnchanged() {
        String broken = "<a><b></a>";
        assertSame(broken, XmlUtil.prettyOrSame(broken));
        assertNull(XmlUtil.tryParse("not xml"));
    }

    @Test
    void externalEntitiesAreRefused(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]><r>&x;</r>";
        String out = XmlUtil.prettyOrSame(xxe);
        assertSame(xxe, out, "documents with a DOCTYPE are not parsed");
        assertFalse(out.contains("TOP-SECRET"));
    }

    @Test
    void prettyBodyPicksJsonOrXml() {
        assertTrue(XmlUtil.prettyBody("{\"a\":1}", "application/json").contains("\n"));
        assertTrue(XmlUtil.prettyBody("<a><b/></a>", "application/xml").contains("\n  <b/>"));
        assertTrue(XmlUtil.prettyBody("<a><b/></a>", "").contains("\n  <b/>"), "detected without a content type");
        assertEquals("<!DOCTYPE html><html><body/></html>", XmlUtil.prettyBody("<!DOCTYPE html><html><body/></html>", "text/html"));
        assertEquals("plain", XmlUtil.prettyBody("plain", "text/plain"));
    }
}
