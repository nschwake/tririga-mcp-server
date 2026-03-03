package com.microsoft.mcp.sample.server.service;

import com.microsoft.mcp.sample.server.oslc.OslcCreateResult;
import com.microsoft.mcp.sample.server.oslc.OslcJsonBuilder;
import com.microsoft.mcp.sample.server.oslc.OslcProperty;
import com.microsoft.mcp.sample.server.oslc.OslcServiceCatalog;
import com.microsoft.mcp.sample.server.oslc.OslcShapeEntry;
import com.microsoft.mcp.sample.server.oslc.OslcShape;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class TririgaOSLCService {

    // ─── Well-known shape paths ───────────────────────────────────────────────
    private static final String SHAPE_WORK_TASK = "/oslc/shapes/triWorkTaskRS";
    private static final String SHAPE_ASSET     = "/oslc/shapes/triAssetRS";
    private static final String SHAPE_LOCATION  = "/oslc/shapes/triLocationRS";

    // ─── Shape cache ─────────────────────────────────────────────────────────
    private final ConcurrentHashMap<String, OslcShape> shapeCache = new ConcurrentHashMap<>();

    // ─── Service catalog (shape index built from all service providers) ──────
    private final OslcServiceCatalog catalog = new OslcServiceCatalog();

    // ─── Transaction ID counter ──────────────────────────────────────────────
    private final AtomicLong txCounter = new AtomicLong(System.currentTimeMillis());

    // ─────────────────────────────────────────────────────────────────────────
    //  TRIRIGA URL conventions
    //
    //  Every TRIRIGA OSLC resource follows a consistent naming pattern based
    //  on the resource name (e.g. "triWorkTask", "cstCustomExample"):
    //
    //    Shape:        /oslc/shapes/{name}RS
    //    Query:        /oslc/spq/{name}QC
    //    Creation:     /oslc/so/{name}CF
    //    Read/Update:  /oslc/so/{name}RS/{id}
    //    Delete:       /oslc/so/{name}RS/{id}  (HTTP DELETE)
    //
    //  The generic tools below use these patterns so they work with ANY resource
    //  — built-in or custom — without requiring new Java code.
    // ─────────────────────────────────────────────────────────────────────────

    private String shapeUrl(String resourceName)    { return tririgaUrl() + "/oslc/shapes/" + resourceName + "RS"; }
    private String queryUrl(String resourceName)    { return tririgaUrl() + "/oslc/spq/"   + resourceName + "QC"; }
    private String createUrl(String resourceName)   { return tririgaUrl() + "/oslc/so/"    + resourceName + "CF"; }
    private String recordUrl(String resourceName, String id) {
        return tririgaUrl() + "/oslc/so/" + resourceName + "RS/" + id;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HTTP helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String tririgaUrl() { return System.getenv("TRIRIGA_URL"); }

    private String encodedAuth() {
        String user = System.getenv("TRIRIGA_USER");
        String pass = System.getenv("TRIRIGA_PASS");
        return Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
    }

    private String get(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization",     "Basic " + encodedAuth())
                .header("OSLC-Core-Version", "2.0")
                .header("Accept",            "application/rdf+xml")
                .GET().build();
        try {
            return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException e) { return "Error: " + e.getMessage(); }
    }

    private OslcCreateResult post(String url, String jsonBody, String transactionId) {
        String txId = (transactionId != null && !transactionId.isBlank())
                ? transactionId : String.valueOf(txCounter.incrementAndGet());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization",     "Basic " + encodedAuth())
                .header("OSLC-Core-Version", "2.0")
                .header("Content-Type",      "application/json;charset=utf-8")
                .header("Accept",            "application/rdf+xml")
                .header("transactionid",     txId)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            return new OslcCreateResult(r.statusCode(),
                    r.headers().firstValue("Location").orElse(null),
                    r.headers().firstValue("ETag").orElse(null),
                    r.body());
        } catch (IOException | InterruptedException e) {
            return new OslcCreateResult(500, null, null, "Error: " + e.getMessage());
        }
    }

    private String put(String url, String jsonBody) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization",     "Basic " + encodedAuth())
                .header("OSLC-Core-Version", "2.0")
                .header("Content-Type",      "application/json;charset=utf-8")
                .header("Accept",            "application/rdf+xml")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
        try {
            return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException e) { return "Error: " + e.getMessage(); }
    }

    private String delete(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization",     "Basic " + encodedAuth())
                .header("OSLC-Core-Version", "2.0")
                .DELETE().build();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 || r.statusCode() == 204
                    ? "Deleted successfully (HTTP " + r.statusCode() + ")."
                    : "Delete failed (HTTP " + r.statusCode() + "): " + r.body();
        } catch (IOException | InterruptedException e) { return "Error: " + e.getMessage(); }
    }

    private String encode(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    // ─────────────────────────────────────────────────────────────────────────
    //  Shape loading & caching
    // ─────────────────────────────────────────────────────────────────────────

    private OslcShape loadShape(String shapePath) {
        String fullUrl = tririgaUrl() + shapePath;
        return shapeCache.computeIfAbsent(fullUrl, url -> {
            try { return OslcShape.parse(url, get(url)); }
            catch (Exception e) {
                throw new RuntimeException("Failed to load shape " + url + ": " + e.getMessage(), e);
            }
        });
    }

    private OslcShape loadShapeByName(String resourceName) {
        return loadShape("/oslc/shapes/" + resourceName + "RS");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Shape tools
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description =
        "Describe the writable fields for any TRIRIGA resource — built-in or custom. "
        + "Shows writable literal fields (plain values) and writable resource-link fields "
        + "(fields requiring a full TRIRIGA resource URL). "
        + "Example paths: '/oslc/shapes/triWorkTaskRS', '/oslc/shapes/cstCustomExampleRS'. "
        + "Call this before any create or update operation to discover available fields.")
    public String describeShape(
            @ToolParam(description = "Shape path, e.g. '/oslc/shapes/triWorkTaskRS' or '/oslc/shapes/cstCustomExampleRS'") String shapePath) {
        try {
            OslcShape shape = loadShape(shapePath);
            StringBuilder sb = new StringBuilder();
            sb.append("Shape: ").append(shape.getTitle()).append("\n");
            sb.append("Describes: ").append(shape.getDescribesType()).append("\n\n");

            List<OslcProperty> literals = shape.getWritableLiteralProperties();
            sb.append("── Writable literal fields (").append(literals.size()).append(") ──\n");
            for (OslcProperty p : literals) {
                sb.append("  ").append(p.getName());
                if (p.isRequired()) sb.append(" [REQUIRED]");
                if (p.getDefaultValue() != null) sb.append(" (default: ").append(p.getDefaultValue()).append(")");
                sb.append("\n    type: ").append(shortType(p.getValueType())).append("\n");
            }

            List<OslcProperty> links = shape.getWritableResourceLinkProperties();
            sb.append("\n── Writable resource-link fields (").append(links.size())
              .append(") — supply as full TRIRIGA resource URLs ──\n");
            for (OslcProperty p : links) {
                sb.append("  ").append(p.getName());
                if (p.isRequired()) sb.append(" [REQUIRED]");
                sb.append("\n    valueShape: ")
                  .append(p.getValueShapeUrl() != null ? p.getValueShapeUrl() : "(none)").append("\n");
            }

            long roCount = shape.getAllProperties().values().stream()
                    .filter(OslcProperty::isReadOnly).count();
            sb.append("\nTotal: ").append(shape.getAllProperties().size())
              .append(" properties (").append(roCount).append(" read-only, skipped).");
            return sb.toString();
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Force a reload of a cached resource shape from TRIRIGA. "
            + "Use after a custom shape has been modified in the TRIRIGA admin console.")
    public String refreshShape(
            @ToolParam(description = "Shape path, e.g. '/oslc/shapes/triWorkTaskRS'") String shapePath) {
        shapeCache.remove(tririgaUrl() + shapePath);
        try {
            OslcShape shape = loadShape(shapePath);
            return "Reloaded: " + shape.getTitle()
                    + " — " + shape.getWritableLiteralProperties().size() + " writable literals, "
                    + shape.getWritableResourceLinkProperties().size() + " writable resource links.";
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ★ GENERIC CRUD TOOLS
    //
    //  These five tools work with ANY TRIRIGA OSLC resource — built-in or
    //  custom — by deriving all URLs from the resource name alone.
    //  No new Java code is needed when a new resource shape is added to TRIRIGA.
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description =
        "Discover a TRIRIGA OSLC resource by name. "
        + "Fetches the resource shape, derives all CRUD URLs, and returns a full summary "
        + "of the resource including writable fields and the URLs needed to query, create, "
        + "read, update, and delete records. "
        + "Use this first when working with any resource — built-in or custom. "
        + "Example resource names: 'triWorkTask', 'triAsset', 'triLocation', 'cstCustomExample'.")
    public String discoverResource(
            @ToolParam(description = "Resource name without the 'RS' suffix, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName) {
        try {
            OslcShape shape = loadShapeByName(resourceName);

            StringBuilder sb = new StringBuilder();
            sb.append("Resource: ").append(resourceName).append("\n");
            sb.append("Shape title: ").append(shape.getTitle()).append("\n");
            sb.append("Describes type: ").append(shape.getDescribesType()).append("\n\n");

            sb.append("── CRUD URLs ──\n");
            sb.append("  Query (search):  ").append(queryUrl(resourceName)).append("\n");
            sb.append("  Create (POST):   ").append(createUrl(resourceName)).append("\n");
            sb.append("  Read/Update:     ").append(recordUrl(resourceName, "{id}")).append("\n");
            sb.append("  Delete:          ").append(recordUrl(resourceName, "{id}")).append(" (HTTP DELETE)\n\n");

            List<OslcProperty> literals = shape.getWritableLiteralProperties();
            sb.append("── Writable literal fields (").append(literals.size()).append(") ──\n");
            for (OslcProperty p : literals) {
                sb.append("  ").append(p.getName());
                if (p.isRequired()) sb.append(" [REQUIRED]");
                if (p.getDefaultValue() != null) sb.append(" (default: ").append(p.getDefaultValue()).append(")");
                sb.append("\n    type: ").append(shortType(p.getValueType())).append("\n");
            }

            List<OslcProperty> links = shape.getWritableResourceLinkProperties();
            sb.append("\n── Writable resource-link fields (").append(links.size()).append(") ──\n");
            for (OslcProperty p : links) {
                sb.append("  ").append(p.getName());
                if (p.isRequired()) sb.append(" [REQUIRED]");
                sb.append("\n    valueShape: ")
                  .append(p.getValueShapeUrl() != null ? p.getValueShapeUrl() : "(none)").append("\n");
            }

            long roCount = shape.getAllProperties().values().stream()
                    .filter(OslcProperty::isReadOnly).count();
            sb.append("\nTotal properties: ").append(shape.getAllProperties().size())
              .append(" (").append(roCount).append(" read-only).");
            return sb.toString();
        } catch (Exception e) {
            return "Error discovering resource '" + resourceName + "': " + e.getMessage()
                    + "\nMake sure the resource name is correct and an OSLC shape exists at: "
                    + shapeUrl(resourceName);
        }
    }

    @Tool(description =
        "Query (search) records for any TRIRIGA OSLC resource by name. "
        + "Works with built-in and custom resources. "
        + "Call discoverResource(resourceName) first to see available field names for filtering. "
        + "Example resource names: 'triWorkTask', 'triAsset', 'triLocation', 'cstCustomExample'.")
    public String queryResource(
            @ToolParam(description = "Resource name, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName,
            @ToolParam(description = "Optional OSLC where clause to filter results, "
                    + "e.g. 'dcterms:title=\"My Record\"'. Leave blank to return all records.") String where,
            @ToolParam(description = "Optional comma-separated list of field names to return. "
                    + "Leave blank to return all fields.") String select,
            @ToolParam(description = "Optional page size (default 50, max 200).") String pageSize) {
        try {
            int size = 50;
            if (pageSize != null && !pageSize.isBlank()) {
                try { size = Math.min(200, Integer.parseInt(pageSize.trim())); }
                catch (NumberFormatException ignored) {}
            }
            StringBuilder url = new StringBuilder(queryUrl(resourceName) + "?oslc.pageSize=" + size);
            if (where  != null && !where.isBlank())  url.append("&oslc.where=").append(encode(where));
            if (select != null && !select.isBlank()) url.append("&oslc.select=").append(encode(select));
            return get(url.toString());
        } catch (Exception e) { return "Error querying '" + resourceName + "': " + e.getMessage(); }
    }

    @Tool(description =
        "Read a single TRIRIGA OSLC record by resource name and record ID. "
        + "Returns all properties of the record. "
        + "Example: resourceName='triWorkTask', recordId='147665710'.")
    public String readResource(
            @ToolParam(description = "Resource name, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName,
            @ToolParam(description = "System Record ID of the record to read, e.g. '147665710'") String recordId) {
        return get(recordUrl(resourceName, recordId));
    }

    @Tool(description =
        "Create a new record for any TRIRIGA OSLC resource — built-in or custom. "
        + "Call discoverResource(resourceName) first to see available field names and types. "
        + "Fields are supplied as a JSON-style string of key=value pairs separated by '|', e.g.: "
        + "'title=My Record|description=Some text|schedstart=2026-03-01T08:00:00'. "
        + "Resource-link fields (references to other records) are supplied as full URLs using the same format: "
        + "'triAssociatedTaskType=http://host/oslc/so/triTaskTypeRS/123'. "
        + "An optional action (e.g. 'Save') can be included to trigger a state transition on creation. "
        + "Returns the Location URL of the newly created record on success.")
    public String createResource(
            @ToolParam(description = "Resource name, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName,
            @ToolParam(description = "Field values as 'fieldName=value' pairs separated by '|', "
                    + "e.g. 'title=My Record|description=Test|schedstart=2026-03-01T08:00:00'") String fields,
            @ToolParam(description = "Optional action to apply after creation, e.g. 'Save'.") String action,
            @ToolParam(description = "Optional unique transaction ID to prevent duplicate submissions.") String transactionId) {
        try {
            OslcShape shape = loadShapeByName(resourceName);
            Map<String, String> literals  = new LinkedHashMap<>();
            Map<String, String> links     = new LinkedHashMap<>();

            parseFields(fields, shape, literals, links);
            if (action != null && !action.isBlank()) literals.put("action", action);

            String json = OslcJsonBuilder.build(shape, literals, links);
            OslcCreateResult result = post(createUrl(resourceName), json, transactionId);
            return result.toString();
        } catch (Exception e) { return "Error creating '" + resourceName + "': " + e.getMessage(); }
    }

    @Tool(description =
        "Update an existing TRIRIGA OSLC record — built-in or custom. "
        + "Call discoverResource(resourceName) first to see available field names. "
        + "Supply only the fields you want to change; all others are left untouched. "
        + "Fields are supplied as a JSON-style string of key=value pairs separated by '|', e.g.: "
        + "'description=Updated text|wopriority=High'. "
        + "An optional action (e.g. 'Save', 'Complete') triggers a state transition.")
    public String updateResource(
            @ToolParam(description = "Resource name, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName,
            @ToolParam(description = "System Record ID of the record to update, e.g. '147665710'") String recordId,
            @ToolParam(description = "Field values as 'fieldName=value' pairs separated by '|', "
                    + "e.g. 'description=New text|wopriority=High'") String fields,
            @ToolParam(description = "Optional action to apply, e.g. 'Save' or 'Complete'.") String action) {
        try {
            OslcShape shape = loadShapeByName(resourceName);
            Map<String, String> literals  = new LinkedHashMap<>();
            Map<String, String> links     = new LinkedHashMap<>();

            parseFields(fields, shape, literals, links);
            if (action != null && !action.isBlank()) literals.put("action", action);

            String json = OslcJsonBuilder.build(shape, literals, links);
            return put(recordUrl(resourceName, recordId), json);
        } catch (Exception e) { return "Error updating '" + resourceName + "' id=" + recordId + ": " + e.getMessage(); }
    }

    @Tool(description =
        "Delete a TRIRIGA OSLC record by resource name and record ID. "
        + "This operation is permanent and cannot be undone. "
        + "Example: resourceName='triWorkTask', recordId='147665710'.")
    public String deleteResource(
            @ToolParam(description = "Resource name, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName,
            @ToolParam(description = "System Record ID of the record to delete.") String recordId) {
        return delete(recordUrl(resourceName, recordId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Catalog helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Ensures the catalog is built, building it lazily on first call. */
    private void ensureCatalog() {
        if (!catalog.isBuilt()) {
            try {
                catalog.build(tririgaUrl() + "/oslc/sp", this::get);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build shape catalog: " + e.getMessage(), e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ★ FIND SHAPE TOOL
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description =
        "Find the correct TRIRIGA OSLC resource shape for a natural language description. "
        + "Use this tool whenever you are unsure which resourceName to use — for example: "
        + "'person', 'people', 'user', 'work order', 'room', 'building', 'asset', 'reservation', "
        + "'security group', 'organization', 'floor plan', and so on. "
        + "The tool searches all registered TRIRIGA resource shapes by their human-readable titles, "
        + "resource types, and names, and returns matching shapes with the resourceName to use "
        + "in discoverResource(), queryResource(), createResource(), updateResource(), and deleteResource(). "
        + "Pass a blank or '*' keywords to list ALL available shapes. "
        + "The catalog is built by crawling all TRIRIGA service providers on first call and cached thereafter. "
        + "Call refreshCatalog() if you believe new custom shapes have been added to TRIRIGA.")
    public String findShape(
            @ToolParam(description = "Natural language keywords describing the resource, "
                    + "e.g. 'person', 'work order', 'building floor', 'reservation', 'security group'. "
                    + "Use '*' or leave blank to list all shapes.") String keywords) {
        try {
            ensureCatalog();

            boolean listAll = keywords == null || keywords.isBlank() || keywords.equals("*");
            List<OslcShapeEntry> results = listAll
                    ? new ArrayList<>(catalog.all())
                    : catalog.search(keywords);

            if (results.isEmpty()) {
                return "No shapes found matching \"" + keywords + "\".\n"
                     + "Tip: try broader terms, or use findShape('*') to list all "
                     + catalog.size() + " shapes in the catalog.";
            }

            StringBuilder sb = new StringBuilder();
            if (listAll) {
                sb.append("All TRIRIGA OSLC resource shapes (").append(catalog.size()).append(" total):\n\n");
            } else {
                sb.append("Shapes matching \"").append(keywords).append("\" — ")
                  .append(results.size()).append(" result(s):\n\n");
            }

            // Group results by service provider for readability
            Map<String, List<OslcShapeEntry>> byProvider = new LinkedHashMap<>();
            for (OslcShapeEntry e : results) {
                byProvider.computeIfAbsent(e.getServiceProviderTitle(), k -> new ArrayList<>()).add(e);
            }

            for (Map.Entry<String, List<OslcShapeEntry>> group : byProvider.entrySet()) {
                sb.append("── ").append(group.getKey()).append(" ──\n");
                for (OslcShapeEntry e : group.getValue()) {
                    sb.append(e.toSummaryLine()).append("\n\n");
                }
            }

            sb.append("─────────────────────────────────────────────\n");
            sb.append("To work with a shape, call discoverResource(resourceName) for full field details,\n");
            sb.append("then use queryResource / createResource / updateResource / deleteResource.");
            return sb.toString();

        } catch (Exception e) {
            return "Error searching shapes: " + e.getMessage();
        }
    }

    @Tool(description =
        "Refresh the TRIRIGA shape catalog by re-crawling all service providers. "
        + "Use this after a new custom resource shape (e.g. cstCustomExample) has been "
        + "registered in TRIRIGA's OSLC configuration, so findShape() can discover it. "
        + "This also clears all cached resource shapes loaded by describeShape().")
    public String refreshCatalog() {
        catalog.invalidate();
        shapeCache.clear();
        try {
            ensureCatalog();
            return "Catalog refreshed: " + catalog.size() + " shapes indexed across all service providers.";
        } catch (Exception e) {
            return "Error refreshing catalog: " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Discovery
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get all TRIRIGA OSLC Service Providers.")
    public String getOSLCSP() { return get(tririgaUrl() + "/oslc/sp"); }

    @Tool(description = "Fetch any TRIRIGA OSLC URL and return its raw content.")
    public String oslcFetch(
            @ToolParam(description = "The full OSLC URL to fetch.") String url) {
        return get(url);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Typed Work Task tools (convenience wrappers over the generic tools)
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Query TRIRIGA Work Tasks with optional OSLC filtering and field selection.")
    public String queryWorkTasks(
            @ToolParam(description = "OSLC where clause, e.g. 'dcterms:title=\"Fix HVAC\"'. Blank = all.") String where,
            @ToolParam(description = "Comma-separated properties to return. Blank = all.") String select) {
        return queryResource("triWorkTask", where, select, "50");
    }

    @Tool(description = "Query TRIRIGA Work Tasks assigned to the authenticated user.")
    public String queryMyAssignedWorkTasks(
            @ToolParam(description = "Optional OSLC where clause.") String where) {
        return get(tririgaUrl() + "/oslc/spq/triMyAssignedWorkTasksQC?oslc.pageSize=50"
                + (where != null && !where.isBlank() ? "&oslc.where=" + encode(where) : ""));
    }

    @Tool(description = "Query TRIRIGA Work Tasks created by the authenticated user.")
    public String queryMyCreatedWorkTasks(
            @ToolParam(description = "Optional OSLC where clause.") String where) {
        return get(tririgaUrl() + "/oslc/spq/triMyCreatedWorkTasksQC?oslc.pageSize=50"
                + (where != null && !where.isBlank() ? "&oslc.where=" + encode(where) : ""));
    }

    @Tool(description = "Query completed TRIRIGA Work Tasks for the authenticated user.")
    public String queryMyCompletedWorkTasks(
            @ToolParam(description = "Optional OSLC where clause.") String where) {
        return get(tririgaUrl() + "/oslc/spq/triMyCompletedWorkTasksQC?oslc.pageSize=50"
                + (where != null && !where.isBlank() ? "&oslc.where=" + encode(where) : ""));
    }

    @Tool(description = "Search TRIRIGA Work Tasks by keyword.")
    public String searchWorkTasks(
            @ToolParam(description = "Keyword or phrase.") String keyword) {
        return get(tririgaUrl() + "/oslc/spq/triWorkTaskSearchResultsQC?oslc.pageSize=50"
                + "&oslc.searchTerms=" + encode(keyword));
    }

    @Tool(description =
        "Create a new TRIRIGA Work Task. Typed convenience wrapper over createResource. "
        + "For full field control use createResource('triWorkTask', ...) after calling "
        + "discoverResource('triWorkTask').")
    public String createWorkTask(
            @ToolParam(description = "Task title (required).") String title,
            @ToolParam(description = "Task description. Optional.") String description,
            @ToolParam(description = "Planned start in ISO-8601, e.g. 2026-03-01T08:00:00. Required.") String plannedStart,
            @ToolParam(description = "Planned end in ISO-8601. Optional.") String plannedEnd,
            @ToolParam(description = "Priority string, e.g. 'Medium'. Optional.") String priority,
            @ToolParam(description = "Task type string, e.g. 'Corrective'. Optional.") String taskType,
            @ToolParam(description = "Primary work location text. Optional.") String workLocation,
            @ToolParam(description = "Full URL of a triTaskTypeRS record. Optional.") String taskTypeUrl,
            @ToolParam(description = "Full URL of a triPriorityRS record. Optional.") String priorityUrl,
            @ToolParam(description = "Action to apply after creation, e.g. 'Save'. Optional.") String action,
            @ToolParam(description = "Optional unique transaction ID.") String transactionId) {
        try {
            OslcShape shape = loadShape(SHAPE_WORK_TASK);

            Map<String, String> literals = new LinkedHashMap<>();
            literals.put("title",                title);
            literals.put("description",          description);
            literals.put("schedstart",           plannedStart);
            literals.put("schedfinish",          plannedEnd);
            literals.put("wopriority",           priority);
            literals.put("triTaskTypeCL",        taskType);
            literals.put("triWorkingLocationTX", workLocation);
            literals.put("action",               action);

            Map<String, String> links = new LinkedHashMap<>();
            links.put("triAssociatedTaskType", taskTypeUrl);
            links.put("triAssociatedPriority", priorityUrl);

            String json = OslcJsonBuilder.build(shape, literals, links);
            return post(createUrl("triWorkTask"), json, transactionId).toString();
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Update an existing TRIRIGA Work Task by its system record ID.")
    public String updateWorkTask(
            @ToolParam(description = "System Record ID.") String recordId,
            @ToolParam(description = "New title. Optional.") String title,
            @ToolParam(description = "New description. Optional.") String description,
            @ToolParam(description = "New planned start in ISO-8601. Optional.") String plannedStart,
            @ToolParam(description = "New planned end in ISO-8601. Optional.") String plannedEnd,
            @ToolParam(description = "New priority string. Optional.") String priority,
            @ToolParam(description = "New task type string. Optional.") String taskType,
            @ToolParam(description = "New work location text. Optional.") String workLocation,
            @ToolParam(description = "New triTaskTypeRS resource URL. Optional.") String taskTypeUrl,
            @ToolParam(description = "New triPriorityRS resource URL. Optional.") String priorityUrl,
            @ToolParam(description = "Action to apply, e.g. 'Save' or 'Complete'. Optional.") String action) {
        try {
            OslcShape shape = loadShape(SHAPE_WORK_TASK);

            Map<String, String> literals = new LinkedHashMap<>();
            literals.put("title",                title);
            literals.put("description",          description);
            literals.put("schedstart",           plannedStart);
            literals.put("schedfinish",          plannedEnd);
            literals.put("wopriority",           priority);
            literals.put("triTaskTypeCL",        taskType);
            literals.put("triWorkingLocationTX", workLocation);
            literals.put("action",               action);
            literals.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());

            Map<String, String> links = new LinkedHashMap<>();
            links.put("triAssociatedTaskType", taskTypeUrl);
            links.put("triAssociatedPriority", priorityUrl);
            links.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());

            String json = OslcJsonBuilder.build(shape, literals, links);
            return put(recordUrl("triWorkTask", recordId), json);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Asset / Location / Reservation / Reference data
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Query TRIRIGA Assets with optional filtering.")
    public String queryAssets(
            @ToolParam(description = "Optional OSLC where clause.") String where,
            @ToolParam(description = "Optional comma-separated property list.") String select) {
        return queryResource("triAsset", where, select, "50");
    }

    @Tool(description = "Look up TRIRIGA Assets by name or barcode.")
    public String lookupAssets(
            @ToolParam(description = "Search term.") String searchTerm) {
        return get(tririgaUrl() + "/oslc/spq/triAssetsLookupQC?oslc.pageSize=25"
                + "&oslc.searchTerms=" + encode(searchTerm));
    }

    @Tool(description = "Query TRIRIGA Locations (buildings, floors, spaces).")
    public String queryLocations(
            @ToolParam(description = "Optional OSLC where clause.") String where,
            @ToolParam(description = "Optional comma-separated property list.") String select) {
        return queryResource("triLocation", where, select, "50");
    }

    @Tool(description = "Look up TRIRIGA Buildings by name.")
    public String lookupBuildings(
            @ToolParam(description = "Optional name filter.") String searchTerm) {
        return get(tririgaUrl() + "/oslc/spq/triBuildingLookupQC?oslc.pageSize=25"
                + (searchTerm != null && !searchTerm.isBlank() ? "&oslc.searchTerms=" + encode(searchTerm) : ""));
    }

    @Tool(description = "Look up TRIRIGA Floors and Spaces by name.")
    public String lookupFloorsAndSpaces(
            @ToolParam(description = "Optional name filter.") String searchTerm) {
        return get(tririgaUrl() + "/oslc/spq/triFloorandSpaceLookupQC?oslc.pageSize=25"
                + (searchTerm != null && !searchTerm.isBlank() ? "&oslc.searchTerms=" + encode(searchTerm) : ""));
    }

    @Tool(description = "Query TRIRIGA Reservable Spaces available for booking.")
    public String queryReservableSpaces(
            @ToolParam(description = "Optional OSLC where clause.") String where) {
        return get(tririgaUrl() + "/oslc/spq/triReservableSpaceQC?oslc.pageSize=50"
                + (where != null && !where.isBlank() ? "&oslc.where=" + encode(where) : ""));
    }

    @Tool(description = "Query TRIRIGA Exchange Appointments and Reservations.")
    public String queryExchangeAppointments(
            @ToolParam(description = "Optional OSLC where clause.") String where) {
        return get(tririgaUrl() + "/oslc/spq/triExchangeAppointmentQC?oslc.pageSize=50"
                + (where != null && !where.isBlank() ? "&oslc.where=" + encode(where) : ""));
    }

    @Tool(description = "Get all TRIRIGA Work Task Statuses.")
    public String getWorkTaskStatuses() { return get(tririgaUrl() + "/oslc/spq/triStatusesQC"); }

    @Tool(description = "Get all TRIRIGA Priority records. "
            + "Use returned resource URLs as priorityUrl in create/update calls.")
    public String getPriorities() { return get(tririgaUrl() + "/oslc/spq/triPrioritiesQC"); }

    @Tool(description = "Get all TRIRIGA Task Type records. "
            + "Use returned resource URLs as taskTypeUrl in create/update calls.")
    public String getTaskTypes() { return get(tririgaUrl() + "/oslc/spq/triTaskTypesQC"); }

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses a pipe-delimited "fieldName=value|fieldName=value" string and
     * routes each entry into either the literals map or the links map based
     * on whether the shape defines that field as a resource link or a literal.
     *
     * Values that look like URLs (start with "http") and belong to a resource-link
     * field are placed in links; everything else goes to literals.
     */
    private void parseFields(String fieldString, OslcShape shape,
                              Map<String, String> literals, Map<String, String> links) {
        if (fieldString == null || fieldString.isBlank()) return;

        for (String pair : fieldString.split("\\|")) {
            int eq = pair.indexOf('=');
            if (eq < 1) continue;

            String name  = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (name.isEmpty() || value.isEmpty()) continue;

            OslcProperty prop = shape.getProperty(name);

            if (prop != null && prop.isResourceLink()) {
                links.put(name, value);
            } else {
                // Unknown fields also go to literals — OslcJsonBuilder will skip them
                // gracefully if they're not in the shape
                literals.put(name, value);
            }
        }
    }

    private String shortType(String uri) {
        if (uri == null) return "unknown";
        int i = Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/'));
        return i >= 0 ? uri.substring(i + 1) : uri;
    }
}
