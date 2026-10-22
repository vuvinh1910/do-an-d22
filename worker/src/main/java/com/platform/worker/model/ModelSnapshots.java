package com.platform.worker.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copying rules shared by HTTP models and runtime; never includes input values in errors. */
public final class ModelSnapshots {

    private ModelSnapshots() {
    }

    public static JsonNode json(JsonNode value) {
        validateJson(value);
        return value == null ? null : value.deepCopy();
    }

    /** Validate iteratively before deepCopy/equals/hashCode can recurse into caller-owned trees. */
    private static void validateJson(JsonNode root) {
        if (root == null) {
            return;
        }
        record Entry(JsonNode node, int depth) { }
        var pending = new ArrayDeque<Entry>();
        pending.push(new Entry(root, 0));
        int count = 0;
        while (!pending.isEmpty()) {
            var entry = pending.pop();
            JsonNode node = entry.node();
            if (++count > 10000 || entry.depth() > 256 || node == null || node.isMissingNode()
                    || node.isPojo() || node.isBinary()
                    || ((node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue()))) {
                throw new IllegalArgumentException("JSON value is unsupported or exceeds structure limits");
            }
            if (node.isContainerNode()) {
                // Count while enqueueing as well: a wide tree must not create an unbounded work queue.
                for (JsonNode child : node) {
                    if ((long) count + pending.size() >= 10000) {
                        throw new IllegalArgumentException("JSON value is unsupported or exceeds structure limits");
                    }
                    pending.push(new Entry(child, entry.depth() + 1));
                }
            }
        }
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