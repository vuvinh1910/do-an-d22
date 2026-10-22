package com.platform.worker.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.ModelSnapshots;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extraction is all-or-nothing; parsed body is cached across multiple body rules in the same response. */
public final class ResponseExtractor {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Configuration configuration = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider(mapper) {
                @Override
                public Object getArrayIndex(Object array, int index) {
                    if (index < 0 || index >= length(array)) {
                        throw new IllegalArgumentException("Extraction array index is missing");
                    }
                    return super.getArrayIndex(array, index);
                }
            }).build();

    public Map<String, JsonNode> extract(List<ExtractionRule> rules, HttpResponseData response) {
        if (rules == null || response == null) {
            throw new IllegalArgumentException("Extraction rules and response are required");
        }
        if (response.error() != null || response.statusCode() == null) {
            throw new IllegalStateException("Cannot extract after HTTP execution failure");
        }
        Map<String, JsonNode> values = new LinkedHashMap<>();
        JsonNode parsedBody = null;
        for (var rule : rules) {
            if (rule == null || rule.variableName() == null || rule.variableName().isBlank()
                    || values.containsKey(rule.variableName())) {
                throw new IllegalArgumentException("Extraction variables require unique non-blank names");
            }
            JsonNode value;
            if ("BODY".equals(rule.source())) {
                if (rule.headerName() != null || rule.jsonPath() == null || !rule.jsonPath().startsWith("$")) {
                    throw new IllegalArgumentException("BODY extraction requires only a root-based JSONPath");
                }
                try {
                    var path = JsonPath.compile(rule.jsonPath());
                    // Reject functions outside quoted property names, as in assertion selectors.
                    if (!path.isDefinite() || hasFunction(rule.jsonPath())) {
                        throw new IllegalArgumentException();
                    }
                    if (parsedBody == null) {
                        parsedBody = ModelSnapshots.json(mapper.readTree(response.body()));
                        if (parsedBody == null) {
                            throw new IllegalArgumentException();
                        }
                    }
                    value = path.read(parsedBody, configuration);
                } catch (Exception error) {
                    throw new IllegalArgumentException("BODY extraction could not resolve a definite JSON value");
                }
            } else if ("HEADER".equals(rule.source())) {
                if (rule.jsonPath() != null || rule.headerName() == null
                        || !rule.headerName().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                    throw new IllegalArgumentException("HEADER extraction requires only a valid headerName");
                }
                var matches = new ArrayList<String>();
                response.headers().forEach((name, entries) -> {
                    if (name.equalsIgnoreCase(rule.headerName())) {
                        matches.addAll(entries);
                    }
                });
                if (matches.size() != 1) {
                    throw new IllegalArgumentException("HEADER extraction requires exactly one header value");
                }
                value = TextNode.valueOf(matches.get(0));
            } else {
                throw new IllegalArgumentException("Unsupported extraction source");
            }
            if (value == null || value.isMissingNode()) {
                throw new IllegalArgumentException("Extraction value is missing");
            }
            values.put(rule.variableName(), ModelSnapshots.json(value));
        }
        return Map.copyOf(values);
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
}
