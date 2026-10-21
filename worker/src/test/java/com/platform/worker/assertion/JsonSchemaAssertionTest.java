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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class JsonSchemaAssertionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaAssertion assertion = new JsonSchemaAssertion();

    static Stream<Arguments> validations() {
        return Stream.of(
                Arguments.of("required present", "{\"type\":\"object\",\"required\":[\"id\"]}", "{\"id\":1}", true),
                Arguments.of("required missing", "{\"required\":[\"id\"]}", "{}", false),
                Arguments.of("no string coercion", "{\"type\":\"integer\"}", "\"18\"", false),
                Arguments.of("integral decimal", "{\"type\":\"integer\"}", "18.0", true),
                Arguments.of("fraction not integer", "{\"type\":\"integer\"}", "18.5", false),
                Arguments.of("enum match", "{\"enum\":[\"MEMBER\",\"ADMIN\"]}", "\"MEMBER\"", true),
                Arguments.of("enum mismatch", "{\"enum\":[\"MEMBER\"]}", "\"member\"", false),
                Arguments.of("minimum inclusive", "{\"minimum\":18}", "18", true),
                Arguments.of("below minimum", "{\"minimum\":18}", "17", false),
                Arguments.of("exclusive minimum", "{\"exclusiveMinimum\":18}", "18", false),
                Arguments.of("maximum inclusive", "{\"maximum\":20}", "20", true),
                Arguments.of("above maximum", "{\"maximum\":20}", "21", false),
                Arguments.of("nested type", "{\"properties\":{\"user\":{\"properties\":{\"age\":{\"type\":\"integer\"}}}}}", "{\"user\":{\"age\":\"18\"}}", false),
                Arguments.of("array item types", "{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}", "[1,2]", true),
                Arguments.of("bad array item", "{\"items\":{\"type\":\"integer\"}}", "[1,\"2\"]", false),
                Arguments.of("tuple", "{\"items\":[{\"type\":\"string\"}],\"additionalItems\":false}", "[\"a\",2]", false),
                Arguments.of("additional properties", "{\"properties\":{\"id\":{}},\"additionalProperties\":false}", "{\"id\":1,\"extra\":2}", false),
                Arguments.of("explicit null", "{\"type\":[\"string\",\"null\"]}", "null", true),
                Arguments.of("null mismatch", "{\"type\":\"string\"}", "null", false),
                Arguments.of("empty schema", "{}", "42", true),
                Arguments.of("true schema", "true", "null", true),
                Arguments.of("false schema", "false", "{}", false),
                Arguments.of("pattern", "{\"pattern\":\"^[A-Z]+$\"}", "\"abc\"", false),
                Arguments.of("string bounds", "{\"minLength\":2,\"maxLength\":3}", "\"ab\"", true),
                Arguments.of("allOf", "{\"allOf\":[{\"minimum\":1},{\"maximum\":3}]}", "4", false),
                Arguments.of("anyOf", "{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}", "2", true),
                Arguments.of("oneOf", "{\"oneOf\":[{\"type\":\"number\"},{\"type\":\"integer\"}]}", "2", false),
                Arguments.of("if then", "{\"if\":{\"type\":\"number\"},\"then\":{\"minimum\":10}}", "2", false),
                Arguments.of("const", "{\"const\":{\"id\":18}}", "{\"id\":18.0}", true),
                Arguments.of("nested enum numeric equality", "{\"enum\":[[18,{\"n\":1.5}]]}", "[18.0,{\"n\":1.50}]", true),
                Arguments.of("unique numeric items", "{\"uniqueItems\":true}", "[18,18.0]", false),
                Arguments.of("exact large integer", "{\"const\":{\"id\":9007199254740993}}", "{\"id\":9007199254740993.0}", true),
                Arguments.of("distinct large integer", "{\"const\":{\"id\":9007199254740993}}", "{\"id\":9007199254740992}", false),
                Arguments.of("decimal multiple", "{\"multipleOf\":0.1}", "0.3", true),
                Arguments.of("not decimal multiple", "{\"multipleOf\":0.1}", "0.31", false),
                Arguments.of("precise minimum", "{\"minimum\":9007199254740993}", "9007199254740992", false),
                Arguments.of("nested unique objects", "{\"uniqueItems\":true}", "[{\"n\":1},{\"n\":1.0}]", false),
                Arguments.of("schema dependency", "{\"dependencies\":{\"a\":{\"required\":[\"b\"]}}}", "{\"a\":1}", false),
                Arguments.of("property dependency", "{\"dependencies\":{\"a\":[\"b\"]}}", "{\"a\":1}", false),
                Arguments.of("uuid format valid", "{\"format\":\"uuid\"}", "\"c71a3962-42da-4fba-bb90-e555191f4211\"", true),
                Arguments.of("uuid format invalid", "{\"format\":\"uuid\"}", "\"not-uuid\"", false),
                Arguments.of("annotations are data", "{\"default\":{\"$ref\":\"https://example.invalid\"}}", "{}", true),
                Arguments.of("property named nullable", "{\"properties\":{\"nullable\":{\"type\":\"string\"}}}", "{\"nullable\":\"yes\"}", true),
                Arguments.of("explicit draft", "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}", "{}", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validations")
    void evaluatesDraftSeven(String name, String schema, String body, boolean passed) throws Exception {
        var result = assertion.evaluate(rule(schema), response(body));
        assertEquals(passed, result.passed());
        assertEquals("schema-rule", result.ruleId());
        assertEquals("JSON_SCHEMA", result.type());
        assertEquals("Response body conforms to supplied Draft 7 schema", result.expected().textValue());
        assertEquals(passed ? "valid" : "invalid", result.actual().textValue());
        assertEquals(passed ? "Schema validation passed" : "Schema validation failed", result.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "not JSON", "{} {}", "{\"id\":1,\"id\":2}", "[1,]"})
    void malformedJsonFailsEvenAgainstTrueSchemaWithoutRawDiagnostics(String body) throws Exception {
        var result = assertion.evaluate(rule("true"), response(body));
        assertFalse(result.passed());
        assertEquals("invalid", result.actual().textValue());
        assertEquals("Response body is not valid JSON", result.message());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "[]", "18", "\"object\"", "{\"type\":\"invalid-type\"}",
            "{\"required\":\"id\"}", "{\"required\":[\"id\",\"id\"]}", "{\"properties\":[]}",
            "{\"minimum\":\"18\"}", "{\"exclusiveMinimum\":true}", "{\"minLength\":-1}",
            "{\"items\":18}", "{\"enum\":[]}", "{\"pattern\":\"[\"}",
            "{\"nullable\":true}", "{\"typo\":true}", "{\"$defs\":{}}",
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}",
            "{\"$schema\":\"http://json-schema.org/draft-04/schema#\"}",
            "{\"$schema\":\"https://example.invalid/secret-marker\"}",
            "{\"$id\":\"https://example.invalid/secret-marker\"}",
            "{\"properties\":{\"id\":{\"type\":\"invalid\"}}}",
            "{\"properties\":{\"id\":{\"nullable\":true}}}",
            "{\"definitions\":{\"unused\":{\"pattern\":\"[\"}}}",
            "{\"properties\":{\"id\":{\"$schema\":\"http://json-schema.org/draft-07/schema#\"}}}"})
    void invalidOrUnsupportedSchemaIsConfigurationErrorEvenWhenBodyIsMalformed(String schema) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> assertion.evaluate(rule(schema), response("not JSON secret-marker")));
        assertEquals("Invalid or unsupported Draft 7 expectedSchema", error.getMessage());
        assertNull(error.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.invalid/secret-marker", "http://127.0.0.1:1/schema",
            "file:///C:/secret-marker", "classpath:secret-marker", "relative.json", "#", "#anchor",
            "#/missing", "#/definitions/a~2b", "#/definitions/a%20b"})
    void rejectsUnsupportedReferences(String ref) throws Exception {
        var schema = mapper.createObjectNode().put("$ref", ref);
        var error = assertThrows(IllegalArgumentException.class,
                () -> assertion.evaluate(rule(schema.toString()), response("{}")));
        assertEquals("Invalid or unsupported Draft 7 expectedSchema", error.getMessage());
    }

    @Test
    void localReferenceWorksAndRepeatedUseDoesNotLeakState() throws Exception {
        var rule = rule("""
                {"definitions":{"a/b~c":{"type":"integer","minimum":18}},
                 "type":"object","properties":{"age":{"$ref":"#/definitions/a~1b~0c"}}}
                """);
        assertTrue(assertion.evaluate(rule, response("{\"age\":18}")).passed());
        assertFalse(assertion.evaluate(rule, response("{\"age\":17}")).passed());
        assertFalse(assertion.evaluate(rule, response("{\"age\":\"18\"}")).passed());
        assertTrue(assertion.evaluate(rule, response("{\"age\":18}")).passed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"definitions\":{\"a\":{\"$ref\":\"#/definitions/a\"}},\"$ref\":\"#/definitions/a\"}",
            "{\"definitions\":{\"a\":{\"$ref\":\"#/definitions/b\"},\"b\":{\"$ref\":\"#/definitions/a\"}}}",
            "{\"definitions\":{\"a\":true},\"$ref\":\"#/definitions/a\",\"type\":\"string\"}",
            "{\"definitions\":{\"unused\":{\"$ref\":\"https://example.invalid/schema\"}}}",
            "{\"default\":{\"type\":\"invalid\"},\"$ref\":\"#/default\"}"
    })
    void rejectsCyclicReferencesIgnoredRefSiblingsAndUnusedExternalReferences(String schema) {
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(rule(schema), response("{}")));
    }

    @Test
    void executionErrorsDoNotProduceAssertionResults() throws Exception {
        var rule = rule("true");
        for (var failed : List.of(
                new HttpResponseData(null, null, null, 10, "secret-marker"),
                new HttpResponseData(200, Map.of(), "{}", 10, "secret-marker"))) {
            var error = assertThrows(IllegalStateException.class, () -> assertion.evaluate(rule, failed));
            assertEquals("Cannot evaluate schema after HTTP execution failure", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 500})
    void completeResponsesAreEvaluatedRegardlessOfHttpStatusOrContentType(int status) throws Exception {
        assertTrue(assertion.evaluate(rule("{\"type\":\"object\"}"),
                new HttpResponseData(status, Map.of("Content-Type", List.of("text/plain")), "{}", 1, null)).passed());
    }

    @Test
    void rejectsInvalidRuleShapeAndNullArguments() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(null, response("{}")));
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(rule("{}"), null));
        for (String id : new String[]{null, "", " "}) {
            var invalid = new AssertionRule(id, "JSON_SCHEMA", null, null, null, mapper.readTree("{}"), null);
            assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(invalid, response("{}")));
        }
        assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(
                new AssertionRule("r", "JSON_PATH", null, null, null, mapper.readTree("{}"), null), response("{}")));
        for (String field : List.of("expected", "operator", "jsonPath")) {
            var invalid = new AssertionRule("r", "JSON_SCHEMA", field.equals("expected") ? mapper.nullNode() : null,
                    field.equals("operator") ? "EQUALS" : null, field.equals("jsonPath") ? "$" : null,
                    mapper.readTree("{}"), null);
            assertThrows(IllegalArgumentException.class, () -> assertion.evaluate(invalid, response("{}")));
        }
    }

    @Test
    void schemaAndResultRoundTripUsesExistingContractFields() throws Exception {
        var json = mapper.readTree("""
                {"id":"schema-rule","type":"JSON_SCHEMA","expectedSchema":{"type":"object"}}
                """);
        var rule = mapper.treeToValue(json, AssertionRule.class);
        assertEquals(json, mapper.valueToTree(rule));
        var result = assertion.evaluate(rule, response("{}"));
        assertEquals(mapper.readTree("""
                {"ruleId":"schema-rule","type":"JSON_SCHEMA","passed":true,
                 "expected":"Response body conforms to supplied Draft 7 schema","actual":"valid",
                 "message":"Schema validation passed"}
                """), mapper.valueToTree(result));
    }

    @Test
    void rejectsSchemaNestingBeyondSupportedLimit() throws Exception {
        var schema = mapper.createObjectNode();
        var cursor = schema;
        for (int index = 0; index < 66; index++) {
            cursor = cursor.putObject("properties").putObject("child");
        }
        assertThrows(IllegalArgumentException.class,
                () -> assertion.evaluate(rule(schema.toString()), response("{}")));
    }

    @Test
    void rejectsOversizedSchemaBeforeRecursiveValidation() throws Exception {
        var schema = mapper.createObjectNode();
        var values = schema.putArray("examples");
        for (int index = 0; index < 10001; index++) {
            values.add(index);
        }
        assertThrows(IllegalArgumentException.class,
                () -> assertion.evaluate(rule(schema.toString()), response("{}")));
    }

    @Test
    void rejectsDeepAnnotationDataBeforeRecursiveValidation() throws Exception {
        var schema = mapper.createObjectNode();
        var cursor = schema.putObject("default");
        for (int index = 0; index < 260; index++) {
            cursor = cursor.putObject("child");
        }
        assertThrows(IllegalArgumentException.class,
                () -> assertion.evaluate(rule(schema.toString()), response("{}")));
    }

    @Test
    void hugePositiveExponentDoesNotExpandIntoUnboundedInteger() throws Exception {
        var error = assertThrows(IllegalStateException.class,
                () -> assertion.evaluate(rule("true"), response("1e1000000000")));
        assertEquals("JSON Schema evaluation could not complete", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void oversizedResponseIsEvaluationErrorNotSchemaMismatch() throws Exception {
        var body = mapper.createArrayNode();
        for (int index = 0; index < 10000; index++) {
            body.add(index);
        }
        var rule = rule("true");
        var error = assertThrows(IllegalStateException.class,
                () -> assertion.evaluate(rule, response(body.toString())));
        assertEquals("JSON Schema evaluation could not complete", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void deepResponseIsRejectedBeforeRecursiveNormalization() throws Exception {
        String body = "[".repeat(300) + "0" + "]".repeat(300);
        var rule = rule("true");
        assertThrows(IllegalStateException.class, () -> assertion.evaluate(rule, response(body)));
    }

    @Test
    void responseAtExactNodeBudgetStillValidates() throws Exception {
        var body = mapper.createArrayNode();
        for (int index = 0; index < 9999; index++) {
            body.add(index);
        }
        assertTrue(assertion.evaluate(rule("true"), response(body.toString())).passed());
    }

    private AssertionRule rule(String schema) throws Exception {
        return new AssertionRule("schema-rule", "JSON_SCHEMA", null, null, null,
                schema == null ? null : mapper.readTree(schema), null);
    }

    private HttpResponseData response(String body) {
        return new HttpResponseData(200, Map.of(), body, 10, null);
    }
}