package com.platform.worker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        headers = ModelSnapshots.entries(headers, "headers");
        pathParams = ModelSnapshots.entries(pathParams, "pathParams");
        queryParams = ModelSnapshots.query(queryParams);
        body = ModelSnapshots.json(body);
    }

    @Override
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public JsonNode body() {
        return ModelSnapshots.json(body);
    }

    @Override
    public Map<String, JsonNode> queryParams() {
        return ModelSnapshots.query(queryParams);
    }
}