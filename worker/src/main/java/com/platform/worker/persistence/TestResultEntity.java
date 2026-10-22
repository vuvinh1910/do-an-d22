package com.platform.worker.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.worker.model.TestResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Existing V1 table only; no relationship/lifecycle ownership over backend entities. */
@Entity
@Table(name = "test_results")
public class TestResultEntity {
    @Id
    private UUID id;
    @Column(name = "test_run_id", nullable = false)
    private UUID testRunId;
    @Column(name = "test_case_id")
    private UUID testCaseId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TestResult.Status status;
    @Column(name = "http_status")
    private Integer httpStatus;
    @Column(name = "response_time_ms")
    private Long responseTimeMs;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_sent", columnDefinition = "jsonb")
    private JsonNode requestSent;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_received", columnDefinition = "jsonb")
    private JsonNode responseReceived;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assertion_details", columnDefinition = "jsonb")
    private JsonNode assertionDetails;
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
    @Column(name = "created_at")
    private Instant createdAt;

    protected TestResultEntity() {
    }

    TestResultEntity(UUID id, TestResult result, JsonNode assertionDetails) {
        this.id = id;
        testRunId = result.testRunId();
        testCaseId = result.testCaseId();
        status = result.status();
        httpStatus = result.httpStatus();
        responseTimeMs = result.responseTimeMs();
        requestSent = result.requestSent();
        responseReceived = result.responseReceived();
        this.assertionDetails = assertionDetails.deepCopy();
        errorMessage = result.errorMessage();
        createdAt = result.executedAt();
    }

    public UUID getId() { return id; }
    public UUID getTestRunId() { return testRunId; }
    public UUID getTestCaseId() { return testCaseId; }
    public TestResult.Status getStatus() { return status; }
    public Integer getHttpStatus() { return httpStatus; }
    public Long getResponseTimeMs() { return responseTimeMs; }
    public JsonNode getRequestSent() { return requestSent == null ? null : requestSent.deepCopy(); }
    public JsonNode getResponseReceived() { return responseReceived == null ? null : responseReceived.deepCopy(); }
    public JsonNode getAssertionDetails() { return assertionDetails.deepCopy(); }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}