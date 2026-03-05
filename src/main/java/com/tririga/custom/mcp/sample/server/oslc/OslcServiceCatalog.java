package com.tririga.custom.mcp.sample.server.oslc;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Crawls all TRIRIGA OSLC service providers and builds a flat, searchable
 * index of every resource shape found — including its human-readable title,
 * resource type, resource name, and which CRUD operations it supports.
 *
 * The catalog is built lazily on first use and cached until invalidated.
 *
 * The catalog is intentionally decoupled from HTTP — it receives raw RDF/XML
 * strings from the service and does all parsing internally.
 */
public class OslcServiceCatalog {

    private static final String NS_OSLC    = "http://open-services.net/ns/core#";
    private static final String NS_DCTERMS = "http://purl.org/dc/terms/";
    private static final String NS_RDF     = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String NS_RDFS    = "http://www.w3.org/2000/01/rdf-schema#";

    // ── Shape index — keyed by shapeUrl to deduplicate across providers ──────
    private final Map<String, OslcShapeEntry> index = new LinkedHashMap<>();

    // ── Track which shapes have a creation factory (shapeUrl → creationUrl) ──
    private final Map<String, String> creationUrls = new HashMap<>();

    // ── Track which shapes have a query capability (shapeUrl → queryUrl) ─────
    private final Map<String, String> queryUrls = new HashMap<>();

    /**
     * Prefix map accumulated across ALL service providers.
     * prefix → namespace URI, e.g. "spi" → "http://jazz.net/ns/ism/smarter_physical_infrastructure#"
     * Used by OslcShape.parse() so property keys render as "spi:triTaskTypeCL" not bare names.
     */
    private final Map<String, String> globalPrefixMap = new LinkedHashMap<>();

    private boolean built = false;

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Callback interface used by the catalog to fetch raw RDF/XML from TRIRIGA.
     * The service layer supplies this so the catalog stays HTTP-agnostic.
     */
    @FunctionalInterface
    public interface Fetcher {
        String fetch(String url);
    }

    /**
     * Build (or rebuild) the full shape index by crawling all service providers.
     * Call this once on startup or when invalidating the cache.
     */
    public synchronized void build(String catalogUrl, Fetcher fetcher) throws Exception {
        index.clear();
        creationUrls.clear();
        queryUrls.clear();
        globalPrefixMap.clear();

        // 1. Fetch the top-level service provider catalog
        String catalogXml = fetcher.fetch(catalogUrl);
        List<String> spUrls = parseMembers(catalogXml);

        // 2. Crawl each service provider
        for (String spUrl : spUrls) {
            try {
                String spXml = fetcher.fetch(spUrl);
                indexServiceProvider(spXml);
            } catch (Exception e) {
                // Skip providers that fail — don't abort the whole crawl
                System.err.println("[OslcServiceCatalog] Skipping " + spUrl + ": " + e.getMessage());
            }
        }

        // 3. Merge creation URLs back into entries (a shape may appear in query
        //    capabilities before its creation factory is encountered)
        for (Map.Entry<String, OslcShapeEntry> e : index.entrySet()) {
            String shapeUrl     = e.getKey();
            OslcShapeEntry entry = e.getValue();
            String creationUrl  = creationUrls.get(shapeUrl);
            String queryUrl     = queryUrls.get(shapeUrl);
            if ((creationUrl != null && entry.getCreationUrl() == null)
                    || (queryUrl != null && entry.getQueryUrl() == null)) {
                // Replace with a fully merged entry
                index.put(shapeUrl, new OslcShapeEntry(
                        entry.getCapabilityTitle(),
                        entry.getServiceProviderTitle(),
                        entry.getResourceTypeLabel(),
                        entry.getResourceTypeUri(),
                        entry.getShapeUrl(),
                        entry.getResourceName(),
                        queryUrl != null ? queryUrl : entry.getQueryUrl(),
                        creationUrl != null ? creationUrl : entry.getCreationUrl()
                ));
            }
        }

        built = true;
    }

    /** Returns true if the catalog has been built at least once. */
    public boolean isBuilt() { return built; }

    /**
     * Returns the accumulated namespace prefix map from all service providers.
     * e.g. {"spi" -> "http://jazz.net/ns/ism/smarter_physical_infrastructure#",
     *        "spi_wm" -> "http://jazz.net/ns/ism/smarter_physical_infrastructure/work#",
     *        "dcterms" -> "http://purl.org/dc/terms/", ...}
     *
     * Pass this to OslcShape.parse(url, xml, catalog.getGlobalPrefixMap()) so that
     * OslcProperty.prefixedName() generates correct "prefix:localName" JSON keys.
     */
    public Map<String, String> getGlobalPrefixMap() {
        return Collections.unmodifiableMap(globalPrefixMap);
    }

