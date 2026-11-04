package com.platform.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.TestCaseRequest;
import com.platform.worker.model.TestExecutionJob;
import com.platform.worker.persistence.TestResultEntity;
import com.platform.worker.persistence.TestResultRepository;
import com.platform.worker.persistence.TestResultStore;
import com.platform.worker.runner.WebClientHttpRunner;
import com.platform.worker.runtime.ExtractionRule;
import com.platform.worker.service.JobExecutionService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobConsumerIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TestResultRepository repository = mock(TestResultRepository.class);
    private HttpServer server;
    private int port;
    private final List<String> receivedRequests = new CopyOnWriteArrayList<>();

    private JobConsumer consumer;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        server.createContext("/api/v1/users", exchange -> {
            try (exchange) {
                receivedRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    byte[] response = "{\"id\":1001,\"role\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(201, response.length);
                    exchange.getResponseBody().write(response);
                } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    byte[] response = "{\"users\":[]}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            }
        });

        server.createContext("/api/v1/users/1001", exchange -> {
            try (exchange) {
                receivedRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                byte[] response = "{\"id\":1001,\"name\":\"Alice Admin\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });

        server.createContext("/api/v1/fail", exchange -> {
            try (exchange) {
                receivedRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                byte[] response = "{\"error\":\"Internal Server Error\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, response.length);
                exchange.getResponseBody().write(response);
            }
        });

        server.start();

        var runner = new WebClientHttpRunner(mapper);
        var store = new TestResultStore(repository, mapper);
        var service = new JobExecutionService(runner, store);
        consumer = new JobConsumer(service);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void endToEndExecutionChainingAndPersistence() {
        var runId = UUID.randomUUID();
        var caseId1 = UUID.randomUUID();
        var caseId2 = UUID.randomUUID();

        // Step 1: Create user -> extract id 1001
        var req1 = new TestCaseRequest("POST", "/api/v1/users",
                Map.of("Content-Type", "application/json"), Map.of(), Map.of(),
                mapper.getNodeFactory().objectNode().put("username", "alice"));
        var assertions1 = List.of(
                new AssertionRule("rule-status-1", "STATUS_CODE", IntNode.valueOf(201), null, null, null, null),
                new AssertionRule("rule-role", "JSON_PATH", TextNode.valueOf("ADMIN"), "EQUALS", "$.role", null, null)
        );
        var extractions1 = List.of(
                new ExtractionRule("userId", "BODY", "$.id", null, null)
        );
        var tc1 = new TestExecutionJob.JobTestCase(caseId1, "createUser", "Create User", "VALID", 1,
                req1, assertions1, extractions1);

        // Step 2: Get user using {{userId}}
        var req2 = new TestCaseRequest("GET", "/api/v1/users/{{userId}}",
                Map.of(), Map.of(), Map.of(), null);
        var assertions2 = List.of(
                new AssertionRule("rule-status-2", "STATUS_CODE", IntNode.valueOf(200), null, null, null, null),
                new AssertionRule("rule-name", "JSON_PATH", TextNode.valueOf("Alice Admin"), "EQUALS", "$.name", null, null)
        );
        var tc2 = new TestExecutionJob.JobTestCase(caseId2, "getUser", "Get User", "VALID", 2,
                req2, assertions2, List.of());

        var env = new TestExecutionJob.Environment(UUID.randomUUID(), "Local", "http://127.0.0.1:" + port, Map.of());
        var settings = new TestExecutionJob.Settings(5000L, 0, 1, true);
        var job = new TestExecutionJob(UUID.randomUUID(), runId, UUID.randomUUID(), env, settings, List.of(tc1, tc2));

        List<UUID> savedIds = consumer.handleTestExecutionJob(job);
        assertEquals(2, savedIds.size());

        // Verify HTTP requests made to loopback server
        assertEquals(List.of("POST /api/v1/users", "GET /api/v1/users/1001"), receivedRequests);

        // Verify entities passed to repository
        var captor = ArgumentCaptor.forClass(TestResultEntity.class);
        verify(repository, times(2)).saveAndFlush(captor.capture());
        List<TestResultEntity> savedEntities = captor.getAllValues();

        var entity1 = savedEntities.get(0);
        assertEquals(runId, entity1.getTestRunId());
        assertEquals(caseId1, entity1.getTestCaseId());
        assertEquals("PASSED", entity1.getStatus().name());
        assertEquals(201, entity1.getHttpStatus());
        assertEquals("[REDACTED]", entity1.getRequestSent().path("url").asText());
        assertEquals("[REDACTED]", entity1.getResponseReceived().path("body").asText());
        assertEquals(2, entity1.getAssertionDetails().size());

        var entity2 = savedEntities.get(1);
        assertEquals(runId, entity2.getTestRunId());
        assertEquals(caseId2, entity2.getTestCaseId());
        assertEquals("PASSED", entity2.getStatus().name());
        assertEquals(200, entity2.getHttpStatus());
        assertEquals("[REDACTED]", entity2.getRequestSent().path("url").asText());
        assertEquals("[REDACTED]", entity2.getResponseReceived().path("body").asText());
        assertEquals(2, entity2.getAssertionDetails().size());
    }

    @Test
    @Timeout(15)
    void stepFailureSkipsSubsequentStepsAndSavesSkippedResult() {
        var runId = UUID.randomUUID();
        var caseId1 = UUID.randomUUID();
        var caseId2 = UUID.randomUUID();

        // Step 1: Hits /api/v1/fail (returns 500, but expects 200) -> FAILED
        var req1 = new TestCaseRequest("GET", "/api/v1/fail", Map.of(), Map.of(), Map.of(), null);
        var assertions1 = List.of(
                new AssertionRule("rule-status", "STATUS_CODE", IntNode.valueOf(200), null, null, null, null)
        );
        var tc1 = new TestExecutionJob.JobTestCase(caseId1, "failOp", "Expected 200 but 500", "NEGATIVE", 1,
                req1, assertions1, List.of());

        // Step 2: Should be SKIPPED
        var req2 = new TestCaseRequest("GET", "/api/v1/users", Map.of(), Map.of(), Map.of(), null);
        var tc2 = new TestExecutionJob.JobTestCase(caseId2, "skipOp", "Skipped Case", "VALID", 2,
                req2, List.of(), List.of());

        var env = new TestExecutionJob.Environment(UUID.randomUUID(), "Local", "http://127.0.0.1:" + port, Map.of());
        var settings = new TestExecutionJob.Settings(5000L, 0, 1, true); // stopOnFirstFailure = true
        var job = new TestExecutionJob(UUID.randomUUID(), runId, UUID.randomUUID(), env, settings, List.of(tc1, tc2));

        List<UUID> savedIds = consumer.handleTestExecutionJob(job);
        assertEquals(2, savedIds.size());

        // Only first request was made to server
        assertEquals(List.of("GET /api/v1/fail"), receivedRequests);

        var captor = ArgumentCaptor.forClass(TestResultEntity.class);
        verify(repository, times(2)).saveAndFlush(captor.capture());
        List<TestResultEntity> savedEntities = captor.getAllValues();

        var entity1 = savedEntities.get(0);
        assertEquals("FAILED", entity1.getStatus().name());
        assertEquals(500, entity1.getHttpStatus());

        var entity2 = savedEntities.get(1);
        assertEquals("SKIPPED", entity2.getStatus().name());
        assertNull(entity2.getHttpStatus());
        assertNull(entity2.getRequestSent());
    }

    @Test
    void rejectsPoisonPillPayloadsWithoutRequeue() {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleTestExecutionJob(null));
        verifyNoInteractions(repository);
    }

    @Test
    void databaseFailurePropagatesForAmqpRetryOrRedelivery() {
        doThrow(new DataIntegrityViolationException("Database unavailable"))
                .when(repository).saveAndFlush(any());

        var req = new TestCaseRequest("GET", "/api/v1/users", Map.of(), Map.of(), Map.of(), null);
        var tc = new TestExecutionJob.JobTestCase(UUID.randomUUID(), "listUsers", "List", "VALID", 1,
                req, List.of(), List.of());
        var env = new TestExecutionJob.Environment(UUID.randomUUID(), "Local", "http://127.0.0.1:" + port, Map.of());
        var job = new TestExecutionJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), env, null, List.of(tc));

        assertThrows(DataIntegrityViolationException.class, () -> consumer.handleTestExecutionJob(job));
    }
}
