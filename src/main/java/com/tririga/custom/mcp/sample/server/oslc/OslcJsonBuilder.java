package com.tririga.custom.mcp.sample.server.oslc;

import java.util.List;
import java.util.Map;

/**
 * Builds TRIRIGA OSLC JSON POST/PUT bodies from an OslcShape and field value maps.
 *
 * TRIRIGA JSON key format (from IBM documentation examples):
 *
 *   Literal field:          "spi:triTaskTypeCL": "Corrective"
 *   Literal field:          "dcterms:title": "Sample01"
 *   Action:                 "spi:action": "Create Draft"
 *
 *   Inline child (new):     "spi:triAssociatedCommentsLR": [
 *                             { "spi:triCommentTX": "...", "spi:action": "Create" }
 *                           ]
 *
 *   Link to existing:       "spi:triAssociatedComments": [
 *                             { "dcterms:identifier": "132633922" }
 *                           ]
 *
 * Key rules:
 *   - All keys use "prefix:localName" derived from the property's definition URI
 *     and the shape's prefix map.
 *   - Inline child records use the field name with "LR" appended (TRIRIGA convention).
 *   - Linking to existing records uses the plain field name with just
 *     {"dcterms:identifier": "recordId"} — NOT rdf:resource wrappers.
 *   - Read-only and unknown fields are silently skipped.
 */
public class OslcJsonBuilder {

    /**
     * A child record to inline-create and associate in a single request.
     * The fields map uses oslc:name keys (same as the parent) — prefixed keys
     * are resolved automatically from the child shape's prefix map.
     */
    public static class InlineChild {
        public final OslcShape shape;
        public final Map<String, String> fields;
        public final String action;

        public InlineChild(OslcShape shape, Map<String, String> fields, String action) {
            this.shape  = shape;
            this.fields = fields;
            this.action = action;
        }
    }

    /**
     * A reference to an existing TRIRIGA record to associate.
     */
    public static class ExistingRecord {
        public final String recordId;
        public ExistingRecord(String recordId) { this.recordId = recordId; }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a JSON body for a TRIRIGA OSLC POST or PUT.
     *
     * @param shape          the parent resource shape (provides the prefix map)
     * @param literalFields  oslc:name → plain string value for scalar fields
     * @param action         optional action string, e.g. "Create Draft", "Save"
     * @param inlineChildren map of oslc:name (without LR suffix) → list of InlineChild objects
     *                       These are new child records created and associated in one call.
     *                       TRIRIGA expects the field name with "LR" suffix appended.
     * @param linkedRecords  map of oslc:name → list of ExistingRecord objects
     *                       These link to already-existing records by their identifier.
     */
    public static String build(
            OslcShape shape,
            Map<String, String> literalFields,
            String action,
            Map<String, List<InlineChild>> inlineChildren,
            Map<String, List<ExistingRecord>> linkedRecords) {

        Map<String, String> pm = shape.getPrefixMap();
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;

        // ── Scalar literal fields ─────────────────────────────────────────────
        if (literalFields != null) {
            for (Map.Entry<String, String> e : literalFields.entrySet()) {
                String name  = e.getKey();
                String value = e.getValue();
                if (value == null || value.isBlank()) continue;

                OslcProperty prop = shape.getProperty(name);
                if (prop == null || prop.isReadOnly() || !prop.isLiteral()) continue;

                if (!first) sb.append(",\n");
                first = false;
                sb.append("  \"").append(prop.prefixedName(pm)).append("\": ")
                  .append(jsonValue(value, prop.getValueType()));
            }
        }

        // ── Action ────────────────────────────────────────────────────────────
        // "spi:action" is in the shape as a writable literal; resolve its key.
        if (action != null && !action.isBlank()) {
            OslcProperty actionProp = shape.getProperty("action");
            String actionKey = actionProp != null ? actionProp.prefixedName(pm) : "spi:action";
            if (!first) sb.append(",\n");
            first = false;
            sb.append("  \"").append(actionKey).append("\": \"").append(escapeJson(action)).append("\"");
        }

        // ── Inline child records (new records created inline, LR suffix) ──────
        if (inlineChildren != null) {
            for (Map.Entry<String, List<InlineChild>> e : inlineChildren.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;

                // Resolve the parent field key from the parent shape
                OslcProperty parentProp = shape.getProperty(e.getKey());
                String fieldKey = parentProp != null
                        ? parentProp.prefixedName(pm) + "LR"   // LR = inline create convention
                        : "spi:" + e.getKey() + "LR";

                if (!first) sb.append(",\n");
                first = false;
                sb.append("  \"").append(fieldKey).append("\": [\n");

                boolean firstChild = true;
                for (InlineChild child : e.getValue()) {
                    if (!firstChild) sb.append(",\n");
                    firstChild = false;
                    sb.append("    {\n");
                    sb.append(buildChildFields(child));
                    sb.append("    }");
                }
                sb.append("\n  ]");
            }
        }

        // ── Links to existing records (plain field name, dcterms:identifier) ──
        if (linkedRecords != null) {
            for (Map.Entry<String, List<ExistingRecord>> e : linkedRecords.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;

                OslcProperty parentProp = shape.getProperty(e.getKey());
                String fieldKey = parentProp != null
                        ? parentProp.prefixedName(pm)
                        : "spi:" + e.getKey();

                if (!first) sb.append(",\n");
                first = false;
                sb.append("  \"").append(fieldKey).append("\": [\n");

                boolean firstLink = true;
                for (ExistingRecord rec : e.getValue()) {
                    if (!firstLink) sb.append(",\n");
                    firstLink = false;
                    sb.append("    { \"dcterms:identifier\": \"")
                      .append(escapeJson(rec.recordId)).append("\" }");
                }
                sb.append("\n  ]");
            }
        }

        sb.append("\n}");
        return sb.toString();
    }

