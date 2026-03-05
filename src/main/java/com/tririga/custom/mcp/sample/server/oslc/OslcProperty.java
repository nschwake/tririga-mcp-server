package com.tririga.custom.mcp.sample.server.oslc;

import java.util.Map;

/**
 * Represents a single property parsed from an OSLC ResourceShape.
 *
 * prefixedName(prefixMap) returns the correct "prefix:localName" JSON key
 * that TRIRIGA expects in POST/PUT bodies, e.g. "spi:triTaskTypeCL",
 * "dcterms:title", "spi_wm:schedstart".
 */
public class OslcProperty {

    public static final String TYPE_RESOURCE       = "http://open-services.net/ns/core#Resource";
    public static final String TYPE_ANY_RESOURCE   = "http://open-services.net/ns/core#AnyResource";
    public static final String TYPE_LOCAL_RESOURCE = "http://open-services.net/ns/core#LocalResource";
    public static final String TYPE_STRING         = "http://www.w3.org/2001/XMLSchema#string";
    public static final String TYPE_DATETIME       = "http://www.w3.org/2001/XMLSchema#dateTime";
    public static final String TYPE_BOOLEAN        = "http://www.w3.org/2001/XMLSchema#boolean";
    public static final String TYPE_INTEGER        = "http://www.w3.org/2001/XMLSchema#integer";
    public static final String TYPE_DECIMAL        = "http://www.w3.org/2001/XMLSchema#decimal";

    private final String name;
    private final String propertyDefinition;
    private final String valueType;
    private final String representation;
    private final String valueShapeUrl;
    private final boolean readOnly;
    private final boolean required;
    private final String defaultValue;

    public OslcProperty(String name, String propertyDefinition, String valueType,
                        String representation, String valueShapeUrl,
                        boolean readOnly, boolean required, String defaultValue) {
        this.name = name;
        this.propertyDefinition = propertyDefinition;
        this.valueType = valueType;
        this.representation = representation;
        this.valueShapeUrl = valueShapeUrl;
        this.readOnly = readOnly;
        this.required = required;
        this.defaultValue = defaultValue;
    }

    // ── Type helpers ─────────────────────────────────────────────────────────

    public boolean isLiteral() {
        if (valueType == null) return true;
        return !valueType.equals(TYPE_RESOURCE)
            && !valueType.equals(TYPE_ANY_RESOURCE)
            && !valueType.equals(TYPE_LOCAL_RESOURCE);
    }

    public boolean isResourceLink()         { return !isLiteral(); }
    public boolean isWritableResourceLink() { return isResourceLink() && !readOnly; }

    // ── Key generation ───────────────────────────────────────────────────────

    /**
     * Returns the prefixed JSON key for this property as TRIRIGA expects it,
     * e.g. "spi:triTaskTypeCL", "dcterms:title", "spi_wm:schedstart".
     *
     * Resolution order:
     *   1. Caller-supplied prefixMap (from the service provider's oslc:prefixDefinitions)
     *   2. Built-in well-known prefixes (dcterms, oslc, rdf)
     *   3. Falls back to bare oslc:name if nothing matches
     */
    public String prefixedName(Map<String, String> prefixMap) {
        if (propertyDefinition == null) return name;

        if (prefixMap != null) {
            for (Map.Entry<String, String> ns : prefixMap.entrySet()) {
                if (propertyDefinition.startsWith(ns.getValue())) {
                    return ns.getKey() + ":" + propertyDefinition.substring(ns.getValue().length());
                }
            }
        }

        // Built-in fallbacks always present
        if (propertyDefinition.startsWith("http://purl.org/dc/terms/"))
            return "dcterms:" + propertyDefinition.substring("http://purl.org/dc/terms/".length());
        if (propertyDefinition.startsWith("http://open-services.net/ns/core#"))
            return "oslc:" + propertyDefinition.substring("http://open-services.net/ns/core#".length());
        if (propertyDefinition.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#"))
            return "rdf:" + propertyDefinition.substring("http://www.w3.org/1999/02/22-rdf-syntax-ns#".length());

        return name; // bare name as last resort
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getName()               { return name; }
    public String getPropertyDefinition() { return propertyDefinition; }
    public String getValueType()          { return valueType; }
    public String getRepresentation()     { return representation; }
    public String getValueShapeUrl()      { return valueShapeUrl; }
    public boolean isReadOnly()           { return readOnly; }
    public boolean isRequired()           { return required; }
    public String getDefaultValue()       { return defaultValue; }

    @Override
    public String toString() {
        return String.format("OslcProperty{name='%s', readOnly=%b, required=%b, literal=%b}",
                name, readOnly, required, isLiteral());
    }
}
