package com.platform.worker.assertion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.platform.worker.model.AssertionResult;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ResponseTimeAssertionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponseTimeAssertion assertion = new ResponseTimeAssertion();

    @ParameterizedTest(name = "limit={0}ms, actual={1}ms -> passed={2}")
    @CsvSource({"1500,142,true", "1500,1499,true", "1500,1500,false", "1500,1501,false",
            "0,0,false", "0,1,false", "1,0,true", "1,1,false"})
    void appliesStrictLessThanAtLatencyBoundaries(long limit, long actual, boolean passed) throws Exception {
        var result = assertion.evaluate(rule(Long.toString(limit)), response(200, actual));
        assertEquals(passed, result.passed());
        assertEquals("rule-time", result.ruleId());
        assertEquals("RESPONSE_TIME", result.type());
        assertTrue(result.expected().isIntegralNumber());
        assertTrue(result.actual().isIntegralNumber());
        assertEquals(limit, result.expected().longValue());
        assertEquals(actual, result.actual().longValue());
        assertEquals("Response time " + actual + "ms "
                + (passed ? "is less than " : "is not less than ") + limit + "ms", result.message());
    }

    @Test
    void doesNotNarrowToInt() throws Exception {
        long aboveIntMax = (long) Integer.MAX_VALUE + 1;
        var belowLimit = assertion.evaluate(rule(Long.toString(aboveIntMax)), response(200, Integer.MAX_VALUE));
        assertTrue(belowLimit.passed());
        assertEquals(aboveIntMax, belowLimit.expected().longValue());
        var atLimit = assertion.evaluate(rule(Long.toString(aboveIntMax)), response(200, aboveIntMax));
        assertFalse(atLimit.passed());
        assertEquals(aboveIntMax, atLimit.actual().longValue());
    }

    @Test
    void handlesLongMaxValueWithoutOverflow() throws Exception {
        // Type-range robustness, not a realistic HTTP latency.
        var maxLimit = rule(Long.toString(Long.MAX_VALUE));
        assertTrue(assertion.evaluate(maxLimit, response(200, Long.MAX_VALUE - 1)).passed());
        var atLimit = assertion.evaluate(maxLimit, response(200, Long.MAX_VALUE));
        assertFalse(atLimit.passed());
        assertEquals(Long.MAX_VALUE, atLimit.actual().longValue());
        assertFalse(assertion.evaluate(rule("1"), response(200, Long.MAX_VALUE)).passed());
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 400, 401, 500})
    void completeHttpResponsesAreTimedRegardlessOfStatusOrBody(int status) throws Exception {
        assertTrue(assertion.evaluate(rule("1500"), response(status, 142)).passed());
        assertFalse(assertion.evaluate(rule("1500"), response(status, 1500)).passed());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "\"1500\"", "1500.0", "1e3", "true", "[]", "{}", "-1",
            "9223372036854775808", "-9223372036854775809"})
    void rejectsInvalidOrMissingExpectedWithoutCoercion(String expected) throws Exception {
        var rule = rule(expected);
        var error = assertThrows(IllegalArgumentException.class,
                () -> assertion.evaluate(rule, response(200, 142)));
        assertEquals("Expected response time must be a non-negative JSON integer within long range", error.getMessage());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "less_than", " LESS_THAN", "LESS_THAN_OR_EQUALS", "EQUALS", "GREATER_THAN", "secret-marker"})
    void requiresExplicitContractOperator(String operator) throws Exception {
        var base = rule("1500");
        var rule = new AssertionRule(base.id(), base.type(), base.expected(), operator, null, null, null);
        var error = assertThrows(IllegalArgumentException.class,
                () -> assertion.evaluate(rule, response(200, 142)));
        assertEquals("RESPONSE_TIME requires the LESS_THAN operator", error.getMessage());
    }

    @Test
    void rejectsWrongRuleTypeNullArgumentsAndUnrelatedFields() throws Exception {
        var valid = rule("1500");
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(null, response(200, 1)));
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(valid, null));
        for (String id : new String[]{null, "", " "}) {
            var invalid = new AssertionRule(id, valid.type(), valid.expected(), "LESS_THAN", null, null, null);
            assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(invalid, response(200, 1)));
        }
        for (String type : new String[]{null, "STATUS_CODE", "response_time"}) {
            var invalid = new AssertionRule(valid.id(), type, valid.expected(), "LESS_THAN", null, null, null);
            assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(invalid, response(200, 1)));
        }
        var pathRule = new AssertionRule(valid.id(), valid.type(), valid.expected(), "LESS_THAN", "$.secret", null, null);
        var schemaRule = new AssertionRule(valid.id(), valid.type(), valid.expected(), "LESS_THAN", null, TextNode.valueOf("secret"), null);
        for (var invalid : new AssertionRule[]{pathRule, schemaRule}) {
            var error = assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(invalid, response(200, 1)));
            assertEquals("RESPONSE_TIME does not support jsonPath or expectedSchema", error.getMessage());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,1", "false,2000", "true,1", "true,2000"})
    void executionErrorNeverBecomesLatencyPassOrFailure(boolean knownStatus, long elapsed) throws Exception {
        var response = new HttpResponseData(knownStatus ? 200 : null, null, null, elapsed, "secret-marker");
        var rule = rule("1500");
        var error = assertThrows(IllegalStateException.class, () -> assertion.evaluate(rule, response));
        assertEquals("Cannot evaluate response time after HTTP execution failure", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void readsContractRuleAndSerializesContractResultWithoutAdditionalFields() throws Exception {
        var rule = mapper.readValue("""
                {"id":"rule-2","type":"RESPONSE_TIME","expected":1500,"operator":"LESS_THAN",
                 "description":"Response time should be under 1500ms"}
                """, AssertionRule.class);
        var result = assertion.evaluate(rule, response(201, 142));
        var json = mapper.readTree(mapper.writeValueAsString(result));
        assertEquals(mapper.readTree("""
                {"ruleId":"rule-2","type":"RESPONSE_TIME","passed":true,"expected":1500,"actual":142,
                 "message":"Response time 142ms is less than 1500ms"}
                """), json);
        // JSON integer width is not a wire-contract distinction.
        AssertionResult restored = mapper.treeToValue(json, AssertionResult.class);
        assertEquals(142, restored.actual().longValue());
        assertTrue(restored.passed());
        assertEquals(result, assertion.evaluate(rule, response(201, 142)));
    }

    private AssertionRule rule(String expected) throws Exception {
        return new AssertionRule("rule-time", "RESPONSE_TIME",
                expected == null ? null : mapper.readTree(expected), "LESS_THAN", null, null, null);
    }

    private HttpResponseData response(int status, long elapsed) {
        return new HttpResponseData(status, null, "", elapsed, null);
    }
}