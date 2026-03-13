package com.tririga.custom.mcp.sample.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Configuration
public class TririgaApiConfig {

    @Value("${MREF_URL:#{null}}")
    private String tririgaUrl;

    @Value("${MREF_USER:#{null}}")
    private String tririgaUser;

    @Value("${MREF_PASS:#{null}}")
    private String tririgaPass;

    @Value("${tririga.api.timeout:30}")
    private int timeoutSeconds;

    private String encodedAuth;

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
    }

    @Bean
    public HttpClient tririgaHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String getTririgaUrl() {
        return tririgaUrl;
    }

    public String getEncodedAuth() {
        return encodedAuth;
    }

    public String getTririgaUser() {
        return tririgaUser;
    }

    public String getTririgaPassword() {
        return tririgaPass;
    }
}