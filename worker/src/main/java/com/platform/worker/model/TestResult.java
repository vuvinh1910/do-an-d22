package com.platform.worker.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire result snapshot. Construct publication-safe instances through TestResultMapper. */
public record TestResult(UUID testRunId, UUID testCaseId, String name, String testType,
                         Status status, Integer httpStatus, Long responseTimeMs,
                         JsonNode requestSent, JsonNode responseReceived,
                         List<AssertionResult> assertionResults, String errorMessage,
                         @JsonFormat(shape = JsonFormat.Shape.STRING) Instant executedAt) {
    public enum Status { PASSED, FAILED, ERROR, SKIPPED }

    public TestResult {
        if (testRunId == null || testCaseId == null || name == null || name.isBlank()
                || testType == null || testType.isBlank() || status == null || executedAt == null
                || assertionResults == null || (responseTimeMs != null && responseTimeMs < 0)) {
            throw new IllegalArgumentException("Result requires identity, metadata, status and execution time");
        }
        requestSent = requestSent == null || requestSent.isNull() ? null : ModelSnapshots.json(requestSent);
        responseReceived = responseReceived == null || responseReceived.isNull() ? null : ModelSnapshots.json(responseReceived);
        assertionResults = List.copyOf(assertionResults);
    }

    @Override
    public JsonNode requestSent() {
        return ModelSnapshots.json(requestSent);
    }

    @Override
    public JsonNode responseReceived() {
        return ModelSnapshots.json(responseReceived);
    }
}