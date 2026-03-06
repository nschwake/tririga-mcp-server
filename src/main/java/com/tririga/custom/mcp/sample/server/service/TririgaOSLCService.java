package com.tririga.custom.mcp.sample.server.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import com.tririga.custom.mcp.sample.server.oslc.OslcCreateResult;
import com.tririga.custom.mcp.sample.server.oslc.OslcJsonBuilder;
import com.tririga.custom.mcp.sample.server.oslc.OslcProperty;
import com.tririga.custom.mcp.sample.server.oslc.OslcResponseParser;
import com.tririga.custom.mcp.sample.server.oslc.OslcServiceCatalog;
import com.tririga.custom.mcp.sample.server.oslc.OslcShape;
import com.tririga.custom.mcp.sample.server.oslc.OslcShapeEntry;
import com.tririga.custom.mcp.sample.server.oslc.OslcJsonBuilder.ExistingRecord;
import com.tririga.custom.mcp.sample.server.oslc.OslcJsonBuilder.InlineChild;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;

@Service
public class TririgaOSLCService {

    private static final Logger logger = LoggerFactory.getLogger(TririgaOSLCService.class);

    // ─── Configuration ───────────────────────────────────────────────────────
    @Value("${MREF_URL:#{null}}")
    private String tririgaUrl;

    @Value("${MREF_USER:#{null}}")
    private String tririgaUser;

    @Value("${MREF_PASS:#{null}}")
    private String tririgaPass;

    private String encodedAuth;

    // ─── HTTP Client (shared, configured) ────────────────────────────────────
    private final HttpClient httpClient;

    // ─── Shape paths ─────────────────────────────────────────────────────────
    private static final String SHAPE_WORK_TASK = "/oslc/shapes/triWorkTaskRS";

    // ─── Shape cache (URL → parsed OslcShape) ────────────────────────────────
    private final ConcurrentHashMap<String, OslcShape> shapeCache = new ConcurrentHashMap<>();

    // ─── Service catalog (findShape tool) ────────────────────────────────────
    private final OslcServiceCatalog catalog = new OslcServiceCatalog();

    // ─── Transaction ID counter ──────────────────────────────────────────────
    private final AtomicLong txCounter = new AtomicLong(System.currentTimeMillis());

    // ─── Constructor ─────────────────────────────────────────────────────────
    public TririgaOSLCService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PostConstruct
    private void init() {
        // Fallback to environment variables if Spring properties not set
        if (tririgaUrl == null) {
            tririgaUrl = System.getenv("MREF_URL");
        }
        if (tririgaUser == null) {
            tririgaUser = System.getenv("MREF_USER");
        }
        if (tririgaPass == null) {
            tririgaPass = System.getenv("MREF_PASS");
        }

        // Validate configuration
        if (tririgaUrl == null || tririgaUrl.isBlank()) {
            throw new IllegalStateException("MREF_URL is not configured");
        }
        if (tririgaUser == null || tririgaUser.isBlank()) {
            throw new IllegalStateException("MREF_USER is not configured");
        }
        if (tririgaPass == null || tririgaPass.isBlank()) {
            throw new IllegalStateException("MREF_PASS is not configured");
        }

        // Pre-encode authentication (only once)
        this.encodedAuth = Base64.getEncoder()
                .encodeToString((tririgaUser + ":" + tririgaPass).getBytes(StandardCharsets.UTF_8));

        logger.info("TririgaOSLCService initialized with URL: {}", tririgaUrl);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TRIRIGA URL conventions
    //
    //  Shape:        /oslc/shapes/{name}RS
    //  Query:        /oslc/spq/{name}QC
    //  Create:       /oslc/so/{name}CF
    //  Read/Update:  /oslc/so/{name}RS/{id}
    //  Delete:       /oslc/so/{name}RS/{id}  (HTTP DELETE)
    // ─────────────────────────────────────────────────────────────────────────

    private String shapeUrl(String n)              { return tririgaUrl + "/oslc/shapes/" + n + "RS"; }
    private String recordUrl(String n, String id)  { return tririgaUrl + "/oslc/so/"    + n + "RS/" + id; }
    
    /**
     * Get the creation factory URL for a resource by looking it up in the catalog.
     * Like query capabilities, creation factory URLs should come from the catalog
     * rather than being assumed to follow a pattern.
     */
    private String createUrlForResource(String resourceName) {
        ensureCatalog();
        
        // Find the first creatable entry for this resource
        for (OslcShapeEntry entry : catalog.all()) {
            if (resourceName.equals(entry.getResourceName())) {
                String createUrl = entry.getCreationUrl();
                if (createUrl != null && !createUrl.isBlank()) {
                    return createUrl;
                }
                // If no creation URL found, fall back to standard pattern
                logger.warn("Resource '{}' has no creation factory in catalog, using standard pattern", resourceName);
                return tririgaUrl + "/oslc/so/" + resourceName + "CF";
            }
        }
        
        // Not in catalog - fall back to standard pattern
        logger.warn("Resource '{}' not found in catalog, using standard creation pattern", resourceName);
        return tririgaUrl + "/oslc/so/" + resourceName + "CF";
    }
    
    /**
     * @deprecated Use createUrlForResource() instead to get URL from catalog
     */
    @Deprecated
    private String createUrl(String n) { 
        return tririgaUrl + "/oslc/so/" + n + "CF"; 
    }
    
    /**
     * Get all query URLs for a resource by looking them up in the catalog.
     * A resource can have multiple query capabilities (e.g., triLocation has
     * triLocationsQC, triBuildingLookupQC, triFloorandSpaceLookupQC).
     * Returns a formatted list of all available query capabilities.
     */
    private String getAllQueryUrlsForResource(String resourceName) {
        ensureCatalog();
        
        List<OslcShapeEntry> matches = new ArrayList<>();
        for (OslcShapeEntry entry : catalog.all()) {
            if (resourceName.equals(entry.getResourceName()) && entry.isQueryable()) {
                matches.add(entry);
            }
        }
        
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                "Resource '" + resourceName + "' has no query capabilities defined in TRIRIGA");
        }
        
