package com.platform.worker.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.platform.worker.model.TestCaseRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Single-pass interpolation: values are data, never recursively executed templates. */
public final class VariableResolver {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([^{}]+)}}");

    public TestCaseRequest resolve(TestCaseRequest request, RuntimeContext context) {
        if (request == null || context == null) {
            throw new IllegalArgumentException("Request template and runtime context are required");
        }
        Map<String, JsonNode> query = new LinkedHashMap<>();
        request.queryParams().forEach((key, value) -> query.put(key, json(value, context)));
        return new TestCaseRequest(request.method(), text(request.path(), context),
                strings(request.headers(), context), strings(request.pathParams(), context),
                query, json(request.body(), context));
    }

    public String text(String template, RuntimeContext context) {
        if (template == null || context == null) {
            throw new IllegalArgumentException("Text template and runtime context are required");
        }
        var matcher = VARIABLE.matcher(template);
        var result = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            appendLiteral(result, template.substring(end, matcher.start()));
            JsonNode value = context.require(matcher.group(1));
            if (!value.isValueNode() || value.isNull() || value.isBinary() || value.isPojo()) {
                throw new IllegalArgumentException("Text interpolation requires a non-null JSON scalar");
            }
            result.append(value.asText());
            end = matcher.end();
        }
        appendLiteral(result, template.substring(end));
        return result.toString();
    }

    private void appendLiteral(StringBuilder target, String literal) {
        if (literal.contains("{{") || literal.contains("}}")) {
            throw new IllegalArgumentException("Malformed runtime variable template");
        }
        target.append(literal);
    }

    private Map<String, String> strings(Map<String, String> source, RuntimeContext context) {
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, text(value, context)));
        return result;
    }

    private JsonNode json(JsonNode node, RuntimeContext context) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            var matcher = VARIABLE.matcher(node.textValue());
            return matcher.matches() ? context.require(matcher.group(1))
                    : JsonNodeFactory.instance.textNode(text(node.textValue(), context));
        }
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> result.set(entry.getKey(), json(entry.getValue(), context)));
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> result.add(json(value, context)));
            return result;
        }
        return node.deepCopy();
    }
}