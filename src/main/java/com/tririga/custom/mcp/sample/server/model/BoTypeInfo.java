package com.tririga.custom.mcp.sample.server.model;

import java.util.Map;

/**
 * Returned by findBoTypeId.
 * Resolves a business object type name to its numeric IDs.
 */
public record BoTypeInfo(
        String boTypeName,
        long   boTypeId,
        long   specTemplateId
) {
    public static BoTypeInfo from(Map<String, Object> row) {
        return new BoTypeInfo(
                str(row, "BO_TYPE_NAME"),
                lng(row, "BO_TYPE_ID"),
                lng(row, "SPEC_TEMPLATE_ID")
        );
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private static long lng(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }
}