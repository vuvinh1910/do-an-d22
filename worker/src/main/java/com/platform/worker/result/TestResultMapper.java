package com.platform.worker.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.platform.worker.model.AssertionResult;
import com.platform.worker.model.TestResult;
import com.platform.worker.runtime.SequentialExecution.StepResult;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Conservative publication boundary: payloads never enter the result, even for text/partial responses. */
public final class TestResultMapper {
    private final Clock clock;

    public TestResultMapper(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Execution clock is required");
        }
        this.clock = clock;
    }

    /** Metadata must come from the job, not interpolated request/response values. */
    public TestResult map(UUID testRunId, UUID testCaseId, String name, String testType, StepResult step) {
        if (step == null || step.status() == null || testCaseId == null
                || !testCaseId.toString().equals(step.stepId())) {
            throw new IllegalArgumentException("Result identity must match the executed step");
        }
        var status = TestResult.Status.valueOf(step.status().name());
        var response = step.response();
        var evaluation = step.evaluation();
        boolean evaluated = status == TestResult.Status.PASSED || status == TestResult.Status.FAILED;
        if (evaluated && (response == null || response.error() != null || evaluation == null
                || evaluation.passed() != (status == TestResult.Status.PASSED))) {
            throw new IllegalArgumentException("Completed result requires consistent HTTP and assertion evidence");
        }
        if (status == TestResult.Status.SKIPPED
                && (step.request() != null || response != null || evaluation != null)) {
            throw new IllegalArgumentException("Skipped result cannot contain execution evidence");
        }
        JsonNode requestSnapshot = null;
        if (step.request() != null) {
            var node = JsonNodeFactory.instance.objectNode();
            node.put("url", "[REDACTED]");
            node.put("method", step.request().method().name());
            node.putObject("headers");
            node.put("body", "[REDACTED]");
            requestSnapshot = node;
        }
        JsonNode responseSnapshot = null;
        if (response != null && response.statusCode() != null) {
            var node = JsonNodeFactory.instance.objectNode();
            node.put("statusCode", response.statusCode());
            node.putObject("headers");
            node.put("body", "[REDACTED]");
            responseSnapshot = node;
        }
        List<AssertionResult> rules = evaluated ? evaluation.assertionResults().stream()
                .map(rule -> new AssertionResult(rule.ruleId(), rule.type(), rule.passed(),
                        JsonNodeFactory.instance.textNode("[REDACTED]"),
                        JsonNodeFactory.instance.textNode("[REDACTED]"),
                        rule.passed() ? "Assertion passed" : "Assertion failed")).toList() : List.of();
        String error = switch (status) {
            case PASSED -> null;
            case FAILED -> "One or more assertions failed";
            case ERROR -> "Execution could not complete";
            case SKIPPED -> "Skipped after previous step failure";
        };
        return new TestResult(testRunId, testCaseId, name, testType, status,
                response == null ? null : response.statusCode(),
                response == null ? null : response.responseTimeMs(), requestSnapshot,
                responseSnapshot, rules, error, clock.instant());
    }
}