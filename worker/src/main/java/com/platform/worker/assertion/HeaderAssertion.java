package com.platform.worker.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.platform.worker.model.AssertionResult;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;

import java.util.regex.Pattern;

/** Case-insensitive field-name lookup, without splitting or joining raw field values. */
public final class HeaderAssertion {
    private static final Pattern FIELD_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    public AssertionResult evaluate(AssertionRule rule, HttpResponseData response) {
        if (rule == null || rule.id() == null || rule.id().isBlank()) {
            throw new IllegalArgumentException("Assertion rule with a non-blank id is required");
        }
        if (!"HEADER".equals(rule.type())) {
            throw new IllegalArgumentException("HEADER rule is required");
        }
        if (rule.headerName() == null || !FIELD_NAME.matcher(rule.headerName()).matches()) {
            throw new IllegalArgumentException("HEADER requires a valid HTTP field name");
        }
        if (rule.jsonPath() != null || rule.expectedSchema() != null) {
            throw new IllegalArgumentException("HEADER does not support jsonPath or expectedSchema");
        }
        boolean equals = "EQUALS".equals(rule.operator());
        boolean contains = "CONTAINS".equals(rule.operator());
        boolean notNull = "NOT_NULL".equals(rule.operator());
        if (!equals && !contains && !notNull) {
            throw new IllegalArgumentException("HEADER requires EQUALS, CONTAINS or NOT_NULL");
        }
        JsonNode expected = rule.expected();
        if (!notNull && (expected == null || !expected.isTextual())) {
            throw new IllegalArgumentException("HEADER comparison requires a JSON string as expected");
        }
        if (notNull && expected != null && !expected.isNull()) {
            throw new IllegalArgumentException("HEADER NOT_NULL does not accept a non-null expected value");
        }
        if (response == null) {
            throw new IllegalArgumentException("HTTP response is required");
        }
        if (response.error() != null || response.statusCode() == null) {
            throw new IllegalStateException("Cannot evaluate header after HTTP execution failure");
        }

        ArrayNode values = JsonNodeFactory.instance.arrayNode();
        response.headers().forEach((name, entries) -> {
            if (rule.headerName().equalsIgnoreCase(name)) {
                entries.forEach(values::add);
            }
        });
        boolean passed = notNull && !values.isEmpty();
        if (!notNull) {
            for (JsonNode value : values) {
                if (equals ? value.textValue().equals(expected.textValue())
                        : value.textValue().contains(expected.textValue())) {
                    passed = true;
                    break;
                }
            }
        }
        String message = values.isEmpty() ? "Header has no values"
                : passed ? "Header assertion passed" : "Header values did not satisfy assertion";
        return new AssertionResult(rule.id(), rule.type(), passed, expected, values, message);
    }
}