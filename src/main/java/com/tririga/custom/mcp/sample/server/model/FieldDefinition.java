package com.tririga.custom.mcp.sample.server.model;

import java.util.Map;

/**
 * Returned by getFieldsForBoType.
 * Describes a single field on a TRIRIGA business object type.
 */
public record FieldDefinition(
        int     atrSeq,
        String  fieldName,
        String  fieldLabel,
        String  sectionLabel,
        String  fieldType,
        boolean readOnly,
        boolean systemField
) {
    public static FieldDefinition from(Map<String, Object> row) {
        return new FieldDefinition(
                num(row, "ATR_SEQ"),
                str(row, "ATR_NAME"),
                str(row, "ATR_FIELD_LABEL"),
                str(row, "SECTION_LABEL"),
                str(row, "ATR_TYPE"),
                num(row, "READ_ONLY") == 1,
                num(row, "SYSTEM_FLAG") == 1
        );
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private static int num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0 : ((Number) v).intValue();
    }
}