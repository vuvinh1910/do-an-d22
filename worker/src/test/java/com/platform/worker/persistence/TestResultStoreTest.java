package com.platform.worker.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.HttpResponseData;
import com.platform.worker.runtime.SequentialExecution;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestResultStoreTest {
    private final TestResultRepository repository = mock(TestResultRepository.class);
    private final TestResultStore store = new TestResultStore(repository, new ObjectMapper());
    private final UUID runId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();

    @Test
    void mapsExistingColumnsAndRedactsBeforeRepositoryReceivesEntity() {
        var raw = new SequentialExecution.StepResult(caseId.toString(), SequentialExecution.Status.ERROR, null,
                new HttpResponseData(502, Map.of("Set-Cookie", java.util.List.of("secret")), "secret", 12, "secret"),
                null, "secret");
        var id = store.save(runId, caseId, "Case", "VALID", raw);
        var captured = ArgumentCaptor.forClass(TestResultEntity.class);
        verify(repository).saveAndFlush(captured.capture());
        var entity = captured.getValue();
        assertEquals(id, entity.getId());
        assertEquals(runId, entity.getTestRunId());
        assertEquals(caseId, entity.getTestCaseId());
        assertEquals("ERROR", entity.getStatus().name());
        assertEquals(502, entity.getHttpStatus());
        assertEquals(12L, entity.getResponseTimeMs());
        assertNotNull(entity.getCreatedAt());
        assertNull(entity.getRequestSent());
        assertTrue(entity.getAssertionDetails().isArray());
        assertFalse(entity.getResponseReceived().toString().contains("secret"));
        assertFalse(entity.getErrorMessage().contains("secret"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) entity.getResponseReceived()).put("body", "changed");
        assertEquals("[REDACTED]", entity.getResponseReceived().path("body").asText());
    }

    @Test
    void invalidIdentityNeverReachesRepository() {
        assertThrows(IllegalArgumentException.class, () -> store.save(runId, caseId, "Case", "VALID",
                new SequentialExecution.StepResult("wrong", SequentialExecution.Status.SKIPPED, null, null, null, null)));
        verifyNoInteractions(repository);
    }

    @Test
    void persistenceFailurePropagatesWithoutRetryOrFalseSuccess() {
        var failure = new org.springframework.dao.DataIntegrityViolationException("FK rejected");
        when(repository.saveAndFlush(any())).thenThrow(failure);
        var raw = new SequentialExecution.StepResult(caseId.toString(), SequentialExecution.Status.SKIPPED,
                null, null, null, null);
        assertSame(failure, assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> store.save(runId, caseId, "Case", "VALID", raw)));
        verify(repository, times(1)).saveAndFlush(any());
    }
}