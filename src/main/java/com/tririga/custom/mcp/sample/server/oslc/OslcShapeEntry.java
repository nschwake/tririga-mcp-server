package com.tririga.custom.mcp.sample.server.oslc;

/**
 * Metadata about a single OSLC resource shape discovered from the service catalog.
 *
 * Populated by crawling all oslc:ServiceProvider documents and extracting
 * every oslc:QueryCapability and oslc:CreationFactory entry.
 *
 * This is the data an LLM uses to map natural language intent
 * (e.g. "people", "work order", "room reservation") to the correct
 * resourceName for use with discoverResource / queryResource / createResource.
 */
public class OslcShapeEntry {

    /** Human-readable title from dcterms:title on the query/creation capability. */
    private final String capabilityTitle;

    /** Human-readable title of the service provider this shape belongs to, e.g. "Work Management". */
    private final String serviceProviderTitle;

    /** The resource type local name, e.g. "WorkOrder", "Person", "Location". Derived from resourceType URI. */
    private final String resourceTypeLabel;

    /** Full resourceType URI, e.g. "http://jazz.net/ns/ism/.../work#WorkOrder". */
    private final String resourceTypeUri;

    /** Full shape URL, e.g. "http://host/oslc/shapes/triWorkTaskRS". */
    private final String shapeUrl;

    /**
     * The resource name to pass to discoverResource / queryResource / createResource etc.
     * Derived by stripping the base URL and "RS" suffix from the shapeUrl.
     * e.g. shapeUrl ".../shapes/triWorkTaskRS" → resourceName "triWorkTask"
     */
    private final String resourceName;

    /** Query base URL — present if this shape has a QueryCapability. Null otherwise. */
    private final String queryUrl;

    /** Creation factory URL — present if this shape has a CreationFactory. Null otherwise. */
    private final String creationUrl;

    public OslcShapeEntry(String capabilityTitle, String serviceProviderTitle,
                          String resourceTypeLabel, String resourceTypeUri,
                          String shapeUrl, String resourceName,
                          String queryUrl, String creationUrl) {
        this.capabilityTitle      = capabilityTitle;
        this.serviceProviderTitle = serviceProviderTitle;
        this.resourceTypeLabel    = resourceTypeLabel;
        this.resourceTypeUri      = resourceTypeUri;
        this.shapeUrl             = shapeUrl;
        this.resourceName         = resourceName;
        this.queryUrl             = queryUrl;
        this.creationUrl          = creationUrl;
    }

    // ── Capability flags ──────────────────────────────────────────────────────

    /** True if records of this type can be queried/searched. */
    public boolean isQueryable()  { return queryUrl    != null; }

    /** True if records of this type can be created via OSLC. */
    public boolean isCreatable()  { return creationUrl != null; }

    /**
     * True if records of this type can be updated or deleted.
     * TRIRIGA supports update (PUT) and delete on any shape that has a resource URL,
     * which is all shapes with a resourceName. We flag it true whenever resourceName
     * is available — i.e. always for well-formed shapes.
     */
    public boolean isUpdatable()  { return resourceName != null; }
    public boolean isDeletable()  { return resourceName != null; }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getCapabilityTitle()      { return capabilityTitle; }
    public String getServiceProviderTitle() { return serviceProviderTitle; }
    public String getResourceTypeLabel()    { return resourceTypeLabel; }
    public String getResourceTypeUri()      { return resourceTypeUri; }
    public String getShapeUrl()             { return shapeUrl; }
    public String getResourceName()         { return resourceName; }
    public String getQueryUrl()             { return queryUrl; }
    public String getCreationUrl()          { return creationUrl; }

    /**
     * Formats a concise single-line summary for display in findShape results.
     *
     * Example:
     *   [QUERY|CREATE|UPDATE|DELETE] triWorkTask
     *     "All Work Tasks Query"  (Work Management)
     *     type: WorkOrder
     */
    public String toSummaryLine() {
        StringBuilder ops = new StringBuilder("[");
        if (isQueryable())  ops.append("QUERY ");
        if (isCreatable())  ops.append("CREATE ");
        if (isUpdatable())  ops.append("UPDATE ");
        if (isDeletable())  ops.append("DELETE");
        String opsStr = ops.toString().trim();
        if (opsStr.equals("[")) opsStr = "[READ-ONLY";
        return opsStr + "]  resourceName=\"" + resourceName + "\"\n"
             + "  title:    \"" + capabilityTitle + "\"\n"
             + "  provider: " + serviceProviderTitle + "\n"
             + "  type:     " + resourceTypeLabel;
    }
}
