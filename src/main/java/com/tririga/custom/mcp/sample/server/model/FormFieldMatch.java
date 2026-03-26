package com.tririga.custom.mcp.sample.server.model;

import java.util.Map;

/**
 * Returned by findFormsContainingField.
 *
 * Represents a single (form, field) match — i.e. one form that contains
 * a field whose GUI label matches the user's search term.
 *
 * The AI model should present all results to the user and ask them to
 * confirm which specific form and BO type they are referring to before
 * proceeding to a workflow mapping query.
 */
public record FormFieldMatch(
        long    guiId,
        String  formName,
        String  formLabel,
        boolean defaultForm,
        long    specTemplateId,
        String  boTypeName,
        String  guiFieldLabel,
        String  guiFieldName,
        int     atrSeq,
        String  formSection,
        String  fieldType,
        String  sobtypeLabel,       // label on SOBJTYPE_FIELDS — may differ from guiFieldLabel
        String  sobtypeSection,
        boolean labelsDiffer        // true when GUI label != SOBJTYPE label
) {
    public static FormFieldMatch from(Map<String, Object> row) {
        String guiLabel    = str(row, "GUI_FIELD_LABEL");
        String sobjLabel   = str(row, "SOBJTYPE_LABEL");
        return new FormFieldMatch(
                lng(row, "GUI_ID"),
                str(row, "GUI_NAME"),
                str(row, "FORM_LABEL"),
                num(row, "DEFAULT_GUI") == 1,
                lng(row, "SPEC_TEMPLATE_ID"),
                str(row, "BO_TYPE_NAME"),
                guiLabel,
                str(row, "GUI_FIELD_NAME"),
                num(row, "ATR_SEQ"),
                str(row, "FORM_SECTION"),
                str(row, "ATR_TYPE"),
                sobjLabel,
                str(row, "SOBJTYPE_SECTION"),
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
    private static long lng(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }
}