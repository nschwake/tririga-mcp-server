package com.tririga.custom.mcp.sample.server.model;

import java.util.Map;
import java.util.Objects;

/**
 * Result row for {@code findWorkflowsReferencingFieldAnywhere}.
 *
 * <p>One instance represents a single field reference inside a published
 * TRIRIGA workflow.  The {@link #direction} field indicates how the field
 * is referenced:
 * <ul>
 *   <li>{@code TARGET}  — field is written to via {@code OBJECT_TYPE_MAP}
 *                         (all map types except 50, 60, 80)</li>
 *   <li>{@code SOURCE}  — field is read from via {@code OBJECT_TYPE_MAP}
 *                         (all map types except 50, 60, 80)</li>
 *   <li>{@code EXPR}    — field appears in an expression-based task
 *                         ({@code EXPRESSION} / {@code EXPRESSION_PARAM}).
 *                         Covers task types confirmed to use EXPRESSION as their
 *                         primary data structure: Start (1), Switch (14),
 *                         Break (21), Variable Definition (40),
 *                         Variable Assignment (41), Fact Condition (43).
 *                         {@link #taskType} identifies which kind matched;
 *                         {@link #fieldValue} carries the full formula string.</li>
 *   <li>{@code FORMULA} — field is an input parameter in a MAP_TYPE 80
 *                         (computed/formula) mapping.  {@code OBJECT_TYPE_MAP
 *                         .SRC_MEMBER_ID} is a {@code SOBJTYPE_FORMULA_HDR
 *                         .FORMULA_ID}; the source field bindings live in
 *                         {@code SOBJTYPE_FORMULA_PARAMS}.  {@link #fieldValue}
 *                         carries {@code "<formula> | param: <PARAM_DISP_STR>"}
 *                         so callers can see both the formula and which parameter
 *                         slot the matched field occupies.</li>
 * </ul>
 *
 * <p>For {@code EXPR} and {@code FORMULA} rows: {@link #sourceFieldName} /
 * {@link #sourceFieldLabel} are {@code null} — see {@link #fieldValue} for
 * expression/formula context.  {@link #targetFieldName} / {@link #targetFieldLabel}
 * hold the matched field (being tested or used as a formula input).
 * {@link #mapType} is {@code null} for EXPR rows and {@code 80} for FORMULA rows.
 */
public class FieldReferenceResult {

    // ── Workflow identity ─────────────────────────────────────────────────────
    private String workflowName;
    private String boTypeName;       // '-Any-' for copy/sync workflows
    private String boEventName;

    // ── Task context ──────────────────────────────────────────────────────────
    private String taskLabel;
    private Integer taskType;

    // ── Mapping metadata ─────────────────────────────────────────────────────
    private Integer mapType;         // 10=FieldCopy, 40=LiteralSet, 70=SourceLookup, …
                                     // 80=Formula (MAP_TYPE 80 formula mapping)
                                     // null for EXPR rows (no OBJECT_TYPE_MAP row)
    private String  fieldValue;      // TARGET/SOURCE LiteralSet : the literal value
                                     // EXPR    : full EXPRESSION.FORMULA string
                                     //             e.g. "p0 == \"TRUE\" "
                                     // FORMULA : "<formula> | param: <PARAM_DISP_STR>"
                                     //             e.g. "BUILDING+\" - \"+FLOOR | param: RecordInformation:triParentFloorTX"

    /**
     * How the field is referenced in this workflow task:
     * <ul>
     *   <li>{@code TARGET}  — field is written to (OBJECT_TYPE_MAP target side,
     *                         map types except 50, 60, 80)</li>
     *   <li>{@code SOURCE}  — field is read from  (OBJECT_TYPE_MAP source side,
     *                         map types except 50, 60, 80)</li>
     *   <li>{@code EXPR}    — field appears in an expression-based task
     *                         (EXPRESSION / EXPRESSION_PARAM); see {@link #taskType}
     *                         for the specific task type and {@link #fieldValue}
     *                         for the full formula</li>
     *   <li>{@code FORMULA} — field is an input parameter in a MAP_TYPE 80
     *                         (computed/formula) mapping; {@link #mapType} = 80;
     *                         {@link #fieldValue} = "&lt;formula&gt; | param: &lt;PARAM_DISP_STR&gt;"</li>
     * </ul>
     */
    private String direction;        // "SOURCE" | "TARGET" | "EXPR" | "FORMULA"

    // ── Field details ─────────────────────────────────────────────────────────
    private String targetFieldName;
    private String targetFieldLabel;
    private String targetSectionLabel;
    private String sourceFieldName;
    private String sourceFieldLabel;

    // ── Constructors ─────────────────────────────────────────────────────────

    public FieldReferenceResult() {}

    // ── Factory ──────────────────────────────────────────────────────────────

