package com.platform.worker.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class JsonPathAssertionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonPathAssertion assertion = new JsonPathAssertion();

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            18 | 18 | true
            18 | 18.0 | true
            18 | "18" | false
            true | true | true
            true | 1 | false
            "hello" | "hello" | true
            "hello" | "HELLO" | false
            null | null | true
            null | 0 | false
            {"age":18,"name":"a"} | {"name":"a","age":18.0} | true
            {"age":18} | {"age":19} | false
            [1,2] | [1.0,2] | true
            [1,2] | [2,1] | false
            [] | {} | false
            9007199254740993 | 9007199254740992 | false
            0.123456789012345678901 | 0.123456789012345678902 | false
            """ )
    void equalsUsesJsonTypesDeepStructureAndExactNumericValue(String expected, String actual, boolean passed) throws Exception {
        // Parse expected with exact decimals too, avoiding test-fixture precision loss.
        JsonNode value = new ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(expected);
        var rule = new AssertionRule("r", "JSON_PATH", value, "EQUALS", "$.value", null, null);
        var result = assertion.evaluate(rule, response("{\"value\":" + actual + "}"));
        assertEquals(passed, result.passed());
        assertEquals("r", result.ruleId());
        assertEquals("JSON_PATH", result.type());
        assertNotNull(result.actual());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "false", "\"\"", "[]", "{}", "18"})
    void notNullDoesNotMeanTruthyOrNonEmpty(String value) throws Exception {
        assertTrue(assertion.evaluate(rule("NOT_NULL", "$.value", null), response("{\"value\":" + value + "}")).passed());
    }

    @Test
    void missingIsNotTheSameAsExplicitJsonNull() throws Exception {
        var equalsNull = rule("EQUALS", "$.value", "null");
        var found = assertion.evaluate(equalsNull, response("{\"value\":null}"));
        assertTrue(found.passed());
        assertTrue(found.actual().isNull());
        var missing = assertion.evaluate(equalsNull, response("{}"));
        assertFalse(missing.passed());
        assertNull(missing.actual());
        assertEquals("JSONPath did not resolve to a value", missing.message());
        assertFalse(assertion.evaluate(rule("NOT_NULL", "$.value", null), response("{}" )).passed());
        assertFalse(assertion.evaluate(rule("NOT_NULL", "$.value", null), response("{\"value\":null}")).passed());
        assertFalse(assertion.evaluate(rule("NOT_NULL", "$.value", "null"), response("{\"value\":null}")).passed());
        assertFalse(assertion.evaluate(rule("EQUALS", "$.items[5]", "null"), response("{\"items\":[null]}" )).passed());
        assertTrue(assertion.evaluate(rule("EQUALS", "$.items[0]", "null"), response("{\"items\":[null]}" )).passed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.users[0].age", "$['users'][0]['age']"})
    void supportsNestedObjectArrayAndBracketSelectors(String path) throws Exception {
        assertTrue(assertion.evaluate(rule("EQUALS", path, "18"), response("{\"users\":[{\"age\":18}]}" )).passed());
    }

    @Test
    void quotedPropertyContainingParenthesesIsNotAFunction() throws Exception {
        assertTrue(assertion.evaluate(rule("EQUALS", "$['latency(ms)']", "18"),
                response("{\"latency(ms)\":18}")).passed());
        assertTrue(assertion.evaluate(rule("EQUALS", "$.items[-1]", "18"),
                response("{\"items\":[1,18]}")).passed());
        assertFalse(assertion.evaluate(rule("EQUALS", "$.items[-5]", "null"),
                response("{\"items\":[null]}")).passed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.users[5]", "$.users[0].missing", "$.users[0].age.child"})
    void absentIndexOrWrongTraversalFailsWithoutThrowing(String path) throws Exception {
        var result = assertion.evaluate(rule("NOT_NULL", path, null), response("{\"users\":[{\"age\":18}]}"));
        assertFalse(result.passed());
        assertEquals("JSONPath did not resolve to a value", result.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "18", "true", "\"text\"", "[]", "{}"})
    void rootSelectorSupportsAnyJsonType(String body) throws Exception {
        assertTrue(assertion.evaluate(rule("EQUALS", "$", body), response(body)).passed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-json-secret", "{", "{\"value\":1} {}", "{\"value\":1,\"value\":2}", "{\"value\":NaN}"})
    void invalidJsonFailsSafelyIncludingTrailingTokensAndDuplicateKeys(String body) throws Exception {
        var result = assertion.evaluate(rule("NOT_NULL", "$.value", null), response(body));
        assertFalse(result.passed());
        assertNull(result.actual());
        assertEquals("Response body is not valid JSON", result.message());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "age", "$[", "$..age", "$.users[*].age", "$.users[?(@.age > 1)]", "$.users.length()"})
    void rejectsInvalidOrMultiValuePaths(String path) throws Exception {
        var rule = rule("NOT_NULL", path, null);
        var error = assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(rule, response("{}")));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("users"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "equals", "LESS_THAN", "GREATER_THAN_OR_EQUALS", "secret-marker"})
    void rejectsUnsupportedOperator(String operator) throws Exception {
        var rule = rule(operator, "$.age", "18");
        var error = assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(rule, response("{}")));
        assertEquals("JSON_PATH requires EQUALS, NOT_NULL, CONTAINS or GREATER_THAN", error.getMessage());
    }

    @Test
    void rejectsInvalidRuleShapesAndArguments() throws Exception {
        var valid = rule("EQUALS", "$.age", "18");
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(null, response("{}")));
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(valid, null));
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(rule("EQUALS", "$.age", null), response("{}")));
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(rule("NOT_NULL", "$.age", "18"), response("{}")));
        for (String id : new String[]{null, "", " "}) {
            var invalid = new AssertionRule(id, "JSON_PATH", valid.expected(), "EQUALS", "$.age", null, null);
            assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(invalid, response("{}")));
        }
        var wrongType = new AssertionRule("r", "STATUS_CODE", valid.expected(), "EQUALS", "$.age", null, null);
        var schema = new AssertionRule("r", "JSON_PATH", valid.expected(), "EQUALS", "$.age", mapper.readTree("{}"), null);
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(wrongType, response("{}")));
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(schema, response("{}")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void executionFailureIsNotAnAssertionResult(boolean knownStatus) throws Exception {
        var failed = new HttpResponseData(knownStatus ? 200 : null, null, null, 10, "secret-marker");
        var rule = rule("NOT_NULL", "$.age", null);
        var error = assertThrows(IllegalStateException.class, () -> assertion.evaluate(rule, failed));
        assertEquals("Cannot evaluate JSONPath after HTTP execution failure", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void contractFixtureAndRepeatedCallsKeepIndependentResults() throws Exception {
        var rule = mapper.readValue("""
                {"id":"rule-4","type":"JSON_PATH","jsonPath":"$.age","operator":"EQUALS",
                 "expected":18,"description":"Created user age must equal 18"}
                """, AssertionRule.class);
        var first = assertion.evaluate(rule, new HttpResponseData(400, null, "{\"age\":18}", 10, null));
        assertTrue(first.passed());
        assertFalse(assertion.evaluate(rule, response("{\"age\":19}")).passed());
        assertEquals(first, assertion.evaluate(rule, response("{\"age\":18}")));
        var json = mapper.readTree(mapper.writeValueAsString(first));
        assertEquals(6, json.size());
        assertEquals("rule-4", json.get("ruleId").textValue());
        assertEquals(18, json.get("actual").intValue());
    }

    private AssertionRule rule(String operator, String path, String expected) throws Exception {
        return new AssertionRule("r", "JSON_PATH", expected == null ? null : mapper.readTree(expected),
                operator, path, null, null);
    }

    private HttpResponseData response(String body) {
        return new HttpResponseData(200, null, body, 10, null);
    }
}