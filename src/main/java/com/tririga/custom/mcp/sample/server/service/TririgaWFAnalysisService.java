package com.tririga.custom.mcp.sample.server.service;

import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.tririga.custom.mcp.sample.server.model.BoTypeInfo;
import com.tririga.custom.mcp.sample.server.model.DagTypeSets;
import com.tririga.custom.mcp.sample.server.model.FieldDefinition;
import com.tririga.custom.mcp.sample.server.model.FieldMappingResult;
import com.tririga.custom.mcp.sample.server.model.FieldReferenceResult;
import com.tririga.custom.mcp.sample.server.model.FormAwareFieldMappingResult;
import com.tririga.custom.mcp.sample.server.model.FormFieldMatch;
import com.tririga.custom.mcp.sample.server.model.StepType;
import com.tririga.custom.mcp.sample.server.model.WorkflowMappingDetail;
import com.tririga.custom.mcp.sample.server.model.WorkflowTracingStep;

import java.io.StringReader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

@Service
public class TririgaWFAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TririgaWFAnalysisService.class);

    private final TririgaDatabaseService databaseService;

    public TririgaWFAnalysisService(TririgaDatabaseService databaseService) {
        this.databaseService = databaseService;

    }

    @McpResource(uri = "db://mref/db/workflow/relationships/workflow-tables", name = "Workflow Table Relationships", description = "Describes the relationships between tables used to define a workflow in MREF")
    public String getWorkflowRelationship() {
        return """
                Workflow & Data Mapping Schema Context
                  Entities & Primary Keys
                      WF_TEMPLATE (t): Root workflow entity. PK: (WF_TEMPLATE_ID, WF_TEMPLATE_VERSION).
                      WF_TEMPLATE_STEP (ts): Individual workflow steps. PK: STEP_ID.
                      TASK (tsk): Functional task details. PK: TASK_ID. Contains MAP_ID for data transformation.
                      OBJECT_TYPE_MAP (otm): Defines data flow between source and target objects. PK: (MAP_ID, WF_TEMPLATE_ID, SRC_MEMBER_ID, TARGET_MEMBER_ID).
                      WF_STEP_REF (sr): Metadata/usage references for steps.

                  Relationship Mappings
                      WF_TEMPLATE [1] → [N] WF_TEMPLATE_STEP
                          Join Keys: WF_TEMPLATE_ID, WF_TEMPLATE_VERSION.
                          Logic: A template version contains multiple steps.

                      WF_TEMPLATE_STEP [1] → [1] TASK
                          Join Keys: STEP_ID = TASK_ID AND WF_TEMPLATE_ID AND WF_TEMPLATE_VERSION = VERSION.
                          Logic: Steps map to specific task versions anchored to the parent template.

                      TASK [1] → [1..N] OBJECT_TYPE_MAP
                          Join Keys: MAP_ID, WF_TEMPLATE_ID.
                          Logic: Tasks utilize specific object maps to define data movement (Source → Target) within the workflow context.

                      WF_TEMPLATE_STEP [1] → [0..1] WF_STEP_REF
                          Join Keys: STEP_ID, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION.
                          Logic: Optional metadata lookup (Left Outer Join).

                      WF_TEMPLATE_STEP [Self-Reference]
                          Key: PARENT_STEP_ID → STEP_ID.
                          Logic: Supports nested step hierarchies.

                      Constraints & Business Logic
                          Mapping Integrity: The OBJECT_TYPE_MAP is strictly bound to a WF_TEMPLATE_ID. A task cannot use a map that belongs to a different template.
                          Data Flow: otm defines relationships between SRC_OBJECT_TYPE_ID (source) and TARGET_OBJECT_TYPE_ID (target), including specific FIELD_VALUE transformations.
                          Version Anchoring: All joins require matching WF_TEMPLATE_ID and VERSION.
                          Current State: Result sets are typically filtered by the MAX(WF_TEMPLATE_VERSION) to ensure only the latest workflow logic is active.
                  """;
    }

    @McpResource(uri = "db://mref/db/platform-check", name = "Platform Vendor Check", description = "example sql to run to determine which db vendor we are using.")
    public String getDBVendorInfo() {
        return """
                SQL Statement 	Database Platform
                SELECT @@version    Microsoft SQL Server
                SELECT version();	PostgreSQL, MySQL, or MariaDB
                SELECT * FROM v$version;	Oracle
                SELECT sqlite_version();	SQLite
                SELECT * FROM SYSIBMADM.ENV_INST_INFO;	IBM DB2
                    """;
    }

    @Tool(description = "Provides a mapping from TASK_TYPE ID to Task Type Name.")
    public List<Map<String, Object>> getTririgaWFTaskTypeToNameMapping() {
        String sqlQuery = "SELECT TASK_TYPE, NAME FROM WF_TASK_NAME";
        log.info("MCP Tool: Fetching TRIRIGA Workflow task type/name, limit: {}", sqlQuery);
        return databaseService.runSimpleQuery(
                "mcp-run-simple-query",
                sqlQuery,
                100);
    }

    @Tool(description = "Find distinct workflow template names." +
            "Provide a Workflow Template partial name or complete name. " +
            "Returns up to the specified limit of workflow template names, ID, and version.")
    public List<Map<String, Object>> getTririgaWFTemplatesByName(
            @ToolParam(description = "Maximum number of results (optional, default: 10)") Integer limit,
            @ToolParam(description = "Name of the Workflow to search for. Can be a partial name or complete name (required)"
                    +
                    "Examples: 'Work Task' or 'triWorkTask - Synchronous - triRevise' or 'triWorkTask'") String wfName) {
        String sqlQuery = """
                SELECT DISTINCT
                    t.WF_NAME,
                    t.WF_TEMPLATE_ID,
                    t.WF_TEMPLATE_VERSION
                FROM WF_TEMPLATE t
                WHERE t.WF_NAME LIKE '%"""
                + wfName + "%'" +
                """
                            AND t.WF_TEMPLATE_VERSION = (
                            SELECT MAX(WF_TEMPLATE_VERSION)
                            FROM WF_TEMPLATE
                            WHERE WF_TEMPLATE_ID = t.WF_TEMPLATE_ID)
                        ORDER BY t.WF_NAME, t.WF_TEMPLATE_VERSION """;
        log.info("MCP Tool: Fetching TRIRIGA Workflow templates, limit: {}", limit, sqlQuery);
        return databaseService.runSimpleQuery(
                "mcp-run-simple-query",
                sqlQuery,
                limit);
    }

    @Tool(description = "Find all tasks in a workflow template." +
            "Provide a Workflow Template ID. " +
            "Returns up to the specified limit of workflow template records.")
    public List<Map<String, Object>> getTririgaWFTemplatesWithTasksByID(
            @ToolParam(description = "Maximum number of results (optional, default: 10)") Integer limit,
            @ToolParam(description = "WF_TEMPLATE_ID of the workflow (required)") Integer wfID) {
        String sqlQuery = """
                SELECT
                    t.WF_NAME,
                    t.WF_TEMPLATE_ID,
                    t.WF_TEMPLATE_VERSION,
                    ts.STEP_ID,
                    ts.STEP_TYPE,
                    ts.PARENT_STEP_ID,
                    sr.USAGE_TYPE,
                    tsk.TASK_ID,
                    tsk.TASK_LABEL,
                    tsk.DESCRIPTION,
                    tsk.MAP_ID
                FROM WF_TEMPLATE t
                JOIN WF_TEMPLATE_STEP ts
                    ON t.WF_TEMPLATE_ID = ts.WF_TEMPLATE_ID AND ts.WF_TEMPLATE_VERSION = t.WF_TEMPLATE_VERSION
                JOIN TASK tsk
                    ON ts.STEP_ID  = tsk.TASK_ID AND t.WF_TEMPLATE_ID = tsk.WF_TEMPLATE_ID AND t.WF_TEMPLATE_VERSION = tsk.VERSION
                LEFT OUTER JOIN WF_STEP_REF sr
                    ON ts.STEP_ID = sr.STEP_ID AND t.WF_TEMPLATE_ID = sr.WF_TEMPLATE_ID AND sr.WF_TEMPLATE_VERSION = t.WF_TEMPLATE_VERSION
                WHERE t.WF_TEMPLATE_ID = """
                + wfID +
                """
                 AND t.WF_TEMPLATE_VERSION = (
                    SELECT MAX(WF_TEMPLATE_VERSION)
                    FROM WF_TEMPLATE
                    WHERE WF_TEMPLATE_ID = t.WF_TEMPLATE_ID)
                ORDER BY t.WF_TEMPLATE_ID, t.WF_TEMPLATE_VERSION, ts.STEP_ID """;
        log.info("MCP Tool: Fetching TRIRIGA Workflow templates, limit: {}", limit, sqlQuery);
        return databaseService.runSimpleQuery(
                "mcp-run-simple-query",
                sqlQuery,
                limit);
    }

    @Tool(description = "Get workflow mappings for a task in a workflow template. " +
            "Returns the Source and Target Business Objects IDs, the field ID, and the type of Mapping for a task in a workflow template.")
    public List<Map<String, Object>> getMappingsForWFAndTask(
            @ToolParam(description = "Maximum number of results (optional, default: 10)") Integer limit,
            @ToolParam(description = "ID of the Workflow Template record (required)") Integer wfTemplateID,
            @ToolParam(description = "ID of the Mapping record (required)") Integer mapID,
            @ToolParam(description = "Version of the Mapping record (required)") Integer version) {
        log.info("MCP Tool: Fetching WF mappings, limit: {}", limit);

        String sqlQuery = """
                SELECT
                tsk.TASK_ID,
                tsk.TASK_LABEL,
                tsk.WF_TEMPLATE_ID,
                tsk.MAP_ID,
                otm.SRC_OBJECT_TYPE_ID,
                otm.SRC_MEMBER_ID,
                otm.TARGET_OBJECT_TYPE_ID,
                otm.TARGET_MEMBER_ID,
                otm.MAP_TYPE,
                otm.FIELD_VALUE
                FROM TASK tsk
                JOIN OBJECT_TYPE_MAP otm
                    ON tsk.MAP_ID = otm.MAP_ID
                    AND tsk.WF_TEMPLATE_ID = otm.WF_TEMPLATE_ID
                WHERE tsk.MAP_ID IS NOT NULL
                    AND t.MAP_ID = """ + mapID +
                " AND tsk.WF_TEMPLATE_ID = " + wfTemplateID +
                " AND tsk.WF_TEMPLATE_VERION = " + version;

        return databaseService.runSimpleQuery(
                "mcp-run-simple-query",
                sqlQuery,
                limit);
    }

    // ══════════════════════════════════════════════════════════
    // NEW TOOL 1 — findFormsContainingField
    //
    // Step 1 of the GUI-aware path. Always call this first when
    // the user refers to a field by the label they see on screen.
    // Present all results to the user and ask them to confirm
    // which form and BO type they mean before proceeding.
    // ══════════════════════════════════════════════════════════

    @Tool(description = """
            Searches all published TRIRIGA forms for fields whose label matches the
            user's search term. Use this as the FIRST step whenever a user refers
            to a field by the label they see on screen, such as:
              'which workflows use the Functional Role field for a person?'
              'what workflows write to the Date of Hire field?'
              'find workflows that use the Task Name field'
            Because the same label can appear on many forms across many business object
            types, and because the label shown on a form frequently differs from the
            technical label on the business object — this tool finds all matches and
            returns the form name, BO type, technical field name, and whether the
            form label differs from the underlying technical label.
            ALWAYS present all results to the user and ask them to confirm which
            specific form and BO type they mean before calling a workflow mapping tool.
            Returns guiId which is required by findWorkflowsMappingIntoFieldViaForm
            and findWorkflowsReadingFromFieldViaForm.
            """)
    public List<FormFieldMatch> findFormsContainingField(

            @ToolParam(description = """
                    The field label as the user sees it on the form, e.g.
                    'Functional Role', 'Date of Hire', 'Task Name'.
                    Partial case-insensitive match is supported.
                    Do NOT pass a technical field name (e.g. triNameTX) here —
                    use findWorkflowsMappingIntoField instead for technical names.
                    """) String fieldLabel,

            @ToolParam(description = """
                    Optional. Partial name of the business object type to narrow results,
                    e.g. 'People', 'WorkTask', 'Building'.
                    Leave blank to search across all BO types.
                    """) String boTypeNameFilter) {
        // Validated against live DB:
        // fieldLabel='Functional Role', boTypeNameFilter='' → 20+ matches across
        // triPeople, triBuilding, triFacilitiesProject, triInspectionTask, etc.
        // fieldLabel='Date of Hire', boTypeNameFilter='People' → triActiveStartDA
        // correctly resolved despite GUI label differing from SOBJTYPE label
        boolean filterBo = boTypeNameFilter != null && !boTypeNameFilter.isBlank();

        String sql = """
                SELECT DISTINCT
                    gh.SPEC_TEMPLATE_ID,
                    wf_bo.BO_TYPE_NAME,
                    gh.GUI_ID,
                    gh.GUI_NAME,
                    gh.GUI_LABEL        AS FORM_LABEL,
                    gh.DEFAULT_GUI,
                    gf.GUI_FIELD_LABEL,
                    gf.GUI_FIELD_NAME,
                    gf.ATR_SEQ,
                    gf.DD_SECTION_NAME  AS FORM_SECTION,
                    gf.ATR_TYPE,
                    sf.ATR_FIELD_LABEL  AS SOBJTYPE_LABEL,
                    sf.SECTION_LABEL    AS SOBJTYPE_SECTION
                FROM GUI_FIELDS_PUBL gf
                JOIN GUI_HEADER gh
                    ON  gh.GUI_ID = gf.GUI_ID
                JOIN SOBJTYPE_FIELDS sf
                    ON  sf.SPEC_TEMPLATE_ID = gh.SPEC_TEMPLATE_ID
                    AND sf.ATR_SEQ          = gf.ATR_SEQ
                JOIN (SELECT DISTINCT BO_TYPE_NAME, BO_TYPE_ID
                      FROM WF_TEMPLATE
                      WHERE STATUS_ID = 10) wf_bo
                    ON  wf_bo.BO_TYPE_ID = gh.SPEC_TEMPLATE_ID
                WHERE UPPER(gf.GUI_FIELD_LABEL) LIKE UPPER
                """
                + "('%" + fieldLabel + "%') "
                + (filterBo ? " AND UPPER(wf_bo.BO_TYPE_NAME) LIKE UPPER('%" + boTypeNameFilter + "%') " : "")
                + """
                        ORDER BY wf_bo.BO_TYPE_NAME, gh.GUI_NAME, gf.GUI_FIELD_LABEL
                        """;

        return databaseService.runSimpleQuery("mcp-run-simple-query", sql, null)
                .stream()
                .map(FormFieldMatch::from)
                .collect(Collectors.toList());
    }

    // // ══════════════════════════════════════════════════════════
    // // NEW TOOL 2 — findWorkflowsMappingIntoFieldViaForm
    // //
    // // Step 2 of the GUI-aware path (write direction).
    // // Call after the user has confirmed a specific form from
    // // the results of findFormsContainingField.
    // // ══════════════════════════════════════════════════════════

    // @Tool(description = """
    //         Finds all published workflows that map (write) data into a specific field,
    //         identified via a confirmed form (GUI). Use this as Step 2 AFTER calling
    //         findFormsContainingField and the user has confirmed which form and BO type
    //         they mean.
    //         Requires guiId (from findFormsContainingField results) and atrSeq
    //         (the field's ATR_SEQ, also from findFormsContainingField results).
    //         Returns workflow name, task step, map type, and data source,
    //         plus the form context showing the label the user sees vs. the
    //         underlying technical label.
    //         """)
    // public List<FormAwareFieldMappingResult> findWorkflowsMappingIntoFieldViaForm(

    //         @ToolParam(description = """
    //                 The GUI_ID of the confirmed form, from findFormsContainingField results.
    //                 e.g. 10002361 for triEmployee.
    //                 """) long guiId,

    //         @ToolParam(description = """
    //                 The ATR_SEQ of the confirmed field, from findFormsContainingField results.
    //                 e.g. 1235 for triFunctionalRoleCL.
    //                 """) int atrSeq) {
    //     // Join back through GUI_HEADER to get SPEC_TEMPLATE_ID,
    //     // then join OBJECT_TYPE_MAP on TARGET_OBJECT_TYPE_ID + TARGET_MEMBER_ID.
    //     // This ensures we only return workflows targeting the exact field on
    //     // the exact BO type the user confirmed — not other BOs with the same ATR_SEQ.
    //     String sql = """
    //             SELECT
    //                 wf.WF_NAME,
    //                 wf.BO_TYPE_NAME,
    //                 wf.BO_EVENT_NAME,
    //                 t.TASK_LABEL,
    //                 t.TASK_TYPE,
    //                 otm.MAP_TYPE,
    //                 otm.FIELD_VALUE,
    //                 sf_target.ATR_NAME          AS TARGETFIELDNAME,
    //                 sf_target.ATR_FIELD_LABEL   AS TARGETFIELDLABEL,
    //                 sf_target.SECTION_LABEL     AS TARGETSECTIONLABEL,
    //                 sf_src.ATR_NAME             AS SOURCEFIELDNAME,
    //                 sf_src.ATR_FIELD_LABEL      AS SOURCEFIELDLABEL,
    //                 gh.GUI_NAME,
    //                 gh.GUI_LABEL                AS FORM_LABEL,
    //                 gf.GUI_FIELD_LABEL          AS FORM_FIELD_LABEL,
    //                 gh.DEFAULT_GUI
    //             FROM GUI_HEADER gh
    //             JOIN GUI_FIELDS_PUBL gf
    //                 ON  gf.GUI_ID   = gh.GUI_ID
    //                 AND gf.ATR_SEQ  =
    //             """
    //             + atrSeq
    //             + """

    //                     JOIN WF_TEMPLATE wf
    //                         ON  wf.BO_TYPE_ID  = gh.SPEC_TEMPLATE_ID
    //                         AND wf.STATUS_ID   = 10
    //                     JOIN TASK t
    //                         ON  t.WF_TEMPLATE_ID = wf.WF_TEMPLATE_ID
    //                         AND t.VERSION        = wf.WF_TEMPLATE_VERSION
    //                     JOIN OBJECT_TYPE_MAP otm
    //                         ON  otm.MAP_ID              = t.MAP_ID
    //                         AND otm.WF_TEMPLATE_ID      = wf.WF_TEMPLATE_ID
    //                         AND otm.WF_TEMPLATE_VERSION = wf.WF_TEMPLATE_VERSION
    //                         AND otm.TARGET_OBJECT_TYPE_ID = gh.SPEC_TEMPLATE_ID
    //                         AND otm.TARGET_MEMBER_ID      = gf.ATR_SEQ
    //                     JOIN SOBJTYPE_FIELDS sf_target
    //                         ON  sf_target.SPEC_TEMPLATE_ID = otm.TARGET_OBJECT_TYPE_ID
    //                         AND sf_target.ATR_SEQ          = otm.TARGET_MEMBER_ID
    //                     LEFT JOIN SOBJTYPE_FIELDS sf_src
    //                         ON  sf_src.SPEC_TEMPLATE_ID = otm.SRC_OBJECT_TYPE_ID
    //                         AND sf_src.ATR_SEQ          = otm.SRC_MEMBER_ID
    //                     WHERE gh.GUI_ID =
    //                     """
    //             + guiId
    //             + """

    //                     AND otm.MAP_TYPE NOT IN (50, 60)
    //                     ORDER BY wf.WF_NAME, t.TASK_LABEL
    //                     """;

    //     return databaseService.runSimpleQuery("mcp-run-simple-query", sql, null)
    //             .stream()
    //             .map(FormAwareFieldMappingResult::from)
    //             .collect(Collectors.toList());
    // }

    // // ══════════════════════════════════════════════════════════
    // // NEW TOOL 3 — findWorkflowsReadingFromFieldViaForm
    // //
    // // Step 2 of the GUI-aware path (read direction).
    // // ══════════════════════════════════════════════════════════

    // @Tool(description = """
    //         Finds all published workflows that read from (use as a source) a specific
    //         field, identified via a confirmed form (GUI). Use this as Step 2 AFTER
    //         calling findFormsContainingField and the user has confirmed which form
    //         and BO type they mean.
    //         Requires guiId and atrSeq from findFormsContainingField results.
    //         This is the read-direction complement to findWorkflowsMappingIntoFieldViaForm.
    //         """)
    // public List<FormAwareFieldMappingResult> findWorkflowsReadingFromFieldViaForm(

    //         @ToolParam(description = """
    //                 The GUI_ID of the confirmed form, from findFormsContainingField results.
    //                 e.g. 10002361 for triEmployee.
    //                 """) long guiId,

    //         @ToolParam(description = """
    //                 The ATR_SEQ of the confirmed field, from findFormsContainingField results.
    //                 e.g. 1235 for triFunctionalRoleCL.
    //                 """) int atrSeq) {
    //     String sql = """
    //             SELECT
    //                 wf.WF_NAME,
    //                 wf.BO_TYPE_NAME,
    //                 wf.BO_EVENT_NAME,
    //                 t.TASK_LABEL,
    //                 t.TASK_TYPE,
    //                 otm.MAP_TYPE,
    //                 otm.FIELD_VALUE,
    //                 sf_target.ATR_NAME          AS TARGETFIELDNAME,
    //                 sf_target.ATR_FIELD_LABEL   AS TARGETFIELDLABEL,
    //                 sf_target.SECTION_LABEL     AS TARGETSECTIONLABEL,
    //                 sf_src.ATR_NAME             AS SOURCEFIELDNAME,
    //                 sf_src.ATR_FIELD_LABEL      AS SOURCEFIELDLABEL,
    //                 gh.GUI_NAME,
    //                 gh.GUI_LABEL                AS FORM_LABEL,
    //                 gf.GUI_FIELD_LABEL          AS FORM_FIELD_LABEL,
    //                 gh.DEFAULT_GUI
    //             FROM GUI_HEADER gh
    //             JOIN GUI_FIELDS_PUBL gf
    //                 ON  gf.GUI_ID   = gh.GUI_ID
    //                 AND gf.ATR_SEQ  =
    //             """
    //             + atrSeq
    //             + """
    //                     JOIN WF_TEMPLATE wf
    //                         ON  wf.BO_TYPE_ID  = gh.SPEC_TEMPLATE_ID
    //                         AND wf.STATUS_ID   = 10
    //                     JOIN TASK t
    //                         ON  t.WF_TEMPLATE_ID = wf.WF_TEMPLATE_ID
    //                         AND t.VERSION        = wf.WF_TEMPLATE_VERSION
    //                     JOIN OBJECT_TYPE_MAP otm
    //                         ON  otm.MAP_ID              = t.MAP_ID
    //                         AND otm.WF_TEMPLATE_ID      = wf.WF_TEMPLATE_ID
    //                         AND otm.WF_TEMPLATE_VERSION = wf.WF_TEMPLATE_VERSION
    //                         AND otm.SRC_OBJECT_TYPE_ID  = gh.SPEC_TEMPLATE_ID
    //                         AND otm.SRC_MEMBER_ID       = gf.ATR_SEQ
    //                     JOIN SOBJTYPE_FIELDS sf_src
    //                         ON  sf_src.SPEC_TEMPLATE_ID = otm.SRC_OBJECT_TYPE_ID
    //                         AND sf_src.ATR_SEQ          = otm.SRC_MEMBER_ID
    //                     LEFT JOIN SOBJTYPE_FIELDS sf_target
    //                         ON  sf_target.SPEC_TEMPLATE_ID = otm.TARGET_OBJECT_TYPE_ID
    //                         AND sf_target.ATR_SEQ          = otm.TARGET_MEMBER_ID
    //                     WHERE gh.GUI_ID =
    //                     """
    //             + guiId
    //             + """
    //                       AND otm.MAP_TYPE NOT IN (50, 60)
    //                     ORDER BY wf.WF_NAME, t.TASK_LABEL
    //                     """;

    //     return databaseService.runSimpleQuery("mcp-run-simple-query", sql, null)
    //             .stream()
    //             .map(FormAwareFieldMappingResult::from)
    //             .collect(Collectors.toList());
    // }

    // // ══════════════════════════════════════════════════════════
    // // v1 TOOLS — unchanged, kept for technical-name queries
    // // ══════════════════════════════════════════════════════════

    // @Tool(description = """
    //         Finds all published TRIRIGA workflows that map (write) data into a specific
    //         field on a specific business object type, searched by technical field name
    //         or SOBJTYPE label.
    //         Use this when you already know the exact technical field name (e.g. 'triNameTX')
    //         or the SOBJTYPE label (e.g. 'Task Name') and the BO type name.
    //         If the user only knows the label they see on a form, use findFormsContainingField
    //         first to resolve the technical name, then confirm with the user.
    //         """)
    // public List<FieldMappingResult> findWorkflowsMappingIntoField(
    //         @ToolParam(description = "The business object type name, e.g. 'triWorkTask'. Required.") String boTypeName,
    //         @ToolParam(description = "Technical field name (e.g. 'triNameTX') or SOBJTYPE label (e.g. 'Task Name'). Partial match supported.") String fieldNameOrLabel) {
    //     String p = "'%" + fieldNameOrLabel + "%'";
    //     String sql = """
    //             SELECT
    //                 wf.WF_NAME,
    //                 wf.BO_TYPE_NAME,
    //                 wf.BO_EVENT_NAME,
    //                 t.TASK_LABEL,
    //                 t.TASK_TYPE,
    //                 otm.MAP_TYPE,
    //                 otm.FIELD_VALUE,
    //                 sf_target.ATR_NAME          AS TARGETFIELDNAME,
    //                 sf_target.ATR_FIELD_LABEL   AS TARGETFIELDLABEL,
    //                 sf_target.SECTION_LABEL     AS TARGETSECTIONLABEL,
    //                 sf_src.ATR_NAME             AS SOURCEFIELDNAME,
    //                 sf_src.ATR_FIELD_LABEL      AS SOURCEFIELDLABEL
    //             FROM WF_TEMPLATE wf
    //             JOIN TASK t
    //                 ON  t.WF_TEMPLATE_ID = wf.WF_TEMPLATE_ID
    //                 AND t.VERSION        = wf.WF_TEMPLATE_VERSION
    //             JOIN OBJECT_TYPE_MAP otm
    //                 ON  otm.MAP_ID              = t.MAP_ID
    //                 AND otm.WF_TEMPLATE_ID      = wf.WF_TEMPLATE_ID
    //                 AND otm.WF_TEMPLATE_VERSION = wf.WF_TEMPLATE_VERSION
    //             JOIN SOBJTYPE_FIELDS sf_target
    //                 ON  sf_target.SPEC_TEMPLATE_ID = otm.TARGET_OBJECT_TYPE_ID
    //                 AND sf_target.ATR_SEQ          = otm.TARGET_MEMBER_ID
    //             LEFT JOIN SOBJTYPE_FIELDS sf_src
    //                 ON  sf_src.SPEC_TEMPLATE_ID = otm.SRC_OBJECT_TYPE_ID
    //                 AND sf_src.ATR_SEQ          = otm.SRC_MEMBER_ID
    //             WHERE wf.STATUS_ID    = 10
    //               AND wf.BO_TYPE_NAME =
    //               """
    //             + boTypeName
    //             + """
    //                         AND otm.MAP_TYPE NOT IN (50, 60)
    //                         AND (
    //                                  UPPER(sf_target.ATR_NAME) LIKE UPPER
    //                     """
    //             + "(" + p + ") "
    //             + """
    //                         OR UPPER(sf_target.ATR_FIELD_LABEL) LIKE UPPER
    //                     """
    //             + "(" + p + ") "
    //             + """
    //                             )
    //                     ORDER BY wf.WF_NAME, t.TASK_LABEL
    //                     """;

    //     return databaseService.runSimpleQuery("mcp-run-simple-query", sql, null)
    //             .stream()
    //             .map(FieldMappingResult::from)
    //             .collect(Collectors.toList());
    // }

    // @Tool(description = """
    //         Finds all published TRIRIGA workflows that read from a specific field
    //         on a specific business object type, searched by technical field name
    //         or SOBJTYPE label.
    //         Use this when you already know the exact technical field name or SOBJTYPE label.
    //         If the user only knows the form label, use findFormsContainingField first.
    //         """)
    // public List<FieldMappingResult> findWorkflowsReadingFromField(
    //         @ToolParam(description = "The business object type name, e.g. 'triWorkTask'. Required.") String boTypeName,
    //         @ToolParam(description = "Technical field name or SOBJTYPE label. Partial match supported.") String fieldNameOrLabel) {
    //     String p = "'%" + fieldNameOrLabel + "%'";
    //     String sql = """
    //             SELECT
    //                 wf.WF_NAME,
    //                 wf.BO_TYPE_NAME,
    //                 wf.BO_EVENT_NAME,
    //                 t.TASK_LABEL,
    //                 t.TASK_TYPE,
    //                 otm.MAP_TYPE,
    //                 otm.FIELD_VALUE,
    //                 sf_target.ATR_NAME          AS TARGETFIELDNAME,
    //                 sf_target.ATR_FIELD_LABEL   AS TARGETFIELDLABEL,
    //                 sf_target.SECTION_LABEL     AS TARGETSECTIONLABEL,
    //                 sf_src.ATR_NAME             AS SOURCEFIELDNAME,
    //                 sf_src.ATR_FIELD_LABEL      AS SOURCEFIELDLABEL
    //             FROM WF_TEMPLATE wf
    //             JOIN TASK t
    //                 ON  t.WF_TEMPLATE_ID = wf.WF_TEMPLATE_ID
    //                 AND t.VERSION        = wf.WF_TEMPLATE_VERSION
    //             JOIN OBJECT_TYPE_MAP otm
    //                 ON  otm.MAP_ID              = t.MAP_ID
    //                 AND otm.WF_TEMPLATE_ID      = wf.WF_TEMPLATE_ID
    //                 AND otm.WF_TEMPLATE_VERSION = wf.WF_TEMPLATE_VERSION
    //             JOIN SOBJTYPE_FIELDS sf_src
    //                 ON  sf_src.SPEC_TEMPLATE_ID = otm.SRC_OBJECT_TYPE_ID
    //                 AND sf_src.ATR_SEQ          = otm.SRC_MEMBER_ID
    //             LEFT JOIN SOBJTYPE_FIELDS sf_target
    //                 ON  sf_target.SPEC_TEMPLATE_ID = otm.TARGET_OBJECT_TYPE_ID
    //                 AND sf_target.ATR_SEQ          = otm.TARGET_MEMBER_ID
    //             WHERE wf.STATUS_ID    = 10
    //               AND wf.BO_TYPE_NAME =
    //               """
    //             + boTypeName
    //             + """
    //                     AND otm.MAP_TYPE    NOT IN (50, 60)
    //                     AND (
    //                           UPPER(sf_src.ATR_NAME)        LIKE UPPER"""
    //             + "(" + p + ") "
    //             + """
    //                     OR UPPER(sf_src.ATR_FIELD_LABEL) LIKE UPPER"""
    //             + "(" + p + ") "
    //             + """
    //                             )
    //                     ORDER BY wf.WF_NAME, t.TASK_LABEL
    //                     """;

    //     return databaseService.runSimpleQuery("mcp-run-simple-query", sql, null)
    //             .stream()
    //             .map(FieldMappingResult::from)
    //             .collect(Collectors.toList());
    // }

    @Tool(description = """
            Returns all field mappings defined in a specific published TRIRIGA workflow.
            Use when the user names a specific workflow and wants to see all its mappings.
            Partial workflow name match is supported.
            """)
    public List<WorkflowMappingDetail> getWorkflowMappings(
            @ToolParam(description = "Full or partial workflow name. Case-insensitive partial match.") String workflowName) {
        String sql = """
                SELECT
                    wf.WF_NAME,
                    wf.WF_TEMPLATE_ID,
                    wf.WF_TEMPLATE_VERSION,
                    wf.BO_TYPE_NAME,
                    wf.BO_EVENT_NAME,
                    t.TASK_ID,
                    t.TASK_LABEL,
                    t.TASK_TYPE,
                    otm.MAP_ID,
                    otm.MAP_TYPE,
                    otm.FIELD_VALUE,
                    sf_target.ATR_NAME          AS TARGETFIELDNAME,
                    sf_target.ATR_FIELD_LABEL   AS TARGETFIELDLABEL,
                    sf_target.SECTION_LABEL     AS TARGETSECTIONLABEL,
                    sf_target.ATR_TYPE          AS TARGETFIELDTYPE,
                    otm.TARGET_OBJECT_TYPE_ID,
                    sf_src.ATR_NAME             AS SOURCEFIELDNAME,
                    sf_src.ATR_FIELD_LABEL      AS SOURCEFIELDLABEL,
                    sf_src.SECTION_LABEL        AS SOURCESECTIONLABEL,
                    otm.SRC_MEMBER_ID
                FROM WF_TEMPLATE wf
                JOIN TASK t
                    ON  t.WF_TEMPLATE_ID = wf.WF_TEMPLATE_ID
                    AND t.VERSION        = wf.WF_TEMPLATE_VERSION
                JOIN OBJECT_TYPE_MAP otm
                    ON  otm.MAP_ID              = t.MAP_ID
                    AND otm.WF_TEMPLATE_ID      = wf.WF_TEMPLATE_ID
                    AND otm.WF_TEMPLATE_VERSION = wf.WF_TEMPLATE_VERSION
                JOIN SOBJTYPE_FIELDS sf_target
                    ON  sf_target.SPEC_TEMPLATE_ID = otm.TARGET_OBJECT_TYPE_ID
                    AND sf_target.ATR_SEQ          = otm.TARGET_MEMBER_ID
                LEFT JOIN SOBJTYPE_FIELDS sf_src
                    ON  sf_src.SPEC_TEMPLATE_ID = otm.SRC_OBJECT_TYPE_ID
                    AND sf_src.ATR_SEQ          = otm.SRC_MEMBER_ID
                WHERE wf.STATUS_ID = 10
                  AND UPPER(wf.WF_NAME) LIKE UPPER"""
                + "('%" + workflowName + "%') "
                + """
                          AND otm.MAP_TYPE NOT IN (50, 60)
                        ORDER BY wf.WF_NAME, t.TASK_ID, otm.TARGET_MEMBER_ID
                        """;

        return databaseService.runSimpleQuery("mcp-run-simple-query", sql, null)
                .stream()
                .map(WorkflowMappingDetail::from)
                .collect(Collectors.toList());
    }

    @Tool(description = """
            Returns all fields defined on a specific TRIRIGA business object type.
            Requires the numeric SPEC_TEMPLATE_ID. Call findBoTypeId first to resolve
            a BO type name to its ID.
            """)
    public List<FieldDefinition> getFieldsForBoType(
            @ToolParam(description = "Numeric SPEC_TEMPLATE_ID, e.g. 10008284 for triWorkTask.") int specTemplateId) {
        String sql = """
                SELECT
                    sf.ATR_SEQ,
                    sf.ATR_NAME,
                    sf.ATR_FIELD_LABEL,
                    sf.SECTION_LABEL,
                    sf.ATR_TYPE,
                    sf.READ_ONLY,
                    sf.SYSTEM_FLAG
                FROM SOBJTYPE_FIELDS sf
                WHERE sf.SPEC_TEMPLATE_ID =
                """
                + specTemplateId
                + """
                          AND sf.DELETED_FLAG = 0
                        ORDER BY sf.SECTION_LABEL, sf.ATR_FIELD_LABEL
                        """;

        return databaseService.runSimpleQuery("mcp-run-simple-query", sql, null)
                .stream()
                .map(FieldDefinition::from)
                .collect(Collectors.toList());
    }

    @Tool(description = """
            Resolves a TRIRIGA business object type name to its numeric SPEC_TEMPLATE_ID.
            Required before calling getFieldsForBoType. Also useful for confirming the
            exact BO type name or discovering related BO types.
            Partial case-insensitive match supported.
            """)
    public List<BoTypeInfo> findBoTypeId(
            @ToolParam(description = "BO type name to look up, e.g. 'triWorkTask'. Partial match supported.") String boTypeName) {
        // Simplified from v1 — no unnecessary join through SOBJTYPE_FIELDS.
        // WF_TEMPLATE is the correct source for this tool's purpose: BO types
        // that have no published workflows have no mappings to find.
        String sql = """
                SELECT DISTINCT
                    BO_TYPE_NAME,
                    BO_TYPE_ID      AS SPEC_TEMPLATE_ID
                FROM WF_TEMPLATE
                WHERE STATUS_ID = 10
                  AND UPPER(BO_TYPE_NAME) LIKE UPPER """
                + "('%" + boTypeName + "%') "
                + """
                        ORDER BY BO_TYPE_NAME
                        """;

        return databaseService.runSimpleQuery("mcp-run-simple-query", sql, null)
                .stream()
                .map(row -> new BoTypeInfo(
                        row.get("BO_TYPE_NAME") == null ? null : row.get("BO_TYPE_NAME").toString(),
                        ((Number) row.getOrDefault("SPEC_TEMPLATE_ID", 0L)).longValue(),
                        ((Number) row.getOrDefault("SPEC_TEMPLATE_ID", 0L)).longValue()))
                .collect(Collectors.toList());
    }

   
    @Tool(name = "generateWorkflowTrace", description = """
            Creates a Directed Analytic Graph of workflow calls.
            By default the tool returns all nodes/tasks in a workflow. However, an optional parameter can be used to only return a graph that contains 'call workflow'
            and 'swtich' nodes/tasks.

            Each node in the DAG contains information in the following format:
            "hashCode()+\": \"+wfName+\" \"+\"'\"+taskLabel+\"'\"+\" \"+type+\" \"+workflowStepID+\" \"+parentWorkflowStepID+\" \"+parentHashCode();"
            However the following node types will contain additional information.
            Type = 14 will also be appended with the expression used for the switch.
            Type = 38 will also be appended with the name of workflow being called.

            Use the 'getWorkflowTraceTypeSets' tool to get a list of valid set names.

            """)
    public String generateWorkflowTrace(
            @ToolParam(description = "The workflow name", required = true) String workflowName,
            @ToolParam(description = "Set of workflow task types to return in the DAG, defaults to 'ALL' is no parameter entered. Another common option is WF_CALL_FLOW, this option will return only Start, Switch, and Call Workflow tasks.", required = false) String... outputDetails) {

        DirectedAcyclicGraph<WorkflowTracingStep, DefaultEdge> workflowMap = generateDAGForWorkflow(workflowName, null);
        String setName = (outputDetails.length > 0) ? outputDetails[0] : "ALL";

        if (setName != "ALL") {
            // 1. Define your "Safe" types
            Set<Integer> keepTypes = DagTypeSets.getIdListByLabel(setName).stream().collect(Collectors.toSet());
            if(keepTypes ==null || keepTypes.size()<1){ return "no types defined";}

            // 2. Identify all nodes that are NOT in your whitelist
            List<WorkflowTracingStep> nodesToBridge = workflowMap.vertexSet().stream()
                    .filter(step -> !keepTypes.contains(step.getType()))
                    .collect(Collectors.toList());

            // 3. Remove them while bridging the gap between their parents and children
            for (WorkflowTracingStep step : nodesToBridge) {
                Graphs.removeVertexAndPreserveConnectivity(workflowMap, step);
            }
        }

        return workflowMap.toString();
    }
    @Tool(name = "generateCustomWorkflowTrace", description = """
            Creates a Directed Analytic Graph of workflow calls.
            By default the tool returns all nodes/tasks in a workflow. However, an optional parameter can be used to only return a graph that contains the task types equal to the passed
            in IDs. Example: generateCustomWorkflowTrace('triServiceAgreementLineItem - Synchronous - triUploadHidden', "1","14","38") 

            Each node in the DAG contains information in the following format:
            "hashCode()+\": \"+wfName+\" \"+\"'\"+taskLabel+\"'\"+\" \"+type+\" \"+workflowStepID+\" \"+parentWorkflowStepID+\" \"+parentHashCode();"
            However the following node types will contain additional information.
            Type = 14 will also contain the expression used for the switch.
            Type = 38 will also contain the name of workflow being called.

            """)
     public String generateCustomWorkflowTrace(
            @ToolParam(description = "The workflow name", required = true) String workflowName,
            @ToolParam(description = "A string of task type IDs separated by commas. example: \"1\",\"14\",\"38\" ", required = false) String... outputDetails) {

        DirectedAcyclicGraph<WorkflowTracingStep, DefaultEdge> workflowMap = generateDAGForWorkflow(workflowName, null);
    

        if (outputDetails.length > 0) {
            // 1. Define your "Safe" types
            Set<Integer> keepTypes = Arrays.stream(outputDetails)
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
            if(keepTypes ==null || keepTypes.size()<1){ return "no types defined";}

            // 2. Identify all nodes that are NOT in your whitelist
            List<WorkflowTracingStep> nodesToBridge = workflowMap.vertexSet().stream()
                    .filter(step -> !keepTypes.contains(step.getType()))
                    .collect(Collectors.toList());

            // 3. Remove them while bridging the gap between their parents and children
            for (WorkflowTracingStep step : nodesToBridge) {
                Graphs.removeVertexAndPreserveConnectivity(workflowMap, step);
            }
        }

        return workflowMap.toString();
    }

    public DirectedAcyclicGraph<WorkflowTracingStep, DefaultEdge> generateDAGForWorkflow(
            String workflowName, DirectedAcyclicGraph<WorkflowTracingStep, DefaultEdge> wfMap) {

        DirectedAcyclicGraph<WorkflowTracingStep, DefaultEdge> workflowMap;
        Map<Integer, WorkflowTracingStep> taskMap = new HashMap<>();
        List<WorkflowTracingStep> taskList;

        if (wfMap == null) {
            workflowMap = new DirectedAcyclicGraph<>(DefaultEdge.class);
        } else
            workflowMap = wfMap;

        taskList = getDAGWorkflowTasksFromName(workflowName);

        // add verticies
        for (WorkflowTracingStep task : taskList) {
            if (task.getType() == StepType.CALL_WORKFLOW.getId()) {
                workflowMap.addVertex(task);
                taskMap.put(task.hashCode(), task);
                DirectedAcyclicGraph<WorkflowTracingStep, DefaultEdge> childWorkflowMap = generateDAGForWorkflow(
                        task.getCalledWFName(), null);
                // log.info(childWorkflowMap.toString());
                WorkflowTracingStep firstChildStep = childWorkflowMap.vertexSet().stream()
                        .filter(v -> childWorkflowMap.inDegreeOf(v) == 0)
                        .findFirst()
                        .orElse(null);
                log.info("Found first step in child workflow: " + firstChildStep.toString());
                Graphs.addGraph(workflowMap, childWorkflowMap);

                workflowMap.addEdge(task, firstChildStep);
            } else {
                workflowMap.addVertex(task);
                taskMap.put(task.hashCode(), task);
            }
            log.info("Added vertex: " + task.toString());
        }

        // Build Edges
        log.info("Adding Edges");
        for (WorkflowTracingStep task : taskList) {
            if (task.getParentWorkflowStepID() != -1) {
                log.info("Looking for parent of: " + task.toString());
                WorkflowTracingStep parentStep = taskMap.get(task.parentHashCode());
                if (parentStep != null) {
                    log.info("Adding edge from: " + parentStep.hashCode() + " TO " + task.hashCode());
                    workflowMap.addEdge(parentStep, task);
                }
            }
        }

        return workflowMap;

    }

    @Tool(name = "getWorkflowTraceTypeSets", description = """
            Returns a list of pre-defined sets of task types to use for 'generateWFTrace' tool.

            """)
     public List<String> getWorkflowTraceTypeSets(){

        List<String> setNames = Stream.of(DagTypeSets.values())
                           .map(DagTypeSets::getLabel)
                           .collect(Collectors.toList());

        return setNames;
    
            }

    public List<WorkflowTracingStep> getDAGWorkflowTasksFromName(String workflowName) {
        String sqlQuery = """
                SELECT
                    wf.WF_NAME,
                    wf.WF_TEMPLATE_ID,
                    wf.WF_TEMPLATE_VERSION,
                    wf.BO_TYPE_NAME,
                    wf.BO_EVENT_NAME,
                    t.TASK_ID,
                    t.TASK_LABEL,
                    t.TASK_TYPE,
                    t.DESCRIPTION,
                    t.FILTER_OBJECT,
                    t.MAP_ID,
                    ts.STEP_ID,
                    ts.PARENT_STEP_ID,
                    ts.STEP_TYPE
                FROM WF_TEMPLATE wf
                JOIN WF_TEMPLATE_STEP ts
                    ON wf.WF_TEMPLATE_ID = ts.WF_TEMPLATE_ID AND ts.WF_TEMPLATE_VERSION = wf.WF_TEMPLATE_VERSION
                JOIN TASK t
                    ON  t.WF_TEMPLATE_ID = wf.WF_TEMPLATE_ID
                    AND t.VERSION        = wf.WF_TEMPLATE_VERSION
                    AND ts.STEP_ID  = t.TASK_ID
                WHERE wf.STATUS_ID = 10
                  AND UPPER(wf.WF_NAME) LIKE UPPER"""
                + "('%" + workflowName + "%') "
                + """
                        ORDER BY wf.WF_NAME, t.TASK_ID
                        """;

        List<Map<String, Object>> results = databaseService.runSimpleQuery(
                "mcp-run-simple-query",
                sqlQuery,
                1200);

        List<WorkflowTracingStep> workflowSteps = results.stream()
                .map(row -> {
                    // Extract values from the map based on the SQL aliases
                    String label = (String) row.get("TASK_LABEL");
                    Integer type = (row.get("STEP_TYPE") != null) ? ((Number) row.get("STEP_TYPE")).intValue() : null;
                    Integer stepId = (row.get("STEP_ID") != null) ? ((Number) row.get("STEP_ID")).intValue() : null;
                    Integer parentStepId = (row.get("PARENT_STEP_ID") != null)
                            ? ((Number) row.get("PARENT_STEP_ID")).intValue()
                            : null;
                    Integer templateId = (row.get("WF_TEMPLATE_ID") != null)
                            ? ((Number) row.get("WF_TEMPLATE_ID")).intValue()
                            : null;
                    Integer wfVersion = (row.get("WF_TEMPLATE_VERSION") != null)
                            ? ((Number) row.get("WF_TEMPLATE_VERSION")).intValue()
                            : null;

                    // Create the instance using the constructor
                    WorkflowTracingStep step = new WorkflowTracingStep(
                            label,
                            type,
                            stepId,
                            parentStepId,
                            templateId,
                            workflowName);
                    log.info("Created Tracing Step: " + step.toString());

                    // Map additional fields not in the constructor
                    // Note: Check if 'CONDITION_EXPRESSION' exists in your actual SQL/Database
                    if (step.getType() == StepType.SWITCH.getId()) {
                        Integer mapId = (row.get("MAP_ID") != null)
                                ? ((Number) row.get("MAP_ID")).intValue()
                                : null;
                        step.setIsConditional(true);
                        String sqlQuery3 = """
                                SELECT
                                   e.FORMULA , ep.PARAM_ID ,ep.PARAM_STR
                                   FROM EXPRESSION e
                                   JOIN EXPRESSION_PARAM ep
                                       ON e.ID = ep.FORMULA_ID
                                   WHERE e.ID = """
                                + Integer.valueOf(mapId);

                        List<Map<String, Object>> results3 = databaseService.runSimpleQuery(
                                "mcp-run-simple-query",
                                sqlQuery3,
                                1200);

                        String formula = results3.stream()
                                .filter(map -> map.containsKey("FORMULA"))
                                .map(map -> (String) map.get("FORMULA"))
                                .findFirst()
                                .orElse("Default Value"); // Or handle the missing case

                        Map<String, String> parameterMap = results3.stream()
                                .collect(Collectors.toMap(
                                        row2 -> "p" + String.valueOf(row2.get("PARAM_ID")), // Key: PARAM_ID as String
                                        row2 -> {
                                            String pStr = (String) row2.get("PARAM_STR");
                                            try {
                                                // Call your parsing method here
                                                return parseTaskRow(pStr, templateId, wfVersion);
                                            } catch (Exception e) {
                                                return "Error parsing XML";
                                            }
                                        }));

                        step.setConditionExpression(replacePlaceholders(formula, parameterMap));

                    }
                    if (step.getTaskLabel() == null) {
                        step.setTaskLabel(StepType.getLabelById(step.getType()));
                    }
                    if (step.getType() == StepType.CALL_WORKFLOW.getId()) {
                        Integer filterObject = (row.get("FILTER_OBJECT") != null)
                                ? ((Number) row.get("FILTER_OBJECT")).intValue()
                                : null;
                        step.setFilterObject(filterObject);
                        String sqlQuery2 = """
                                 SELECT DISTINCT
                                    t.WF_NAME,
                                    t.WF_TEMPLATE_ID,
                                    t.WF_TEMPLATE_VERSION
                                FROM WF_TEMPLATE t
                                WHERE t.WF_TEMPLATE_ID = """
                                + filterObject
                                + """
                                            AND t.WF_TEMPLATE_VERSION = (
                                            SELECT MAX(WF_TEMPLATE_VERSION)
                                            FROM WF_TEMPLATE
                                            WHERE WF_TEMPLATE_ID = t.WF_TEMPLATE_ID)
                                        ORDER BY t.WF_NAME, t.WF_TEMPLATE_VERSION """;

                        List<Map<String, Object>> results2 = databaseService.runSimpleQuery(
                                "mcp-run-simple-query",
                                sqlQuery2,
                                1200);
                        String wfName = results2.stream()
                                .filter(map -> map.containsKey("WF_NAME"))
                                .map(map -> (String) map.get("WF_NAME"))
                                .findFirst()
                                .orElse("Default Value"); // Or handle the missing case
                        step.setCalledWFName(wfName);
                        log.info("Updated Step: " + step.hashCode() + " with WF name.");
                    }

                    return step;
                })
                .collect(Collectors.toList());
        return workflowSteps;
    }

    // TODO: put this in a different file
    @Tool(name = "get_db2_table_schema", description = "Fetches column names, types, and lengths for a DB2 table")
    public List<Map<String, Object>> getTableSchema(
            @ToolParam(description = "The table name (case-sensitive)", required = true) String tableName,
            @ToolParam(description = "The schema/creator name", required = false) String schemaName) {

        // Authoritative DB2 catalog query
        String sqlQuery = "SELECT NAME, COLTYPE, LENGTH FROM SYSIBM.SYSCOLUMNS WHERE TBNAME = '" + tableName + "'";

        return databaseService.runSimpleQuery(
                "mcp-run-simple-query",
                sqlQuery,
                1200);
    }

    public String parseTaskRow(String xmlRow, Integer templateId, Integer wfVersion) throws Exception {
        // Prepare the XML document
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new InputSource(new StringReader(xmlRow)));

        Element task = doc.getDocumentElement();
        String type = task.getAttribute("type");
        String taskId = task.getAttribute("id");

        String sqlQuery1 = """
                SELECT
                t.TASK_ID , t.TASK_LABEL
                FROM TASK t
                WHERE """
                + " t.WF_TEMPLATE_ID = " + templateId
                + " and t.VERSION = " + wfVersion
                + " AND t.TASK_ID  = " + taskId
                + ";";
        List<Map<String, Object>> results2 = databaseService.runSimpleQuery(
                "mcp-run-simple-query",
                sqlQuery1,
                1200);
        String taskLabel = results2.stream()
                .filter(map -> map.containsKey("TASK_LABEL"))
                .map(map -> (String) map.get("TASK_LABEL"))
                .findFirst()
                .orElse("Default Value"); // Or handle the missing case
        if ("item".equals(type)) {
            // Return the 'item' attribute directly
            return taskLabel + "::" + task.getAttribute("item");
        } else if ("field".equals(type)) {
            // Look for the nested <field> tag and get 'fieldName'
            NodeList fields = task.getElementsByTagName("field");
            if (fields.getLength() > 0) {
                return taskLabel + "::" + ((Element) fields.item(0)).getAttribute("SectionName") + "::"
                        + ((Element) fields.item(0)).getAttribute("fieldName");
            }
        }

        return null;
    }

    public String replacePlaceholders(String formula, Map<String, String> parameterMap) {
        if (formula == null || parameterMap == null)
            return formula;

        // \b ensures we match "p1" but not the "p1" inside "p10" or "temp1"
        Pattern pattern = Pattern.compile("\\bp\\d+\\b");
        Matcher matcher = pattern.matcher(formula);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group();
            // Get the raw value (e.g., "triStatusTX") or keep "pX" if missing
            String replacement = parameterMap.getOrDefault(key, key);

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

   // ══════════════════════════════════════════════════════════════════════════
    // findWorkflowsReferencingFieldAnywhere
    //
    // WHY THIS EXISTS
    // ───────────────
    // The existing field-search tools (findWorkflowsMappingIntoField,
    // findWorkflowsReadingFromField, and their ViaForm variants) have three gaps:
    //
    // GAP 1 — '-Any-' workflows are invisible.
    //   All four tools join WF_TEMPLATE on BO_TYPE_ID = SPEC_TEMPLATE_ID, which
    //   silently drops every workflow registered against '-Any-'.  Real example:
    //   'triPeople - triCopy - Creates a Copy' copies triFunctionalRoleCL but
    //   never appears in a Functional Role search via those tools.
    //
    // GAP 2 — Expression-based task field references are never checked.
    //   Some task types store a condition/filter in EXPRESSION / EXPRESSION_PARAM
    //   with TASK.MAP_ID = EXPRESSION.ID.  No existing tool queries those tables
    //   for field references.
    //
    //   IMPORTANT: EXPRESSION.ID and OBJECT_TYPE_MAP.MAP_ID share the same integer
    //   space and collide frequently, so joining to EXPRESSION without a TASK_TYPE
    //   filter produces false positives for mapping task types (Create Record,
    //   Modify Records, etc.) whose MAP_ID happens to equal an unrelated
    //   EXPRESSION.ID.  The query uses an explicit IN-list of task types confirmed
    //   to use EXPRESSION as their primary data structure:
    //     1  = Start
    //     14 = Switch
    //     21 = Break
    //     40 = Variable Definition
    //     41 = Variable Assignment
    //     43 = Fact Condition
    //
    // GAP 3 — MAP_TYPE 80 (formula mapping) field references are never resolved.
    //   For MAP_TYPE 80 rows, OBJECT_TYPE_MAP.SRC_MEMBER_ID is NOT a field ATR_SEQ;
    //   it is a SOBJTYPE_FORMULA_HDR.FORMULA_ID.  The formula's input parameters
    //   (the source fields) are stored in SOBJTYPE_FORMULA_PARAMS with
    //   PARAM_TYPE='I' and PARAM_FLD_QRY_FLG='F'.  The field name is in
    //   PARAM_DISP_STR as "SectionName:FieldTechnicalName" — the part after the
    //   colon is the ATR_NAME.  SOBJTYPE_FORMULA_HDR.SPEC_TEMPLATE_ID carries the
    //   negated WF_TEMPLATE_ID (a workflow-instance marker), so the SOBJTYPE_FIELDS
    //   join must use OTM.SRC_OBJECT_TYPE_ID rather than h.SPEC_TEMPLATE_ID.
    //   The existing TARGET/SOURCE queries silently skip all MAP_TYPE 80 rows
    //   because SRC_MEMBER_ID never resolves in SOBJTYPE_FIELDS.
    //
    // APPROACH — FOUR QUERIES, ONE RESULT SET
    // ─────────────────────────────────────────
    // Query 1 (TARGET): field is the write target in OBJECT_TYPE_MAP.
    //   Excludes MAP_TYPE 80 (handled separately) and 50/60 (filter/conditional).
    //
    // Query 2 (SOURCE): field is the read source in OBJECT_TYPE_MAP.
    //   Excludes MAP_TYPE 80 and 50/60.
    //
    // Query 3 (EXPR): field appears in a condition/filter expression.
    //   TASK (types 1,14,21,40,41,43) → EXPRESSION → EXPRESSION_PARAM
    //   → SOBJTYPE_FIELDS via LIKE match on PARAM_STR XML fieldName attribute.
    //   Explicit TASK_TYPE filter prevents false positives from ID collision.
    //
    // Query 4 (FORMULA): field is a formula input parameter (MAP_TYPE 80).
    //   OBJECT_TYPE_MAP (MAP_TYPE=80) → SOBJTYPE_FORMULA_HDR (SRC_MEMBER_ID=FORMULA_ID)
    //   → SOBJTYPE_FORMULA_PARAMS (PARAM_TYPE='I', PARAM_FLD_QRY_FLG='F')
    //   → SOBJTYPE_FIELDS on OTM.SRC_OBJECT_TYPE_ID + field name from PARAM_DISP_STR.
    //   FIELD_VALUE carries both the formula string and the formula param display
    //   string so callers can see the full computation context.
    //
    // All four results are merged, deduplicated, and sorted by workflow name.
    //
    // RESULT MODEL  →  FieldReferenceResult
    // ──────────────────────────────────────
    //   direction:   TARGET | SOURCE | EXPR | FORMULA
    //   workflowName, boTypeName, boEventName
    //   taskLabel, taskType
    //   mapType      — null for EXPR; 80 for FORMULA; map type for TARGET/SOURCE
    //   fieldValue   — EXPR: EXPRESSION.FORMULA
    //                  FORMULA: "<formula> | param: <PARAM_DISP_STR>"
    //                  LiteralSet TARGET/SOURCE: the literal value; else null
    //   targetFieldName / targetFieldLabel / targetSectionLabel
    //     → EXPR/FORMULA: the matched source field (being tested / used as input)
    //   sourceFieldName / sourceFieldLabel
    //     → EXPR: null    → FORMULA: null (formula has no single source field;
    //                                      see fieldValue for full param context)
    // ══════════════════════════════════════════════════════════════════════════

    @Tool(description = """
            Finds ALL published TRIRIGA workflows that reference a specific field —
            regardless of the BO type the workflow is registered against, and
            regardless of the type of task or mapping that contains the reference. Basically a "where used" check for a field.

            Closes three gaps in the existing field-search tools:

            GAP 1 — '-Any-' workflows (e.g. triCopy workflows) are invisible to
            findWorkflowsMappingIntoField and findWorkflowsReadingFromField because
            those tools join through BO_TYPE_ID, which never matches the sentinel
            ID carried by '-Any-' workflows.

            GAP 2 — Expression-based task field references are never checked. Some
            task types store field references in EXPRESSION / EXPRESSION_PARAM via
            TASK.MAP_ID = EXPRESSION.ID. Covered task types: Start (1), Switch (14),
            Break (21), Variable Definition (40), Variable Assignment (41), Fact
            Condition (43). An explicit task type filter is required — EXPRESSION.ID
            and OBJECT_TYPE_MAP.MAP_ID share the same integer space and collide
            frequently, causing false positives without the filter.

            GAP 3 — MAP_TYPE 80 (formula mapping) field references are never resolved.
            For MAP_TYPE 80, OBJECT_TYPE_MAP.SRC_MEMBER_ID is a SOBJTYPE_FORMULA_HDR
            FORMULA_ID, not a field ATR_SEQ. The source fields are stored in
            SOBJTYPE_FORMULA_PARAMS and are completely invisible to the existing
            TARGET/SOURCE queries.

            This tool runs four queries and merges the results:
              • TARGET  — field is written to in a mapping task (OBJECT_TYPE_MAP)
              • SOURCE  — field is read from in a mapping task (OBJECT_TYPE_MAP)
              • EXPR    — field appears in an expression-based task; taskType
                          identifies which kind; fieldValue carries the formula
              • FORMULA — field is an input parameter in a MAP_TYPE 80 formula;
                          fieldValue carries the formula and the param display string

            Accepts either a technical field name (e.g. triFunctionalRoleCL) or a
            SOBJTYPE label (e.g. 'Functional Role'). Partial, case-insensitive match
            on both ATR_NAME and ATR_FIELD_LABEL in SOBJTYPE_FIELDS.

            Use this as the definitive field-reference search. The narrower BO-scoped
            tools are still useful when you need to filter by a specific BO type.
            """)
    public List<FieldReferenceResult> findWorkflowsReferencingFieldAnywhere(

            @ToolParam(description = """
                    Technical field name (e.g. triFunctionalRoleCL) or SOBJTYPE label
                    (e.g. 'Functional Role'). Partial case-insensitive match supported.
                    If you only know the on-screen form label, call findFormsContainingField
                    first to resolve the underlying technical name, then pass it here.
                    """) String fieldNameOrLabel) {

        String p = "'%" + fieldNameOrLabel + "%'";

        // ── Query 1: TARGET — field is being written to ───────────────────────
        // MAP_TYPE 80 excluded: SRC_MEMBER_ID is a FORMULA_ID, not an ATR_SEQ,
        // so the SOBJTYPE_FIELDS join on SOURCE side always returns nothing for
        // those rows. They are handled by Query 4 instead.
        // MAP_TYPE 50 (Filter) and 60 (Conditional) excluded: they do not move data.
        String sqlTarget = """
                SELECT
                    wf.WF_NAME,
                    wf.BO_TYPE_NAME,
                    wf.BO_EVENT_NAME,
                    t.TASK_LABEL,
                    t.TASK_TYPE,
                    otm.MAP_TYPE,
                    otm.FIELD_VALUE,
                    'TARGET'                    AS DIRECTION,
                    sf_target.ATR_NAME          AS TARGETFIELDNAME,
                    sf_target.ATR_FIELD_LABEL   AS TARGETFIELDLABEL,
                    sf_target.SECTION_LABEL     AS TARGETSECTIONLABEL,
                    sf_src.ATR_NAME             AS SOURCEFIELDNAME,
                    sf_src.ATR_FIELD_LABEL      AS SOURCEFIELDLABEL
                FROM WF_TEMPLATE wf
                JOIN TASK t
                    ON  t.WF_TEMPLATE_ID = wf.WF_TEMPLATE_ID
                    AND t.VERSION        = wf.WF_TEMPLATE_VERSION
                JOIN OBJECT_TYPE_MAP otm
                    ON  otm.MAP_ID              = t.MAP_ID
                    AND otm.WF_TEMPLATE_ID      = wf.WF_TEMPLATE_ID
                    AND otm.WF_TEMPLATE_VERSION = wf.WF_TEMPLATE_VERSION
                JOIN SOBJTYPE_FIELDS sf_target
                    ON  sf_target.SPEC_TEMPLATE_ID = otm.TARGET_OBJECT_TYPE_ID
                    AND sf_target.ATR_SEQ          = otm.TARGET_MEMBER_ID
                LEFT JOIN SOBJTYPE_FIELDS sf_src
                    ON  sf_src.SPEC_TEMPLATE_ID = otm.SRC_OBJECT_TYPE_ID
                    AND sf_src.ATR_SEQ          = otm.SRC_MEMBER_ID
                WHERE wf.STATUS_ID = 10
                  AND otm.MAP_TYPE NOT IN (50, 60, 80)
                  AND (
                        UPPER(sf_target.ATR_NAME)        LIKE UPPER\s"""
                + "(" + p + ") "
                + "OR UPPER(sf_target.ATR_FIELD_LABEL) LIKE UPPER"
                + "(" + p + ") "
                + ")";

        // ── Query 2: SOURCE — field is being read from ────────────────────────
        // Same MAP_TYPE exclusions as Query 1.
        String sqlSource = """
                SELECT
                    wf.WF_NAME,
                    wf.BO_TYPE_NAME,
                    wf.BO_EVENT_NAME,
                    t.TASK_LABEL,
                    t.TASK_TYPE,
                    otm.MAP_TYPE,
                    otm.FIELD_VALUE,
                    'SOURCE'                    AS DIRECTION,
                    sf_target.ATR_NAME          AS TARGETFIELDNAME,
                    sf_target.ATR_FIELD_LABEL   AS TARGETFIELDLABEL,
                    sf_target.SECTION_LABEL     AS TARGETSECTIONLABEL,
                    sf_src.ATR_NAME             AS SOURCEFIELDNAME,
                    sf_src.ATR_FIELD_LABEL      AS SOURCEFIELDLABEL
                FROM WF_TEMPLATE wf
                JOIN TASK t
                    ON  t.WF_TEMPLATE_ID = wf.WF_TEMPLATE_ID
                    AND t.VERSION        = wf.WF_TEMPLATE_VERSION
                JOIN OBJECT_TYPE_MAP otm
                    ON  otm.MAP_ID              = t.MAP_ID
                    AND otm.WF_TEMPLATE_ID      = wf.WF_TEMPLATE_ID
                    AND otm.WF_TEMPLATE_VERSION = wf.WF_TEMPLATE_VERSION
                JOIN SOBJTYPE_FIELDS sf_src
                    ON  sf_src.SPEC_TEMPLATE_ID = otm.SRC_OBJECT_TYPE_ID
                    AND sf_src.ATR_SEQ          = otm.SRC_MEMBER_ID
                LEFT JOIN SOBJTYPE_FIELDS sf_target
                    ON  sf_target.SPEC_TEMPLATE_ID = otm.TARGET_OBJECT_TYPE_ID
                    AND sf_target.ATR_SEQ          = otm.TARGET_MEMBER_ID
                WHERE wf.STATUS_ID = 10
                  AND otm.MAP_TYPE NOT IN (50, 60, 80)
                  AND (
                        UPPER(sf_src.ATR_NAME)        LIKE UPPER\s"""
                + "(" + p + ") "
                + "OR UPPER(sf_src.ATR_FIELD_LABEL) LIKE UPPER"
                + "(" + p + ") "
                + ")";

        // ── Query 3: EXPR — field appears in an expression-based task ─────────
        //
        // PARAM_STR XML structure for field-type params:
        //   <task type='field' id='...'><field fieldName='triXxx'
        //       sectionName='...' boId='...' /></task>
        //
        // We pre-filter to type='field' rows, then join SOBJTYPE_FIELDS where
        // the ATR_NAME appears literally in PARAM_STR as fieldName='<ATR_NAME>'.
        //
        // TASK_TYPE IN-list is required to prevent ID collision false positives.
        // Verified types and their EXPR_ONLY vs IN_BOTH collision counts:
        //
        //   Type  Name                  EXPR_ONLY  IN_BOTH
        //   ────  ──────────────────    ─────────  ───────
        //    1    Start                 14,520       387   ← expression is primary
        //   14    Switch                23,001       485   ← expression is primary
        //   21    Break                    280        22   ← expression is primary
        //   40    Variable Definition   13,772         0   ← expression only
        //   41    Variable Assignment    3,171         0   ← expression only
        //   43    Fact Condition           111         0   ← expression only
        //
        // FIELD_VALUE carries EXPRESSION.FORMULA for full expression context.
        // TASK_TYPE is projected so callers can identify which task type matched.
        String sqlExpr = """
                SELECT DISTINCT
                    wf.WF_NAME,
                    wf.BO_TYPE_NAME,
                    wf.BO_EVENT_NAME,
                    t.TASK_LABEL,
                    t.TASK_TYPE,
                    NULL                        AS MAP_TYPE,
                    e.FORMULA                   AS FIELD_VALUE,
                    'EXPR'                      AS DIRECTION,
                    sf.ATR_NAME                 AS TARGETFIELDNAME,
                    sf.ATR_FIELD_LABEL          AS TARGETFIELDLABEL,
                    sf.SECTION_LABEL            AS TARGETSECTIONLABEL,
                    NULL                        AS SOURCEFIELDNAME,
                    NULL                        AS SOURCEFIELDLABEL
                FROM WF_TEMPLATE wf
                JOIN TASK t
                    ON  t.WF_TEMPLATE_ID = wf.WF_TEMPLATE_ID
                    AND t.VERSION        = wf.WF_TEMPLATE_VERSION
                JOIN EXPRESSION e
                    ON  e.ID = t.MAP_ID
                JOIN EXPRESSION_PARAM ep
                    ON  ep.FORMULA_ID = e.ID
                JOIN SOBJTYPE_FIELDS sf
                    ON  ep.PARAM_STR LIKE '%fieldName=''' || sf.ATR_NAME || '''%'
                WHERE wf.STATUS_ID = 10
                  AND t.TASK_TYPE  IN (1, 14, 21, 40, 41, 43)
                  AND ep.PARAM_STR LIKE '%type=''field''%'
                  AND (
                        UPPER(sf.ATR_NAME)        LIKE UPPER\s"""
                + "(" + p + ") "
                + "OR UPPER(sf.ATR_FIELD_LABEL) LIKE UPPER"
                + "(" + p + ") "
                + ")";

        // ── Query 4: FORMULA — field is an input parameter in a MAP_TYPE 80 row ─
        //
        // MAP_TYPE 80 is a computed/formula mapping.  FIELD_VALUE contains the
        // formula expression (e.g. "A+\" space\"" or "BUILDING+\" - \"+FLOOR").
        // The placeholder letters (A, B, FLOOR, …) are bound to source fields via
        // SOBJTYPE_FORMULA_PARAMS, NOT via a direct ATR_SEQ in OBJECT_TYPE_MAP.
        //
        // Join chain:
        //   OBJECT_TYPE_MAP (MAP_TYPE=80)
        //     → SOBJTYPE_FORMULA_HDR    (OTM.SRC_MEMBER_ID = h.FORMULA_ID)
        //     → SOBJTYPE_FORMULA_PARAMS (matching FORMULA_ID / SPEC_TEMPLATE_ID /
        //                                ATR_SEQ; PARAM_TYPE='I'; PARAM_FLD_QRY_FLG='F')
        //     → SOBJTYPE_FIELDS         (OTM.SRC_OBJECT_TYPE_ID + ATR_NAME extracted
        //                                from PARAM_DISP_STR after the colon)
        //
        // Key facts verified against live data:
        //   • SOBJTYPE_FORMULA_HDR.SPEC_TEMPLATE_ID = −WF_TEMPLATE_ID (negative,
        //     a workflow-instance marker).  It is NOT a valid SOBJTYPE_FIELDS key.
        //     Always use OTM.SRC_OBJECT_TYPE_ID for the SOBJTYPE_FIELDS join.
        //   • PARAM_DISP_STR format: "SectionName:FieldTechnicalName"
        //     SUBSTR(..., INSTR(..., ':') + 1) reliably extracts the ATR_NAME.
        //   • PARAM_TYPE='I' = input/source parameter (field reference).
        //     PARAM_TYPE='O' = output parameter (target field via PARAM_ATR_SEQ).
        //     Only 'I' with PARAM_FLD_QRY_FLG='F' are direct field references.
        //   • PARAM_ATR_SEQ is always 0 for PARAM_TYPE='I' — do not use it.
        //
        // FIELD_VALUE is set to "<formula> | param: <PARAM_DISP_STR>" so callers
        // can see both what the formula computes and which param slot the matched
        // field occupies (e.g. "BUILDING+\" - \"+FLOOR | param: RecordInformation:triParentFloorTX").
        //
        // DISTINCT collapses duplicates that arise when the same field appears as
        // multiple params in one formula (e.g. A and C both bound to the same field).
        String sqlFormula = """
                SELECT DISTINCT
                    wf.WF_NAME,
                    wf.BO_TYPE_NAME,
                    wf.BO_EVENT_NAME,
                    t.TASK_LABEL,
                    t.TASK_TYPE,
                    otm.MAP_TYPE,
                    otm.FIELD_VALUE || ' | param: ' || p.PARAM_DISP_STR AS FIELD_VALUE,
                    'FORMULA'                   AS DIRECTION,
                    sf_param.ATR_NAME           AS TARGETFIELDNAME,
                    sf_param.ATR_FIELD_LABEL    AS TARGETFIELDLABEL,
                    sf_param.SECTION_LABEL      AS TARGETSECTIONLABEL,
                    NULL                        AS SOURCEFIELDNAME,
                    NULL                        AS SOURCEFIELDLABEL
                FROM OBJECT_TYPE_MAP otm
                JOIN SOBJTYPE_FORMULA_HDR h
                    ON  h.FORMULA_ID = otm.SRC_MEMBER_ID
                JOIN SOBJTYPE_FORMULA_PARAMS p
                    ON  p.FORMULA_ID       = h.FORMULA_ID
                    AND p.SPEC_TEMPLATE_ID = h.SPEC_TEMPLATE_ID
                    AND p.ATR_SEQ          = h.ATR_SEQ
                    AND p.PARAM_TYPE       = 'I'
                    AND p.PARAM_FLD_QRY_FLG = 'F'
                JOIN SOBJTYPE_FIELDS sf_param
                    ON  sf_param.SPEC_TEMPLATE_ID = otm.SRC_OBJECT_TYPE_ID
                    AND sf_param.ATR_NAME =
                        SUBSTR(p.PARAM_DISP_STR, INSTR(p.PARAM_DISP_STR, ':') + 1)
                JOIN TASK t
                    ON  t.MAP_ID         = otm.MAP_ID
                    AND t.WF_TEMPLATE_ID = otm.WF_TEMPLATE_ID
                    AND t.VERSION        = otm.WF_TEMPLATE_VERSION
                JOIN WF_TEMPLATE wf
                    ON  wf.WF_TEMPLATE_ID      = t.WF_TEMPLATE_ID
                    AND wf.WF_TEMPLATE_VERSION = t.VERSION
                LEFT JOIN SOBJTYPE_FIELDS sf_target
                    ON  sf_target.SPEC_TEMPLATE_ID = otm.TARGET_OBJECT_TYPE_ID
                    AND sf_target.ATR_SEQ          = otm.TARGET_MEMBER_ID
                WHERE otm.MAP_TYPE  = 80
                  AND wf.STATUS_ID  = 10
                  AND (
                        UPPER(sf_param.ATR_NAME)        LIKE UPPER\s"""
                + "(" + p + ") "
                + "OR UPPER(sf_param.ATR_FIELD_LABEL) LIKE UPPER"
                + "(" + p + ") "
                + ")";

        // ── Execute all four and merge ────────────────────────────────────────
        List<FieldReferenceResult> targetRows = databaseService
                .runSimpleQuery("mcp-run-simple-query", sqlTarget, null)
                .stream()
                .map(FieldReferenceResult::from)
                .collect(Collectors.toList());

        List<FieldReferenceResult> sourceRows = databaseService
                .runSimpleQuery("mcp-run-simple-query", sqlSource, null)
                .stream()
                .map(FieldReferenceResult::from)
                .collect(Collectors.toList());

        List<FieldReferenceResult> exprRows = databaseService
                .runSimpleQuery("mcp-run-simple-query", sqlExpr, null)
                .stream()
                .map(FieldReferenceResult::from)
                .collect(Collectors.toList());

        List<FieldReferenceResult> formulaRows = databaseService
                .runSimpleQuery("mcp-run-simple-query", sqlFormula, null)
                .stream()
                .map(FieldReferenceResult::from)
                .collect(Collectors.toList());

        // Deduplicate on (wfName, taskLabel, direction, targetFieldName, sourceFieldName).
        // Duplicates can arise when:
        //   • A field has multiple SOBJTYPE_FIELDS rows (same ATR_NAME, diff ATR_SEQ)
        //   • An EXPR param XML matches both the ATR_NAME and ATR_FIELD_LABEL branches
        //   • A FORMULA param appears in multiple PARAM rows for the same formula
        return Stream.of(targetRows, sourceRows, exprRows, formulaRows)
                .flatMap(List::stream)
                .distinct()
                .sorted(Comparator
                        .comparing(FieldReferenceResult::getWorkflowName,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(FieldReferenceResult::getTaskLabel,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(FieldReferenceResult::getDirection,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }
}