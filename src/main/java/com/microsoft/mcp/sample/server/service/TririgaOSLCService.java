package com.microsoft.mcp.sample.server.service;

import com.microsoft.mcp.sample.server.oslc.OslcCreateResult;
import com.microsoft.mcp.sample.server.oslc.OslcJsonBuilder;
import com.microsoft.mcp.sample.server.oslc.OslcJsonBuilder.InlineChild;
import com.microsoft.mcp.sample.server.oslc.OslcJsonBuilder.ExistingRecord;
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

    // ─── Shape paths ─────────────────────────────────────────────────────────
    private static final String SHAPE_WORK_TASK = "/oslc/shapes/triWorkTaskRS";

    // ─── Shape cache (URL → parsed OslcShape) ────────────────────────────────
    private final ConcurrentHashMap<String, OslcShape> shapeCache = new ConcurrentHashMap<>();

    // ─── Service catalog (findShape tool) ────────────────────────────────────
    private final OslcServiceCatalog catalog = new OslcServiceCatalog();

    // ─── Transaction ID counter ──────────────────────────────────────────────
    private final AtomicLong txCounter = new AtomicLong(System.currentTimeMillis());

    // ─────────────────────────────────────────────────────────────────────────
    //  TRIRIGA URL conventions
    //
    //  Shape:        /oslc/shapes/{name}RS
    //  Query:        /oslc/spq/{name}QC
    //  Create:       /oslc/so/{name}CF
    //  Read/Update:  /oslc/so/{name}RS/{id}
    //  Delete:       /oslc/so/{name}RS/{id}  (HTTP DELETE)
    // ─────────────────────────────────────────────────────────────────────────

    private String shapeUrl(String n)              { return tririgaUrl() + "/oslc/shapes/" + n + "RS"; }
    private String queryUrl(String n)              { return tririgaUrl() + "/oslc/spq/"   + n + "QC"; }
    private String createUrl(String n)             { return tririgaUrl() + "/oslc/so/"    + n + "CF"; }
    private String recordUrl(String n, String id)  { return tririgaUrl() + "/oslc/so/"    + n + "RS/" + id; }

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

    /**
     * Update a TRIRIGA record using POST with method-override headers.
     * TRIRIGA uses POST+PATCH instead of HTTP PUT for updates:
     *   x-method-override: PATCH
     *   PATCHTYPE: MERGE
     * Only fields present in the body are changed; omitted fields are untouched.
     */
    private String patch(String url, String jsonBody) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization",     "Basic " + encodedAuth())
                .header("OSLC-Core-Version", "2.0")
                .header("Content-Type",      "application/json;charset=utf-8")
                .header("Accept",            "application/rdf+xml")
                .header("x-method-override", "PATCH")
                .header("PATCHTYPE",         "MERGE")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
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
            return (r.statusCode() == 200 || r.statusCode() == 204)
                    ? "Deleted successfully (HTTP " + r.statusCode() + ")."
                    : "Delete failed (HTTP " + r.statusCode() + "): " + r.body();
        } catch (IOException | InterruptedException e) { return "Error: " + e.getMessage(); }
    }

    private String encode(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    // ─────────────────────────────────────────────────────────────────────────
    //  Shape loading & caching
    //  Shapes are always parsed with the global prefix map from the catalog
    //  so that OslcProperty.prefixedName() returns correct "prefix:localName" keys.
    // ─────────────────────────────────────────────────────────────────────────

    private OslcShape loadShape(String shapePath) {
        String fullUrl = tririgaUrl() + shapePath;
        return shapeCache.computeIfAbsent(fullUrl, url -> {
            try {
                ensureCatalog();
                return OslcShape.parse(url, get(url), catalog.getGlobalPrefixMap());
            } catch (Exception e) {
                throw new RuntimeException("Failed to load shape " + url + ": " + e.getMessage(), e);
            }
        });
    }

    private OslcShape loadShapeByName(String resourceName) {
        return loadShape("/oslc/shapes/" + resourceName + "RS");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Catalog helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void ensureCatalog() {
        if (!catalog.isBuilt()) {
            try { catalog.build(tririgaUrl() + "/oslc/sp", this::get); }
            catch (Exception e) {
                throw new RuntimeException("Failed to build shape catalog: " + e.getMessage(), e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Shape tools
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description =
        "Describe the writable fields for any TRIRIGA resource. " +
        "Shows writable literal fields (plain values) and writable resource-link fields. " +
        "Also shows the correct JSON key (prefix:localName) for each field as TRIRIGA expects it. " +
        "Example paths: '/oslc/shapes/triWorkTaskRS', '/oslc/shapes/cstCustomExampleRS'.")
    public String describeShape(
            @ToolParam(description = "Shape path, e.g. '/oslc/shapes/triWorkTaskRS'") String shapePath) {
        try {
            OslcShape shape = loadShape(shapePath);
            Map<String, String> pm = shape.getPrefixMap();
            StringBuilder sb = new StringBuilder();
            sb.append("Shape: ").append(shape.getTitle()).append("\n");
            sb.append("Describes: ").append(shape.getDescribesType()).append("\n\n");

            List<OslcProperty> literals = shape.getWritableLiteralProperties();
            sb.append("── Writable literal fields (").append(literals.size()).append(") ──\n");
            for (OslcProperty p : literals) {
                sb.append("  ").append(p.getName())
                  .append("  →  JSON key: \"").append(p.prefixedName(pm)).append("\"");
                if (p.isRequired()) sb.append("  [REQUIRED]");
                if (p.getDefaultValue() != null) sb.append("  (default: ").append(p.getDefaultValue()).append(")");
                sb.append("\n    type: ").append(shortType(p.getValueType())).append("\n");
            }

            List<OslcProperty> links = shape.getWritableResourceLinkProperties();
            sb.append("\n── Writable resource-link fields (").append(links.size()).append(") ──\n");
            for (OslcProperty p : links) {
                sb.append("  ").append(p.getName())
                  .append("  →  JSON key: \"").append(p.prefixedName(pm)).append("\"");
                if (p.isRequired()) sb.append("  [REQUIRED]");
                sb.append("\n    valueShape: ")
                  .append(p.getValueShapeUrl() != null ? p.getValueShapeUrl() : "(none)").append("\n");
            }
            long roCount = shape.getAllProperties().values().stream().filter(OslcProperty::isReadOnly).count();
            sb.append("\nTotal: ").append(shape.getAllProperties().size())
              .append(" properties (").append(roCount).append(" read-only, skipped).");
            return sb.toString();
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Force a reload of a cached resource shape from TRIRIGA.")
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
    //  ★ FIND SHAPE TOOL
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description =
        "Find the correct TRIRIGA OSLC resource shape for a natural language description. " +
        "Use this when you are unsure which resourceName to use — e.g. 'person', 'people', " +
        "'work order', 'room', 'building', 'reservation', 'security group', etc. " +
        "Searches all registered shapes by title, resource type, and name. " +
        "Pass '*' or blank to list ALL available shapes. " +
        "Call refreshCatalog() after new custom shapes are added to TRIRIGA.")
    public String findShape(
            @ToolParam(description = "Keywords, e.g. 'person', 'work order', 'floor'. Use '*' to list all.") String keywords) {
        try {
            ensureCatalog();
            boolean listAll = keywords == null || keywords.isBlank() || keywords.equals("*");
            List<OslcShapeEntry> results = listAll
                    ? new ArrayList<>(catalog.all()) : catalog.search(keywords);

            if (results.isEmpty()) {
                return "No shapes found matching \"" + keywords + "\". " +
                       "Try broader terms or findShape('*') to list all " + catalog.size() + " shapes.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(listAll
                    ? "All TRIRIGA OSLC resource shapes (" + catalog.size() + " total):\n\n"
                    : "Shapes matching \"" + keywords + "\" — " + results.size() + " result(s):\n\n");

            Map<String, List<OslcShapeEntry>> byProvider = new LinkedHashMap<>();
            for (OslcShapeEntry e : results)
                byProvider.computeIfAbsent(e.getServiceProviderTitle(), k -> new ArrayList<>()).add(e);

            for (Map.Entry<String, List<OslcShapeEntry>> group : byProvider.entrySet()) {
                sb.append("── ").append(group.getKey()).append(" ──\n");
                for (OslcShapeEntry e : group.getValue())
                    sb.append(e.toSummaryLine()).append("\n\n");
            }
            sb.append("─────────────────────────────────────────────\n");
            sb.append("Next: call discoverResource(resourceName) for full field details.");
            return sb.toString();
        } catch (Exception e) { return "Error searching shapes: " + e.getMessage(); }
    }

    @Tool(description =
        "Refresh the TRIRIGA shape catalog by re-crawling all service providers. " +
        "Also clears all cached resource shapes. Use after new custom shapes are added to TRIRIGA.")
    public String refreshCatalog() {
        catalog.invalidate();
        shapeCache.clear();
        try {
            ensureCatalog();
            return "Catalog refreshed: " + catalog.size() + " shapes indexed.";
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ★ GENERIC CRUD TOOLS
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description =
        "Discover a TRIRIGA OSLC resource by name. Returns shape title, all CRUD URLs, " +
        "and all writable fields with their correct JSON keys. " +
        "Use this first when working with any resource — built-in or custom. " +
        "Example resource names: 'triWorkTask', 'triAsset', 'triLocation', 'cstCustomExample'.")
    public String discoverResource(
            @ToolParam(description = "Resource name without 'RS' suffix, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName) {
        try {
            OslcShape shape = loadShapeByName(resourceName);
            Map<String, String> pm = shape.getPrefixMap();
            StringBuilder sb = new StringBuilder();
            sb.append("Resource: ").append(resourceName).append("\n");
            sb.append("Shape title: ").append(shape.getTitle()).append("\n");
            sb.append("Describes type: ").append(shape.getDescribesType()).append("\n\n");
            sb.append("── CRUD URLs ──\n");
            sb.append("  Query:   ").append(queryUrl(resourceName)).append("\n");
            sb.append("  Create:  ").append(createUrl(resourceName)).append("\n");
            sb.append("  Read:    ").append(recordUrl(resourceName, "{id}")).append("\n");
            sb.append("  Update:  ").append(recordUrl(resourceName, "{id}")).append(" (POST + x-method-override: PATCH)\n");
            sb.append("  Delete:  ").append(recordUrl(resourceName, "{id}")).append(" (DELETE)\n\n");

            List<OslcProperty> literals = shape.getWritableLiteralProperties();
            sb.append("── Writable literal fields (").append(literals.size()).append(") ──\n");
            for (OslcProperty p : literals) {
                sb.append("  ").append(p.getName())
                  .append("  →  JSON key: \"").append(p.prefixedName(pm)).append("\"");
                if (p.isRequired()) sb.append("  [REQUIRED]");
                if (p.getDefaultValue() != null) sb.append("  (default: ").append(p.getDefaultValue()).append(")");
                sb.append("\n    type: ").append(shortType(p.getValueType())).append("\n");
            }

            List<OslcProperty> links = shape.getWritableResourceLinkProperties();
            sb.append("\n── Writable resource-link fields (").append(links.size()).append(") ──\n");
            for (OslcProperty p : links) {
                sb.append("  ").append(p.getName())
                  .append("  →  JSON key: \"").append(p.prefixedName(pm)).append("\"");
                if (p.isRequired()) sb.append("  [REQUIRED]");
                sb.append("\n    valueShape: ")
                  .append(p.getValueShapeUrl() != null ? p.getValueShapeUrl() : "(none)").append("\n");
            }
            long roCount = shape.getAllProperties().values().stream().filter(OslcProperty::isReadOnly).count();
            sb.append("\nTotal: ").append(shape.getAllProperties().size())
              .append(" properties (").append(roCount).append(" read-only).");
            return sb.toString();
        } catch (Exception e) {
            return "Error discovering '" + resourceName + "': " + e.getMessage() +
                   "\nShape URL tried: " + shapeUrl(resourceName);
        }
    }

    @Tool(description =
        "Query (search) records for any TRIRIGA OSLC resource by name. " +
        "Call discoverResource(resourceName) first to see available field names. " +
        "Example resource names: 'triWorkTask', 'triAsset', 'triLocation', 'cstCustomExample'.")
    public String queryResource(
            @ToolParam(description = "Resource name, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName,
            @ToolParam(description = "Optional OSLC where clause, e.g. 'dcterms:title=\"My Record\"'") String where,
            @ToolParam(description = "Optional comma-separated field list to return") String select,
            @ToolParam(description = "Page size (default 50, max 200)") String pageSize) {
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

    @Tool(description = "Read a single TRIRIGA OSLC record by resource name and record ID.")
    public String readResource(
            @ToolParam(description = "Resource name, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName,
            @ToolParam(description = "System Record ID, e.g. '147665710'") String recordId) {
        return get(recordUrl(resourceName, recordId));
    }

    @Tool(description =
        "Create a new record for any TRIRIGA OSLC resource — built-in or custom. " +
        "Fields are supplied as pipe-delimited 'fieldName=value' pairs using the oslc:name of each field. " +
        "Example: 'title=My Record|description=Test|schedstart=2026-03-01T08:00:00'. " +
        "The builder resolves each field to the correct prefixed JSON key (e.g. 'dcterms:title') automatically. " +
        "Use inlineChildren to create and associate child records in a single call " +
        "(format: 'childFieldName:childField1=val1;childField2=val2|...'). " +
        "Use linkedRecordIds to associate existing records by ID " +
        "(format: 'fieldName:recordId1,recordId2'). " +
        "Returns the Location URL of the newly created record on success.")
    public String createResource(
            @ToolParam(description = "Resource name, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName,
            @ToolParam(description = "Fields as 'name=value' pairs separated by '|'") String fields,
            @ToolParam(description = "Action to apply after creation, e.g. 'Create Draft'. Optional.") String action,
            @ToolParam(description =
                "Inline child records to create and associate in one call. " +
                "Format: 'childFieldName:field1=val1;field2=val2;action=Create|childFieldName:...' " +
                "Example: 'triAssociatedComments:triCommentTX=Hello;triCommentTypeLI=E-mail;action=Create'. Optional.") String inlineChildren,
            @ToolParam(description =
                "Existing record IDs to associate. " +
                "Format: 'fieldName:id1,id2'. Example: 'triAssociatedComments:132633922'. Optional.") String linkedRecordIds,
            @ToolParam(description = "Optional unique transaction ID to prevent duplicate submissions.") String transactionId) {
        try {
            OslcShape shape = loadShapeByName(resourceName);
            Map<String, String> literalFields = new LinkedHashMap<>();
            Map<String, List<InlineChild>> children = new LinkedHashMap<>();
            Map<String, List<ExistingRecord>> links = new LinkedHashMap<>();

            parseFields(fields, shape, literalFields, new LinkedHashMap<>());

            // Parse inline children
            if (inlineChildren != null && !inlineChildren.isBlank()) {
                for (String childSpec : inlineChildren.split("\\|")) {
                    int colon = childSpec.indexOf(':');
                    if (colon < 1) continue;
                    String childFieldName = childSpec.substring(0, colon).trim();
                    String childFieldStr  = childSpec.substring(colon + 1).trim();

                    // Determine child shape from the parent property's valueShape
                    OslcProperty parentProp = shape.getProperty(childFieldName);
                    String childShapeUrl = parentProp != null ? parentProp.getValueShapeUrl() : null;
                    OslcShape childShape = childShapeUrl != null
                            ? shapeCache.computeIfAbsent(childShapeUrl, u ->
                                  { try { return OslcShape.parse(u, get(u), catalog.getGlobalPrefixMap()); }
                                    catch (Exception ex) { throw new RuntimeException(ex); }})
                            : shape; // fallback to parent shape if no valueShape

                    Map<String, String> childLiterals = new LinkedHashMap<>();
                    String childAction = null;
                    for (String pair : childFieldStr.split(";")) {
                        int eq = pair.indexOf('=');
                        if (eq < 1) continue;
                        String k = pair.substring(0, eq).trim();
                        String v = pair.substring(eq + 1).trim();
                        if ("action".equals(k)) childAction = v;
                        else childLiterals.put(k, v);
                    }
                    children.computeIfAbsent(childFieldName, k -> new ArrayList<>())
                            .add(new InlineChild(childShape, childLiterals, childAction));
                }
            }

            // Parse existing record links
            if (linkedRecordIds != null && !linkedRecordIds.isBlank()) {
                for (String linkSpec : linkedRecordIds.split("\\|")) {
                    int colon = linkSpec.indexOf(':');
                    if (colon < 1) continue;
                    String fieldName = linkSpec.substring(0, colon).trim();
                    String idList    = linkSpec.substring(colon + 1).trim();
                    List<ExistingRecord> recs = links.computeIfAbsent(fieldName, k -> new ArrayList<>());
                    for (String id : idList.split(",")) {
                        String trimId = id.trim();
                        if (!trimId.isEmpty()) recs.add(new ExistingRecord(trimId));
                    }
                }
            }

            String json = OslcJsonBuilder.build(shape, literalFields, action,
                    children.isEmpty() ? null : children,
                    links.isEmpty()    ? null : links);
            OslcCreateResult result = post(createUrl(resourceName), json, transactionId);
            return result.toString();
        } catch (Exception e) { return "Error creating '" + resourceName + "': " + e.getMessage(); }
    }

    @Tool(description =
        "Update an existing TRIRIGA OSLC record — built-in or custom. " +
        "Supply only the fields you want to change. " +
        "Supports inline child creation and existing record linking with same syntax as createResource.")
    public String updateResource(
            @ToolParam(description = "Resource name, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName,
            @ToolParam(description = "System Record ID, e.g. '147665710'") String recordId,
            @ToolParam(description = "Fields as 'name=value' pairs separated by '|'") String fields,
            @ToolParam(description = "Optional action to apply, e.g. 'Save' or 'Complete'.") String action) {
        try {
            OslcShape shape = loadShapeByName(resourceName);
            Map<String, String> literalFields = new LinkedHashMap<>();
            parseFields(fields, shape, literalFields, new LinkedHashMap<>());
            literalFields.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());
            String json = OslcJsonBuilder.build(shape, literalFields, action);
            return patch(recordUrl(resourceName, recordId), json);
        } catch (Exception e) { return "Error updating '" + resourceName + "' id=" + recordId + ": " + e.getMessage(); }
    }

    @Tool(description = "Delete a TRIRIGA OSLC record by resource name and record ID. Permanent.")
    public String deleteResource(
            @ToolParam(description = "Resource name, e.g. 'triWorkTask' or 'cstCustomExample'") String resourceName,
            @ToolParam(description = "System Record ID to delete.") String recordId) {
        return delete(recordUrl(resourceName, recordId));
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
    //  Typed Work Task tools (convenience wrappers)
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Query TRIRIGA Work Tasks with optional OSLC filtering.")
    public String queryWorkTasks(
            @ToolParam(description = "OSLC where clause. Blank = all.") String where,
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
        "Create a new TRIRIGA Work Task with full support for inline comments. " +
        "For generic resource creation use createResource('triWorkTask', ...).")
    public String createWorkTask(
            @ToolParam(description = "Task title (required).") String title,
            @ToolParam(description = "Task description. Optional.") String description,
            @ToolParam(description = "Planned start in ISO-8601. Required.") String plannedStart,
            @ToolParam(description = "Planned end in ISO-8601. Optional.") String plannedEnd,
            @ToolParam(description = "Task type, e.g. 'Corrective'. Optional.") String taskType,
            @ToolParam(description = "Action, e.g. 'Create Draft'. Optional.") String action,
            @ToolParam(description = "Transaction ID. Optional.") String transactionId) {
        try {
            OslcShape shape = loadShape(SHAPE_WORK_TASK);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("title",       title);
            fields.put("description", description);
            fields.put("schedstart",  plannedStart);
            fields.put("schedfinish", plannedEnd);
            fields.put("triTaskTypeCL", taskType);
            fields.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());
            String json = OslcJsonBuilder.build(shape, fields, action);
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
            @ToolParam(description = "New task type. Optional.") String taskType,
            @ToolParam(description = "Action, e.g. 'Save' or 'Complete'. Optional.") String action) {
        try {
            OslcShape shape = loadShape(SHAPE_WORK_TASK);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("title",       title);
            fields.put("description", description);
            fields.put("schedstart",  plannedStart);
            fields.put("schedfinish", plannedEnd);
            fields.put("triTaskTypeCL", taskType);
            fields.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());
            String json = OslcJsonBuilder.build(shape, fields, action);
            return patch(recordUrl("triWorkTask", recordId), json);
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

    @Tool(description = "Query TRIRIGA Reservable Spaces.")
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

    @Tool(description = "Get all TRIRIGA Priority records.")
    public String getPriorities() { return get(tririgaUrl() + "/oslc/spq/triPrioritiesQC"); }

    @Tool(description = "Get all TRIRIGA Task Type records.")
    public String getTaskTypes() { return get(tririgaUrl() + "/oslc/spq/triTaskTypesQC"); }

    // ─────────────────────────────────────────────────────────────────────────
    //  Utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses a pipe-delimited "fieldName=value" string and routes each entry
     * into the literals or links map based on the shape's property type.
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
            if (prop != null && prop.isResourceLink()) links.put(name, value);
            else literals.put(name, value);
        }
    }

    private String shortType(String uri) {
        if (uri == null) return "unknown";
        int i = Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/'));
        return i >= 0 ? uri.substring(i + 1) : uri;
    }
}