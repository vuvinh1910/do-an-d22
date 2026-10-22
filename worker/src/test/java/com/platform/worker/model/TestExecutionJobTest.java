package com.platform.worker.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.platform.worker.runtime.ExtractionRule;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TestExecutionJobTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesFromContractFixtureSuccessfully() throws Exception {
        TestExecutionJob job;
        try (InputStream stream = getClass().getResourceAsStream("/fixtures/test-execution-job.json")) {
            assertNotNull(stream, "Fixture test-execution-job.json must exist");
            job = mapper.readValue(stream, TestExecutionJob.class);
        }

        assertEquals(UUID.fromString("f78a1092-23bd-4601-831d-b8d0092fa112"), job.jobId());
        assertEquals(UUID.fromString("b182cb22-c941-4556-9b88-518da06e0019"), job.testRunId());
        assertEquals(UUID.fromString("73c6838a-ff99-4c12-9c12-3200df189021"), job.projectId());

        var env = job.environment();
        assertEquals(UUID.fromString("e9324a33-3112-4211-9a77-440182aa1234"), env.id());
        assertEquals("Staging", env.name());
        assertEquals("https://api-staging.example.com", env.baseUrl());
        assertTrue(env.variables().containsKey("auth_token"));
        assertEquals("comp_9981", env.variables().get("companyId").asText());

        var settings = job.settings();
        assertEquals(10000L, settings.timeoutMs());
        assertEquals(1, settings.maxRetries());
        assertEquals(5, settings.concurrency());
        assertFalse(settings.stopOnFirstFailure());

        assertEquals(1, job.testCases().size());
        var tc = job.testCases().get(0);
        assertEquals(UUID.fromString("e4b17f8a-9821-4f7b-a2c9-d58611122334"), tc.id());
        assertEquals("createUser", tc.operationId());
        assertEquals("Create user boundary test", tc.name());
        assertEquals("BOUNDARY", tc.testType());
        assertEquals(1, tc.orderIndex());

        var req = tc.request();
        assertEquals("POST", req.method());
        assertEquals("/api/v1/users", req.path());
        assertEquals("application/json", req.headers().get("Content-Type"));
        assertEquals("tester01", req.body().path("username").asText());
        assertEquals(18, req.body().path("age").asInt());

        assertEquals(1, tc.assertions().size());
        var assertion = tc.assertions().get(0);
        assertEquals("rule-1", assertion.id());
        assertEquals("STATUS_CODE", assertion.type());
        assertEquals(201, assertion.expected().asInt());

        assertEquals(1, tc.extractions().size());
        var extraction = tc.extractions().get(0);
        assertEquals("createdUserId", extraction.variableName());
        assertEquals("BODY", extraction.source());
        assertEquals("$.id", extraction.jsonPath());
    }

    @Test
    void roundTripSerializationPreservesStructure() throws Exception {
        var envId = UUID.randomUUID();
        var env = new TestExecutionJob.Environment(envId, "Dev", "http://localhost:8080",
                Map.of("token", TextNode.valueOf("secret-123")));
        var settings = new TestExecutionJob.Settings(5000L, 2, 3, true);

        var caseId = UUID.randomUUID();
        var req = new TestCaseRequest("GET", "/ping", Map.of(), Map.of(), Map.of(), null);
        var tc = new TestExecutionJob.JobTestCase(caseId, "pingOp", "Ping test", "VALID", 1, req,
                List.of(new AssertionRule("r1", "STATUS_CODE", IntNode.valueOf(200), null, null, null, "Status 200")),
                List.of());

        var job = new TestExecutionJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                env, settings, List.of(tc));

        String json = mapper.writeValueAsString(job);
        TestExecutionJob deserialized = mapper.readValue(json, TestExecutionJob.class);

        assertEquals(job.jobId(), deserialized.jobId());
        assertEquals(job.testRunId(), deserialized.testRunId());
        assertEquals(job.projectId(), deserialized.projectId());
        assertEquals(job.environment().id(), deserialized.environment().id());
        assertEquals(job.environment().baseUrl(), deserialized.environment().baseUrl());
        assertEquals("secret-123", deserialized.environment().variables().get("token").asText());
        assertEquals(5000L, deserialized.settings().timeoutMs());
        assertEquals(2, deserialized.settings().maxRetries());
        assertEquals(3, deserialized.settings().concurrency());
        assertTrue(deserialized.settings().stopOnFirstFailure());
        assertEquals(1, deserialized.testCases().size());
        assertEquals(caseId, deserialized.testCases().get(0).id());
    }

    @Test
    void convertsToSequentialExecutionSteps() {
        var caseId = UUID.randomUUID();
        var req = new TestCaseRequest("GET", "/health", Map.of(), Map.of(), Map.of(), null);
        var rule = new AssertionRule("r1", "STATUS_CODE", IntNode.valueOf(200), null, null, null, null);
        var ext = new ExtractionRule("status", "BODY", "$.status", null, null);
        var tc = new TestExecutionJob.JobTestCase(caseId, "healthCheck", "Health", "VALID", 1, req,
                List.of(rule), List.of(ext));

        var job = new TestExecutionJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new TestExecutionJob.Environment(UUID.randomUUID(), "Test", "http://test", Map.of()),
                null, List.of(tc));

        var steps = job.toSteps();
        assertEquals(1, steps.size());
        var step = steps.get(0);
        assertEquals(caseId.toString(), step.id());
        assertEquals(1, step.orderIndex());
        assertEquals("GET", step.request().method());
        assertEquals(1, step.assertions().size());
        assertEquals(1, step.extractions().size());
    }

    @Test
    void defaultsMissingSettings() throws Exception {
        String minimalJson = """
        {
          "jobId": "f78a1092-23bd-4601-831d-b8d0092fa112",
          "testRunId": "b182cb22-c941-4556-9b88-518da06e0019",
          "projectId": "73c6838a-ff99-4c12-9c12-3200df189021",
          "environment": {
            "id": "e9324a33-3112-4211-9a77-440182aa1234",
            "name": "Default Env",
            "baseUrl": "http://api.local"
          },
          "testCases": [
            {
              "id": "e4b17f8a-9821-4f7b-a2c9-d58611122334",
              "name": "Case 1",
              "testType": "VALID",
              "orderIndex": 1,
              "request": {
                "method": "GET",
                "path": "/test",
                "headers": {},
                "pathParams": {},
                "queryParams": {}
              }
            }
          ]
        }
        """;

        var job = mapper.readValue(minimalJson, TestExecutionJob.class);
        assertNotNull(job.settings());
        assertEquals(10000L, job.settings().timeoutMs());
        assertEquals(0, job.settings().maxRetries());
        assertEquals(1, job.settings().concurrency());
        assertFalse(job.settings().stopOnFirstFailure());
    }

    @Test
    void rejectsInvalidSettingsValues() {
        assertThrows(IllegalArgumentException.class, () -> new TestExecutionJob.Settings(0L, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new TestExecutionJob.Settings(-100L, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new TestExecutionJob.Settings(1000L, -1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new TestExecutionJob.Settings(1000L, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new TestExecutionJob.Settings(1000L, 0, -5, false));
    }

    @Test
    void rejectsInvalidEnvironment() {
        assertThrows(IllegalArgumentException.class, () ->
                new TestExecutionJob.Environment(null, "Name", "http://base", Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new TestExecutionJob.Environment(UUID.randomUUID(), "  ", "http://base", Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new TestExecutionJob.Environment(UUID.randomUUID(), "Name", "   ", Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new TestExecutionJob.Environment(UUID.randomUUID(), "Name", "http://base", Map.of(" ", TextNode.valueOf("val"))));
    }

    @Test
    void rejectsInvalidJobTestCase() {
        var req = new TestCaseRequest("GET", "/test", Map.of(), Map.of(), Map.of(), null);
        assertThrows(IllegalArgumentException.class, () ->
                new TestExecutionJob.JobTestCase(null, "op", "name", "VALID", 1, req, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new TestExecutionJob.JobTestCase(UUID.randomUUID(), "op", "  ", "VALID", 1, req, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new TestExecutionJob.JobTestCase(UUID.randomUUID(), "op", "name", "  ", 1, req, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new TestExecutionJob.JobTestCase(UUID.randomUUID(), "op", "name", "VALID", 1, null, List.of(), List.of()));
    }

    @Test
    void rejectsDuplicateTestCaseIdOrOrderIndex() {
        var env = new TestExecutionJob.Environment(UUID.randomUUID(), "Env", "http://base", Map.of());
        var req = new TestCaseRequest("GET", "/test", Map.of(), Map.of(), Map.of(), null);

        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();

        // Duplicate id
        var tc1 = new TestExecutionJob.JobTestCase(id1, "op1", "name1", "VALID", 1, req, List.of(), List.of());
        var tc2 = new TestExecutionJob.JobTestCase(id1, "op2", "name2", "VALID", 2, req, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () ->
                new TestExecutionJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), env, null, List.of(tc1, tc2)));

        // Duplicate order index
        var tc3 = new TestExecutionJob.JobTestCase(id1, "op1", "name1", "VALID", 1, req, List.of(), List.of());
        var tc4 = new TestExecutionJob.JobTestCase(id2, "op2", "name2", "VALID", 1, req, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () ->
                new TestExecutionJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), env, null, List.of(tc3, tc4)));
    }

    @Test
    void rejectsEmptyTestCases() {
        var env = new TestExecutionJob.Environment(UUID.randomUUID(), "Env", "http://base", Map.of());
        assertThrows(IllegalArgumentException.class, () ->
                new TestExecutionJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), env, null, List.of()));
    }

    @Test
    void defensiveCopiesPreserveImmutability() {
        var env = new TestExecutionJob.Environment(UUID.randomUUID(), "Env", "http://base",
                Map.of("key", TextNode.valueOf("original")));

        var vars = env.variables();
        assertEquals("original", vars.get("key").asText());

        // Modifying returned JsonNode does not mutate inner state
        var obj = new ObjectNode(mapper.getNodeFactory());
        obj.put("nested", "original");
        var env2 = new TestExecutionJob.Environment(UUID.randomUUID(), "Env", "http://base",
                Map.of("complex", obj));
        ((ObjectNode) env2.variables().get("complex")).put("nested", "mutated");
        assertEquals("original", env2.variables().get("complex").get("nested").asText());
    }
}
