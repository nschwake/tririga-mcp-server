package com.tririga.custom.mcp.sample.server.service;

import com.tririga.custom.mcp.sample.server.config.TririgaApiConfig;
import com.tririga.custom.mcp.sample.server.query.DatabaseQueryRequest;
import com.tririga.custom.mcp.sample.server.query.DatabaseQueryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TririgaDatabaseService {

    private static final Logger log = LoggerFactory.getLogger(TririgaDatabaseService.class);
    
    private final HttpClient httpClient;
    private final TririgaApiConfig config;
    private final TririgaSessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private static final String RUN_QUERY_ENDPOINT = "/api/v1/admin/databaseQuery/run";
    
    public TririgaDatabaseService(HttpClient httpClient, TririgaApiConfig config, TririgaSessionManager sessionManager) {
        this.httpClient = httpClient;
        this.config = config;
        this.sessionManager = sessionManager;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Execute a SQL query against the TRIRIGA database
     *
     * @param request The database query request
     * @return The query response with results
     */
    public DatabaseQueryResponse runQuery(DatabaseQueryRequest request) {
        log.info("Executing TRIRIGA database query: {}", request.getScriptName());

        try {
            String requestBody = objectMapper.writeValueAsString(request);
            String url = config.getTririgaUrl() + RUN_QUERY_ENDPOINT;

            // Get session cookie from session manager
            String sessionCookie = sessionManager.getSessionCookie();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Cookie", sessionCookie)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                DatabaseQueryResponse queryResponse = objectMapper.readValue(response.body(), DatabaseQueryResponse.class);
                log.info("Query executed successfully. Retrieved {} rows", 
                        queryResponse.getResults() != null ? queryResponse.getResults().size() : 0);
                return queryResponse;
            } else if (response.statusCode() == 401) {
                // Session expired, refresh and retry once
                log.warn("Session expired (401), refreshing session and retrying");
                sessionCookie = sessionManager.refreshSession();
                
                // Retry with new session
                httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Cookie", sessionCookie)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();
                
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    DatabaseQueryResponse queryResponse = objectMapper.readValue(response.body(), DatabaseQueryResponse.class);
                    log.info("Query executed successfully after session refresh. Retrieved {} rows", 
                            queryResponse.getResults() != null ? queryResponse.getResults().size() : 0);
                    return queryResponse;
                } else {
                    log.error("Query failed after session refresh. Status: {}", response.statusCode());
                    throw new RuntimeException("Failed to execute query after retry. Status: " + response.statusCode() + 
                                             ", Body: " + response.body());
                }
            } else {
                log.error("Unexpected response status: {}", response.statusCode());
                throw new RuntimeException("Failed to execute query. Status: " + response.statusCode() + 
                                         ", Body: " + response.body());
            }

        } catch (Exception e) {
            log.error("Error executing query", e);
            throw new RuntimeException("Error executing query: " + e.getMessage(), e);
        }
    }

    /**
     * Execute a simple SQL query and return results as a list of maps
     * This is a convenience method for easier data access
     *
     * @param scriptName Name of the query script
     * @param sqlQuery The SQL query to execute
     * @param limit Maximum number of results
     * @return List of row data as maps (column name -> value)
     */
    public List<Map<String, Object>> runSimpleQuery(String scriptName, String sqlQuery, Integer limit) {
        DatabaseQueryRequest request = DatabaseQueryRequest.builder()
                .scriptName(scriptName)
                .sqlQuery(sqlQuery)
                .limit(limit != null ? limit : 100)
                .build();

        DatabaseQueryResponse response = runQuery(request);
        return convertResultsToMaps(response);
    }

    /**
     * Convert the complex nested result structure to a simpler list of maps
     *
     * @param response The database query response
     * @return List of maps where each map represents a row
     */
    private List<Map<String, Object>> convertResultsToMaps(DatabaseQueryResponse response) {
        List<Map<String, Object>> simplifiedResults = new ArrayList<>();

        if (response.getResults() == null || response.getMetaDataHelper() == null) {
            return simplifiedResults;
        }

        Map<String, String> columnLabels = response.getMetaDataHelper().getColumnLabels();

        for (List<DatabaseQueryResponse.ResultValue> row : response.getResults()) {
            Map<String, Object> rowMap = new HashMap<>();
            
            for (int i = 0; i < row.size(); i++) {
                String columnName = columnLabels.get(String.valueOf(i));
                Object value = row.get(i).getValue();
                rowMap.put(columnName, value);
            }
            
            simplifiedResults.add(rowMap);
        }

        return simplifiedResults;
    }
}