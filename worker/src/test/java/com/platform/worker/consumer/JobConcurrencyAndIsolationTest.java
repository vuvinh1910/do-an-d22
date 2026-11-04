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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobConcurrencyAndIsolationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TestResultRepository repository = mock(TestResultRepository.class);
    private HttpServer server;
    private int port;
    private final List<String> capturedAuthHeaders = new CopyOnWriteArrayList<>();

    private JobConsumer consumer;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        server.createContext("/api/v1/auth-check", exchange -> {
            try (exchange) {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth != null) {
                    capturedAuthHeaders.add(auth);
                }
                byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
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
    void parallelJobsExecuteConcurrentlyWithIsolatedContexts() throws Exception {
        var runIdA = UUID.randomUUID();
        var runIdB = UUID.randomUUID();

        // Job A: Bearer token-AAA
        var reqA = new TestCaseRequest("GET", "/api/v1/auth-check",
                Map.of("Authorization", "Bearer {{auth_token}}"), Map.of(), Map.of(), null);
        var tcA = new TestExecutionJob.JobTestCase(UUID.randomUUID(), "checkA", "Case A", "VALID", 1,
                reqA, List.of(new AssertionRule("r1", "STATUS_CODE", IntNode.valueOf(200), null, null, null, null)), List.of());
        var envA = new TestExecutionJob.Environment(UUID.randomUUID(), "EnvA", "http://127.0.0.1:" + port,
                Map.of("auth_token", TextNode.valueOf("token-AAA")));
        var jobA = new TestExecutionJob(UUID.randomUUID(), runIdA, UUID.randomUUID(), envA, null, List.of(tcA));

        // Job B: Bearer token-BBB
        var reqB = new TestCaseRequest("GET", "/api/v1/auth-check",
                Map.of("Authorization", "Bearer {{auth_token}}"), Map.of(), Map.of(), null);
        var tcB = new TestExecutionJob.JobTestCase(UUID.randomUUID(), "checkB", "Case B", "VALID", 1,
                reqB, List.of(new AssertionRule("r2", "STATUS_CODE", IntNode.valueOf(200), null, null, null, null)), List.of());
        var envB = new TestExecutionJob.Environment(UUID.randomUUID(), "EnvB", "http://127.0.0.1:" + port,
                Map.of("auth_token", TextNode.valueOf("token-BBB")));
        var jobB = new TestExecutionJob(UUID.randomUUID(), runIdB, UUID.randomUUID(), envB, null, List.of(tcB));

        // Execute concurrently
        var futureA = CompletableFuture.supplyAsync(() -> consumer.handleTestExecutionJob(jobA));
        var futureB = CompletableFuture.supplyAsync(() -> consumer.handleTestExecutionJob(jobB));

        CompletableFuture.allOf(futureA, futureB).join();

        var resultA = futureA.get();
        var resultB = futureB.get();
        assertEquals(1, resultA.size());
        assertEquals(1, resultB.size());

        // Assert both tokens were sent without collision
        assertTrue(capturedAuthHeaders.contains("Bearer token-AAA"));
        assertTrue(capturedAuthHeaders.contains("Bearer token-BBB"));
        assertEquals(2, capturedAuthHeaders.size());

        // Assert repository saved both results
        var captor = ArgumentCaptor.forClass(TestResultEntity.class);
        verify(repository, times(2)).saveAndFlush(captor.capture());
        var savedRuns = captor.getAllValues().stream().map(TestResultEntity::getTestRunId).toList();
        assertTrue(savedRuns.contains(runIdA));
        assertTrue(savedRuns.contains(runIdB));
    }
}
