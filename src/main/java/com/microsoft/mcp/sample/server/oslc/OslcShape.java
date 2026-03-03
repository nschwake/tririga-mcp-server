package com.microsoft.mcp.sample.server.oslc;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses an OSLC ResourceShape RDF/XML document and provides typed access
 * to its properties — distinguishing literals, read-only resource links,
 * and writable resource links.
 */
public class OslcShape {

    private static final String NS_OSLC    = "http://open-services.net/ns/core#";
    private static final String NS_DCTERMS = "http://purl.org/dc/terms/";
    private static final String NS_RDF     = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private final String shapeUrl;
    private final String title;
    private final String describesType;
    private final Map<String, OslcProperty> properties;

    private OslcShape(String shapeUrl, String title, String describesType,
                      Map<String, OslcProperty> properties) {
        this.shapeUrl = shapeUrl;
        this.title = title;
        this.describesType = describesType;
        this.properties = Collections.unmodifiableMap(properties);
    }

    public static OslcShape parse(String shapeUrl, String rdfXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(rdfXml.getBytes(StandardCharsets.UTF_8)));

        String title         = firstText(doc, NS_DCTERMS, "title");
        String describesType = firstAttr(doc, NS_OSLC, "describes", NS_RDF, "resource");

        Map<String, OslcProperty> props = new LinkedHashMap<>();
        NodeList nodes = doc.getElementsByTagNameNS(NS_OSLC, "Property");

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);

            String name           = childText(el, NS_OSLC, "name");
            String propDef        = childAttr(el, NS_OSLC, "propertyDefinition", NS_RDF, "resource");
            String valueType      = childAttr(el, NS_OSLC, "valueType",          NS_RDF, "resource");
            String representation = childAttr(el, NS_OSLC, "representation",     NS_RDF, "resource");
            String valueShapeUrl  = childAttr(el, NS_OSLC, "valueShape",         NS_RDF, "resource");
            String readOnlyStr    = childText(el, NS_OSLC, "readOnly");
            String occursUri      = childAttr(el, NS_OSLC, "occurs",             NS_RDF, "resource");
            String defaultValue   = childText(el, NS_OSLC, "defaultValue");

            if (name == null || name.isBlank()) continue;

            boolean readOnly = "true".equalsIgnoreCase(readOnlyStr);
            boolean required = occursUri != null && occursUri.endsWith("Exactly-one");

            props.put(name, new OslcProperty(name, propDef, valueType, representation,
                    valueShapeUrl, readOnly, required, defaultValue));
        }

        return new OslcShape(shapeUrl, title, describesType, props);
    }

    public Map<String, OslcProperty> getAllProperties() { return properties; }

    public List<OslcProperty> getWritableLiteralProperties() {
        List<OslcProperty> result = new ArrayList<>();
        for (OslcProperty p : properties.values())
            if (!p.isReadOnly() && p.isLiteral()) result.add(p);
        return result;
    }

    public List<OslcProperty> getWritableResourceLinkProperties() {
        List<OslcProperty> result = new ArrayList<>();
        for (OslcProperty p : properties.values())
            if (p.isWritableResourceLink()) result.add(p);
        return result;
    }

    public OslcProperty getProperty(String name) { return properties.get(name); }

    public String getShapeUrl()      { return shapeUrl; }
    public String getTitle()         { return title; }
    public String getDescribesType() { return describesType; }

    private static String firstText(Document doc, String ns, String local) {
        NodeList nl = doc.getElementsByTagNameNS(ns, local);
        return nl.getLength() > 0 ? nl.item(0).getTextContent().trim() : null;
    }

    private static String firstAttr(Document doc, String elNs, String elLocal,
                                     String attrNs, String attrLocal) {
        NodeList nl = doc.getElementsByTagNameNS(elNs, elLocal);
        if (nl.getLength() == 0) return null;
        String v = ((Element) nl.item(0)).getAttributeNS(attrNs, attrLocal);
        return v.isBlank() ? null : v;
    }

    private static String childText(Element parent, String ns, String local) {
        NodeList nl = parent.getElementsByTagNameNS(ns, local);
        return nl.getLength() > 0 ? nl.item(0).getTextContent().trim() : null;
    }

    private static String childAttr(Element parent, String elNs, String elLocal,
                                     String attrNs, String attrLocal) {
        NodeList nl = parent.getElementsByTagNameNS(elNs, elLocal);
        if (nl.getLength() == 0) return null;
        String v = ((Element) nl.item(0)).getAttributeNS(attrNs, attrLocal);
        return v.isBlank() ? null : v;
    }

    @Override
    public String toString() {
        return String.format("OslcShape{title='%s', total=%d, writableLiterals=%d, writableLinks=%d}",
                title, properties.size(),
                getWritableLiteralProperties().size(),
                getWritableResourceLinkProperties().size());
    }
}
