package com.platform.worker.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AssertionModelTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void headerNameIsNotSilentlyIgnoredForOtherRuleTypes() throws Exception {
        for (String type : new String[]{"STATUS_CODE", "RESPONSE_TIME", "JSON_PATH", "JSON_SCHEMA"}) {
            assertThrows(IllegalArgumentException.class, () -> new AssertionRule("r", type, null,
                    null, null, null, null, "X-Test"));
            String json = "{\"id\":\"r\",\"type\":\"" + type + "\",\"headerName\":\"X-Test\"}";
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(json, AssertionRule.class));
        }
        // Existing JSON and seven-argument construction remain compatible.
        var old = new AssertionRule("r", "STATUS_CODE", mapper.readTree("200"), null, null, null, null);
        assertNull(old.headerName());
        assertFalse(mapper.valueToTree(old).has("headerName"));
        assertEquals(old, mapper.readValue(mapper.writeValueAsString(old), AssertionRule.class));
    }

    @Test
    void statusRuleUsesExactContractFieldsAndOmitsAbsentOptions() throws Exception {
        var json = mapper.readTree("""
                {"id":"rule-1","type":"STATUS_CODE","expected":201,"description":"Expected HTTP 201 Created"}
                """);
        var rule = mapper.treeToValue(json, AssertionRule.class);
        assertEquals(json, mapper.valueToTree(rule));
        assertNull(rule.operator());
        assertNull(rule.expectedSchema());
        assertFalse(mapper.valueToTree(rule).has("ruleId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":\"r\",\"type\":\"RESPONSE_TIME\",\"expected\":1500,\"operator\":\"LESS_THAN\"}",
            "{\"id\":\"r\",\"type\":\"JSON_PATH\",\"jsonPath\":\"$.age\",\"operator\":\"EQUALS\",\"expected\":18}",
            "{\"id\":\"r\",\"type\":\"JSON_SCHEMA\",\"expectedSchema\":{\"type\":\"object\",\"required\":[\"id\"]}}"})
    void preservesOtherContractRuleShapesWithoutImplementingTheirEvaluation(String json) throws Exception {
        var expected = mapper.readTree(json);
        assertEquals(expected, mapper.valueToTree(mapper.treeToValue(expected, AssertionRule.class)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[1,true,null]", "\"text\"", "42", "1.5", "true", "null"})
    void resultRoundTripPreservesJsonValueTypesAndContractNames(String value) throws Exception {
        var json = mapper.readTree("{\"ruleId\":\"r\",\"type\":\"JSON_PATH\",\"passed\":false,\"expected\":"
                + value + ",\"actual\":" + value + ",\"message\":\"Mismatch\"}");
        var result = mapper.treeToValue(json, AssertionResult.class);
        assertEquals(json, mapper.valueToTree(result));
        assertFalse(result.passed());
        assertFalse(mapper.valueToTree(result).has("id"));
    }

    @Test
    void rulesAndResultsOwnMutableJsonOnConstructionAndAccess() throws Exception {
        ObjectNode expected = (ObjectNode) mapper.readTree("{\"nested\":{\"value\":1}}");
        ObjectNode schema = (ObjectNode) mapper.readTree("{\"properties\":{\"id\":{\"type\":\"string\"}}}");
        var rule = new AssertionRule("r", "JSON_SCHEMA", expected, null, null, schema, null);
        var result = new AssertionResult("r", "JSON_PATH", false, expected, expected, "Mismatch");
        ((ObjectNode) expected.get("nested")).put("value", 2);
        schema.removeAll();
        ((ObjectNode) rule.expected().get("nested")).put("value", 3);
        ((ObjectNode) rule.expectedSchema()).removeAll();
        ((ObjectNode) result.expected()).removeAll();
        ((ObjectNode) result.actual()).removeAll();
        assertEquals(1, rule.expected().at("/nested/value").intValue());
        assertEquals("string", rule.expectedSchema().at("/properties/id/type").textValue());
        assertEquals(1, result.expected().at("/nested/value").intValue());
        assertEquals(1, result.actual().at("/nested/value").intValue());
    }
}