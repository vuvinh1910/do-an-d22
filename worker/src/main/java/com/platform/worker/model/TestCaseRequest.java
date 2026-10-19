package com.platform.worker.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** Request template from the TestCase contract; values may still contain variables. */
public record TestCaseRequest(
        String method,
        String path,
        Map<String, String> headers,
        Map<String, String> pathParams,
        Map<String, JsonNode> queryParams,
        JsonNode body
) {
    public TestCaseRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        pathParams = pathParams == null ? Map.of() : Map.copyOf(pathParams);
        queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
    }
}