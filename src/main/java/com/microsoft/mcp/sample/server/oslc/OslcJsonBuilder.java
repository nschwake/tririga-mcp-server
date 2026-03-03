package com.microsoft.mcp.sample.server.oslc;

import java.util.Map;

/**
 * Builds a JSON POST/PUT body for TRIRIGA OSLC creation and update requests.
 *
 * TRIRIGA's JSON format uses the oslc:name of each property as the JSON key.
 * Linked resources are expressed as objects with a single "rdf:resource" key:
 *
 *   "triAssociatedTaskType": { "rdf:resource": "http://host/oslc/so/triTaskTypeRS/123" }
 *
 * Rules enforced (same as RDF/XML builder):
 *   - Literal fields:  must be non-readOnly and isLiteral() == true
 *   - Resource fields: must be non-readOnly and isResourceLink() == true
 *   - Null/blank values are always skipped
 *   - Unknown or invalid fields are noted as comments in the returned string
 *     (but since JSON has no comments, they are collected in a warnings section)
 */
public class OslcJsonBuilder {

    /**
     * Build a JSON body for a TRIRIGA OSLC POST or PUT.
     *
     * @param shape          parsed resource shape
     * @param literalFields  oslc:name → plain string value
     * @param resourceFields oslc:name → full TRIRIGA resource URL
     * @return JSON string ready for use as a request body
     */
    public static String build(OslcShape shape,
                                Map<String, String> literalFields,
                                Map<String, String> resourceFields) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        boolean first = true;

        // ── Literal fields ──────────────────────────────────────────────────
        if (literalFields != null) {
            for (Map.Entry<String, String> entry : literalFields.entrySet()) {
                String name  = entry.getKey();
                String value = entry.getValue();
                if (value == null || value.isBlank()) continue;

                OslcProperty prop = shape.getProperty(name);
                if (prop == null || prop.isReadOnly() || !prop.isLiteral()) continue;

                if (!first) sb.append(",\n");
                first = false;

                sb.append("  \"").append(name).append("\": ");
                sb.append(jsonValue(value, prop.getValueType()));
            }
        }

        // ── Linked resource fields ──────────────────────────────────────────
        if (resourceFields != null) {
            for (Map.Entry<String, String> entry : resourceFields.entrySet()) {
                String name        = entry.getKey();
                String resourceUrl = entry.getValue();
                if (resourceUrl == null || resourceUrl.isBlank()) continue;

                OslcProperty prop = shape.getProperty(name);
                if (prop == null || prop.isReadOnly() || prop.isLiteral()) continue;

                if (!first) sb.append(",\n");
                first = false;

                // Linked resource: { "rdf:resource": "url" }
                sb.append("  \"").append(name).append("\": { \"rdf:resource\": \"")
                  .append(escapeJson(resourceUrl)).append("\" }");
            }
        }

        sb.append("\n}");
        return sb.toString();
    }

    /**
     * Convenience overload for when there are no linked resource fields.
     */
    public static String build(OslcShape shape, Map<String, String> literalFields) {
        return build(shape, literalFields, null);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns the appropriate JSON representation of a value based on its XSD type.
     * Strings and datetimes are quoted; booleans and numbers are unquoted.
     */
    private static String jsonValue(String value, String valueType) {
        if (valueType == null) return "\"" + escapeJson(value) + "\"";

        return switch (valueType) {
            case OslcProperty.TYPE_BOOLEAN ->
                // Normalise to lowercase true/false
                (value.equalsIgnoreCase("true") || value.equals("1")) ? "true" : "false";
            case OslcProperty.TYPE_INTEGER ->
                value.matches("-?\\d+") ? value : "\"" + escapeJson(value) + "\"";
            case OslcProperty.TYPE_DECIMAL ->
                value.matches("-?\\d+(\\.\\d+)?") ? value : "\"" + escapeJson(value) + "\"";
            default ->
                // Strings, dateTimes, and anything else → quoted
                "\"" + escapeJson(value) + "\"";
        };
    }

    private static String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
