package com.platform.worker.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.TestCaseRequest;
import com.platform.worker.runner.WebClientHttpRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SequentialExecutionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeout = Duration.ofSeconds(3);

    @Test
    void excessiveTextExpansionBecomesErrorWithoutSendingHttpAndSkipsNext() {
        var calls = new AtomicInteger();
        var executor = new SequentialExecution((request, duration) -> {
            calls.incrementAndGet();
            return Mono.just(response(200, "{}"));
        });
        var first = new SequentialExecution.Step("a", 1, request("GET", "/{{v}}{{v}}"), List.of(), List.of());
        var next = new SequentialExecution.Step("b", 2, request("GET", "/"), List.of(), List.of());
        var context = Map.<String, com.fasterxml.jackson.databind.JsonNode>of("v",
                mapper.getNodeFactory().textNode("x".repeat(500_000)));
        var results = executor.execute("http://localhost", context, List.of(first, next), timeout, true).block(timeout);
        assertNotNull(results);
        assertEquals(SequentialExecution.Status.ERROR, results.get(0).status());
        assertNull(results.get(0).request());
        assertEquals(SequentialExecution.Status.SKIPPED, results.get(1).status());
        assertEquals(0, calls.get());
    }

    @Test
    @Timeout(15)
    void realCreateGetChainOrdersStepsAndResolvesExtractedId() throws Exception {
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/users", exchange -> {
            try (exchange) {
                calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                byte[] bytes = "{\"id\":42}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders("POST".equals(exchange.getRequestMethod()) ? 201 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var executor = new SequentialExecution(new WebClientHttpRunner(mapper)::execute);
            var create = new SequentialExecution.Step("create", 1, request("POST", "/users"),
                    List.of(status(201)), List.of(new ExtractionRule("id", "BODY", "$.id", null, null)));
            var get = new SequentialExecution.Step("get", 2, request("GET", "/users/{{id}}"),
                    List.of(status(200)), List.of());
            var results = executor.execute("http://127.0.0.1:" + server.getAddress().getPort(), Map.of(),
                    List.of(get, create), timeout, true).block(Duration.ofSeconds(8));
            assertNotNull(results);
            assertEquals(List.of(SequentialExecution.Status.PASSED, SequentialExecution.Status.PASSED),
                    results.stream().map(SequentialExecution.StepResult::status).toList());
            assertEquals(List.of("POST /users", "GET /users/42"), calls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failureSkipsRemainingStepsWithoutExtractingOrMakingRequests() throws Exception {
        var calls = new AtomicInteger();
        var executor = new SequentialExecution((request, duration) -> {
            calls.incrementAndGet();
            return Mono.just(response(500, "{\"id\":42}"));
        });
        var first = new SequentialExecution.Step("a", 1, request("GET", "/"), List.of(status(200)),
                List.of(new ExtractionRule("id", "BODY", "$.id", null, null)));
        var next = new SequentialExecution.Step("b", 2, request("GET", "/{{id}}"), List.of(), List.of());
        var results = executor.execute("http://localhost", Map.of(), List.of(first, next), timeout, true).block();
        assertEquals(SequentialExecution.Status.FAILED, results.get(0).status());
        assertEquals(SequentialExecution.Status.SKIPPED, results.get(1).status());
        assertEquals(1, calls.get());
        var continued = executor.execute("http://localhost", Map.of(), List.of(first, next), timeout, false).block();
        assertEquals(SequentialExecution.Status.ERROR, continued.get(1).status());
        assertEquals(2, calls.get());
    }

    @Test
    void failedExtractionUpdatesNothingAndIndependentStepContinues() {
        var executor = new SequentialExecution((request, duration) -> Mono.just(response(200, "{\"id\":42}")));
        var first = new SequentialExecution.Step("a", 1, request("GET", "/"), List.of(), List.of(
                new ExtractionRule("id", "BODY", "$.id", null, null),
                new ExtractionRule("bad", "BODY", "$.missing", null, null)));
        var dependent = new SequentialExecution.Step("b", 2, request("GET", "/{{id}}"), List.of(), List.of());
        var independent = new SequentialExecution.Step("c", 3, request("GET", "/ok"), List.of(), List.of());
        var results = executor.execute("http://localhost", Map.of(), List.of(first, dependent, independent),
                timeout, false).block();
        assertEquals(List.of(SequentialExecution.Status.ERROR, SequentialExecution.Status.ERROR,
                        SequentialExecution.Status.PASSED),
                results.stream().map(SequentialExecution.StepResult::status).toList());
    }

    @Test
    void everySubscriptionGetsFreshContextAndCallerMutationCannotAffectRun() throws Exception {
        var executor = new SequentialExecution((request, duration) -> Mono.just(response(200, "{\"id\":99}")));
        var variables = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        variables.put("id", mapper.readTree("1"));
        var step = new SequentialExecution.Step("a", 1, request("GET", "/{{id}}"), List.of(),
                List.of(new ExtractionRule("id", "BODY", "$.id", null, null)));
        var run = executor.execute("http://localhost", variables, List.of(step), timeout, false);
        variables.clear();
        assertEquals("/1", run.block().get(0).request().uri().getPath());
        assertEquals("/1", run.block().get(0).request().uri().getPath());
    }

    @Test
    void transportErrorEmptyPublisherAndThrownExceptionsBecomeErrorWithoutSecretEcho() {
        var step = new SequentialExecution.Step("a", 1, request("GET", "/"), List.of(), List.of());
        var transport = new SequentialExecution((request, duration) -> Mono.just(
                new HttpResponseData(null, null, null, 1, "private")));
        var result = transport.execute("http://localhost", Map.of(), List.of(step), timeout, false).block().get(0);
        assertEquals(SequentialExecution.Status.ERROR, result.status());
        assertFalse(result.error().contains("private"));
        var empty = new SequentialExecution((request, duration) -> Mono.empty());
        assertEquals(SequentialExecution.Status.ERROR,
                empty.execute("http://localhost", Map.of(), List.of(step), timeout, false).block().get(0).status());
        var thrown = new SequentialExecution((request, duration) -> { throw new IllegalArgumentException("private"); });
        assertEquals(SequentialExecution.Status.ERROR,
                thrown.execute("http://localhost", Map.of(), List.of(step), timeout, false).block().get(0).status());
    }

    @Test
    void duplicateOrderIndexesAreRejectedBeforeExecution() {
        var executor = new SequentialExecution((request, duration) -> Mono.error(new AssertionError()));
        var a = new SequentialExecution.Step("a", 1, request("GET", "/"), List.of(), List.of());
        var b = new SequentialExecution.Step("b", 1, request("GET", "/"), List.of(), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute("http://localhost", Map.of(), List.of(a, b), timeout, false));
    }

    @Test
    void invalidAssertionConfigurationBecomesErrorNotFailedOrPartialSummary() throws Exception {
        var executor = new SequentialExecution((request, duration) -> Mono.just(response(200, "{}")));
        var bad = new AssertionRule("bad", "UNKNOWN", null, null, null, null, null);
        var step = new SequentialExecution.Step("a", 1, request("GET", "/"),
                List.of(status(500), bad), List.of());
        var result = executor.execute("http://localhost", Map.of(), List.of(step), timeout, false).block().get(0);
        assertEquals(SequentialExecution.Status.ERROR, result.status());
        assertNull(result.evaluation());
        assertNotNull(result.response());
    }

    private AssertionRule status(int expected) throws Exception {
        return new AssertionRule("status", "STATUS_CODE", mapper.readTree(Integer.toString(expected)),
                null, null, null, null);
    }

    @Test
    void failedExtractionDoesNotPartiallyOverwriteExistingVariable() throws Exception {
        var executor = new SequentialExecution((request, duration) -> Mono.just(response(200, "{\"id\":99}")));
        var first = new SequentialExecution.Step("a", 1, request("GET", "/"), List.of(),
                List.of(new ExtractionRule("id", "BODY", "$.id", null, null),
                        new ExtractionRule("missing", "BODY", "$.missing", null, null)));
        var next = new SequentialExecution.Step("b", 2, request("GET", "/{{id}}"), List.of(), List.of());
        var results = executor.execute("http://localhost", Map.of("id", mapper.readTree("1")),
                List.of(first, next), timeout, false).block(timeout);
        assertNotNull(results);
        assertEquals(SequentialExecution.Status.ERROR, results.get(0).status());
        assertEquals(SequentialExecution.Status.PASSED, results.get(1).status());
        assertEquals("/1", results.get(1).request().uri().getPath());
    }

    @Test
    void concurrentSubscriptionsHaveIndependentExtractionContexts() {
        var calls = new AtomicInteger();
        var executor = new SequentialExecution((request, duration) -> {
            if (request.uri().getPath().equals("/create")) {
                int id = calls.incrementAndGet();
                return Mono.delay(Duration.ofMillis(20)).map(ignored -> response(200, "{\"id\":" + id + "}"));
            }
            return Mono.just(response(200, "{}"));
        });
        var create = new SequentialExecution.Step("a", 1, request("GET", "/create"), List.of(),
                List.of(new ExtractionRule("id", "BODY", "$.id", null, null)));
        var get = new SequentialExecution.Step("b", 2, request("GET", "/{{id}}"), List.of(), List.of());
        var run = executor.execute("http://localhost", Map.of(), List.of(create, get), timeout, false);
        var pair = Mono.zip(run, run).block(timeout);
        assertNotNull(pair);
        assertEquals("/1", pair.getT1().get(1).request().uri().getPath());
        assertEquals("/2", pair.getT2().get(1).request().uri().getPath());
        assertEquals(2, calls.get());
    }

    @Test
    void cancellationCancelsActiveRequestAndNeverStartsNextStep() {
        var calls = new AtomicInteger();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var executor = new SequentialExecution((request, duration) -> {
            calls.incrementAndGet();
            return Mono.<HttpResponseData>never().doOnCancel(() -> cancelled.set(true));
        });
        var first = new SequentialExecution.Step("a", 1, request("GET", "/"), List.of(), List.of());
        var second = new SequentialExecution.Step("b", 2, request("GET", "/"), List.of(), List.of());
        var subscription = executor.execute("http://localhost", Map.of(), List.of(first, second), timeout, false)
                .subscribe();
        assertEquals(1, calls.get());
        subscription.dispose();
        assertTrue(cancelled.get());
        assertEquals(1, calls.get());
    }

    private TestCaseRequest request(String method, String path) {
        return new TestCaseRequest(method, path, Map.of(), Map.of(), Map.of(), null);
    }

    private HttpResponseData response(int status, String body) {
        return new HttpResponseData(status, Map.of(), body, 1, null);
    }
}