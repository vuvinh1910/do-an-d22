package com.platform.worker.model;

import java.util.List;
import java.util.Map;

/** Raw runner output, not TestResult. Read failures may retain known status/headers and partial body. */
public record HttpResponseData(
        Integer statusCode,
        Map<String, List<String>> headers,
        String body,
        long responseTimeMs,
        String error
) {
    public HttpResponseData {
        headers = ModelSnapshots.headers(headers);
        if (responseTimeMs < 0) {
            throw new IllegalArgumentException("responseTimeMs must be non-negative");
        }
        if (error != null && error.isBlank()) {
            throw new IllegalArgumentException("error must be non-blank when present");
        }
        if (statusCode == null) {
            if (error == null || body != null || !headers.isEmpty()) {
                throw new IllegalArgumentException("Without HTTP status, a transport error and no response data are required");
            }
        } else {
            if (statusCode < 100 || statusCode > 999) {
                throw new IllegalArgumentException("statusCode must be a three-digit HTTP status");
            }
            if (body == null && error == null) {
                throw new IllegalArgumentException("A complete HTTP response requires a body string, empty when absent");
            }
        }
    }
}