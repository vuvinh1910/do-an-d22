package com.platform.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.platform.worker.assertion.AssertionEngine;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.TestCaseRequest;
import com.platform.worker.model.TestExecutionJob;
import com.platform.worker.persistence.TestResultStore;
import com.platform.worker.runtime.SequentialExecution;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobExecutionServiceTest {
    private final TestResultStore resultStore = mock(TestResultStore.class);
    private final UUID runId = UUID.randomUUID();
    private final UUID caseId1 = UUID.randomUUID();
    private final UUID caseId2 = UUID.randomUUID();

    @Test
    void executesJobAndPersistsResultsInStream() {
        var req1 = new TestCaseRequest("GET", "/first", Map.of(), Map.of(), Map.of(), null);
        var req2 = new TestCaseRequest("GET", "/second", Map.of(), Map.of(), Map.of(), null);

        var rule1 = new AssertionRule("r1", "STATUS_CODE", IntNode.valueOf(200), null, null, null, null);
        var tc1 = new TestExecutionJob.JobTestCase(caseId1, "op1", "First Test", "VALID", 1, req1, List.of(rule1), List.of());
        var tc2 = new TestExecutionJob.JobTestCase(caseId2, "op2", "Second Test", "VALID", 2, req2, List.of(), List.of());

        var env = new TestExecutionJob.Environment(UUID.randomUUID(), "Staging", "http://localhost:8080",
                Map.of("token", TextNode.valueOf("abc")));
        var settings = new TestExecutionJob.Settings(5000L, 0, 1, false);

        var job = new TestExecutionJob(UUID.randomUUID(), runId, UUID.randomUUID(), env, settings, List.of(tc1, tc2));

        var execution = mock(SequentialExecution.class);
        var stepResult1 = new SequentialExecution.StepResult(
                caseId1.toString(),
                SequentialExecution.Status.PASSED,
                null,
                new HttpResponseData(200, Map.of(), "ok", 15, null),
                new AssertionEngine.Evaluation(List.of()),
                null
        );
        var stepResult2 = new SequentialExecution.StepResult(
                caseId2.toString(),
                SequentialExecution.Status.PASSED,
                null,
                new HttpResponseData(200, Map.of(), "ok2", 20, null),
                new AssertionEngine.Evaluation(List.of()),
                null
        );

        when(execution.executeStream(eq("http://localhost:8080"), any(), anyList(), any(), eq(false)))
                .thenReturn(Flux.just(stepResult1, stepResult2));

        UUID savedId1 = UUID.randomUUID();
        UUID savedId2 = UUID.randomUUID();
        when(resultStore.save(eq(runId), eq(caseId1), eq("First Test"), eq("VALID"), eq(stepResult1)))
                .thenReturn(savedId1);
        when(resultStore.save(eq(runId), eq(caseId2), eq("Second Test"), eq("VALID"), eq(stepResult2)))
                .thenReturn(savedId2);

        var service = new JobExecutionService(execution, resultStore);
        var savedIds = service.executeJob(job);

        assertEquals(List.of(savedId1, savedId2), savedIds);
        verify(resultStore, times(1)).save(runId, caseId1, "First Test", "VALID", stepResult1);
        verify(resultStore, times(1)).save(runId, caseId2, "Second Test", "VALID", stepResult2);
    }

    @Test
    void rejectsNullJob() {
        var execution = mock(SequentialExecution.class);
        var service = new JobExecutionService(execution, resultStore);

        assertThrows(IllegalArgumentException.class, () -> service.executeJob(null));
        verifyNoInteractions(resultStore);
    }
}
