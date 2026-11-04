package com.platform.worker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** Assertion template fields defined by the TestCase contract; semantics belong to evaluators. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssertionRule(String id, String type, JsonNode expected, String operator,
                            String jsonPath, JsonNode expectedSchema, String description, String headerName) {
    public AssertionRule {
        if (headerName != null && !"HEADER".equals(type)) {
            throw new IllegalArgumentException("headerName is only supported for HEADER rules");
        }
        expected = ModelSnapshots.json(expected);
        expectedSchema = ModelSnapshots.json(expectedSchema);
    }

    /** Preserve source compatibility for existing non-header rule construction. */
    public AssertionRule(String id, String type, JsonNode expected, String operator,
                         String jsonPath, JsonNode expectedSchema, String description) {
        this(id, type, expected, operator, jsonPath, expectedSchema, description, null);
    }

    @Override
    public JsonNode expected() {
        return ModelSnapshots.json(expected);
    }

    @Override
    public JsonNode expectedSchema() {
        return ModelSnapshots.json(expectedSchema);
    }
}