        // If only one query capability, return it directly
        if (matches.size() == 1) {
            return matches.get(0).getQueryUrl();
        }
        
        // Multiple query capabilities - format for display
        StringBuilder sb = new StringBuilder();
        sb.append("Resource '").append(resourceName).append("' has ")
          .append(matches.size()).append(" query capabilities:\n");
        for (int i = 0; i < matches.size(); i++) {
            OslcShapeEntry entry = matches.get(i);
            sb.append("  ").append(i + 1).append(". ")
              .append(entry.getCapabilityTitle()).append("\n")
              .append("     URL: ").append(entry.getQueryUrl()).append("\n");
        }
        sb.append("\nUsing the first one: ").append(matches.get(0).getQueryUrl());
        return matches.get(0).getQueryUrl();
    }
    
    /**
     * Get the query URL for a resource by looking it up in the catalog.
     * If multiple query capabilities exist, returns the first one found.
     * Use getAllQueryUrlsForResource() to see all available options.
     */
    private String queryUrlForResource(String resourceName) {
        ensureCatalog();
        
        // Find the first queryable entry for this resource
        for (OslcShapeEntry entry : catalog.all()) {
            if (resourceName.equals(entry.getResourceName())) {
                String queryUrl = entry.getQueryUrl();
                if (queryUrl != null && !queryUrl.isBlank()) {
                    return queryUrl;
                }
                // If no query URL found, throw exception
                throw new IllegalArgumentException(
                    "Resource '" + resourceName + "' has no query capability defined in TRIRIGA");
            }
        }
        
        throw new IllegalArgumentException(
            "Resource '" + resourceName + "' not found in catalog. " +
            "Call findShape() to discover available resources.");
    }
    
    /**
     * Find a query URL by searching for a capability title or usage.
     * This handles specialized query capabilities that don't follow standard naming.
     * Returns null if not found.
     */
    private String findQueryUrlByName(String queryName) {
        ensureCatalog();
        
        // First, check if the full query URL path matches
        for (OslcShapeEntry entry : catalog.all()) {
            String queryUrl = entry.getQueryUrl();
            if (queryUrl != null && queryUrl.contains(queryName)) {
                return queryUrl;
            }
        }
        
        // If not found, construct the traditional URL pattern as fallback
        // (this maintains backward compatibility with existing code)
        return tririgaUrl + "/oslc/spq/" + queryName;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HTTP helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String get(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization",     "Basic " + encodedAuth)
                .header("OSLC-Core-Version", "2.0")
                .header("Accept",            "application/rdf+xml")
                .GET().build();
        try {
            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 400) {
                logger.warn("HTTP {} error for GET {}: {}", 
                    response.statusCode(), url, response.body());
                return "Error: HTTP " + response.statusCode() + " - " + response.body();
            }
            
            // Parse the XML response to remove redundant structure
            String rawXml = response.body();
            return OslcResponseParser.parse(rawXml);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Request interrupted for URL: {}", url, e);
            return "Error: Request interrupted - " + e.getMessage();
        } catch (IOException e) {
            logger.error("I/O error for GET {}: {}", url, e.getMessage());
            return "Error: Network error - " + e.getMessage();
        }
    }

    /**
     * Get raw XML response without parsing.
     * Used internally for shape discovery and other cases where we need to parse XML ourselves.
     */
    private String getRaw(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization",     "Basic " + encodedAuth)
                .header("OSLC-Core-Version", "2.0")
                .header("Accept",            "application/rdf+xml")
                .GET().build();
        try {
            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 400) {
                logger.warn("HTTP {} error for GET {}: {}", 
                    response.statusCode(), url, response.body());
                return "Error: HTTP " + response.statusCode() + " - " + response.body();
            }
            
            return response.body();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Request interrupted for URL: {}", url, e);
            return "Error: Request interrupted - " + e.getMessage();
        } catch (IOException e) {
            logger.error("I/O error for GET {}: {}", url, e.getMessage());
            return "Error: Network error - " + e.getMessage();
        }
    }

    private OslcCreateResult post(String url, String jsonBody, String transactionId) {
        String txId = (transactionId != null && !transactionId.isBlank())
                ? transactionId : String.valueOf(txCounter.incrementAndGet());
        
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization",     "Basic " + encodedAuth)
                .header("OSLC-Core-Version", "2.0")
                .header("Content-Type",      "application/json;charset=utf-8")
                .header("Accept",            "application/rdf+xml")
                .header("transactionid",     txId)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
        
        try {
            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 400) {
                logger.warn("HTTP {} error for POST {}: {}", 
                    response.statusCode(), url, response.body());
            }
            
            return new OslcCreateResult(
                response.statusCode(),
                response.headers().firstValue("Location").orElse(null),
                response.headers().firstValue("ETag").orElse(null),
                response.body()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Request interrupted for URL: {}", url, e);
            return new OslcCreateResult(500, null, null, "Error: Request interrupted - " + e.getMessage());
        } catch (IOException e) {
            logger.error("I/O error for POST {}: {}", url, e.getMessage());
            return new OslcCreateResult(500, null, null, "Error: Network error - " + e.getMessage());
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
                .timeout(Duration.ofSeconds(30))
                .header("Authorization",     "Basic " + encodedAuth)
                .header("OSLC-Core-Version", "2.0")
                .header("Content-Type",      "application/json;charset=utf-8")
                .header("Accept",            "application/rdf+xml")
                .header("x-method-override", "PATCH")
                .header("PATCHTYPE",         "MERGE")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
        
        try {
            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 400) {
                logger.warn("HTTP {} error for PATCH {}: {}", 
                    response.statusCode(), url, response.body());
                return "Error: HTTP " + response.statusCode() + " - " + response.body();
            }
            
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Request interrupted for URL: {}", url, e);
            return "Error: Request interrupted - " + e.getMessage();
        } catch (IOException e) {
            logger.error("I/O error for PATCH {}: {}", url, e.getMessage());
            return "Error: Network error - " + e.getMessage();
        }
    }

    private String delete(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization",     "Basic " + encodedAuth)
                .header("OSLC-Core-Version", "2.0")
                .DELETE().build();
        
        try {
            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200 || response.statusCode() == 204) {
                logger.info("Successfully deleted resource at {}", url);
                return "Deleted successfully (HTTP " + response.statusCode() + ").";
            } else {
                logger.warn("Delete failed with HTTP {} for {}: {}", 
                    response.statusCode(), url, response.body());
                return "Delete failed (HTTP " + response.statusCode() + "): " + response.body();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Request interrupted for URL: {}", url, e);
            return "Error: Request interrupted - " + e.getMessage();
        } catch (IOException e) {
            logger.error("I/O error for DELETE {}: {}", url, e.getMessage());
            return "Error: Network error - " + e.getMessage();
        }
    }

    private String encode(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    // ─────────────────────────────────────────────────────────────────────────
    //  Shape loading & caching
    //  Shapes are always parsed with the global prefix map from the catalog
    //  so that OslcProperty.prefixedName() returns correct "prefix:localName" keys.
    // ─────────────────────────────────────────────────────────────────────────

    private OslcShape loadShape(String shapePath) {
        String fullUrl = tririgaUrl + shapePath;
        return shapeCache.computeIfAbsent(fullUrl, url -> {
            try {
                ensureCatalog();
                // Use getRaw() because we need to parse the XML ourselves
                return OslcShape.parse(url, getRaw(url), catalog.getGlobalPrefixMap());
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
            try { 
                // Use getRaw() because catalog needs to parse the XML itself
                catalog.build(tririgaUrl + "/oslc/sp", this::getRaw); 
            }
            catch (Exception e) {
                throw new RuntimeException("Failed to build shape catalog: " + e.getMessage(), e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Shape tools
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description =
        "ADVANCED USE. Returns the full list of writable fields for a TRIRIGA resource shape, " +
        "including the correct JSON key (prefix:localName), data type, and whether each field " +
        "is required. Prefer discoverResource() over this tool — discoverResource() is simpler " +
        "and also returns CRUD URLs. Use describeShape() only when you have a raw shape path " +
        "and need detailed field metadata without the full discovery output. " +
        "Shape paths follow the pattern /oslc/shapes/{resourceName}RS. " +
        "Examples: '/oslc/shapes/triWorkTaskRS', '/oslc/shapes/cstCustomExampleRS'.")
    public String describeShape(
            @ToolParam(description = "Full shape path. Pattern: /oslc/shapes/{resourceName}RS. " +
                "Examples: '/oslc/shapes/triWorkTaskRS', '/oslc/shapes/triLocationRS'.") String shapePath) {
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

    @Tool(description =
        "MAINTENANCE TOOL. Forces a reload of a single cached resource shape from TRIRIGA. " +
        "Use this if a TRIRIGA administrator has modified the fields or properties of a specific " +
        "resource shape and you want to pick up those changes without restarting the server. " +
        "To reload all shapes and re-crawl all service providers at once, use refreshCatalog() instead.")
    public String refreshShape(
            @ToolParam(description = "Full shape path to reload. Pattern: /oslc/shapes/{resourceName}RS. " +
                "Example: '/oslc/shapes/triWorkTaskRS'.") String shapePath) {
        shapeCache.remove(tririgaUrl + shapePath);
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
        "STEP 1 OF EVERY WORKFLOW — ALWAYS CALL THIS FIRST. " +
        "Use this tool whenever a user asks about any type of TRIRIGA record and you do not " +
        "already know the exact resourceName. " +
        "Accepts plain English — users do not need to know TRIRIGA internals. " +
        "Examples that should trigger this tool: " +
        "'find a location', 'look up a person', 'show me work orders', 'search for a building', " +
        "'what rooms can I book?', 'find an asset', 'show me a floor plan', " +
        "'who is in the admin security group?', 'give me information about this person'. " +
        "AMBIGUITY RULE — THIS IS MANDATORY: if the search returns more than one shape, " +
        "you MUST stop and present ALL matching options to the user in a clear numbered list, " +
        "showing the resource title, provider, and what each one is used for. " +
        "Ask the user to choose before calling any other tool. " +
        "Do NOT pick one automatically and proceed — the wrong shape will return wrong data. " +
        "SINGLE MATCH: if exactly one shape matches, proceed directly to discoverResource(). " +
        "Pass '*' or blank to list every available shape — useful when the user asks " +
        "'what can you do?' or 'what records are in TRIRIGA?'. " +
        "NEXT STEP after user selects a shape: call discoverResource(resourceName).")
    public String findShape(
            @ToolParam(description = "Natural language description of what the user is looking for. " +
                "Use the user's own words — do not translate to TRIRIGA terms before calling this. " +
                "Examples: 'location', 'person', 'people', 'work order', 'work task', 'building', " +
                "'floor', 'room', 'space', 'asset', 'equipment', 'reservation', 'booking', " +
                "'security group', 'organization', 'floor plan', 'BIM'. " +
                "Pass '*' to list every available shape.") String keywords) {
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
        "MAINTENANCE TOOL. Re-crawls all TRIRIGA OSLC service providers and rebuilds the complete " +
        "shape catalog. Also clears all individually cached resource shapes. " +
        "Use this after a TRIRIGA administrator has added or modified custom resource shapes " +
        "(e.g. a new cst-prefixed shape) so that findShape() can discover them. " +
        "This performs multiple HTTP calls to TRIRIGA and may take a few seconds. " +
        "For reloading a single known shape use refreshShape() instead.")
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
        "STEP 2 OF EVERY WORKFLOW. Call this immediately after findShape() returns a resourceName. " +
        "Returns the complete field catalogue for a resource type — developers can use this " +
        "for technical detail; the LLM uses it to understand what filter fields are available " +
        "and what field names to use when creating or updating records. " +
        "Shows every writable field with its plain name (oslc:name), correct JSON key, " +
        "data type, and whether it is required. Also returns all CRUD endpoint URLs. " +
        "Works with built-in and custom resource types. " +
        "NEXT STEP — choose based on user intent: " +
        "  • User wants to FIND or VIEW records  → call queryResource() " +
        "  • User wants to VIEW one specific record  → call readResource() " +
        "  • User wants to CREATE a new record  → call createResource() " +
        "  • User wants to CHANGE or UPDATE a record  → call queryResource() to find it first")
    public String discoverResource(
            @ToolParam(description = "Resource name without the RS suffix. " +
                "Obtain this from findShape(). " +
                "Examples: 'triWorkTask', 'triLocation', 'triPeople', 'cstCustomExample'.") String resourceName) {
        try {
            OslcShape shape = loadShapeByName(resourceName);
            Map<String, String> pm = shape.getPrefixMap();
            StringBuilder sb = new StringBuilder();
            sb.append("Resource: ").append(resourceName).append("\n");
            sb.append("Shape title: ").append(shape.getTitle()).append("\n");
            sb.append("Describes type: ").append(shape.getDescribesType()).append("\n\n");
            
            sb.append("── CRUD URLs ──\n");
            
            // Show all query capabilities for this resource
            ensureCatalog();
            List<OslcShapeEntry> queryCapabilities = new ArrayList<>();
            for (OslcShapeEntry entry : catalog.all()) {
                if (resourceName.equals(entry.getResourceName()) && entry.isQueryable()) {
                    queryCapabilities.add(entry);
                }
            }
            
            if (queryCapabilities.isEmpty()) {
                sb.append("  Query:   [NOT AVAILABLE]\n");
            } else if (queryCapabilities.size() == 1) {
                sb.append("  Query:   ").append(queryCapabilities.get(0).getQueryUrl()).append("\n");
            } else {
                sb.append("  Query Capabilities (").append(queryCapabilities.size()).append("):\n");
                for (OslcShapeEntry entry : queryCapabilities) {
                    sb.append("    - ").append(entry.getCapabilityTitle()).append("\n");
                    sb.append("      URL: ").append(entry.getQueryUrl()).append("\n");
                }
            }
            
            sb.append("  Create:  ").append(createUrlForResource(resourceName)).append("\n");
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
        "STEP 3A — SEARCH. Find records matching criteria supplied by the user. " +
        "Triggered by requests like: 'find me a location called...', 'search for work tasks', " +
        "'show me all active assets', 'look up this person', 'list all buildings'. " +
        "Translate the user's plain-English request into an OSLC filter using the prefixed " +
        "field names returned by discoverResource(). " +
        "Filter syntax: prefixedFieldName=\"value\" — " +
        "e.g. user says 'find Building A' → where='dcterms:title=\"Building A\"'; " +
        "user says 'show active work tasks' → where='spi_wm:status=\"Active\"'. " +
        "If the user gives a name with no field specified, try dcterms:title first. " +
        "Leave where blank and use pageSize=10 when exploring a resource for the first time. " +
        "Summarise results in plain language for the user, then offer to show full details. " +
        "NEXT STEP: call readResource() if the user wants full detail on a specific result. " +
        "NOTE: If a resource has multiple query capabilities (shown in discoverResource), " +
        "this uses the first one found. Use queryByUrl() to use a specific query capability.")
    public String queryResource(
            @ToolParam(description = "Resource name from findShape() / discoverResource(). " +
                "Examples: 'triWorkTask', 'triLocation', 'triPeople', 'cstCustomExample'.") String resourceName,
            @ToolParam(description = "Optional filter translated from the user's request. " +
                "Build this from field names shown by discoverResource(). " +
                "Syntax: prefixedName=\"value\" — " +
                "Examples: 'dcterms:title=\"Building A\"' (search by name), " +
                "'spi_wm:status=\"Active\"' (filter by status). " +
                "Leave blank to return all records.") String where,
            @ToolParam(description = "Optional comma-separated list of field names to include in the response. " +
                "Leave blank to return all fields. Use to reduce response size for large datasets.") String select,
            @ToolParam(description = "Maximum records to return. Default 50, maximum 200. " +
                "Use a small value like 10 when exploring an unfamiliar resource.") String pageSize) {
        try {
            int size = 50;
            if (pageSize != null && !pageSize.isBlank()) {
                try { size = Math.min(200, Integer.parseInt(pageSize.trim())); }
                catch (NumberFormatException ignored) {}
            }
            StringBuilder url = new StringBuilder(queryUrlForResource(resourceName) + "?oslc.pageSize=" + size);
            if (where  != null && !where.isBlank())  url.append("&oslc.where=").append(encode(where));
            if (select != null && !select.isBlank()) url.append("&oslc.select=").append(encode(select));
            return get(url.toString());
        } catch (Exception e) { return "Error querying '" + resourceName + "': " + e.getMessage(); }
    }

    @Tool(description =
        "Query TRIRIGA using a specific query capability URL. " +
        "Use this when a resource has multiple query capabilities and you want to use a specific one. " +
        "For example, triLocation has 'Locations Query', 'Building Lookup', and 'Floor and Space Lookup'. " +
        "Get the available query URLs from discoverResource() output. " +
        "This tool allows you to choose which query capability to use based on your specific needs.")
    public String queryByUrl(
            @ToolParam(description = "Full query capability URL from discoverResource(). " +
                "Example: 'http://host/oslc/spq/triBuildingLookupQC'") String queryUrl,
            @ToolParam(description = "Optional OSLC filter. Leave blank to return all records.") String where,
            @ToolParam(description = "Optional comma-separated field names to return. Blank = all.") String select,
            @ToolParam(description = "Maximum records to return (default 50, max 200).") String pageSize) {
        try {
            String size = (pageSize != null && !pageSize.isBlank()) ? pageSize : "50";
            int maxSize = Math.min(Integer.parseInt(size), 200);
            
            StringBuilder url = new StringBuilder(queryUrl + "?oslc.pageSize=" + maxSize);
            if (where != null && !where.isBlank()) {
                url.append("&oslc.where=").append(encode(where));
            }
            if (select != null && !select.isBlank()) {
                url.append("&oslc.select=").append(encode(select));
            }
            
            return get(url.toString());
        } catch (Exception e) {
            return "Error querying '" + queryUrl + "': " + e.getMessage();
        }
    }

    @Tool(description =
        "STEP 3B — READ. Fetch every field of a single TRIRIGA record by its ID. " +
        "Use this when the user asks for the details of a specific record — " +
        "e.g. 'show me work task 147665710', 'what is the status of this location?', " +
        "'give me the full details of this person'. " +
        "Also use before any update to confirm you have the right record and to see its " +
        "current field values and status before deciding what to change. " +
        "Returns all fields including read-only system fields, current workflow status, " +
        "and links to associated records. " +
        "Translate the raw field values into plain language for end users. " +
        "NEXT STEP: " +
        "  • User wants to CHANGE the record  → call getAvailableActions(), then updateResource() " +
        "  • User wants to DELETE the record  → confirm with the user first, then deleteResource()")
    public String readResource(
            @ToolParam(description = "Resource name from findShape() / discoverResource(). " +
                "Examples: 'triWorkTask', 'triLocation', 'triPeople'.") String resourceName,
            @ToolParam(description = "System Record ID (dcterms:identifier value) of the record. " +
                "Obtain this from queryResource() results. Example: '147665710'.") String recordId) {
        return get(recordUrl(resourceName, recordId));
    }

    @Tool(description =
        "STEP 4A — CREATE. Create a new record in TRIRIGA on behalf of the user. " +
        "Triggered by requests like: 'create a work order', 'add a new location', " +
        "'submit a maintenance request', 'log a new task', 'create a record for...'. " +
        "REQUIRED PREPARATION — do not skip: " +
        "  1. Call discoverResource() to get the list of field names (oslc:name values). " +
        "  2. If the user did not provide required fields, ask for them before proceeding. " +
        "  3. If a valid action list is needed at creation time, call getAvailableActions() " +
        "     on an existing record of the same type to confirm valid action names. " +
        "FIELD FORMAT: pipe-delimited name=value pairs using the oslc:name from discoverResource(). " +
        "Example: 'title=Fix HVAC|description=Unit 3 not cooling|schedstart=2026-03-15T08:00:00'. " +
        "Do NOT use the prefixed JSON key (e.g. spi:triTaskTypeCL) as the field name — " +
        "use the plain oslc:name (e.g. triTaskTypeCL). " +
        "INLINE CHILDREN: create and associate child records atomically (e.g. add a comment at " +
        "creation time). Format: 'childFieldName:field1=val1;field2=val2;action=Create'. " +
        "Example: 'triAssociatedComments:triCommentTX=Urgent issue;triCommentTypeLI=E-mail;action=Create'. " +
        "LINK EXISTING RECORDS: attach an existing record by ID. " +
        "Format: 'fieldName:recordId'. Example: 'triAssociatedComments:132633922'. " +
        "RETURNS: on success (HTTP 201), returns the URL of the new record — " +
        "confirm creation to the user in plain language.")
    public String createResource(
            @ToolParam(description = "Resource name from findShape() / discoverResource(). " +
                "Examples: 'triWorkTask', 'triLocation', 'cstCustomExample'.") String resourceName,
            @ToolParam(description = "Field values using oslc:name keys, pipe-delimited. " +
                "Get valid field names from discoverResource(). " +
                "Example: 'title=Fix HVAC|description=Urgent repair|schedstart=2026-03-01T08:00:00'.") String fields,
            @ToolParam(description = "Optional state-transition action to apply immediately after creation. " +
                "Example: 'Create Draft'. Call getAvailableActions() on an existing record of the same " +
                "type to see valid action names.") String action,
            @ToolParam(description = "Optional inline child records to create and associate in one call. " +
                "Format: 'childFieldName:field1=val1;field2=val2;action=Create'. " +
                "Multiple children separated by '|'. " +
                "Example: 'triAssociatedComments:triCommentTX=Note here;triCommentTypeLI=E-mail;action=Create'.") String inlineChildren,
            @ToolParam(description = "Optional existing record IDs to associate. " +
                "Format: 'fieldName:id1,id2'. " +
                "Example: 'triAssociatedComments:132633922,132633923'.") String linkedRecordIds,
            @ToolParam(description = "Optional unique transaction ID (any string) to prevent duplicate " +
                "submissions if the request is retried. Auto-generated if not supplied.") String transactionId) {
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
            OslcCreateResult result = post(createUrlForResource(resourceName), json, transactionId);
            return result.toString();
        } catch (Exception e) { return "Error creating '" + resourceName + "': " + e.getMessage(); }
    }

    @Tool(description =
        "STEP 4B — UPDATE. Change field values or trigger a workflow transition on an existing record. " +
        "Triggered by requests like: 'update this work order', 'change the description of...', " +
        "'mark this task as complete', 'close this work order', 'set the priority to High', " +
        "'put this on hold', 'reassign this task'. " +
        "PARTIAL UPDATE: only fields you supply are changed — all others remain exactly as they are. " +
        "REQUIRED PREPARATION — do not skip: " +
        "  1. If you do not have the record ID, call queryResource() to find it. " +
        "  2. Call readResource() to confirm you have the right record and see its current values. " +
        "  3. If the user wants a state transition (complete, approve, hold, retire, etc.), " +
        "     call getAvailableActions() FIRST — supplying an invalid action causes an error. " +
        "FIELD FORMAT: pipe-delimited name=value pairs, oslc:name keys from discoverResource(). " +
        "Only include the fields the user asked to change. " +
        "Example: 'description=Updated description|schedfinish=2026-04-01T17:00:00'. " +
        "STATE TRANSITIONS: pass the exact action string from getAvailableActions() — " +
        "e.g. action='Complete', action='Retire', action='Hold for Parts'. " +
        "Confirm the update result to the user in plain language.")
    public String updateResource(
            @ToolParam(description = "Resource name from findShape() / discoverResource(). " +
                "Examples: 'triWorkTask', 'triLocation', 'cstCustomExample'.") String resourceName,
            @ToolParam(description = "System Record ID (dcterms:identifier) of the record to update. " +
                "Obtain from queryResource() results. Example: '147665710'.") String recordId,
            @ToolParam(description = "Fields to update as pipe-delimited name=value pairs. " +
                "Only include fields you want to change. " +
                "Get valid field names from discoverResource(). " +
                "Example: 'description=Updated description|schedfinish=2026-04-01T17:00:00'.") String fields,
            @ToolParam(description = "Optional state-transition action. " +
                "Must be a value returned by getAvailableActions() for this specific record. " +
                "Examples: 'Save', 'Complete', 'Retire', 'Hold for Parts'.") String action) {
        try {
            OslcShape shape = loadShapeByName(resourceName);
            Map<String, String> literalFields = new LinkedHashMap<>();
            parseFields(fields, shape, literalFields, new LinkedHashMap<>());
            literalFields.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());
            String json = OslcJsonBuilder.build(shape, literalFields, action);
            return patch(recordUrl(resourceName, recordId), json);
        } catch (Exception e) { return "Error updating '" + resourceName + "' id=" + recordId + ": " + e.getMessage(); }
    }

    @Tool(description =
        "STEP 4C — DELETE. Permanently remove a TRIRIGA record. This action cannot be undone. " +
        "Triggered by requests like: 'delete this record', 'remove this work order', " +
        "'get rid of this location'. " +
        "MANDATORY PRE-DELETE CHECKLIST — do not skip any step: " +
        "  1. If you do not have the record ID, call queryResource() to find the exact record. " +
        "  2. Call readResource() to show the user what will be deleted and ask for explicit confirmation. " +
        "  3. Do NOT proceed until the user has said yes — this is permanent. " +
        "FALLBACK: not all TRIRIGA record types support OSLC deletion. " +
        "If the delete returns a 4xx error, the record type may require a workflow action instead " +
        "(e.g. 'Retire', 'Close', 'Cancel'). " +
        "In that case call getAvailableActions() to find the appropriate action and use updateResource().")
    public String deleteResource(
            @ToolParam(description = "Resource name from findShape() / discoverResource(). " +
                "Examples: 'triWorkTask', 'triLocation', 'cstCustomExample'.") String resourceName,
            @ToolParam(description = "System Record ID (dcterms:identifier) of the record to delete. " +
                "Confirm this is correct by calling readResource() before proceeding.") String recordId) {
        return delete(recordUrl(resourceName, recordId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Discovery
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description =
        "DIAGNOSTIC TOOL. Returns the list of all TRIRIGA OSLC Service Providers. " +
        "Each service provider groups related resource shapes (e.g. Work Management, BIM, Reservations). " +
        "Use findShape() to search across all providers in plain language. " +
        "Use this tool only if you need the raw service provider catalog structure.")
    public String getOSLCSP() { return get(tririgaUrl + "/oslc/sp"); }

    @Tool(description =
        "DIAGNOSTIC TOOL. Fetches any TRIRIGA OSLC URL and returns the raw RDF/XML response. " +
        "Use for debugging, exploring undocumented endpoints, or fetching a specific resource URL " +
        "returned in a previous response (e.g. a resource link URL). " +
        "Prefer readResource() for reading a known record by ID — it is simpler and returns the same data.")
    public String oslcFetch(
            @ToolParam(description = "Full TRIRIGA OSLC URL to fetch. Must start with http. " +
                "Example: 'http://host/oslc/so/triWorkTaskRS/147665710'.") String url) {
        return get(url);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Typed Work Task tools (convenience wrappers)
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description =
        "Search all TRIRIGA Work Tasks with optional filtering. " +
        "Convenience shortcut — equivalent to queryResource('triWorkTask', ...). " +
        "For filtering by the current user's tasks prefer queryMyAssignedWorkTasks() or " +
        "queryMyCreatedWorkTasks(). Use searchWorkTasks() for free-text keyword search. " +
        "Filter examples: 'spi_wm:status=\"Active\"', 'dcterms:title=\"Fix HVAC\"'.")
    public String queryWorkTasks(
            @ToolParam(description = "Optional OSLC filter using prefixed field names. " +
                "Examples: 'spi_wm:status=\"Active\"', 'dcterms:title=\"Fix HVAC\"'. Blank = all.") String where,
            @ToolParam(description = "Optional comma-separated field names to return. Blank = all fields.") String select) {
        return queryResource("triWorkTask", where, select, "50");
    }

    @Tool(description =
        "Returns Work Tasks currently assigned to the authenticated user. " +
        "Use when the user asks 'what are my tasks', 'what work is assigned to me', " +
        "or 'show me my open work orders'.")
    public String queryMyAssignedWorkTasks(
            @ToolParam(description = "Optional additional OSLC filter, e.g. 'spi_wm:status=\"Active\"'. " +
                "Leave blank to return all assigned tasks.") String where) {
        return get(findQueryUrlByName("triMyAssignedWorkTasksQC") + "?oslc.pageSize=50"
                + (where != null && !where.isBlank() ? "&oslc.where=" + encode(where) : ""));
    }

    @Tool(description =
        "Returns Work Tasks created by the authenticated user (regardless of current assignee). " +
        "Use when the user asks 'what tasks have I created', 'show me work orders I submitted', " +
        "or 'tasks I raised'.")
    public String queryMyCreatedWorkTasks(
            @ToolParam(description = "Optional additional OSLC filter. Leave blank for all created tasks.") String where) {
        return get(findQueryUrlByName("triMyCreatedWorkTasksQC") + "?oslc.pageSize=50"
                + (where != null && !where.isBlank() ? "&oslc.where=" + encode(where) : ""));
    }

    @Tool(description =
        "Returns completed Work Tasks for the authenticated user. " +
        "Use when the user asks 'show me my completed tasks', 'work orders I have finished', " +
        "or 'my task history'.")
    public String queryMyCompletedWorkTasks(
            @ToolParam(description = "Optional additional OSLC filter. Leave blank for all completed tasks.") String where) {
        return get(findQueryUrlByName("triMyCompletedWorkTasksQC") + "?oslc.pageSize=50"
                + (where != null && !where.isBlank() ? "&oslc.where=" + encode(where) : ""));
    }

    @Tool(description =
        "Full-text keyword search across all TRIRIGA Work Tasks. " +
        "Use when the user provides a descriptive phrase rather than a specific field value — " +
        "e.g. 'find tasks about the HVAC system', 'search for roof leak work orders'. " +
        "For structured filtering by field value use queryWorkTasks() instead.")
    public String searchWorkTasks(
            @ToolParam(description = "Keyword or phrase to search for across all work task fields. " +
                "Examples: 'HVAC', 'roof leak', 'elevator inspection'.") String keyword) {
        return get(findQueryUrlByName("triWorkTaskSearchResultsQC") + "?oslc.pageSize=50"
                + "&oslc.searchTerms=" + encode(keyword));
    }

    @Tool(description =
        "Create a new TRIRIGA Work Task. Typed convenience wrapper with named parameters. " +
        "Use this for the common work task creation case. For full field control or custom resource " +
        "types use createResource('triWorkTask', ...) after calling discoverResource('triWorkTask'). " +
        "Call getTaskTypes() to get valid task type values. " +
        "Call getAvailableActions() on an existing task to confirm valid action names before supplying one.")
    public String createWorkTask(
            @ToolParam(description = "Task title. Required. Example: 'Fix HVAC Unit 3'.") String title,
            @ToolParam(description = "Detailed description of the work required. Optional.") String description,
            @ToolParam(description = "Planned start date and time in ISO-8601 format. Required. " +
                "Example: '2026-03-15T08:00:00'.") String plannedStart,
            @ToolParam(description = "Planned completion date and time in ISO-8601 format. Optional. " +
                "Example: '2026-03-15T17:00:00'.") String plannedEnd,
            @ToolParam(description = "Task type classification. Optional. " +
                "Call getTaskTypes() to see valid values. Example: 'Corrective'.") String taskType,
            @ToolParam(description = "State-transition action to apply immediately after creation. Optional. " +
                "Example: 'Create Draft'. Call getAvailableActions() on an existing task to confirm valid names.") String action,
            @ToolParam(description = "Optional unique transaction ID to prevent duplicate submissions on retry.") String transactionId) {
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
            return post(createUrlForResource("triWorkTask"), json, transactionId).toString();
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description =
        "Update an existing TRIRIGA Work Task by its record ID. Partial (MERGE) update — " +
        "only fields you supply are changed, all others remain untouched. " +
        "ALWAYS call getAvailableActions() before supplying an action to confirm it is valid " +
        "for the task's current state. Supplying an invalid action causes a business validation error.")
    public String updateWorkTask(
            @ToolParam(description = "System Record ID (dcterms:identifier) of the task. " +
                "Obtain from queryWorkTasks() or queryMyAssignedWorkTasks() results.") String recordId,
            @ToolParam(description = "New title. Supply only if changing. Optional.") String title,
            @ToolParam(description = "New description. Supply only if changing. Optional.") String description,
            @ToolParam(description = "New planned start in ISO-8601. Supply only if changing. Optional.") String plannedStart,
            @ToolParam(description = "New planned end in ISO-8601. Supply only if changing. Optional.") String plannedEnd,
            @ToolParam(description = "New task type. Supply only if changing. Optional.") String taskType,
            @ToolParam(description = "State-transition action. Must be a value returned by " +
                "getAvailableActions() for this specific task. Examples: 'Save', 'Complete', 'Retire'.") String action) {
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

    @Tool(description =
        "Search TRIRIGA Assets (equipment, devices, physical items tracked in the asset register). " +
        "Use when the user asks about equipment, devices, or physical assets. " +
        "For free-text search by name or barcode use lookupAssets() instead.")
    public String queryAssets(
            @ToolParam(description = "Optional OSLC filter using prefixed field names. Blank = all assets.") String where,
            @ToolParam(description = "Optional comma-separated field names to return. Blank = all fields.") String select) {
        return queryResource("triAsset", where, select, "50");
    }

    @Tool(description =
        "Find TRIRIGA Assets by name or barcode scan. " +
        "Use when the user provides an asset name, description, or barcode value.")
    public String lookupAssets(
            @ToolParam(description = "Asset name or barcode to search for. Example: 'Elevator 2B' or '00042391'.") String searchTerm) {
        return get(findQueryUrlByName("triAssetsLookupQC") + "?oslc.pageSize=25"
                + "&oslc.searchTerms=" + encode(searchTerm));
    }

    @Tool(description =
        "Search TRIRIGA Locations — the hierarchy of buildings, floors, and spaces. " +
        "Use when the user asks about a building, floor, room, space, or any physical location. " +
        "For looking up a building by name use lookupBuildings(). " +
        "For floors and rooms use lookupFloorsAndSpaces().")
    public String queryLocations(
            @ToolParam(description = "Optional OSLC filter. Blank = all locations. " +
                "Example: 'spi:triNameTX=\"North Tower\"'.") String where,
            @ToolParam(description = "Optional comma-separated field names to return. Blank = all fields.") String select) {
        return queryResource("triLocation", where, select, "50");
    }

    @Tool(description =
        "Find TRIRIGA Buildings by name. Use when the user refers to a building by name " +
        "and you need its record ID or details. Returns building name, ID, address, and status.")
    public String lookupBuildings(
            @ToolParam(description = "Building name or partial name to search for. " +
                "Leave blank to return all buildings. Example: 'North Tower'.") String searchTerm) {
        return get(findQueryUrlByName("triBuildingLookupQC") + "?oslc.pageSize=25"
                + (searchTerm != null && !searchTerm.isBlank() ? "&oslc.searchTerms=" + encode(searchTerm) : ""));
    }

    @Tool(description =
        "Find TRIRIGA Floors and Spaces (rooms, offices, common areas) by name. " +
        "Use when the user refers to a floor, room, or space by name or number.")
    public String lookupFloorsAndSpaces(
            @ToolParam(description = "Floor or space name to search for. " +
                "Leave blank to return all. Example: 'Floor 3' or 'Conference Room B'.") String searchTerm) {
        return get(findQueryUrlByName("triFloorandSpaceLookupQC") + "?oslc.pageSize=25"
                + (searchTerm != null && !searchTerm.isBlank() ? "&oslc.searchTerms=" + encode(searchTerm) : ""));
    }

    @Tool(description =
        "Find TRIRIGA spaces that are available for reservation (meeting rooms, hot desks, etc.). " +
        "Use when the user asks about booking a room, reserving a space, or finding available rooms.")
    public String queryReservableSpaces(
            @ToolParam(description = "Optional OSLC filter to narrow results, e.g. by capacity or building. " +
                "Leave blank to return all reservable spaces.") String where) {
        return get(findQueryUrlByName("triReservableSpaceQC") + "?oslc.pageSize=50"
                + (where != null && !where.isBlank() ? "&oslc.where=" + encode(where) : ""));
    }

    @Tool(description =
        "Query TRIRIGA room and space reservations synced from Microsoft Exchange / Outlook. " +
        "Use when the user asks about existing room bookings, calendar reservations, or appointments.")
    public String queryExchangeAppointments(
            @ToolParam(description = "Optional OSLC filter. Leave blank to return all reservations.") String where) {
        return get(findQueryUrlByName("triExchangeAppointmentQC") + "?oslc.pageSize=50"
                + (where != null && !where.isBlank() ? "&oslc.where=" + encode(where) : ""));
    }

    @Tool(description =
        "STEP 3C — INSPECT ACTIONS. Returns the exact workflow actions available for a specific " +
        "TRIRIGA record RIGHT NOW, based on its current status. " +
        "Call this when: " +
        "  • The user asks 'what can I do with this record?' or 'what are my options?' " +
        "  • The user wants to close, complete, approve, hold, retire, or transition a record " +
        "  • You are about to call updateResource() or updateWorkTask() with an action parameter " +
        "  • A previous update failed because an action was invalid " +
        "Available actions change with the record lifecycle — a Draft work task offers different " +
        "actions than an Active or On-Hold one. NEVER guess an action name. " +
        "MANDATORY: always call this before passing any action to updateResource() or updateWorkTask(). " +
        "Supplying an action not in this list will fail with a TRIRIGA business validation error. " +
        "Present the action list to the user in plain language if they asked what options are available. " +
        "NEXT STEP: pass the exact action string the user chose to the action parameter of " +
        "updateResource() or updateWorkTask().")
    public String getAvailableActions(
            @ToolParam(description = "Resource name from findShape() / discoverResource(). " +
                "Examples: 'triWorkTask', 'triLocation', 'cstCustomExample'.") String resourceName,
            @ToolParam(description = "System Record ID (dcterms:identifier) of the record. " +
                "Obtain from queryResource() results. Example: '147665710'.") String recordId) {
        try {
            String actionUrl = tririgaUrl + "/oslc/system/action/" + resourceName + "RS/" + recordId;
            // Use getRaw() because we need to parse the XML ourselves
            String xml = getRaw(actionUrl);

            // Parse oslc:allowedValue elements from the RDF/XML response
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            NodeList nodes = doc.getElementsByTagNameNS("http://open-services.net/ns/core#", "allowedValue");

            if (nodes.getLength() == 0) {
                return "No actions available for " + resourceName + " record " + recordId +
                       ". The record may be in a terminal state, or the record ID may be invalid.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Available actions for ").append(resourceName)
              .append(" record ").append(recordId).append(":\n");
            for (int i = 0; i < nodes.getLength(); i++) {
                String action = nodes.item(i).getTextContent().trim();
                if (!action.isBlank()) sb.append("  - ").append(action).append("\n");
            }
            sb.append("\nPass one of these action values to the action parameter of ");
            sb.append("updateResource() or updateWorkTask() to trigger a state transition.");
            return sb.toString();
        } catch (Exception e) {
            return "Error fetching actions for " + resourceName + " / " + recordId + ": " + e.getMessage();
        }
    }

    @Tool(description =
        "Returns all valid Work Task status values in TRIRIGA " +
        "(e.g. Draft, Active, On Hold, Completed, Retired). " +
        "Use when filtering work tasks by status in a where clause, " +
        "or when the user asks what statuses are possible.")
    public String getWorkTaskStatuses() { 
        return get(findQueryUrlByName("triStatusesQC")); 
    }

    @Tool(description =
        "Returns all valid Priority records with their names and resource URLs. " +
        "Use when the user specifies a priority level for a work task and you need " +
        "the resource URL to pass as a linked field in createResource() or updateResource().")
    public String getPriorities() { 
        return get(findQueryUrlByName("triPrioritiesQC")); 
    }

    @Tool(description =
        "Returns all valid Task Type records (e.g. Corrective, Preventive, Inspection) " +
        "with their names and resource URLs. " +
        "Use when the user specifies a task type and you need to confirm it is valid, " +
        "or to get the resource URL for a linked field in createResource() or updateResource().")
    public String getTaskTypes() { 
        return get(findQueryUrlByName("triTaskTypesQC")); 
    }

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