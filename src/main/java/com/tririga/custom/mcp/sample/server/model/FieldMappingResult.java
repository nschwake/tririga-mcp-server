package com.tririga.custom.mcp.sample.server.model;

import java.util.Map;

/**
 * Returned by findWorkflowsMappingIntoField and findWorkflowsReadingFromField.
 * Describes a single OTM row — one field-to-field (or literal-to-field) mapping
 * within a workflow task step.
 */
public record FieldMappingResult(
        String workflowName,
        String boTypeName,
        String triggerEvent,
        String taskLabel,
        int    taskType,
        int    mapType,
        String mapTypeDescription,
        String literalValue,
        String targetFieldName,
        String targetFieldLabel,
        String targetSectionLabel,
        String sourceFieldName,
        String sourceFieldLabel
) {
    public static FieldMappingResult from(Map<String, Object> row) {
        int mapType = num(row, "MAP_TYPE");
        return new FieldMappingResult(
                str(row, "WF_NAME"),
                str(row, "BO_TYPE_NAME"),
                str(row, "BO_EVENT_NAME"),
                str(row, "TASK_LABEL"),
                num(row, "TASK_TYPE"),
                mapType,
                MapTypeDescriptions.describe(mapType),
                str(row, "FIELD_VALUE"),
                str(row, "TARGETFIELDNAME"),
                str(row, "TARGETFIELDLABEL"),
                str(row, "TARGETSECTIONLABEL"),
                str(row, "SOURCEFIELDNAME"),
                str(row, "SOURCEFIELDLABEL")
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