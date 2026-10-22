package com.platform.worker.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.result.TestResultMapper;
import com.platform.worker.runtime.SequentialExecution.StepResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Blocking JPA boundary. Call outside the WebClient event loop; no implicit retry or queue publication. */
@Service
public class TestResultStore {
    private final TestResultRepository repository;
    private final ObjectMapper json;
    private final TestResultMapper mapper = new TestResultMapper(Clock.systemUTC());

    public TestResultStore(TestResultRepository repository, ObjectMapper json) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.json = java.util.Objects.requireNonNull(json).copy();
    }

    /** Each invocation inserts a new row. Caller must not retry blindly after an uncertain commit. */
    @Transactional
    public UUID save(UUID testRunId, UUID testCaseId, String name, String testType, StepResult raw) {
        var safe = mapper.map(testRunId, testCaseId, name, testType, raw);
        UUID id = UUID.randomUUID();
        var entity = new TestResultEntity(id, safe, json.valueToTree(safe.assertionResults()));
        repository.saveAndFlush(entity);
        return id;
    }
}