    /** Invalidate the catalog so it will be rebuilt on next use. */
    public synchronized void invalidate() { built = false; }

    /**
     * Search the shape index using one or more keywords.
     * Matches against: capability title, service provider title, resource type label,
     * and resource name — all case-insensitively.
     *
     * Returns all matching entries, sorted by relevance (number of keyword hits).
     */
    public List<OslcShapeEntry> search(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return new ArrayList<>(index.values());
        }

        String[] terms = keywords.toLowerCase().split("[\\s,]+");

        // Score each entry by how many terms match
        List<OslcShapeEntry> results = new ArrayList<>();
        Map<OslcShapeEntry, Integer> scores = new LinkedHashMap<>();

        for (OslcShapeEntry entry : index.values()) {
            String haystack = buildSearchText(entry).toLowerCase();
            int score = 0;
            for (String term : terms) {
                if (haystack.contains(term)) score++;
            }
            if (score > 0) {
                results.add(entry);
                scores.put(entry, score);
            }
        }

        // Sort descending by score, then by resource name for stable ordering
        results.sort((a, b) -> {
            int scoreDiff = scores.get(b) - scores.get(a);
            if (scoreDiff != 0) return scoreDiff;
            return a.getResourceName().compareTo(b.getResourceName());
        });

        return results;
    }

    /** Returns all entries in the index. */
    public Collection<OslcShapeEntry> all() { return Collections.unmodifiableCollection(index.values()); }

    /** Returns the total number of shapes indexed. */
    public int size() { return index.size(); }

    // ─────────────────────────────────────────────────────────────────────────
    //  Parsing
    // ─────────────────────────────────────────────────────────────────────────

    /** Extract rdfs:member URLs from the top-level catalog RDF/XML. */
    private List<String> parseMembers(String rdfXml) throws Exception {
        Document doc = parse(rdfXml);
        List<String> urls = new ArrayList<>();
        NodeList members = doc.getElementsByTagNameNS(NS_RDFS, "member");
        for (int i = 0; i < members.getLength(); i++) {
            String url = ((Element) members.item(i)).getAttributeNS(NS_RDF, "resource");
            if (url != null && !url.isBlank()) urls.add(url);
        }
        return urls;
    }

    /** Parse a single service provider document and add all shapes to the index. */
    private void indexServiceProvider(String rdfXml) throws Exception {
        Document doc = parse(rdfXml);

        // Service provider title
        String spTitle = firstText(doc, NS_DCTERMS, "title");
        if (spTitle == null) spTitle = "Unknown Provider";
        final String finalTitle = spTitle;
        // ── Extract oslc:prefixDefinition entries into globalPrefixMap ─────────
        NodeList prefixDefs = doc.getElementsByTagNameNS(NS_OSLC, "PrefixDefinition");
        for (int i = 0; i < prefixDefs.getLength(); i++) {
            Element pd = (Element) prefixDefs.item(i);
            String prefix = childText(pd, NS_OSLC, "prefix");
            String base   = childAttr(pd, NS_OSLC, "prefixBase", NS_RDF, "resource");
            if (prefix != null && !prefix.isBlank() && base != null && !base.isBlank()) {
                globalPrefixMap.putIfAbsent(prefix, base);
            }
        }

        // ── Query capabilities ────────────────────────────────────────────────
        NodeList qcNodes = doc.getElementsByTagNameNS(NS_OSLC, "QueryCapability");
        for (int i = 0; i < qcNodes.getLength(); i++) {
            Element qc = (Element) qcNodes.item(i);
            String title      = childText(qc, NS_DCTERMS, "title");
            String shapeUrl   = childAttr(qc, NS_OSLC, "resourceShape",  NS_RDF, "resource");
            String typeUri    = childAttr(qc, NS_OSLC, "resourceType",   NS_RDF, "resource");
            String queryBase  = childAttr(qc, NS_OSLC, "queryBase",      NS_RDF, "resource");

            if (shapeUrl == null) continue;
            String resourceName = extractResourceName(shapeUrl);
            String typeLabel    = extractLabel(typeUri);

            queryUrls.put(shapeUrl, queryBase);

            // Only add to index if not already present (first occurrence wins for title)
            index.computeIfAbsent(shapeUrl, k -> new OslcShapeEntry(
                    title != null ? title : resourceName,
                    finalTitle, typeLabel, typeUri,
                    shapeUrl, resourceName, queryBase, creationUrls.get(shapeUrl)));
        }

        // ── Creation factories ────────────────────────────────────────────────
        NodeList cfNodes = doc.getElementsByTagNameNS(NS_OSLC, "CreationFactory");
        for (int i = 0; i < cfNodes.getLength(); i++) {
            Element cf = (Element) cfNodes.item(i);
            String title       = childText(cf, NS_DCTERMS, "title");
            String shapeUrl    = childAttr(cf, NS_OSLC, "resourceShape", NS_RDF, "resource");
            String typeUri     = childAttr(cf, NS_OSLC, "resourceType",  NS_RDF, "resource");
            String creationUrl = childAttr(cf, NS_OSLC, "creation",      NS_RDF, "resource");

            if (shapeUrl == null) continue;
            String resourceName = extractResourceName(shapeUrl);
            String typeLabel    = extractLabel(typeUri);

            creationUrls.put(shapeUrl, creationUrl);

            index.computeIfAbsent(shapeUrl, k -> new OslcShapeEntry(
                    title != null ? title : resourceName,
                    finalTitle, typeLabel, typeUri,
                    shapeUrl, resourceName, queryUrls.get(shapeUrl), creationUrl));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Derives the resourceName from a shape URL.
     * "/oslc/shapes/triWorkTaskRS" → "triWorkTask"
     * "/oslc/shapes/cstCustomExampleRS" → "cstCustomExample"
     * Strips the trailing "RS" suffix only if it ends with "RS".
     */
    static String extractResourceName(String shapeUrl) {
        if (shapeUrl == null) return null;
        int slash = shapeUrl.lastIndexOf('/');
        String name = slash >= 0 ? shapeUrl.substring(slash + 1) : shapeUrl;
        // Strip trailing "RS" suffix (TRIRIGA convention for resource shapes)
        if (name.endsWith("RS")) name = name.substring(0, name.length() - 2);
        return name;
    }

    /**
     * Derives a human-readable label from a URI by taking the fragment or
     * last path segment, then splitting on camelCase boundaries.
     * "http://...#WorkOrder" → "Work Order"
     * "http://.../work#schedstart" → "schedstart"
     */
    static String extractLabel(String uri) {
        if (uri == null) return "Unknown";
        // URL-decode percent-encoded spaces
        String decoded = uri.replace("%20", " ");
        int hash  = decoded.lastIndexOf('#');
        int slash = decoded.lastIndexOf('/');
        int idx   = Math.max(hash, slash);
        String local = idx >= 0 ? decoded.substring(idx + 1) : decoded;
        // Split camelCase into words for readability
        return local.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    /** Builds the full text haystack used for keyword searching. */
    private String buildSearchText(OslcShapeEntry e) {
        return String.join(" ",
            nvl(e.getCapabilityTitle()),
            nvl(e.getServiceProviderTitle()),
            nvl(e.getResourceTypeLabel()),
            nvl(e.getResourceName()),
            // Also expand the resource name by splitting camelCase
            nvl(e.getResourceName()).replaceAll("([a-z])([A-Z])", "$1 $2"),
            // Strip common prefixes so "triWorkTask" also matches "work task"
            nvl(e.getResourceName()).replaceAll("^(tri|cst|bim|spi)", "")
        );
    }

    private String nvl(String s) { return s != null ? s : ""; }

    // ── DOM helpers ───────────────────────────────────────────────────────────

    private Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private String firstText(Document doc, String ns, String local) {
        NodeList nl = doc.getElementsByTagNameNS(ns, local);
        return nl.getLength() > 0 ? nl.item(0).getTextContent().trim() : null;
    }

    private String childText(Element el, String ns, String local) {
        NodeList nl = el.getElementsByTagNameNS(ns, local);
        return nl.getLength() > 0 ? nl.item(0).getTextContent().trim() : null;
    }

    private String childAttr(Element el, String elNs, String elLocal,
                               String attrNs, String attrLocal) {
        NodeList nl = el.getElementsByTagNameNS(elNs, elLocal);
        if (nl.getLength() == 0) return null;
        String v = ((Element) nl.item(0)).getAttributeNS(attrNs, attrLocal);
        return v.isBlank() ? null : v;
    }
}
