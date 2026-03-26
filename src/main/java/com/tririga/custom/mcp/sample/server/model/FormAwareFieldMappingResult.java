package com.tririga.custom.mcp.sample.server.model;

import java.util.Map;

/**
 * Returned by findWorkflowsMappingIntoFieldViaForm and findWorkflowsReadingFromFieldViaForm.
 *
 * Extends FieldMappingResult with the form context — which form the field
 * was resolved through, and both the GUI label and SOBJTYPE label.
 */
public record FormAwareFieldMappingResult(
        String  workflowName,
        String  boTypeName,
        String  triggerEvent,
        String  taskLabel,
        int     taskType,
        int     mapType,
        String  mapTypeDescription,
        String  literalValue,
        String  targetFieldName,
        String  targetFieldLabel,
        String  targetSectionLabel,
        String  sourceFieldName,
        String  sourceFieldLabel,
        // Form context
        String  resolvedViaFormName,
        String  resolvedViaFormLabel,
        String  guiFieldLabel,          // label as seen on the form
        boolean guiLabelDiffersFromSobjtype
) {
    public static FormAwareFieldMappingResult from(Map<String, Object> row) {
        String guiLabel  = str(row, "FORM_FIELD_LABEL");
        String sobjLabel = str(row, "TARGETFIELDLABEL");
        return new FormAwareFieldMappingResult(
                str(row, "WF_NAME"),
                str(row, "BO_TYPE_NAME"),
                str(row, "BO_EVENT_NAME"),
                str(row, "TASK_LABEL"),
                num(row, "TASK_TYPE"),
                num(row, "MAP_TYPE"),
                MapTypeDescriptions.describe(num(row, "MAP_TYPE")),
                str(row, "FIELD_VALUE"),
                str(row, "TARGETFIELDNAME"),
                sobjLabel,
                str(row, "TARGETSECTIONLABEL"),
                str(row, "SOURCEFIELDNAME"),
                str(row, "SOURCEFIELDLABEL"),
                str(row, "GUI_NAME"),
                str(row, "FORM_LABEL"),
                guiLabel,
                guiLabel != null && !guiLabel.equals(sobjLabel)
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