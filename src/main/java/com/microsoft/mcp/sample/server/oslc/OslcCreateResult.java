package com.microsoft.mcp.sample.server.oslc;

/**
 * Wraps the result of an OSLC creation (POST) request.
 *
 * On success (HTTP 201), TRIRIGA returns:
 *   - Location header: URL of the newly created resource
 *   - ETag header: version identifier
 *   - Body: may be empty or contain the created resource properties
 *
 * This class captures all three so callers can extract the new resource URL
 * without having to parse headers manually.
 */
public class OslcCreateResult {

    private final int statusCode;
    private final String locationUrl;   // from Location response header
    private final String etag;          // from ETag response header
    private final String body;          // response body (may be empty)

    public OslcCreateResult(int statusCode, String locationUrl, String etag, String body) {
        this.statusCode  = statusCode;
        this.locationUrl = locationUrl;
        this.etag        = etag;
        this.body        = body;
    }

    public boolean isSuccess()      { return statusCode == 201; }
    public int getStatusCode()      { return statusCode; }
    public String getLocationUrl()  { return locationUrl; }
    public String getEtag()         { return etag; }
    public String getBody()         { return body; }

    @Override
    public String toString() {
        if (isSuccess()) {
            return String.format("Created successfully.\nLocation: %s\nETag: %s\n%s",
                    locationUrl, etag, body != null && !body.isBlank() ? "Body:\n" + body : "");
        }
        return String.format("Failed (HTTP %d).\n%s", statusCode, body);
    }
}
