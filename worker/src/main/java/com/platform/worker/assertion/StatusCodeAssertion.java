package com.platform.worker.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.platform.worker.model.AssertionResult;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;

/** Pure equality check; execution errors must be handled by orchestration, not marked as assertion failures. */
public final class StatusCodeAssertion {
    public AssertionResult evaluate(AssertionRule rule, HttpResponseData response) {
        if (rule == null || rule.id() == null || rule.id().isBlank()) {
            throw new IllegalArgumentException("Assertion rule with a non-blank id is required");
        }
        if (!"STATUS_CODE".equals(rule.type())) {
            throw new IllegalArgumentException("STATUS_CODE rule is required");
        }
        if (rule.operator() != null || rule.jsonPath() != null || rule.expectedSchema() != null) {
            throw new IllegalArgumentException("STATUS_CODE does not support operator, jsonPath or expectedSchema");
        }
        JsonNode expected = rule.expected();
        if (expected == null || !expected.isIntegralNumber() || !expected.canConvertToInt()
                || expected.intValue() < 100 || expected.intValue() > 999) {
            throw new IllegalArgumentException("Expected status must be a three-digit JSON integer");
        }
        if (response == null) {
            throw new IllegalArgumentException("HTTP response is required");
        }
        if (response.error() != null || response.statusCode() == null) {
            throw new IllegalStateException("Cannot evaluate status code after HTTP execution failure");
        }
        int actual = response.statusCode();
        boolean passed = expected.intValue() == actual;
        String message = passed ? "Status code matched " + actual
                : "Expected HTTP status " + expected.intValue() + " but received " + actual;
        return new AssertionResult(rule.id(), rule.type(), passed, expected, IntNode.valueOf(actual), message);
    }
}