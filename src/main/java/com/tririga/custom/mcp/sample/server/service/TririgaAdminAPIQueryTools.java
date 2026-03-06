package com.tririga.custom.mcp.sample.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


@Component
public class TririgaAdminAPIQueryTools {

    private static final Logger log = LoggerFactory.getLogger(TririgaAdminAPIQueryTools.class);
    
    private final TririgaDatabaseService databaseService;
    
    public TririgaAdminAPIQueryTools(TririgaDatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @Tool(description =
        "Execute a SQL query against the TRIRIGA database. " +
        "Returns results as a list of rows with column names and values. " +
        "Use this to query tables like T_triPeople, license_metric, or any TRIRIGA table.")
    public List<Map<String, Object>> queryTririgaDatabase(
            @ToolParam(description = "The SQL query to execute") String sqlQuery,
            @ToolParam(description = "Name for the query script (optional)") String scriptName,
            @ToolParam(description = "Maximum number of results (optional, default: 10)") Integer limit
    ) {
        log.info("MCP Tool: Executing TRIRIGA query - {}", scriptName);
        
        try {
            List<Map<String, Object>> results = databaseService.runSimpleQuery(
                scriptName != null ? scriptName : "MCP_Query",
                sqlQuery,
                limit
            );
            
            log.info("MCP Tool: Query returned {} rows", results.size());
            return results;
            
        } catch (Exception e) {
            log.error("MCP Tool: Error executing query", e);
            throw new RuntimeException("Failed to execute TRIRIGA query: " + e.getMessage(), e);
        }
    }

    @Tool(description =
        "Get a list of all people from TRIRIGA (T_triPeople table). " +
        "Returns up to the specified limit of person records.")
    public List<Map<String, Object>> getTririgaPeople(
            @ToolParam(description = "Maximum number of results (optional, default: 10)") Integer limit) {
        log.info("MCP Tool: Fetching TRIRIGA people, limit: {}", limit);
        
        String sqlQuery = "SELECT * FROM T_triPeople ORDER BY TRIMODIFIEDSY DESC";
        
        return databaseService.runSimpleQuery(
            "Get_People",
            sqlQuery,
            limit
        );
    }

    @Tool(description =
        "Get license metrics from TRIRIGA. " +
        "Returns recent license usage data ordered by timestamp.")
    public List<Map<String, Object>> getLicenseMetrics(
            @ToolParam(description = "Maximum number of results (optional, default: 10)") Integer limit) {
        log.info("MCP Tool: Fetching license metrics, limit: {}", limit);
        
        String sqlQuery = "SELECT * FROM license_metric ORDER BY time_snapshot DESC";
        
        return databaseService.runSimpleQuery(
            "License_Metrics",
            sqlQuery,
            limit
        );
    }
}