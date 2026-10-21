package com.platform.worker.assertion;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class JsonPathComparisonTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final JsonPathAssertion assertion = new JsonPathAssertion();

    static Stream<Arguments> containsCases() {
        return Stream.of(
                Arguments.of("substring", "\"hello world\"", "\"world\"", true),
                Arguments.of("case sensitive", "\"Hello\"", "\"hello\"", false),
                Arguments.of("Unicode", "\"Xin chào Việt Nam\"", "\"Việt\"", true),
                Arguments.of("literal not regex", "\"abc\"", "\".*\"", false),
                Arguments.of("literal punctuation", "\"a.*b\"", "\".*\"", true),
                Arguments.of("empty substring", "\"abc\"", "\"\"", true),
                Arguments.of("empty strings", "\"\"", "\"\"", true),
                Arguments.of("not found", "\"\"", "\"a\"", false),
                Arguments.of("no string coercion", "\"18\"", "18", false),
                Arguments.of("numeric member", "[1,18,3]", "18.0", true),
                Arguments.of("typed member", "[\"18\"]", "18", false),
                Arguments.of("null member", "[null]", "null", true),
                Arguments.of("null absent", "[]", "null", false),
                Arguments.of("boolean member", "[false,true]", "false", true),
                Arguments.of("deep object member", "[{\"a\":1,\"b\":[2]}]", "{\"b\":[2.0],\"a\":1}", true),
                Arguments.of("not object subset", "[{\"a\":1,\"b\":2}]", "{\"a\":1}", false),
                Arguments.of("nested array member", "[[1,2],[3]]", "[1.0,2]", true),
                Arguments.of("array order matters", "[[1,2]]", "[2,1]", false),
                Arguments.of("not array subset", "[1,2]", "[1,2]", false),
                Arguments.of("no recursive search", "[[1,2]]", "1", false),
                Arguments.of("object not key containment", "{\"key\":1}", "\"key\"", false),
                Arguments.of("null container", "null", "null", false),
                Arguments.of("numeric container", "18", "18", false),
                Arguments.of("boolean container", "true", "true", false));
    }

    @ParameterizedTest(name = "CONTAINS: {0}")
    @MethodSource("containsCases")
    void containsUsesLiteralSubstringOrDeepArrayMembership(String name, String actual, String expected, boolean passed) throws Exception {
        assertComparison("CONTAINS", actual, expected, passed);
    }

    static Stream<Arguments> greaterThanCases() {
        String beyondLong = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toString();
        return Stream.of(
                Arguments.of("above", "19", "18", true),
                Arguments.of("equal", "18", "18", false),
                Arguments.of("below", "17", "18", false),
                Arguments.of("equal decimal", "18.0", "18", false),
                Arguments.of("negative values", "-1", "-2", true),
                Arguments.of("zero boundary", "0", "-1", true),
                Arguments.of("negative zero", "-0.0", "0", false),
                Arguments.of("exact fraction", "0.123456789012345678902", "0.123456789012345678901", true),
                Arguments.of("beyond long", beyondLong, Long.toString(Long.MAX_VALUE), true),
                Arguments.of("large exponent", "1e400", "1e399", true),
                Arguments.of("small exponent", "1e-400", "0", true),
                Arguments.of("numeric string", "\"19\"", "18", false),
                Arguments.of("null actual", "null", "18", false),
                Arguments.of("boolean actual", "true", "0", false),
                Arguments.of("array actual", "[19]", "18", false),
                Arguments.of("object actual", "{\"age\":19}", "18", false));
    }

    @ParameterizedTest(name = "GREATER_THAN: {0}")
    @MethodSource("greaterThanCases")
    void greaterThanUsesExactNumericComparisonWithoutCoercion(String name, String actual, String expected, boolean passed) throws Exception {
        assertComparison("GREATER_THAN", actual, expected, passed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"18\"", "true", "[]", "{}"})
    void greaterThanRejectsNonNumericExpected(String expected) throws Exception {
        var rule = rule("GREATER_THAN", "$.value", expected);
        var error = assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(rule, response("{}")));
        assertEquals("GREATER_THAN requires a finite JSON number as expected", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void greaterThanRejectsProgrammaticNonFiniteNumbers() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> {
                var rule = new AssertionRule("r", "JSON_PATH", DoubleNode.valueOf(value), "GREATER_THAN", "$", null, null);
                assertion.evaluate(rule, response("19"));
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTAINS", "GREATER_THAN"})
    void missingExpectedIsConfigurationError(String operator) {
        var rule = new AssertionRule("r", "JSON_PATH", null, operator, "$.value", null, null);
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(rule, response("{}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTAINS", "GREATER_THAN"})
    void newOperatorsPreserveMissingInvalidJsonAndTransportFailurePolicies(String operator) throws Exception {
        var rule = rule(operator, "$.value", "18");
        var missing = assertion.evaluate(rule, response("{}"));
        assertFalse(missing.passed());
        assertNull(missing.actual());
        assertEquals("JSONPath did not resolve to a value", missing.message());
        var invalid = assertion.evaluate(rule, response("secret-invalid-json"));
        assertFalse(invalid.passed());
        assertEquals("Response body is not valid JSON", invalid.message());
        for (Integer status : new Integer[]{null, 400}) {
            var failed = new HttpResponseData(status, null, null, 10, "secret-error");
            var error = assertThrows(IllegalStateException.class, () -> assertion.evaluate(rule, failed));
            assertEquals("Cannot evaluate JSONPath after HTTP execution failure", error.getMessage());
            assertNull(error.getCause());
        }
        for (String path : new String[]{"$[", "$.items[*]", "$.items.length()"}) {
            var invalidPath = rule(operator, path, "18");
            assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(invalidPath, response("{}")));
        }
    }

    private void assertComparison(String operator, String actual, String expected, boolean passed) throws Exception {
        var rule = rule(operator, "$.value", expected);
        // Complete error-status HTTP responses are valid assertion inputs too.
        var response = new HttpResponseData(400, null, "{\"value\":" + actual + "}", 10, null);
        var result = assertion.evaluate(rule, response);
        assertEquals(passed, result.passed());
        var json = mapper.readTree(mapper.writeValueAsString(result));
        assertEquals(6, json.size());
        assertEquals("r", json.get("ruleId").textValue());
        assertEquals("JSON_PATH", json.get("type").textValue());
        // DecimalNode may serialize 18.0 as 18; normalize both sides through the same wire round-trip.
        assertEquals(mapper.readTree(mapper.writeValueAsString(mapper.readTree(expected))), json.get("expected"));
        assertEquals(mapper.readTree(mapper.writeValueAsString(mapper.readTree(actual))), json.get("actual"));
        assertFalse(result.message().contains("$.value"));
        assertEquals(result, assertion.evaluate(rule, response));
    }

    private AssertionRule rule(String operator, String path, String expected) throws Exception {
        return new AssertionRule("r", "JSON_PATH", mapper.readTree(expected), operator, path, null, null);
    }

    private HttpResponseData response(String body) {
        return new HttpResponseData(200, null, body, 10, null);
    }
}