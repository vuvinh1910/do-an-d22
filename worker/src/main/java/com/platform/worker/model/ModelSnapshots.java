package com.platform.worker.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Package-local copying rules shared by HTTP models; never includes input values in errors. */
final class ModelSnapshots {

    private ModelSnapshots() {
    }

    static JsonNode json(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }

    static <T> Map<String, T> entries(Map<String, T> source, String field) {
        if (source == null) {
            return Map.of();
        }
        if (source.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException(field + " must not contain Java null keys or values");
        }
        return Map.copyOf(source);
    }

    static Map<String, JsonNode> query(Map<String, JsonNode> source) {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        entries(source, "queryParams").forEach((key, value) -> copy.put(key, json(value)));
        return Map.copyOf(copy);
    }

    static Map<String, List<String>> headers(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        entries(source, "headers").forEach((key, values) -> {
            if (values.stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException("headers must not contain Java null list elements");
            }
            copy.put(key, List.copyOf(values));
        });
        return Map.copyOf(copy);
    }
}