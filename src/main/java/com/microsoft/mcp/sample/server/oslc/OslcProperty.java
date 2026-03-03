package com.microsoft.mcp.sample.server.oslc;

/**
 * Represents a single property parsed from an OSLC ResourceShape.
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

    public boolean isLiteral() {
        if (valueType == null) return true;
        return !valueType.equals(TYPE_RESOURCE)
            && !valueType.equals(TYPE_ANY_RESOURCE)
            && !valueType.equals(TYPE_LOCAL_RESOURCE);
    }

    public boolean isResourceLink() { return !isLiteral(); }

    public boolean isWritableResourceLink() { return isResourceLink() && !readOnly; }

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
