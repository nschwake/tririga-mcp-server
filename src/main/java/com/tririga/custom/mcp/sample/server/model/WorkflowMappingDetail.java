package com.tririga.custom.mcp.sample.server.model;

import java.util.Map;

/**
 * Returned by getWorkflowMappings.
 * Full detail of a single mapping row within a specific workflow,
 * with both source and target field names resolved.
 */
public record WorkflowMappingDetail(
        String workflowName,
        long   workflowId,
        int    workflowVersion,
        String boTypeName,
        String triggerEvent,
        int    taskId,
        String taskLabel,
        int    taskType,
        int    mapType,
        String mapTypeDescription,
        String literalValue,
        String targetFieldName,
        String targetFieldLabel,
        String targetSectionLabel,
        String targetFieldType,
        String sourceFieldName,
        String sourceFieldLabel,
        String sourceSectionLabel
) {
    public static WorkflowMappingDetail from(Map<String, Object> row) {
        int mapType = num(row, "MAP_TYPE");
        return new WorkflowMappingDetail(
                str(row, "WF_NAME"),
                lng(row, "WF_TEMPLATE_ID"),
                num(row, "WF_TEMPLATE_VERSION"),
                str(row, "BO_TYPE_NAME"),
                str(row, "BO_EVENT_NAME"),
                num(row, "TASK_ID"),
                str(row, "TASK_LABEL"),
                num(row, "TASK_TYPE"),
                mapType,
                MapTypeDescriptions.describe(mapType),
                str(row, "FIELD_VALUE"),
                str(row, "TARGETFIELDNAME"),
                str(row, "TARGETFIELDLABEL"),
                str(row, "TARGETSECTIONLABEL"),
                str(row, "TARGETFIELDTYPE"),
                str(row, "SOURCEFIELDNAME"),
                str(row, "SOURCEFIELDLABEL"),
                str(row, "SOURCESECTIONLABEL")
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

    private static long lng(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }
}