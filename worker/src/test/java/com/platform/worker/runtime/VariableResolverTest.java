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
}