package com.microsoft.mcp.sample.server.oslc;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses an OSLC ResourceShape and exposes its properties plus the namespace
 * prefix map required to generate correct JSON keys for TRIRIGA POST/PUT bodies.
 *
 * JSON key format: "prefix:localName"  e.g. "spi:triTaskTypeCL", "dcterms:title"
 *
 * The prefix map should come from OslcServiceCatalog.getGlobalPrefixMap(), which
 * collects oslc:prefixDefinition entries from every service provider.
 */
public class OslcShape {

    private static final String NS_OSLC    = "http://open-services.net/ns/core#";
    private static final String NS_DCTERMS = "http://purl.org/dc/terms/";
    private static final String NS_RDF     = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private final String shapeUrl;
    private final String title;
    private final String describesType;
    private final Map<String, OslcProperty> properties;
    private final Map<String, String> prefixMap;

    private OslcShape(String shapeUrl, String title, String describesType,
                      Map<String, OslcProperty> properties, Map<String, String> prefixMap) {
        this.shapeUrl      = shapeUrl;
        this.title         = title;
        this.describesType = describesType;
        this.properties    = Collections.unmodifiableMap(properties);
        this.prefixMap     = Collections.unmodifiableMap(prefixMap);
    }

    /** Parse shape RDF/XML using a caller-supplied prefix map. */
    public static OslcShape parse(String shapeUrl, String rdfXml,
                                   Map<String, String> callerPrefixMap) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(rdfXml.getBytes(StandardCharsets.UTF_8)));

        String title         = firstText(doc, NS_DCTERMS, "title");
        String describesType = firstAttr(doc, NS_OSLC, "describes", NS_RDF, "resource");

        Map<String, OslcProperty> props = new LinkedHashMap<>();
        NodeList nodes = doc.getElementsByTagNameNS(NS_OSLC, "Property");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el       = (Element) nodes.item(i);
            String name      = childText(el, NS_OSLC, "name");
            String propDef   = childAttr(el, NS_OSLC, "propertyDefinition", NS_RDF, "resource");
            String valueType = childAttr(el, NS_OSLC, "valueType",          NS_RDF, "resource");
            String repr      = childAttr(el, NS_OSLC, "representation",     NS_RDF, "resource");
            String valShape  = childAttr(el, NS_OSLC, "valueShape",         NS_RDF, "resource");
            String roStr     = childText(el, NS_OSLC, "readOnly");
            String occursUri = childAttr(el, NS_OSLC, "occurs",             NS_RDF, "resource");
            String defVal    = childText(el, NS_OSLC, "defaultValue");
            if (name == null || name.isBlank()) continue;
            boolean ro  = "true".equalsIgnoreCase(roStr);
            boolean req = occursUri != null && occursUri.endsWith("Exactly-one");
            props.put(name, new OslcProperty(name, propDef, valueType, repr, valShape, ro, req, defVal));
        }

        Map<String, String> eff = new LinkedHashMap<>(callerPrefixMap != null ? callerPrefixMap : Collections.emptyMap());
        eff.putIfAbsent("dcterms", "http://purl.org/dc/terms/");
        eff.putIfAbsent("oslc",    "http://open-services.net/ns/core#");
        eff.putIfAbsent("rdf",     "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        return new OslcShape(shapeUrl, title, describesType, props, eff);
    }

    /** Parse without a caller prefix map — uses built-in prefixes only. */
    public static OslcShape parse(String shapeUrl, String rdfXml) throws Exception {
        return parse(shapeUrl, rdfXml, null);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Map<String, OslcProperty> getAllProperties()  { return properties; }
    public OslcProperty getProperty(String name)         { return properties.get(name); }
    /** Prefix map to pass to OslcProperty.prefixedName() or OslcJsonBuilder.build(). */
    public Map<String, String> getPrefixMap()            { return prefixMap; }
    public String getShapeUrl()                          { return shapeUrl; }
    public String getTitle()                             { return title; }
    public String getDescribesType()                     { return describesType; }

    public List<OslcProperty> getWritableLiteralProperties() {
        List<OslcProperty> r = new ArrayList<>();
        for (OslcProperty p : properties.values()) if (!p.isReadOnly() && p.isLiteral()) r.add(p);
        return r;
    }

    public List<OslcProperty> getWritableResourceLinkProperties() {
        List<OslcProperty> r = new ArrayList<>();
        for (OslcProperty p : properties.values()) if (p.isWritableResourceLink()) r.add(p);
        return r;
    }

    private static String firstText(Document doc, String ns, String local) {
        NodeList nl = doc.getElementsByTagNameNS(ns, local);
        return nl.getLength() > 0 ? nl.item(0).getTextContent().trim() : null;
    }
    private static String firstAttr(Document doc, String eNs, String eL, String aNs, String aL) {
        NodeList nl = doc.getElementsByTagNameNS(eNs, eL);
        if (nl.getLength() == 0) return null;
        String v = ((Element)nl.item(0)).getAttributeNS(aNs, aL);
        return v.isBlank() ? null : v;
    }
    private static String childText(Element p, String ns, String local) {
        NodeList nl = p.getElementsByTagNameNS(ns, local);
        return nl.getLength() > 0 ? nl.item(0).getTextContent().trim() : null;
    }
    private static String childAttr(Element p, String eNs, String eL, String aNs, String aL) {
        NodeList nl = p.getElementsByTagNameNS(eNs, eL);
        if (nl.getLength() == 0) return null;
        String v = ((Element)nl.item(0)).getAttributeNS(aNs, aL);
        return v.isBlank() ? null : v;
    }

    @Override public String toString() {
        return String.format("OslcShape{title='%s', total=%d, literals=%d, links=%d}",
                title, properties.size(), getWritableLiteralProperties().size(),
                getWritableResourceLinkProperties().size());
    }
}
