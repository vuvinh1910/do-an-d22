package com.platform.worker.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Internal runner input: URI includes resolved path and query, not a wire contract DTO. */
public record ResolvedHttpRequest(
        HttpMethod method,
        URI uri,
        Map<String, List<String>> headers,
        JsonNode body
) {
    public ResolvedHttpRequest {
        if (method == null) {
            throw new IllegalArgumentException("Resolved request method is required");
        }
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException("Resolved request URI must be HTTP(S) without credentials or fragment");
        }
        headers = ModelSnapshots.headers(headers);
        body = ModelSnapshots.json(body);
    }

    @Override
    public JsonNode body() {
        return ModelSnapshots.json(body);
    }
}