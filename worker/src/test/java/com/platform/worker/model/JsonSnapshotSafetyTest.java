package com.platform.worker.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.POJONode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class JsonSnapshotSafetyTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Test
    void rejectsDeepTreesBeforeSnapshotRecursion() {
        var root = NODES.objectNode();
        var cursor = root;
        for (int index = 0; index < 2000; index++) {
            cursor = cursor.putObject("child");
        }
        assertSafeRejection(root);
    }

    @Test
    void rejectsCyclicCallerTreesWithoutStackOverflow() {
        var root = NODES.objectNode();
        root.set("cycle", root);
        assertSafeRejection(root);
    }

    @Test
    void rejectsOversizedTreeButAcceptsExactNodeBudget() {
        var array = NODES.arrayNode();
        for (int index = 0; index < 9999; index++) {
            array.add(index);
        }
        assertEquals(9999, ModelSnapshots.json(array).size());
        array.add(9999);
        assertSafeRejection(array);
    }

    @Test
    void acceptsExactDepthBudgetAndRejectsNextLevel() {
        var root = NODES.objectNode();
        var cursor = root;
        for (int index = 0; index < 256; index++) {
            cursor = cursor.putObject("child");
        }
        assertNotNull(ModelSnapshots.json(root));
        cursor.putObject("child");
        assertSafeRejection(root);
    }

    @ParameterizedTest
    @MethodSource("nonJsonValues")
    void rejectsNonJsonNodesAtEveryModelBoundary(JsonNode value) {
        assertSafeRejection(value);
        assertThrows(IllegalArgumentException.class,
                () -> new TestCaseRequest("GET", "/", null, null, Map.of("q", value), null));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule("r", "JSON_SCHEMA", null, null, null, value, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionResult("r", "JSON_PATH", true, null, value, "safe"));
    }

    static Stream<JsonNode> nonJsonValues() {
        return Stream.of(NODES.numberNode(Double.NaN), NODES.numberNode(Double.POSITIVE_INFINITY),
                NODES.numberNode(Float.NEGATIVE_INFINITY), NODES.binaryNode(new byte[]{1}),
                new POJONode("private-marker"), MissingNode.getInstance());
    }

    @Test
    void sharedAcyclicSubtreeRemainsValidAndSnapshotsAreIndependent() {
        var child = NODES.objectNode().put("value", 1);
        var root = NODES.objectNode();
        root.set("left", child);
        root.set("right", child);
        var copy = (com.fasterxml.jackson.databind.node.ObjectNode) ModelSnapshots.json(root);
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.get("left")).put("value", 2);
        assertEquals(1, copy.get("right").get("value").intValue());
        assertEquals(1, child.get("value").intValue());
    }

    private void assertSafeRejection(JsonNode value) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new TestCaseRequest("POST", "/", null, null, null, value));
        assertFalse(error.getMessage().contains("private-marker"));
        assertNull(error.getCause());
    }
}