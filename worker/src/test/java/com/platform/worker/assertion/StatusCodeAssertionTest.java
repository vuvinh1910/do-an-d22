package com.platform.worker.assertion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StatusCodeAssertionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StatusCodeAssertion assertion = new StatusCodeAssertion();

    @ParameterizedTest
    @ValueSource(ints = {100, 200, 201, 302, 400, 401, 403, 500, 999})
    void matchingStatusPassesIncludingNegativeAndAuthTests(int status) {
        var result = assertion.evaluate(rule(Integer.toString(status)), response(status));
        assertTrue(result.passed());
        assertEquals("rule-1", result.ruleId());
        assertEquals("STATUS_CODE", result.type());
        assertEquals(status, result.expected().intValue());
        assertEquals(status, result.actual().intValue());
        assertEquals("Status code matched " + status, result.message());
    }

    @Test
    void mismatchReturnsFailureNotException() {
        var result = assertion.evaluate(rule("400"), response(500));
        assertFalse(result.passed());
        assertEquals(400, result.expected().intValue());
        assertEquals(500, result.actual().intValue());
        assertEquals("Expected HTTP status 400 but received 500", result.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"400\"", "400.0", "true", "[]", "{}", "99", "1000", "-1", "999999999999999999999"})
    void rejectsInvalidExpectedWithoutCoercion(String expected) {
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(rule(expected), response(400)));
    }

    @Test
    void rejectsMissingExpectedAndUnsupportedRuleFields() {
        for (String json : List.of(
                "{\"id\":\"rule-1\",\"type\":\"STATUS_CODE\"}",
                "{\"id\":\"rule-1\",\"type\":\"HEADER\",\"expected\":400}",
                "{\"id\":\"rule-1\",\"type\":\"STATUS_CODE\",\"expected\":400,\"operator\":\"EQUALS\"}",
                "{\"id\":\"rule-1\",\"type\":\"STATUS_CODE\",\"expected\":400,\"jsonPath\":\"$.secret\"}",
                "{\"id\":\"rule-1\",\"type\":\"STATUS_CODE\",\"expected\":400,\"expectedSchema\":{}}",
                "{\"type\":\"STATUS_CODE\",\"expected\":400}",
                "{\"id\":\" \",\"type\":\"STATUS_CODE\",\"expected\":400}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> assertion.evaluate(mapper.readValue(json, AssertionRule.class), response(400)));
        }
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(null, response(200)));
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(rule("200"), null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void executionFailureNeverBecomesFalsePassOrAssertionMismatch(boolean knownStatus) {
        var failed = new HttpResponseData(knownStatus ? 400 : null, null, null, 10, "sensitive-error-marker");
        var error = assertThrows(IllegalStateException.class, () -> assertion.evaluate(rule("400"), failed));
        assertEquals("Cannot evaluate status code after HTTP execution failure", error.getMessage());
        assertNull(error.getCause());
    }

    private AssertionRule rule(String expected) {
        try {
            return mapper.readValue("{\"id\":\"rule-1\",\"type\":\"STATUS_CODE\",\"expected\":" + expected + "}", AssertionRule.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private HttpResponseData response(int status) {
        return new HttpResponseData(status, Map.of(), "not JSON", 10, null);
    }
}