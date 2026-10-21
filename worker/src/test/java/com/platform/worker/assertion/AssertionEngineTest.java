package com.platform.worker.assertion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssertionEngineTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AssertionEngine engine = new AssertionEngine();

    @Test
    void routesEveryTypeInInputOrderAndPassesExpectedHttpErrorResponse() throws Exception {
        var rules = rules();
        var evaluation = engine.evaluate(rules, response(400, 10));
        assertTrue(evaluation.passed());
        assertEquals(rules.stream().map(AssertionRule::id).toList(),
                evaluation.assertionResults().stream().map(result -> result.ruleId()).toList());
        assertEquals(rules.stream().map(AssertionRule::type).toList(),
                evaluation.assertionResults().stream().map(result -> result.type()).toList());
        assertEquals(5, evaluation.assertionResults().size());
    }

    @Test
    void collectsAllFailuresAndLaterPassesWithoutShortCircuitingOrStateLeak() throws Exception {
        var first = engine.evaluate(rules(), response(500, 100));
        assertFalse(first.passed());
        assertEquals(List.of(false, false, true, true, true),
                first.assertionResults().stream().map(result -> result.passed()).toList());
        assertTrue(engine.evaluate(rules(), response(400, 10)).passed());
        assertFalse(first.passed());
    }

    @Test
    void malformedBodyKeepsIndependentStatusAndHeaderResults() throws Exception {
        var evaluation = engine.evaluate(rules(), new HttpResponseData(400,
                Map.of("Content-Type", List.of("application/json")), "{", 10, null));
        assertEquals(List.of(true, true, false, true, false),
                evaluation.assertionResults().stream().map(result -> result.passed()).toList());
        assertFalse(evaluation.passed());
    }

    @Test
    void emptyAssertionsSucceedOnlyForCompleteResponse() {
        var evaluation = engine.evaluate(List.of(), response(500, 10));
        assertTrue(evaluation.passed());
        assertTrue(evaluation.assertionResults().isEmpty());
        assertThrows(IllegalStateException.class, () -> engine.evaluate(List.of(),
                new HttpResponseData(null, null, null, 10, "secret-marker")));
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(null, response(200, 10)));
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(List.of(), null));
    }

    @Test
    void resultsAreImmutableAndIndependentOfCallerList() throws Exception {
        var mutable = new ArrayList<>(rules());
        var evaluation = engine.evaluate(mutable, response(400, 10));
        mutable.clear();
        assertEquals(5, evaluation.assertionResults().size());
        assertThrows(UnsupportedOperationException.class, () -> evaluation.assertionResults().clear());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "secret-marker", "status_code"})
    void unsupportedTypesAreConfigurationErrorsWithoutEcho(String type) {
        var rule = new AssertionRule("r", type, null, null, null, null, null);
        var error = assertThrows(IllegalArgumentException.class,
                () -> engine.evaluate(List.of(rule), response(200, 10)));
        assertEquals("Unsupported assertion rule type", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void nullRuleBlankAndDuplicateIdsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> engine.evaluate(Arrays.asList((AssertionRule) null), response(200, 10)));
        for (String id : new String[]{null, "", " "}) {
            var rule = new AssertionRule(id, "STATUS_CODE", mapper.readTree("200"), null, null, null, null);
            assertThrows(IllegalArgumentException.class, () -> engine.evaluate(List.of(rule), response(200, 10)));
        }
        var rule = rules().getFirst();
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(List.of(rule, rule), response(400, 10)));
    }

    @Test
    void badConfigurationAfterAnAssertionFailureStillThrowsInsteadOfReturningPartialEvaluation() throws Exception {
        var valid = rules().getFirst();
        var invalid = new AssertionRule("bad-schema", "JSON_SCHEMA", null, null, null,
                mapper.readTree("{\"type\":\"invalid\"}"), null);
        assertThrows(IllegalArgumentException.class,
                () -> engine.evaluate(List.of(valid, invalid), response(500, 10)));
        assertTrue(engine.evaluate(rules(), response(400, 10)).passed());
    }

    @Test
    void executionErrorWithKnownStatusAndHeadersNeverProducesSummary() throws Exception {
        var error = assertThrows(IllegalStateException.class, () -> engine.evaluate(rules(),
                new HttpResponseData(400, Map.of("Content-Type", List.of("application/json")),
                        "{\"age\":18}", 10, "secret-marker")));
        assertEquals("Cannot evaluate assertions after HTTP execution failure", error.getMessage());
        assertNull(error.getCause());
    }

    private List<AssertionRule> rules() throws Exception {
        var json = mapper.readTree("""
                [
                  {"id":"status","type":"STATUS_CODE","expected":400},
                  {"id":"latency","type":"RESPONSE_TIME","operator":"LESS_THAN","expected":100},
                  {"id":"path","type":"JSON_PATH","jsonPath":"$.age","operator":"EQUALS","expected":18},
                  {"id":"header","type":"HEADER","headerName":"content-type","operator":"CONTAINS","expected":"application/json"},
                  {"id":"schema","type":"JSON_SCHEMA","expectedSchema":{"type":"object","required":["age"],"properties":{"age":{"type":"integer"}}}}
                ]
                """);
        var rules = new ArrayList<AssertionRule>();
        for (var node : json) {
            rules.add(mapper.treeToValue(node, AssertionRule.class));
        }
        return rules;
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void sharedEngineKeepsConcurrentEvaluationsIndependent() throws Exception {
        var sharedRules = rules();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int index = 0; index < 40; index++) {
                final boolean expectedPass = index % 2 == 0;
                tasks.add(() -> {
                    var evaluation = engine.evaluate(sharedRules, response(expectedPass ? 400 : 500, 10));
                    return evaluation.passed() == expectedPass && evaluation.assertionResults().size() == 5;
                });
            }
            for (var future : pool.invokeAll(tasks, 10, java.util.concurrent.TimeUnit.SECONDS)) {
                assertTrue(future.get());
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private HttpResponseData response(int status, long latency) {
        return new HttpResponseData(status, Map.of("Content-Type", List.of("application/json")),
                "{\"age\":18}", latency, null);
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void realHttpResponseRunsThroughAllFiveEvaluators() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] body = "{\"age\":18}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var request = new com.platform.worker.model.ResolvedHttpRequest(org.springframework.http.HttpMethod.GET,
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Map.of(), null);
            var response = new com.platform.worker.runner.WebClientHttpRunner(mapper)
                    .execute(request, java.time.Duration.ofSeconds(3)).block(java.time.Duration.ofSeconds(5));
            assertNotNull(response);
            assertNull(response.error());
            var rules = new ArrayList<>(rules());
            // Generous deterministic limit: this test checks wiring, not machine speed.
            rules.set(1, new AssertionRule("latency", "RESPONSE_TIME", mapper.readTree("10000"),
                    "LESS_THAN", null, null, null));
            var evaluation = engine.evaluate(rules, response);
            assertTrue(evaluation.passed());
            assertEquals(5, evaluation.assertionResults().size());
        } finally {
            server.stop(0);
        }
    }
}