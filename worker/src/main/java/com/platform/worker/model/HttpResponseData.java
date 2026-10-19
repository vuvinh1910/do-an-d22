package com.platform.worker.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Raw runner output, not TestResult. A transport error has no HTTP status or body. */
public record HttpResponseData(
        Integer statusCode,
        Map<String, List<String>> headers,
        String body,
        long responseTimeMs,
        String error
) {
    public HttpResponseData {
        headers = headers == null ? Map.of() : headers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
    }
}