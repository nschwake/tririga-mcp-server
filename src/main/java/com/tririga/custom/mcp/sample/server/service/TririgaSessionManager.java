package com.tririga.custom.mcp.sample.server.service;

import com.tririga.custom.mcp.sample.server.config.TririgaApiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

@Service
public class TririgaSessionManager {

    private static final Logger log = LoggerFactory.getLogger(TririgaSessionManager.class);
    
    private final HttpClient httpClient;
    private final TririgaApiConfig config;
    private static final String LOGIN_ENDPOINT = "/html/en/default/rest/Login";
    
    private volatile String sessionCookie;
    
    public TririgaSessionManager(HttpClient httpClient, TririgaApiConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    @PostConstruct
    private void init() {
        log.info("TririgaSessionManager initialized");
    }

    /**
     * Get a valid session cookie, logging in if necessary
     */
    public String getSessionCookie() {
        if (sessionCookie == null) {
            synchronized (this) {
                if (sessionCookie == null) {
                    sessionCookie = login();
                }
            }
        }
        return sessionCookie;
    }

    /**
     * Force a new login and refresh the session cookie
     */
    public String refreshSession() {
        synchronized (this) {
            log.info("Refreshing TRIRIGA session");
            sessionCookie = login();
            return sessionCookie;
        }
    }

    /**
     * Login to TRIRIGA and get a session cookie
     */
    private String login() {
        log.info("Logging in to TRIRIGA to obtain session cookie");
        
        try {
            String url = config.getTririgaUrl() + LOGIN_ENDPOINT;
            
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + config.getEncodedAuth())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Optional<String> setCookie = response.headers().firstValue("Set-Cookie");
                if (setCookie.isPresent()) {
                    String cookie = setCookie.get();
                    String sessionId = extractSessionId(cookie);
                    log.info("Successfully logged in and obtained session");
                    return sessionId;
                } else {
                    throw new RuntimeException("Login successful but no session cookie returned");
                }
            } else {
                throw new RuntimeException("Login failed. Status: " + response.statusCode() + 
                                         ", Body: " + response.body());
            }
        } catch (Exception e) {
            log.error("Error during TRIRIGA login", e);
            throw new RuntimeException("Failed to login to TRIRIGA: " + e.getMessage(), e);
        }
    }

    /**
     * Extract session ID from Set-Cookie header
     */
    private String extractSessionId(String setCookieHeader) {
        // Usually format is: JSESSIONID=xxx; Path=/; HttpOnly
        if (setCookieHeader.contains("JSESSIONID=")) {
            int start = setCookieHeader.indexOf("JSESSIONID=");
            int end = setCookieHeader.indexOf(";", start);
            if (end == -1) end = setCookieHeader.length();
            return setCookieHeader.substring(start, end);
        }
        // Return entire cookie if JSESSIONID not found
        int semicolon = setCookieHeader.indexOf(";");
        return semicolon > 0 ? setCookieHeader.substring(0, semicolon) : setCookieHeader;
    }

    /**
     * Clear the current session
     */
    public void clearSession() {
        synchronized (this) {
            log.info("Clearing TRIRIGA session");
            sessionCookie = null;
        }
    }
}