    /**
     * Builds a {@code FieldReferenceResult} from a raw DB row.
     *
     * <p>All four queries in {@code findWorkflowsReferencingFieldAnywhere}
     * project identical column aliases, so this factory handles TARGET, SOURCE,
     * EXPR, and FORMULA rows uniformly.  Columns that are not meaningful for a
     * given direction are projected as {@code NULL} in the SQL and arrive as
     * {@code null} here:
     * <ul>
     *   <li>EXPR rows    : {@code MAP_TYPE}, {@code SOURCEFIELDNAME},
     *                      {@code SOURCEFIELDLABEL} are {@code null};
     *                      {@code FIELD_VALUE} = {@code EXPRESSION.FORMULA}</li>
     *   <li>FORMULA rows : {@code SOURCEFIELDNAME}, {@code SOURCEFIELDLABEL}
     *                      are {@code null}; {@code MAP_TYPE} = 80;
     *                      {@code FIELD_VALUE} = {@code "<formula> | param: <PARAM_DISP_STR>"};
     *                      {@code TARGETFIELDNAME/LABEL} hold the matched formula
     *                      input parameter field</li>
     * </ul>
     */
    public static FieldReferenceResult from(Map<String, Object> row) {
        FieldReferenceResult r = new FieldReferenceResult();
        r.workflowName      = str(row, "WF_NAME");
        r.boTypeName        = str(row, "BO_TYPE_NAME");
        r.boEventName       = str(row, "BO_EVENT_NAME");
        r.taskLabel         = str(row, "TASK_LABEL");
        r.taskType          = intVal(row, "TASK_TYPE");
        r.mapType           = intVal(row, "MAP_TYPE");
        r.fieldValue        = str(row, "FIELD_VALUE");
        r.direction         = str(row, "DIRECTION");
        r.targetFieldName   = str(row, "TARGETFIELDNAME");
        r.targetFieldLabel  = str(row, "TARGETFIELDLABEL");
        r.targetSectionLabel= str(row, "TARGETSECTIONLABEL");
        r.sourceFieldName   = str(row, "SOURCEFIELDNAME");
        r.sourceFieldLabel  = str(row, "SOURCEFIELDLABEL");
        return r;
    }

    // ── Equality & deduplication ─────────────────────────────────────────────

    /**
     * Two results are considered duplicates when they describe the same field
     * reference: same workflow, same task, same direction, and same target +
     * source field names.
     *
     * <p>This handles two known duplication sources:
     * <ul>
     *   <li>A field appearing on multiple {@code SOBJTYPE_FIELDS} rows with
     *       different {@code ATR_SEQ} values (rare but possible across BO subtypes)</li>
     *   <li>EXPR rows where the same field param appears in multiple
     *       {@code EXPRESSION_PARAM} rows for the same expression (e.g. a field
     *       referenced in both branches of an AND), or where the ATR_NAME LIKE
     *       match hits multiple {@code SOBJTYPE_FIELDS} rows</li>
     * </ul>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FieldReferenceResult)) return false;
        FieldReferenceResult that = (FieldReferenceResult) o;
        return Objects.equals(workflowName,      that.workflowName)
            && Objects.equals(taskLabel,          that.taskLabel)
            && Objects.equals(direction,          that.direction)
            && Objects.equals(targetFieldName,    that.targetFieldName)
            && Objects.equals(sourceFieldName,    that.sourceFieldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workflowName, taskLabel, direction,
                            targetFieldName, sourceFieldName);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer intVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return null;
        return ((Number) v).intValue();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String  getWorkflowName()       { return workflowName; }
    public String  getBoTypeName()         { return boTypeName; }
    public String  getBoEventName()        { return boEventName; }
    public String  getTaskLabel()          { return taskLabel; }
    public Integer getTaskType()           { return taskType; }
    public Integer getMapType()            { return mapType; }
    public String  getFieldValue()         { return fieldValue; }
    public String  getDirection()          { return direction; }
    public String  getTargetFieldName()    { return targetFieldName; }
    public String  getTargetFieldLabel()   { return targetFieldLabel; }
    public String  getTargetSectionLabel() { return targetSectionLabel; }
    public String  getSourceFieldName()    { return sourceFieldName; }
    public String  getSourceFieldLabel()   { return sourceFieldLabel; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setWorkflowName(String workflowName)             { this.workflowName = workflowName; }
    public void setBoTypeName(String boTypeName)                 { this.boTypeName = boTypeName; }
    public void setBoEventName(String boEventName)               { this.boEventName = boEventName; }
    public void setTaskLabel(String taskLabel)                   { this.taskLabel = taskLabel; }
    public void setTaskType(Integer taskType)                    { this.taskType = taskType; }
    public void setMapType(Integer mapType)                      { this.mapType = mapType; }
    public void setFieldValue(String fieldValue)                 { this.fieldValue = fieldValue; }
    public void setDirection(String direction)                   { this.direction = direction; }
    public void setTargetFieldName(String targetFieldName)       { this.targetFieldName = targetFieldName; }
    public void setTargetFieldLabel(String targetFieldLabel)     { this.targetFieldLabel = targetFieldLabel; }
    public void setTargetSectionLabel(String targetSectionLabel) { this.targetSectionLabel = targetSectionLabel; }
    public void setSourceFieldName(String sourceFieldName)       { this.sourceFieldName = sourceFieldName; }
    public void setSourceFieldLabel(String sourceFieldLabel)     { this.sourceFieldLabel = sourceFieldLabel; }
}