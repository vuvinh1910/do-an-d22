package com.platform.worker.consumer;

import com.fasterxml.jackson.databind.node.TextNode;
import com.platform.worker.model.TestCaseRequest;
import com.platform.worker.model.TestExecutionJob;
import com.platform.worker.service.JobExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobConsumerTest {
    private final JobExecutionService service = mock(JobExecutionService.class);
    private final JobConsumer consumer = new JobConsumer(service);

    private TestExecutionJob createValidJob() {
        var env = new TestExecutionJob.Environment(UUID.randomUUID(), "Dev", "http://localhost:8080", Map.of());
        var req = new TestCaseRequest("GET", "/ping", Map.of(), Map.of(), Map.of(), null);
        var tc = new TestExecutionJob.JobTestCase(UUID.randomUUID(), "ping", "Ping", "VALID", 1, req, List.of(), List.of());
        return new TestExecutionJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), env, null, List.of(tc));
    }

    @Test
    void consumerExecutesValidJobSuccessfully() {
        var job = createValidJob();
        var id = UUID.randomUUID();
        when(service.executeJob(job)).thenReturn(List.of(id));

        var result = consumer.handleTestExecutionJob(job);
        assertEquals(List.of(id), result);
        verify(service, times(1)).executeJob(job);
    }

    @Test
    void rejectsNullJobWithoutRequeue() {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleTestExecutionJob(null));
        verifyNoInteractions(service);
    }

    @Test
    void wrapsIllegalArgumentExceptionWithoutRequeue() {
        var job = createValidJob();
        when(service.executeJob(job)).thenThrow(new IllegalArgumentException("Invalid state"));

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleTestExecutionJob(job));
        verify(service, times(1)).executeJob(job);
    }

    @Test
    void propagatesTransientExceptionForRequeue() {
        var job = createValidJob();
        when(service.executeJob(job)).thenThrow(new RuntimeException("Database down"));

        assertThrows(RuntimeException.class, () -> consumer.handleTestExecutionJob(job));
        verify(service, times(1)).executeJob(job);
    }
}
