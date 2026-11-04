package com.platform.worker.assertion;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.platform.worker.model.AssertionResult;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;

/** Single-value JSONPath assertions. No global Jayway defaults or exception suppression. */
public final class JsonPathAssertion {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Configuration configuration = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider(mapper) {
                @Override
                public Object getArrayIndex(Object array, int index) {
                    // Prevent Jayway from converting an absent array element to NullNode later.
                    if (index < 0 || index >= length(array)) {
                        throw new PathNotFoundException("Array index did not resolve to a value");
                    }
                    return super.getArrayIndex(array, index);
                }
            }).build();

    public AssertionResult evaluate(AssertionRule rule, HttpResponseData response) {
        if (rule == null || rule.id() == null || rule.id().isBlank()) {
            throw new IllegalArgumentException("Assertion rule with a non-blank id is required");
        }
        if (!"JSON_PATH".equals(rule.type())) {
            throw new IllegalArgumentException("JSON_PATH rule is required");
        }
        boolean equals = "EQUALS".equals(rule.operator());
        boolean notNull = "NOT_NULL".equals(rule.operator());
        boolean contains = "CONTAINS".equals(rule.operator());
        boolean greaterThan = "GREATER_THAN".equals(rule.operator());
        if (!equals && !notNull && !contains && !greaterThan) {
            throw new IllegalArgumentException("JSON_PATH requires EQUALS, NOT_NULL, CONTAINS or GREATER_THAN");
        }
        JsonNode expected = rule.expected();
        if (equals && expected == null) {
            throw new IllegalArgumentException("EQUALS requires expected, including explicit JSON null");
        }
        if (notNull && expected != null && !expected.isNull()) {
            throw new IllegalArgumentException("NOT_NULL does not accept a non-null expected value");
        }
        if (contains && expected == null) {
            throw new IllegalArgumentException("CONTAINS requires expected, including explicit JSON null");
        }
        if (greaterThan && !isFiniteNumber(expected)) {
            throw new IllegalArgumentException("GREATER_THAN requires a finite JSON number as expected");
        }
        if (rule.expectedSchema() != null) {
            throw new IllegalArgumentException("JSON_PATH does not support expectedSchema");
        }
        JsonPath path = compile(rule.jsonPath());
        if (response == null) {
            throw new IllegalArgumentException("HTTP response is required");
        }
        if (response.error() != null || response.statusCode() == null) {
            throw new IllegalStateException("Cannot evaluate JSONPath after HTTP execution failure");
        }
        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (JsonProcessingException error) {
            return result(rule, false, null, "Response body is not valid JSON");
        }
        if (body == null || body.isMissingNode()) {
            return result(rule, false, null, "Response body is not valid JSON");
        }
        JsonNode actual;
        try {
            Object selected = path.read(body, configuration);
            // Jackson provider returns Java null for an out-of-range array index, but NullNode for JSON null.
            if (selected == null || selected instanceof JsonNode node && node.isMissingNode()) {
                return result(rule, false, null, "JSONPath did not resolve to a value");
            }
            if (!(selected instanceof JsonNode)) {
                throw new IllegalArgumentException("JSONPath must select a JSON value, not a function result");
            }
            actual = (JsonNode) selected;
        } catch (PathNotFoundException error) {
            return result(rule, false, null, "JSONPath did not resolve to a value");
        }
        if (contains) {
            return contains(rule, expected, actual);
        }
        if (greaterThan) {
            if (!actual.isNumber()) {
                return result(rule, false, actual, "GREATER_THAN requires a numeric JSONPath value");
            }
            boolean passed = actual.decimalValue().compareTo(expected.decimalValue()) > 0;
            return result(rule, passed, actual, passed
                    ? "JSONPath value is greater than expected" : "JSONPath value is not greater than expected");
        }
        boolean passed = equals ? expected.equals(JsonPathAssertion::compareValues, actual) : !actual.isNull();
        return result(rule, passed, actual, equals
                ? (passed ? "JSONPath value matched expected" : "JSONPath value did not match expected")
                : (passed ? "JSONPath value is not null" : "JSONPath value is null"));
    }

    private AssertionResult contains(AssertionRule rule, JsonNode expected, JsonNode actual) {
        boolean passed = false;
        if (actual.isTextual()) {
            if (!expected.isTextual()) {
                return result(rule, false, actual, "String CONTAINS requires a string expected value");
            }
            passed = actual.textValue().contains(expected.textValue());
        } else if (actual.isArray()) {
            for (JsonNode element : actual) {
                if (expected.equals(JsonPathAssertion::compareValues, element)) {
                    passed = true;
                    break;
                }
            }
        } else {
            return result(rule, false, actual, "CONTAINS requires a string or array JSONPath value");
        }
        return result(rule, passed, actual, passed
                ? "JSONPath value contains expected" : "JSONPath value does not contain expected");
    }

    private JsonPath compile(String expression) {
        if (expression == null || expression.isBlank() || !expression.startsWith("$")) {
            throw new IllegalArgumentException("A root-based JSONPath is required");
        }
        try {
            JsonPath path = JsonPath.compile(expression);
            // Functions have different provider-dependent output types; outside the selector scope of C3.
            if (!path.isDefinite() || hasFunction(expression)) {
                throw new IllegalArgumentException("Only definite JSONPath selectors are supported");
            }
            return path;
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException("Invalid JSONPath expression");
        }
    }

    private boolean hasFunction(String expression) {
        char quote = 0;
        boolean escaped = false;
        for (char character : expression.toCharArray()) {
            if (escaped) {
                escaped = false;
            } else if (quote != 0 && character == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '(') {
                return true;
            }
        }
        return false;
    }

    private static int compareValues(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue());
        }
        return left.equals(right) ? 0 : 1;
    }

    private static boolean isFiniteNumber(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return false;
        }
        // BigDecimal and BigInteger must not be narrowed to double (e.g. 1e400 is finite).
        return !(value.isDouble() || value.isFloat()) || Double.isFinite(value.doubleValue());
    }

    private AssertionResult result(AssertionRule rule, boolean passed, JsonNode actual, String message) {
        return new AssertionResult(rule.id(), rule.type(), passed, rule.expected(), actual, message);
    }
}