package com.tririga.custom.mcp.sample.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class TririgaWFDevelopmentService {

    private static final Logger log = LoggerFactory.getLogger(TririgaWFDevelopmentService.class);

    private final TririgaDatabaseService databaseService;

    public TririgaWFDevelopmentService(TririgaDatabaseService databaseService) {
        this.databaseService = databaseService;
        
    }

    @McpResource(
        uri = "db://mref/db/workflow/relationships/workflow-tables",
        name = "Workflow Table Relationships",
        description = "Describes the relationships between tables used to define a workflow in MREF"
    )
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

     @McpResource(
        uri = "db://mref/db/platform-check",
        name = "Platform Vendor Check",
        description = "example sql to run to determine which db vendor we are using."
    )
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

    // TODO: put this in a different file
      @McpTool(name = "get_db2_table_schema", 
             description = "Fetches column names, types, and lengths for a DB2 table")
    public List<Map<String, Object>> getTableSchema(
            @McpToolParam(description = "The table name (case-sensitive)", required = true) String tableName,
            @McpToolParam(description = "The schema/creator name", required = false) String schemaName) {
        
        // Authoritative DB2 catalog query
        String sqlQuery = "SELECT NAME, COLTYPE, LENGTH FROM SYSIBM.SYSCOLUMNS WHERE TBNAME = '"+tableName+"'";

         return databaseService.runSimpleQuery(
                "mcp-run-simple-query",
                sqlQuery,
                1200);
         }
}