    /** Convenience overload: scalar fields + action only, no child records. */
    public static String build(OslcShape shape, Map<String, String> literalFields, String action) {
        return build(shape, literalFields, action, null, null);
    }

    /** Convenience overload: scalar fields only, no action, no child records. */
    public static String build(OslcShape shape, Map<String, String> literalFields) {
        return build(shape, literalFields, null, null, null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String buildChildFields(InlineChild child) {
        Map<String, String> pm = child.shape.getPrefixMap();
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        if (child.fields != null) {
            for (Map.Entry<String, String> e : child.fields.entrySet()) {
                String name  = e.getKey();
                String value = e.getValue();
                if (value == null || value.isBlank()) continue;
                OslcProperty prop = child.shape.getProperty(name);
                if (prop == null || prop.isReadOnly() || !prop.isLiteral()) continue;
                if (!first) sb.append(",\n");
                first = false;
                sb.append("      \"").append(prop.prefixedName(pm)).append("\": ")
                  .append(jsonValue(value, prop.getValueType()));
            }
        }
        if (child.action != null && !child.action.isBlank()) {
            OslcProperty ap = child.shape.getProperty("action");
            String ak = ap != null ? ap.prefixedName(pm) : "spi:action";
            if (!first) sb.append(",\n");
            sb.append("      \"").append(ak).append("\": \"").append(escapeJson(child.action)).append("\"");
        }
        if (sb.length() > 0) sb.append("\n");
        return sb.toString();
    }

    private static String jsonValue(String value, String valueType) {
        if (valueType == null) return "\"" + escapeJson(value) + "\"";
        return switch (valueType) {
            case OslcProperty.TYPE_BOOLEAN ->
                    (value.equalsIgnoreCase("true") || value.equals("1")) ? "true" : "false";
            case OslcProperty.TYPE_INTEGER ->
                    value.matches("-?\\d+") ? value : "\"" + escapeJson(value) + "\"";
            case OslcProperty.TYPE_DECIMAL ->
                    value.matches("-?\\d+(\\.\\d+)?") ? value : "\"" + escapeJson(value) + "\"";
            default -> "\"" + escapeJson(value) + "\"";
        };
    }

    private static String escapeJson(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
