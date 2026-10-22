package com.platform.worker.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.platform.worker.assertion.AssertionEngine;
import com.platform.worker.model.AssertionResult;
import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.ResolvedHttpRequest;
import com.platform.worker.model.TestResult;
import com.platform.worker.runtime.SequentialExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TestResultMapperTest {
    private final UUID runId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-22T00:00:00Z");
    private final TestResultMapper mapper = new TestResultMapper(Clock.fixed(now, ZoneOffset.UTC));
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @ValueSource(strings = {"{\"token\":\"private-value\"}", "private-value", "", "null"})
    void serializesPassedWithoutRawJsonTextHeadersUrlOrAssertionValues(String body) throws Exception {
        var raw = step(SequentialExecution.Status.PASSED, body, true);
        var result = map(raw);
        var tree = json.readTree(json.writeValueAsString(result));
        var fields = new java.util.HashSet<String>();
        tree.fieldNames().forEachRemaining(fields::add);
        assertEquals(java.util.Set.of("testRunId", "testCaseId", "name", "testType", "status",
                "httpStatus", "responseTimeMs", "requestSent", "responseReceived", "assertionResults",
                "errorMessage", "executedAt"), fields);
        assertEquals(runId.toString(), tree.path("testRunId").asText());
        assertEquals("PASSED", tree.path("status").asText());
        assertEquals(now, result.executedAt());
        assertEquals(now.toString(), tree.path("executedAt").asText());
        assertEquals(200, result.httpStatus());
        assertEquals(12L, result.responseTimeMs());
        assertTrue(tree.path("errorMessage").isNull());
        assertFalse(tree.toString().contains("private-value"));
        assertEquals("[REDACTED]", tree.at("/responseReceived/body").asText());
        assertEquals(body, raw.response().body());
        assertEquals("private-value", raw.request().body().asText());
    }

    @Test
    void failedRetainsOrderedRuleOutcomesButNotMessagesOrValues() {
        var result = map(step(SequentialExecution.Status.FAILED, "private-value", false));
        assertEquals(TestResult.Status.FAILED, result.status());
        assertFalse(result.assertionResults().get(0).passed());
        assertEquals("rule", result.assertionResults().get(0).ruleId());
        assertEquals("Assertion failed", result.assertionResults().get(0).message());
        assertNotNull(result.errorMessage());
    }

    @Test
    void transportErrorHasNoInventedStatusBodyOrAssertions() {
        var raw = new SequentialExecution.StepResult(caseId.toString(), SequentialExecution.Status.ERROR,
                null, new HttpResponseData(null, null, null, 42, "private-value"), null, "private-value");
        var result = map(raw);
        assertEquals(TestResult.Status.ERROR, result.status());
        assertNull(result.httpStatus());
        assertNull(result.responseReceived());
        assertEquals(42L, result.responseTimeMs());
        assertTrue(result.assertionResults().isEmpty());
        assertFalse(result.toString().contains("private-value"));
    }

    @Test
    void partialReadErrorRetainsKnownStatusWithoutPublishingPartialBody() {
        var raw = new SequentialExecution.StepResult(caseId.toString(), SequentialExecution.Status.ERROR,
                null, new HttpResponseData(502, Map.of(), "private-value", 7, "private-value"), null, null);
        var result = map(raw);
        assertEquals(502, result.httpStatus());
        assertEquals("[REDACTED]", result.responseReceived().path("body").asText());
        assertTrue(result.assertionResults().isEmpty());
    }

    @Test
    void skippedAndPreRequestErrorHaveNoInventedTiming() {
        for (var status : List.of(SequentialExecution.Status.ERROR, SequentialExecution.Status.SKIPPED)) {
            var result = map(new SequentialExecution.StepResult(caseId.toString(), status,
                    null, null, null, "private-value"));
            assertNull(result.responseTimeMs());
            assertNull(result.requestSent());
            assertNull(result.httpStatus());
            assertEquals(status.name(), result.status().name());
        }
    }

    @Test
    void resultSnapshotsCannotBeMutatedThroughAccessors() {
        var result = map(step(SequentialExecution.Status.PASSED, "{}", true));
        ((com.fasterxml.jackson.databind.node.ObjectNode) result.requestSent()).put("url", "changed");
        assertEquals("[REDACTED]", result.requestSent().path("url").asText());
        assertThrows(UnsupportedOperationException.class, () -> result.assertionResults().clear());
    }

    @Test
    void rejectsMismatchedIdentityAndInconsistentCompletion() {
        var raw = step(SequentialExecution.Status.PASSED, "{}", false);
        assertThrows(IllegalArgumentException.class, () -> map(raw));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(runId, UUID.randomUUID(),
                "Case", "VALID", step(SequentialExecution.Status.PASSED, "{}", true)));
        assertThrows(IllegalArgumentException.class, () -> map(new SequentialExecution.StepResult(
                caseId.toString(), SequentialExecution.Status.PASSED, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> new TestResultMapper(null));
    }

    @Test
    void runtimeChainPreservesExtractedSecretInternallyButNeverPublishesIt() throws Exception {
        var secondId = UUID.randomUUID();
        var executor = new SequentialExecution((request, timeout) -> reactor.core.publisher.Mono.just(
                new HttpResponseData(200, Map.of(), "{\"id\":\"private-value\"}", 5, null)));
        var first = new SequentialExecution.Step(caseId.toString(), 1,
                new com.platform.worker.model.TestCaseRequest("POST", "/items", Map.of(), Map.of(), Map.of(), null),
                List.of(), List.of(new com.platform.worker.runtime.ExtractionRule("id", "BODY", "$.id", null, null)));
        var second = new SequentialExecution.Step(secondId.toString(), 2,
                new com.platform.worker.model.TestCaseRequest("GET", "/items/{{id}}", Map.of(), Map.of(), Map.of(), null),
                List.of(), List.of());
        var results = executor.execute("https://example.com", Map.of(), List.of(first, second),
                java.time.Duration.ofSeconds(1), true).block(java.time.Duration.ofSeconds(3));
        assertNotNull(results);
        assertEquals("/items/private-value", results.get(1).request().uri().getPath());
        for (var raw : results) {
            var safe = mapper.map(runId, UUID.fromString(raw.stepId()), "Case", "VALID", raw);
            assertEquals(TestResult.Status.PASSED, safe.status());
            assertFalse(json.writeValueAsString(safe).contains("private-value"));
        }
    }

    private TestResult map(SequentialExecution.StepResult raw) {
        return mapper.map(runId, caseId, "Case", "VALID", raw);
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void realHttpRuntimeMapsAllStatusesToRedactedSerializableResults() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                byte[] bytes = "{\"token\":\"private-value\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Set-Cookie", "private-value");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var executor = new SequentialExecution(new com.platform.worker.runner.WebClientHttpRunner(json)::execute);
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            var timeout = java.time.Duration.ofSeconds(3);
            for (var status : List.of(TestResult.Status.PASSED, TestResult.Status.FAILED, TestResult.Status.ERROR)) {
                var request = new com.platform.worker.model.TestCaseRequest("GET", "/", Map.of("Authorization", "private-value"),
                        Map.of(), Map.of(), null);
                var rule = new com.platform.worker.model.AssertionRule("status", "STATUS_CODE",
                        json.getNodeFactory().numberNode(status == TestResult.Status.FAILED ? 201 : 200),
                        null, null, null, null);
                var extraction = status == TestResult.Status.ERROR
                        ? List.of(new com.platform.worker.runtime.ExtractionRule("id", "BODY", "$.missing", null, null))
                        : List.<com.platform.worker.runtime.ExtractionRule>of();
                var nextId = UUID.randomUUID();
                var steps = List.of(new SequentialExecution.Step(caseId.toString(), 1, request, List.of(rule), extraction),
                        new SequentialExecution.Step(nextId.toString(), 2, request, List.of(), List.of()));
                var raw = executor.execute(baseUrl, Map.of(), steps, timeout, true).block(java.time.Duration.ofSeconds(8));
                assertNotNull(raw);
                assertEquals(status, map(raw.get(0)).status());
                assertEquals(status == TestResult.Status.PASSED ? SequentialExecution.Status.PASSED
                        : SequentialExecution.Status.SKIPPED, raw.get(1).status());
                for (var step : raw) {
                    var safe = mapper.map(runId, UUID.fromString(step.stepId()), "Case", "VALID", step);
                    String wire = json.writeValueAsString(safe);
                    assertFalse(wire.contains("private-value"));
                    assertFalse(wire.contains(baseUrl));
                    assertEquals(safe, json.readValue(wire, TestResult.class));
                    if (safe.status() == TestResult.Status.ERROR || safe.status() == TestResult.Status.SKIPPED) {
                        assertTrue(safe.assertionResults().isEmpty());
                    }
                }
            }
            assertEquals(4, calls.get());
        } finally {
            server.stop(0);
        }
    }

    private SequentialExecution.StepResult step(SequentialExecution.Status status, String body, boolean passed) {
        var request = new ResolvedHttpRequest(HttpMethod.POST, URI.create("https://example.com/private-value"),
                Map.of("Authorization", List.of("private-value")), TextNode.valueOf("private-value"));
        var rule = new AssertionResult("rule", "JSON_PATH", passed, TextNode.valueOf("private-value"),
                TextNode.valueOf("private-value"), "private-value");
        return new SequentialExecution.StepResult(caseId.toString(), status, request,
                new HttpResponseData(200, Map.of("Set-Cookie", List.of("private-value")), body, 12, null),
                new AssertionEngine.Evaluation(List.of(rule)), null);
    }
}