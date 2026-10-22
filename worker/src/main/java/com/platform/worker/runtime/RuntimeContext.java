package com.platform.worker.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.worker.model.ModelSnapshots;

import java.util.HashMap;
import java.util.Map;

/** Mutable state owned by exactly one sequential execution, never a singleton bean. */
public final class RuntimeContext {
    private final Map<String, JsonNode> values = new HashMap<>();

    public RuntimeContext(Map<String, JsonNode> initial) {
        putAll(initial);
    }

    public JsonNode require(String name) {
        if (!values.containsKey(name)) {
            throw new IllegalArgumentException("Runtime variable is missing");
        }
        return ModelSnapshots.json(values.get(name));
    }

    public void putAll(Map<String, JsonNode> additions) {
        if (additions == null || additions.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new IllegalArgumentException("Runtime variables require non-blank names and JSON values");
        }
        Map<String, JsonNode> copies = new HashMap<>();
        additions.forEach((key, value) -> copies.put(key, ModelSnapshots.json(value)));
        values.putAll(copies);
    }
}