package com.platform.worker.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Internal runner input: URI includes resolved path and query, not a wire contract DTO. */
public record ResolvedHttpRequest(
        HttpMethod method,
        URI uri,
        Map<String, List<String>> headers,
        JsonNode body
) {
    public ResolvedHttpRequest {
        headers = headers == null ? Map.of() : headers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
    }
}