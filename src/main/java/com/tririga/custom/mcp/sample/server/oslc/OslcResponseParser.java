package com.tririga.custom.mcp.sample.server.oslc;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses TRIRIGA OSLC RDF/XML responses and converts them to concise, LLM-friendly formats.
 * 
 * Key principles:
 * 1. Remove redundant XML namespace declarations and RDF boilerplate
 * 2. Convert data to flat, readable structures (JSON-like format)
 * 3. Preserve ALL data - never truncate or limit results
 * 4. Custom field handling - don't assume "essential" fields
 * 5. Handle various OSLC response types (lists, single records, errors)
 */
public class OslcResponseParser {

    private static final Logger logger = LoggerFactory.getLogger(OslcResponseParser.class);

    // Common OSLC/RDF namespaces
    private static final String NS_OSLC    = "http://open-services.net/ns/core#";
    private static final String NS_DCTERMS = "http://purl.org/dc/terms/";
    private static final String NS_RDF     = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String NS_RDFS    = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String NS_SPI     = "http://jazz.net/ns/ism/smarter_physical_infrastructure#";
    private static final String NS_SPI_WM  = "http://jazz.net/ns/ism/smarter_physical_infrastructure/work#";

    /**
     * Parse any TRIRIGA OSLC response and return a concise, readable format.
     * Automatically detects response type and applies appropriate parsing.
     */
    public static String parse(String rdfXml) {
        if (rdfXml == null || rdfXml.isBlank()) {
            return "Empty response";
        }

        // Check for error responses (JSON format)
        if (rdfXml.trim().startsWith("{") && rdfXml.contains("oslc:Error")) {
            return formatJsonError(rdfXml);
        }

        try {
            DocumentBuilderFactory factory = createSecureDocumentBuilderFactory();
            Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(rdfXml.getBytes(StandardCharsets.UTF_8)));

            // Detect response type and parse accordingly
            if (isContainerResponse(doc)) {
                return parseContainer(doc);
            } else if (isSingleResourceResponse(doc)) {
                return parseSingleResource(doc);
            } else if (isServiceProviderCatalog(doc)) {
                return parseServiceProviderCatalog(doc);
            } else {
                // Fallback: generic RDF parsing
                return parseGenericRdf(doc);
            }
        } catch (Exception e) {
            logger.error("Error parsing OSLC response", e);
            return "Error parsing response: " + e.getMessage() + "\n\nRaw response:\n" + rdfXml;
        }
    }

    /**
     * Parse a container/collection response (query results, lists).
     * Returns a concise list of records with all their properties.
     */
    private static String parseContainer(Document doc) {
        StringBuilder sb = new StringBuilder();
        NodeList members = doc.getElementsByTagNameNS(NS_RDFS, "member");
        
        if (members.getLength() == 0) {
            return "No results found";
        }

        sb.append("Found ").append(members.getLength()).append(" record(s):\n\n");

        // Extract actual resource URLs
        List<String> resourceUrls = new ArrayList<>();
        for (int i = 0; i < members.getLength(); i++) {
            Element member = (Element) members.item(i);
            String resourceUrl = member.getAttributeNS(NS_RDF, "resource");
            if (resourceUrl != null && !resourceUrl.isBlank()) {
                resourceUrls.add(resourceUrl);
            }
        }

        // Group by resource type for better readability
        Map<String, List<String>> byType = groupByResourceType(resourceUrls);

        for (Map.Entry<String, List<String>> entry : byType.entrySet()) {
            String resourceType = entry.getKey();
            List<String> urls = entry.getValue();
            
            sb.append("Resource Type: ").append(resourceType).append("\n");
            sb.append("Count: ").append(urls.size()).append("\n");
            sb.append("Records:\n");
            
            for (String url : urls) {
                String recordId = extractRecordId(url);
                sb.append("  - ID: ").append(recordId)
                  .append(", URL: ").append(url).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Parse a single resource response (read operations).
     * Returns all properties in a readable key-value format.
     */
    private static String parseSingleResource(Document doc) {
        StringBuilder sb = new StringBuilder();
        
        // Find the main resource element (any element with rdf:about)
        Element resourceElement = findResourceElement(doc);
        
        if (resourceElement == null) {
            return "No resource data found";
        }

        String resourceUrl = resourceElement.getAttributeNS(NS_RDF, "about");
        String resourceType = resourceElement.getLocalName();
        String recordId = extractRecordId(resourceUrl);

        sb.append("Resource Type: ").append(resourceType).append("\n");
        sb.append("Record ID: ").append(recordId).append("\n");
        sb.append("URL: ").append(resourceUrl).append("\n");
        sb.append("\nProperties:\n");

        // Extract all child elements as properties
        Map<String, String> properties = extractAllProperties(resourceElement);
        
        // Sort properties for consistent output
        List<String> sortedKeys = new ArrayList<>(properties.keySet());
        Collections.sort(sortedKeys);

        for (String key : sortedKeys) {
            String value = properties.get(key);
            sb.append("  ").append(key).append(": ").append(value).append("\n");
        }

        return sb.toString();
    }

    /**
     * Parse service provider catalog response.
     */
    private static String parseServiceProviderCatalog(Document doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("TRIRIGA OSLC Service Provider Catalog\n\n");
        
        NodeList members = doc.getElementsByTagNameNS(NS_RDFS, "member");
        
        if (members.getLength() == 0) {
            return "No service providers found";
        }

        sb.append("Found ").append(members.getLength()).append(" service provider(s):\n\n");

        for (int i = 0; i < members.getLength(); i++) {
            Element member = (Element) members.item(i);
            String spUrl = member.getAttributeNS(NS_RDF, "resource");
            String spName = extractServiceProviderName(spUrl);
            sb.append("  ").append(i + 1).append(". ").append(spName).append("\n");
            sb.append("     URL: ").append(spUrl).append("\n");
        }

        return sb.toString();
    }

    /**
     * Generic RDF parser for unknown response types.
     */
    private static String parseGenericRdf(Document doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("RDF Response:\n\n");
        
        Element root = doc.getDocumentElement();
        traverseElement(root, sb, 0);
        
        return sb.toString();
    }

    /**
     * Extract all properties from a resource element.
     * Returns map of prefixed-name -> value.
     */
    private static Map<String, String> extractAllProperties(Element resourceElement) {
        Map<String, String> properties = new LinkedHashMap<>();
        NodeList children = resourceElement.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            Element childElement = (Element) child;
            String namespace = childElement.getNamespaceURI();
            String localName = childElement.getLocalName();
            
            // Create prefixed name
            String prefix = getPrefixForNamespace(namespace);
            String key = prefix != null ? prefix + ":" + localName : localName;

            // Get value - could be text content or rdf:resource attribute
            String value;
            String resourceAttr = childElement.getAttributeNS(NS_RDF, "resource");
            if (resourceAttr != null && !resourceAttr.isBlank()) {
                value = resourceAttr;
            } else {
                value = childElement.getTextContent().trim();
            }

            if (!value.isBlank()) {
                properties.put(key, value);
            }
        }

        return properties;
    }

    /**
     * Traverse XML element tree and build readable representation.
     */
    private static void traverseElement(Element element, StringBuilder sb, int depth) {
        String indent = "  ".repeat(depth);
        String name = element.getLocalName();
        
        // Skip RDF wrapper elements
        if ("RDF".equals(name) || "Description".equals(name)) {
            NodeList children = element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    traverseElement((Element) children.item(i), sb, depth);
                }
            }
            return;
        }

        sb.append(indent).append(name);
        
        String about = element.getAttributeNS(NS_RDF, "about");
        String resource = element.getAttributeNS(NS_RDF, "resource");
        
        if (about != null && !about.isBlank()) {
            sb.append(" [@about=").append(about).append("]");
        }
        if (resource != null && !resource.isBlank()) {
            sb.append(" [@resource=").append(resource).append("]");
        }

        String textContent = element.getTextContent().trim();
        NodeList children = element.getChildNodes();
        boolean hasElementChildren = false;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                hasElementChildren = true;
                break;
            }
        }

        if (!hasElementChildren && !textContent.isBlank()) {
            sb.append(": ").append(textContent);
        }
        sb.append("\n");

        if (hasElementChildren) {
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    traverseElement((Element) children.item(i), sb, depth + 1);
                }
            }
        }
    }

    // ── Helper Methods ───────────────────────────────────────────────────────

    private static boolean isContainerResponse(Document doc) {
        return doc.getElementsByTagNameNS(NS_RDFS, "Container").getLength() > 0;
    }

    private static boolean isSingleResourceResponse(Document doc) {
        Element root = doc.getDocumentElement();
        NodeList children = root.getChildNodes();
        
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) children.item(i);
                String about = child.getAttributeNS(NS_RDF, "about");
                if (about != null && !about.isBlank() && !about.endsWith("/sp")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isServiceProviderCatalog(Document doc) {
        NodeList desc = doc.getElementsByTagNameNS(NS_RDF, "Description");
        if (desc.getLength() > 0) {
            Element el = (Element) desc.item(0);
            String about = el.getAttributeNS(NS_RDF, "about");
            return about != null && about.endsWith("/sp");
        }
        return false;
    }

    private static Element findResourceElement(Document doc) {
        Element root = doc.getDocumentElement();
        NodeList children = root.getChildNodes();
        
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) children.item(i);
                String about = child.getAttributeNS(NS_RDF, "about");
                if (about != null && !about.isBlank()) {
                    return child;
                }
            }
        }
        return null;
    }

    private static String extractRecordId(String url) {
        if (url == null) return "unknown";
        int lastSlash = url.lastIndexOf('/');
        return lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
    }

    private static String extractResourceType(String url) {
        if (url == null) return "unknown";
        // URL format: http://host/oslc/so/triWorkTaskRS/12345
        String[] parts = url.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].matches("[a-zA-Z]+RS")) {
                return parts[i].substring(0, parts[i].length() - 2); // Remove "RS"
            }
        }
        return "unknown";
    }

    private static String extractServiceProviderName(String url) {
        if (url == null) return "unknown";
        int lastSlash = url.lastIndexOf('/');
        String name = lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
        // Remove "SP" suffix and convert camelCase to readable
        if (name.endsWith("SP")) {
            name = name.substring(0, name.length() - 2);
        }
        return name.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private static Map<String, List<String>> groupByResourceType(List<String> urls) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        
        for (String url : urls) {
            String type = extractResourceType(url);
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(url);
        }
        
        return grouped;
    }

    private static String getPrefixForNamespace(String namespace) {
        if (namespace == null) return null;
        
        if (namespace.equals(NS_DCTERMS)) return "dcterms";
        if (namespace.equals(NS_OSLC)) return "oslc";
        if (namespace.equals(NS_RDF)) return "rdf";
        if (namespace.equals(NS_SPI)) return "spi";
        if (namespace.equals(NS_SPI_WM)) return "spi_wm";
        
        // For unknown namespaces, try to extract a reasonable prefix
        if (namespace.contains("#")) {
            String[] parts = namespace.split("#");
            if (parts.length > 0) {
                String lastPart = parts[parts.length - 1];
                if (!lastPart.isBlank()) return lastPart.toLowerCase();
            }
        }
        
        return null;
    }

    private static String formatJsonError(String jsonError) {
        // Parse JSON error response and format nicely
        try {
            // Simple JSON parsing without external dependencies
            if (jsonError.contains("oslc:statusCode")) {
                String statusCode = extractJsonValue(jsonError, "oslc:statusCode");
                String message = extractJsonValue(jsonError, "oslc:message");
                
                return "OSLC Error:\n" +
                       "  Status Code: " + statusCode + "\n" +
                       "  Message: " + message;
            }
        } catch (Exception e) {
            // Fall through to return raw JSON
        }
        return "Error Response:\n" + jsonError;
    }

    private static String extractJsonValue(String json, String key) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) return "";
        
        int colonIndex = json.indexOf(":", keyIndex);
        if (colonIndex < 0) return "";
        
        int quoteStart = json.indexOf("\"", colonIndex);
        if (quoteStart < 0) return "";
        
        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        if (quoteEnd < 0) return "";
        
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static DocumentBuilderFactory createSecureDocumentBuilderFactory() 
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        
        // Prevent XXE attacks
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        
        return factory;
    }
}
