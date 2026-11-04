package com.platform.worker.assertion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HeaderAssertionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HeaderAssertion assertion = new HeaderAssertion();

    static Stream<Arguments> comparisons() {
        return Stream.of(
                Arguments.of("equals matches any entry", "EQUALS", "\"second\"", List.of("first", "second"), true),
                Arguments.of("equals mismatch", "EQUALS", "\"third\"", List.of("first", "second"), false),
                Arguments.of("case-sensitive values", "EQUALS", "\"SECOND\"", List.of("second"), false),
                Arguments.of("no trim", "EQUALS", "\"second\"", List.of(" second "), false),
                Arguments.of("content type substring", "CONTAINS", "\"application/json\"", List.of("application/json;charset=UTF-8"), true),
                Arguments.of("contains matches later entry", "CONTAINS", "\"son\"", List.of("text/plain", "application/json"), true),
                Arguments.of("contains mismatch", "CONTAINS", "\"xml\"", List.of("application/json"), false),
                Arguments.of("literal not regex", "CONTAINS", "\".*\"", List.of("application/json"), false),
                Arguments.of("empty substring", "CONTAINS", "\"\"", List.of("value"), true),
                Arguments.of("empty equals", "EQUALS", "\"\"", List.of(""), true),
                Arguments.of("empty substring requires existing value", "CONTAINS", "\"\"", List.of(), false),
                Arguments.of("not null accepts empty string", "NOT_NULL", null, List.of(""), true),
                Arguments.of("not null accepts explicit null expected", "NOT_NULL", "null", List.of("value"), true),
                Arguments.of("not null empty list", "NOT_NULL", null, List.of(), false),
                Arguments.of("no comma split", "EQUALS", "\"a\"", List.of("a, b"), false),
                Arguments.of("no comma join", "EQUALS", "\"a, b\"", List.of("a", "b"), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("comparisons")
    void evaluatesRawValuesWithAnyMatchSemantics(String name, String operator, String expected,
                                                List<String> values, boolean passed) throws Exception {
        var result = assertion.evaluate(rule("x-test", operator, expected), response(Map.of("X-Test", values)));
        assertEquals(passed, result.passed());
        assertEquals("rule-header", result.ruleId());
        assertEquals("HEADER", result.type());
        assertEquals(mapper.valueToTree(values), result.actual());
        assertEquals(expected == null ? null : mapper.readTree(expected), result.expected());
        assertEquals(values.isEmpty() ? "Header has no values" : passed
                ? "Header assertion passed" : "Header values did not satisfy assertion", result.message());
    }

    @Test
    void combinesCaseVariantsWithoutDependingOnMapOrderOrMatchingOtherNames() throws Exception {
        var headers = Map.of("X-Test", List.of("first"), "x-test", List.of("second"),
                "X-TEST", List.of("third"), "Other", List.of("secret"));
        var result = assertion.evaluate(rule("x-TeSt", "EQUALS", "\"second\""), response(headers));
        assertTrue(result.passed());
        assertEquals(3, result.actual().size());
        Set<String> selected = new java.util.HashSet<>();
        result.actual().forEach(value -> selected.add(value.textValue()));
        assertEquals(Set.of("first", "second", "third"), selected);
        assertFalse(assertion.evaluate(rule("absent", "EQUALS", "\"secret\""), response(headers)).passed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EQUALS", "CONTAINS", "NOT_NULL"})
    void missingHeaderFailsEvenWhenOtherHeaderWouldMatch(String operator) throws Exception {
        var result = assertion.evaluate(rule("missing", operator, "NOT_NULL".equals(operator) ? null : "\"value\""),
                response(Map.of("Other", List.of("value"))));
        assertFalse(result.passed());
        assertTrue(result.actual().isArray());
        assertTrue(result.actual().isEmpty());
    }

    @Test
    void setCookieValueIsPreservedIncludingExpiresComma() throws Exception {
        String cookie = "session=value; Expires=Wed, 21 Oct 2026 07:28:00 GMT; HttpOnly";
        var result = assertion.evaluate(rule("set-cookie", "EQUALS", mapper.writeValueAsString(cookie)),
                response(Map.of("Set-Cookie", List.of("other=1", cookie))));
        assertTrue(result.passed());
        assertEquals(cookie, result.actual().get(1).textValue());
        assertEquals(2, result.actual().size());
        // Returned mutable JSON cannot change the stored result.
        ((com.fasterxml.jackson.databind.node.ArrayNode) result.actual()).removeAll();
        assertEquals(2, result.actual().size());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", " Content-Type", "Content-Type ", "Bad:Name", "Bad\r\nName", "Tiếng-Việt", ":status", "Bad/Name"})
    void rejectsInvalidFieldNamesWithoutEchoingThem(String name) throws Exception {
        var error = assertThrows(IllegalArgumentException.class,
                () -> assertion.evaluate(rule(name, "NOT_NULL", null), response(Map.of())));
        assertEquals("HEADER requires a valid HTTP field name", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void acceptsAllHttpTokenCharactersInFieldName() throws Exception {
        String name = "!#$%&'*+-.^_`|~0123456789AZaz";
        assertTrue(assertion.evaluate(rule(name, "NOT_NULL", null), response(Map.of(name, List.of("")))).passed());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "equals", "GREATER_THAN", "secret-marker"})
    void requiresSupportedExplicitOperator(String operator) throws Exception {
        var error = assertThrows(IllegalArgumentException.class,
                () -> assertion.evaluate(rule("X-Test", operator, "\"value\""), response(Map.of())));
        assertEquals("HEADER requires EQUALS, CONTAINS or NOT_NULL", error.getMessage());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "18", "true", "[]", "{}"})
    void comparisonsRequireStringExpectedWithoutCoercion(String expected) throws Exception {
        for (String operator : List.of("EQUALS", "CONTAINS")) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> assertion.evaluate(rule("X-Test", operator, expected), response(Map.of())));
            assertEquals("HEADER comparison requires a JSON string as expected", error.getMessage());
        }
    }

    @Test
    void rejectsInvalidRuleShapesAndNullArguments() throws Exception {
        var valid = rule("X-Test", "NOT_NULL", null);
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(null, response(Map.of())));
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(valid, null));
        for (String id : new String[]{null, "", " "}) {
            var invalid = new AssertionRule(id, "HEADER", null, "NOT_NULL", null, null, null, "X-Test");
            assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(invalid, response(Map.of())));
        }
        var wrongType = new AssertionRule("r", "STATUS_CODE", null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(wrongType, response(Map.of())));
        for (String field : List.of("jsonPath", "expectedSchema")) {
            var invalid = new AssertionRule("r", "HEADER", null, "NOT_NULL",
                    field.equals("jsonPath") ? "$.value" : null,
                    field.equals("expectedSchema") ? mapper.readTree("{}") : null, null, "X-Test");
            assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(invalid, response(Map.of())));
        }
        assertThrows(IllegalArgumentException.class,
                () -> assertion.evaluate(rule("X-Test", "NOT_NULL", "\"value\""), response(Map.of())));
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 400, 401, 500})
    void completeResponsesAreEvaluatedIndependentlyOfStatusOrBody(int status) throws Exception {
        var response = new HttpResponseData(status, Map.of("X-Test", List.of("value")), "not JSON", 10, null);
        assertTrue(assertion.evaluate(rule("x-test", "EQUALS", "\"value\""), response).passed());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void executionErrorIsNeverAnAssertionResultEvenWhenHeaderMatches(boolean knownStatus) throws Exception {
        var failed = new HttpResponseData(knownStatus ? 200 : null,
                knownStatus ? Map.of("X-Test", List.of("value")) : null, null, 10, "secret-marker");
        var error = assertThrows(IllegalStateException.class,
                () -> assertion.evaluate(rule("X-Test", "NOT_NULL", null), failed));
        assertEquals("Cannot evaluate header after HTTP execution failure", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void readsAndWritesDocumentedContractAndKeepsCallsIndependent() throws Exception {
        var json = mapper.readTree("""
                {"id":"rule-header","type":"HEADER","headerName":"Content-Type","operator":"CONTAINS",
                 "expected":"application/json","description":"Response declares a JSON content type"}
                """);
        var rule = mapper.treeToValue(json, AssertionRule.class);
        assertEquals(json, mapper.valueToTree(rule));
        var response = response(Map.of("content-type", List.of("application/json;charset=UTF-8")));
        var first = assertion.evaluate(rule, response);
        assertEquals(mapper.readTree("""
                {"ruleId":"rule-header","type":"HEADER","passed":true,"expected":"application/json",
                 "actual":["application/json;charset=UTF-8"],"message":"Header assertion passed"}
                """), mapper.valueToTree(first));
        assertFalse(assertion.evaluate(rule, response(Map.of())).passed());
        assertEquals(first, assertion.evaluate(rule, response));
        var presence = assertion.evaluate(rule("X-Test", "NOT_NULL", null), response(Map.of()));
        assertTrue(mapper.valueToTree(presence).get("expected").isNull());
    }

    private AssertionRule rule(String name, String operator, String expected) throws Exception {
        return new AssertionRule("rule-header", "HEADER", expected == null ? null : mapper.readTree(expected),
                operator, null, null, null, name);
    }

    private HttpResponseData response(Map<String, List<String>> headers) {
        return new HttpResponseData(200, headers, "", 10, null);
    }
}