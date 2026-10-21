package com.platform.worker.assertion;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.platform.worker.model.AssertionResult;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;

import java.io.IOException;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Set;

/** Offline Draft 7 validation. No shared library defaults, raw diagnostics or schema cache. */
public final class JsonSchemaAssertion {
    private static final String DIALECT = "http://json-schema.org/draft-07/schema#";
    private static final Set<String> KEYWORDS = Set.of(
            "$schema", "$id", "$ref", "$comment", "title", "description", "default", "examples",
            "readOnly", "writeOnly", "definitions", "type", "enum", "const", "multipleOf", "maximum",
            "exclusiveMaximum", "minimum", "exclusiveMinimum", "maxLength", "minLength", "pattern",
            "format", "items", "additionalItems", "maxItems", "minItems", "uniqueItems", "contains",
            "maxProperties", "minProperties", "required", "properties", "patternProperties",
            "additionalProperties", "dependencies", "propertyNames", "if", "then", "else", "allOf",
            "anyOf", "oneOf", "not", "contentEncoding", "contentMediaType");
    private static final Set<String> SINGLE_SCHEMAS = Set.of(
            "additionalItems", "contains", "additionalProperties", "propertyNames", "if", "then", "else", "not");
    private static final Set<String> SCHEMA_MAPS = Set.of("definitions", "properties", "patternProperties");
    private static final Set<String> SCHEMA_ARRAYS = Set.of("allOf", "anyOf", "oneOf");
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7,
            builder -> builder.enableSchemaCache(false).schemaLoaders(loaders -> loaders.add(iri -> {
                throw invalidSchema();
            })));
    private final JsonSchema metaSchema;

    public JsonSchemaAssertion() {
        // Load only the trusted meta-schema bundled with the existing library, never from its URI.
        try (var stream = JsonSchemaFactory.class.getResourceAsStream("/draft-07/schema")) {
            if (stream == null) {
                throw new IllegalStateException("Bundled Draft 7 meta-schema is unavailable");
            }
            metaSchema = factory.getSchema(mapper.readTree(stream), configuration(false));
        } catch (IOException error) {
            throw new IllegalStateException("Bundled Draft 7 meta-schema is unavailable");
        }
    }

    public AssertionResult evaluate(AssertionRule rule, HttpResponseData response) {
        if (rule == null || rule.id() == null || rule.id().isBlank()) {
            throw new IllegalArgumentException("Assertion rule with a non-blank id is required");
        }
        if (!"JSON_SCHEMA".equals(rule.type())) {
            throw new IllegalArgumentException("JSON_SCHEMA rule is required");
        }
        if (rule.expected() != null || rule.operator() != null || rule.jsonPath() != null) {
            throw new IllegalArgumentException("JSON_SCHEMA only supports expectedSchema");
        }
        JsonSchema schema = compile(rule.expectedSchema());
        if (response == null) {
            throw new IllegalArgumentException("HTTP response is required");
        }
        if (response.error() != null || response.statusCode() == null) {
            throw new IllegalStateException("Cannot evaluate schema after HTTP execution failure");
        }
        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (JsonProcessingException error) {
            return result(rule, false, "Response body is not valid JSON");
        }
        if (body == null || body.isMissingNode()) {
            return result(rule, false, "Response body is not valid JSON");
        }
        boolean passed;
        try {
            checkStructureBudget(body);
            passed = schema.validate(canonicalNumbers(body)).isEmpty();
        } catch (RuntimeException error) {
            // Validator failures are not evidence that the response violated the schema.
            throw new IllegalStateException("JSON Schema evaluation could not complete");
        }
        return result(rule, passed, passed ? "Schema validation passed" : "Schema validation failed");
    }

    private JsonSchema compile(JsonNode root) {
        if (root == null || (!root.isObject() && !root.isBoolean())) {
            throw invalidSchema();
        }
        try {
            checkStructureBudget(root);
            root = canonicalNumbers(root);
            if (!metaSchema.validate(root).isEmpty()) {
                throw invalidSchema();
            }
            inspect(root, root, Collections.newSetFromMap(new IdentityHashMap<>()),
                    Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            var schema = factory.getSchema(root, configuration(true));
            schema.initializeValidators();
            return schema;
        } catch (RuntimeException error) {
            throw invalidSchema();
        }
    }

    /** Bound schema/response work before recursive canonicalization and validation. */
    private void checkStructureBudget(JsonNode root) {
        record Entry(JsonNode node, int depth) { }
        var pending = new ArrayDeque<Entry>();
        pending.push(new Entry(root, 0));
        int count = 0;
        while (!pending.isEmpty()) {
            var entry = pending.pop();
            if (++count > 10000 || entry.depth() > 256) {
                throw invalidSchema();
            }
            if (entry.node().isContainerNode()) {
                for (JsonNode child : entry.node()) {
                    if ((long) count + pending.size() >= 10000) {
                        throw invalidSchema();
                    }
                    pending.push(new Entry(child, entry.depth() + 1));
                }
            }
        }
    }

    private void inspect(JsonNode node, JsonNode root, Set<JsonNode> visiting, Set<JsonNode> visited, int depth) {
        if (depth > 64 || (!node.isObject() && !node.isBoolean()) || visiting.contains(node)) {
            throw invalidSchema();
        }
        if (visited.contains(node) || node.isBoolean()) {
            return;
        }
        if (!metaSchema.validate(node).isEmpty()) {
            throw invalidSchema();
        }
        visiting.add(node);
        node.fieldNames().forEachRemaining(keyword -> {
            if (!KEYWORDS.contains(keyword)) {
                throw invalidSchema();
            }
        });
        // Embedded dialect changes and resource identifiers complicate local reference resolution.
        if ((node.has("$schema") && (node != root || !DIALECT.equals(node.get("$schema").textValue())))
                || node.has("$id")) {
            throw invalidSchema();
        }
        if (node.has("$ref")) {
            String ref = node.get("$ref").textValue();
            if (ref == null || !ref.startsWith("#/") || ref.contains("%") || ref.matches(".*~(?![01]).*")) {
                throw invalidSchema();
            }
            // Draft 7 ignores validation siblings of $ref: reject them rather than silently ignoring.
            node.fieldNames().forEachRemaining(key -> {
                if (!Set.of("$ref", "$schema", "$comment", "title", "description", "definitions").contains(key)) {
                    throw invalidSchema();
                }
            });
            inspect(root.at(ref.substring(1)), root, visiting, visited, depth + 1);
        }
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (SCHEMA_MAPS.contains(key)) {
                value.forEach(child -> inspect(child, root, visiting, visited, depth + 1));
            } else if (SINGLE_SCHEMAS.contains(key)) {
                inspect(value, root, visiting, visited, depth + 1);
            } else if (SCHEMA_ARRAYS.contains(key) || ("items".equals(key) && value.isArray())) {
                value.forEach(child -> inspect(child, root, visiting, visited, depth + 1));
            } else if ("items".equals(key)) {
                inspect(value, root, visiting, visited, depth + 1);
            } else if ("dependencies".equals(key)) {
                value.forEach(child -> {
                    if (!child.isArray()) {
                        inspect(child, root, visiting, visited, depth + 1);
                    }
                });
            }
        });
        visiting.remove(node);
        visited.add(node);
    }

    private static SchemaValidatorsConfig configuration(boolean assertFormats) {
        var config = new SchemaValidatorsConfig();
        config.setTypeLoose(false);
        config.setHandleNullableField(false);
        config.setFormatAssertionsEnabled(assertFormats);
        return config;
    }

    /** Networknt 1.5.1 uses Jackson structural equality inside const/enum and uniqueItems. */
    private JsonNode canonicalNumbers(JsonNode node) {
        if (node.isNumber()) {
            var number = node.decimalValue().stripTrailingZeros();
            // A short exponent such as 1e1000000000 must not allocate a billion-digit integer.
            if (number.scale() <= 0 && (long) number.precision() - number.scale() > 10000) {
                throw new IllegalArgumentException("JSON number exceeds supported expanded precision");
            }
            return number.scale() <= 0 ? BigIntegerNode.valueOf(number.toBigIntegerExact())
                    : DecimalNode.valueOf(number);
        }
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            node.fields().forEachRemaining(entry -> copy.set(entry.getKey(), canonicalNumbers(entry.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            node.forEach(child -> copy.add(canonicalNumbers(child)));
            return copy;
        }
        return node;
    }

    private static IllegalArgumentException invalidSchema() {
        return new IllegalArgumentException("Invalid or unsupported Draft 7 expectedSchema");
    }

    private AssertionResult result(AssertionRule rule, boolean passed, String message) {
        return new AssertionResult(rule.id(), rule.type(), passed,
                TextNode.valueOf("Response body conforms to supplied Draft 7 schema"),
                TextNode.valueOf(passed ? "valid" : "invalid"), message);
    }
}