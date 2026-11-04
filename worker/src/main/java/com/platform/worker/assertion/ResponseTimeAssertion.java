package com.platform.worker.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.platform.worker.model.AssertionResult;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;

/** Strict millisecond limit over the runner's full-exchange measurement; no clock or I/O here. */
public final class ResponseTimeAssertion {
    public AssertionResult evaluate(AssertionRule rule, HttpResponseData response) {
        if (rule == null || rule.id() == null || rule.id().isBlank()) {
            throw new IllegalArgumentException("Assertion rule with a non-blank id is required");
        }
        if (!"RESPONSE_TIME".equals(rule.type())) {
            throw new IllegalArgumentException("RESPONSE_TIME rule is required");
        }
        if (!"LESS_THAN".equals(rule.operator())) {
            throw new IllegalArgumentException("RESPONSE_TIME requires the LESS_THAN operator");
        }
        if (rule.jsonPath() != null || rule.expectedSchema() != null) {
            throw new IllegalArgumentException("RESPONSE_TIME does not support jsonPath or expectedSchema");
        }
        JsonNode expected = rule.expected();
        if (expected == null || !expected.isIntegralNumber() || !expected.canConvertToLong()
                || expected.longValue() < 0) {
            throw new IllegalArgumentException("Expected response time must be a non-negative JSON integer within long range");
        }
        if (response == null) {
            throw new IllegalArgumentException("HTTP response is required");
        }
        if (response.error() != null || response.statusCode() == null) {
            throw new IllegalStateException("Cannot evaluate response time after HTTP execution failure");
        }
        long actual = response.responseTimeMs();
        long limit = expected.longValue();
        boolean passed = actual < limit;
        String message = "Response time " + actual + "ms "
                + (passed ? "is less than " : "is not less than ") + limit + "ms";
        return new AssertionResult(rule.id(), rule.type(), passed, expected, LongNode.valueOf(actual), message);
    }
}