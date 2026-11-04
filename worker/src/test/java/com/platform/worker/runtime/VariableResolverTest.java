package com.platform.worker.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.TestCaseRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariableResolverTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final VariableResolver resolver = new VariableResolver();

    @Test
    void textLimitChecksLiteralReplacementAndRepeatedExpansionAtExactBoundary() {
        var limited = new VariableResolver(4);
        var context = new RuntimeContext(Map.of("v", mapper.getNodeFactory().textNode("ab")));
        assertEquals("abcd", limited.text("abcd", context));
        assertEquals("abab", limited.text("{{v}}{{v}}", context));
        for (String text : java.util.List.of("abcde", "{{v}}{{v}}x", "x{{v}}{{v}}")) {
            var error = assertThrows(IllegalArgumentException.class, () -> limited.text(text, context));
            assertEquals("Resolved text exceeds character limit", error.getMessage());
            assertNull(error.getCause());
        }
        assertEquals("ab", limited.text("{{v}}", context));
        assertThrows(IllegalArgumentException.class, () -> new VariableResolver(0));
    }

    @Test
    void textLimitCountsUtf16AndChecksStringsInsideWholeVariableObjects() {
        var limited = new VariableResolver(4);
        var context = new RuntimeContext(Map.of("v", mapper.createObjectNode().put("secret", "12345")));
        var template = new TestCaseRequest("POST", "/", Map.of(), Map.of(), Map.of(),
                mapper.getNodeFactory().textNode("{{v}}"));
        assertThrows(IllegalArgumentException.class, () -> limited.resolve(template, context));
        assertEquals("😀😀", limited.text("😀😀", context));
        assertThrows(IllegalArgumentException.class, () -> limited.text("😀😀x", context));
    }

    @Test
    void expandedOccurrencesShareBudgetAcrossBodyAndQueryAndAcceptExactBoundary() {
        var array = mapper.createArrayNode();
        for (int i = 0; i < 4999; i++) {
            array.add(i);
        }
        var context = new RuntimeContext(Map.of("v", array));
        var placeholder = mapper.getNodeFactory().textNode("{{v}}");
        var exact = new TestCaseRequest("POST", "/", Map.of(), Map.of(), Map.of("q", placeholder), placeholder);
        var resolved = resolver.resolve(exact, context);
        assertEquals(4999, resolved.body().size());
        assertEquals(4999, resolved.queryParams().get("q").size());
        var over = new TestCaseRequest("POST", "/", Map.of(), Map.of(),
                Map.of("q", placeholder), mapper.createArrayNode().add("{{v}}"));
        var error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(over, context));
        assertEquals("Resolved JSON exceeds structure limits", error.getMessage());
        assertEquals("{{v}}", exact.body().asText());
    }

    @Test
    void repeatedLargeVariableIsRejectedWithoutRetainingAllExpandedBranches() {
        var array = mapper.createArrayNode();
        for (int i = 0; i < 9998; i++) {
            array.add(i);
        }
        var context = new RuntimeContext(Map.of("v", array));
        var body = mapper.createArrayNode();
        for (int i = 0; i < 9999; i++) {
            body.add("{{v}}");
        }
        var template = new TestCaseRequest("POST", "/", Map.of(), Map.of(), Map.of(), body);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(template, context));
        assertEquals(9998, context.require("v").size());
    }

    @Test
    void expandedDepthIncludesInsertionPointAndBudgetResetsAfterFailure() {
        var value = mapper.createArrayNode();
        var cursor = value;
        for (int i = 0; i < 255; i++) {
            cursor = cursor.addArray();
        }
        var context = new RuntimeContext(Map.of("v", value));
        var exact = new TestCaseRequest("POST", "/", Map.of(), Map.of(), Map.of(),
                mapper.createArrayNode().add("{{v}}"));
        assertNotNull(resolver.resolve(exact, context));
        var over = new TestCaseRequest("POST", "/", Map.of(), Map.of(), Map.of(),
                mapper.createArrayNode().add(mapper.createArrayNode().add("{{v}}")));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(over, context));
        assertNotNull(resolver.resolve(exact, context));
    }

    @Test
    void resolvesNestedValuesPreservingTypesAndTemplate() throws Exception {
        var values = mapper.readTree("""
                {"id":42,"flag":true,"obj":{"x":1},"arr":[1,2],"nil":null,"token":"secret"}
                """);
        var source = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        values.fields().forEachRemaining(entry -> source.put(entry.getKey(), entry.getValue()));
        var context = new RuntimeContext(source);
        var body = mapper.readTree("""
                {"id":"{{id}}","nested":["{{flag}}","{{obj}}","{{arr}}","{{nil}}"],"text":"x{{id}}"}
                """);
        var template = new TestCaseRequest("POST", "/users/{id}", Map.of("Authorization", "Bearer {{token}}"),
                Map.of("id", "{{id}}"), Map.of("ids", mapper.readTree("\"{{arr}}\"")), body);
        var result = resolver.resolve(template, context);
        assertEquals(mapper.readTree("""
                {"id":42,"nested":[true,{"x":1},[1,2],null],"text":"x42"}
                """), result.body());
        assertEquals("42", result.pathParams().get("id"));
        assertEquals("Bearer secret", result.headers().get("Authorization"));
        assertEquals(mapper.readTree("[1,2]"), result.queryParams().get("ids"));
        assertEquals(body, template.body());
        source.clear();
        assertEquals("42", resolver.text("{{id}}", context));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{missing}}", "{{bad", "bad}}", "{{}}"})
    void rejectsMissingOrMalformedWithoutEcho(String template) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> resolver.text(template, new RuntimeContext(Map.of())));
        assertFalse(error.getMessage().contains(template));
        assertNull(error.getCause());
    }

    @Test
    void doesNotRecursivelyInterpolateVariableValues() {
        var context = new RuntimeContext(Map.of("value", mapper.getNodeFactory().textNode("{{secret}}")));
        assertEquals("{{secret}}", resolver.text("{{value}}", context));
    }

    @Test
    void contextCopiesOnReadAndWriteAndRejectsInvalidUpdatesAtomically() throws Exception {
        var node = mapper.readTree("{\"x\":1}");
        var context = new RuntimeContext(Map.of("v", node));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("x", 2);
        ((com.fasterxml.jackson.databind.node.ObjectNode) context.require("v")).put("x", 3);
        assertEquals(1, context.require("v").get("x").intValue());
        var bad = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        bad.put("v", mapper.readTree("4"));
        bad.put("bad", null);
        assertThrows(IllegalArgumentException.class, () -> context.putAll(bad));
        assertEquals(1, context.require("v").get("x").intValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]"})
    void rejectsNonScalarTextInterpolation(String json) throws Exception {
        var context = new RuntimeContext(Map.of("v", mapper.readTree(json)));
        assertThrows(IllegalArgumentException.class, () -> resolver.text("x{{v}}", context));
    }

    @Test
    void contextRejectsUnsafeTreesAndUpdatesNothingOnValidationFailure() {
        var nodes = mapper.getNodeFactory();
        var cycle = nodes.arrayNode();
        cycle.add(cycle);
        var deep = nodes.arrayNode();
        var cursor = deep;
        for (int i = 0; i < 257; i++) {
            cursor = cursor.addArray();
        }
        var wide = nodes.arrayNode();
        for (int i = 0; i < 10000; i++) {
            wide.add(i);
        }
        for (var invalid : java.util.List.of(cycle, deep, wide, nodes.pojoNode(new Object()),
                nodes.binaryNode(new byte[]{1}), nodes.numberNode(Double.NaN), nodes.missingNode())) {
            var context = new RuntimeContext(Map.of("v", nodes.numberNode(1)));
            var additions = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            additions.put("v", nodes.numberNode(2));
            additions.put("bad", invalid);
            assertThrows(IllegalArgumentException.class, () -> context.putAll(additions));
            assertEquals(1, context.require("v").intValue());
            assertThrows(IllegalArgumentException.class, () -> context.require("bad"));
        }
    }
}