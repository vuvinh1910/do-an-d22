package com.platform.worker.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Per-rule output from the TestResult contract, not the overall execution status. */
public record AssertionResult(String ruleId, String type, boolean passed, JsonNode expected,
                              JsonNode actual, String message) {
    public AssertionResult {
        expected = ModelSnapshots.json(expected);
        actual = ModelSnapshots.json(actual);
    }

    @Override
    public JsonNode expected() {
        return ModelSnapshots.json(expected);
    }

    @Override
    public JsonNode actual() {
        return ModelSnapshots.json(actual);
    }
}