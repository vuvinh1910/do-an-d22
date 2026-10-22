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
    public static final int DEFAULT_MAX_TEXT_CHARACTERS = 1_000_000;
    private final int maxTextCharacters;

    public VariableResolver() {
        this(DEFAULT_MAX_TEXT_CHARACTERS);
    }

    /** Limit is UTF-16 code units per text value, not encoded HTTP bytes or a job-wide budget. */
    public VariableResolver(int maxTextCharacters) {
        if (maxTextCharacters < 1) {
            throw new IllegalArgumentException("Text limit must be positive");
        }
        this.maxTextCharacters = maxTextCharacters;
    }

    public TestCaseRequest resolve(TestCaseRequest request, RuntimeContext context) {
        if (request == null || context == null) {
            throw new IllegalArgumentException("Request template and runtime context are required");
        }
        var budget = new JsonBudget();
        Map<String, JsonNode> query = new LinkedHashMap<>();
        request.queryParams().forEach((key, value) -> query.put(key, json(value, context, budget, 0)));
        return new TestCaseRequest(request.method(), text(request.path(), context),
                strings(request.headers(), context), strings(request.pathParams(), context),
                query, json(request.body(), context, budget, 0));
    }

    public String text(String template, RuntimeContext context) {
        if (template == null || context == null) {
            throw new IllegalArgumentException("Text template and runtime context are required");
        }
        var matcher = VARIABLE.matcher(template);
        var result = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            appendLiteral(result, template, end, matcher.start());
            JsonNode value = context.require(matcher.group(1));
            if (!value.isValueNode() || value.isNull() || value.isBinary() || value.isPojo()) {
                throw new IllegalArgumentException("Text interpolation requires a non-null JSON scalar");
            }
            String replacement = value.asText();
            checkTextLength((long) result.length() + replacement.length());
            result.append(replacement);
            end = matcher.end();
        }
        appendLiteral(result, template, end, template.length());
        return result.toString();
    }

    private void appendLiteral(StringBuilder target, String source, int start, int end) {
        checkTextLength((long) target.length() + end - start);
        int open = source.indexOf("{{", start);
        int close = source.indexOf("}}", start);
        if ((open >= start && open + 2 <= end) || (close >= start && close + 2 <= end)) {
            throw new IllegalArgumentException("Malformed runtime variable template");
        }
        target.append(source, start, end);
    }

    private void checkTextLength(long length) {
        if (length > maxTextCharacters) {
            throw new IllegalArgumentException("Resolved text exceeds character limit");
        }
    }

    private Map<String, String> strings(Map<String, String> source, RuntimeContext context) {
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, text(value, context)));
        return result;
    }

    private JsonNode json(JsonNode node, RuntimeContext context, JsonBudget budget, int depth) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            var matcher = VARIABLE.matcher(node.textValue());
            if (matcher.matches()) {
                // require returns at most one validated 10,000-node snapshot. Charge every occurrence
                // before retaining it in the output, including its depth at the insertion point.
                var value = context.require(matcher.group(1));
                budget.tree(value, depth);
                return value;
            }
            budget.node(depth);
            return JsonNodeFactory.instance.textNode(text(node.textValue(), context));
        }
        budget.node(depth);
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> result.set(entry.getKey(),
                    json(entry.getValue(), context, budget, depth + 1)));
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> result.add(json(value, context, budget, depth + 1)));
            return result;
        }
        return node.deepCopy();
    }

    /** Per resolve call, shared across body and all query values; no shared mutable resolver state. */
    private final class JsonBudget {
        private int nodes;

        void node(int depth) {
            if (++nodes > 10000 || depth > 256) {
                throw new IllegalArgumentException("Resolved JSON exceeds structure limits");
            }
        }

        void tree(JsonNode value, int depth) {
            node(depth);
            if (value.isTextual()) {
                checkTextLength(value.textValue().length());
            }
            if (value.isContainerNode()) {
                for (JsonNode child : value) {
                    tree(child, depth + 1);
                }
            }
        }
    }
}