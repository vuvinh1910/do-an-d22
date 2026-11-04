package com.platform.worker.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.platform.worker.runtime.ExtractionRule;
import com.platform.worker.runtime.SequentialExecution;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inbound job payload adhering 100% to docs/api-contract/execution-contract.md.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TestExecutionJob(
        UUID jobId,
        UUID testRunId,
        UUID projectId,
        Environment environment,
        Settings settings,
        List<JobTestCase> testCases
) {
    public TestExecutionJob {
        if (jobId == null || testRunId == null || projectId == null || environment == null || testCases == null) {
            throw new IllegalArgumentException("TestExecutionJob requires jobId, testRunId, projectId, environment and testCases");
        }
        if (testCases.isEmpty()) {
            throw new IllegalArgumentException("testCases must not be empty");
        }
        settings = settings == null ? Settings.defaultSettings() : settings;
        testCases = List.copyOf(testCases);
        var ids = new HashSet<UUID>();
        var indexes = new HashSet<Integer>();
        for (var tc : testCases) {
            if (tc == null || !ids.add(tc.id()) || !indexes.add(tc.orderIndex())) {
                throw new IllegalArgumentException("Test cases require unique ids and unique order indexes");
            }
        }
    }

    public List<SequentialExecution.Step> toSteps() {
        return testCases.stream()
                .map(JobTestCase::toStep)
                .toList();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Environment(
            UUID id,
            String name,
            String baseUrl,
            Map<String, JsonNode> variables
    ) {
        public Environment {
            if (id == null || name == null || name.isBlank() || baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("Environment requires id, name and baseUrl");
            }
            if (variables == null) {
                variables = Map.of();
            } else {
                Map<String, JsonNode> copy = new LinkedHashMap<>();
                variables.forEach((k, v) -> {
                    if (k == null || k.isBlank()) {
                        throw new IllegalArgumentException("Variable name must not be blank");
                    }
                    if (v == null) {
                        throw new IllegalArgumentException("Variable value must not be Java null");
                    }
                    copy.put(k, ModelSnapshots.json(v));
                });
                variables = Map.copyOf(copy);
            }
        }

        @Override
        public Map<String, JsonNode> variables() {
            Map<String, JsonNode> copy = new LinkedHashMap<>();
            variables.forEach((k, v) -> copy.put(k, ModelSnapshots.json(v)));
            return Map.copyOf(copy);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Settings(
            long timeoutMs,
            int maxRetries,
            int concurrency,
            boolean stopOnFirstFailure
    ) {
        public Settings {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("timeoutMs must be positive");
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must not be negative");
            }
            if (concurrency <= 0) {
                throw new IllegalArgumentException("concurrency must be positive");
            }
        }

        @JsonCreator
        public static Settings of(
                @JsonProperty("timeoutMs") Long timeoutMs,
                @JsonProperty("maxRetries") Integer maxRetries,
                @JsonProperty("concurrency") Integer concurrency,
                @JsonProperty("stopOnFirstFailure") Boolean stopOnFirstFailure
        ) {
            return new Settings(
                    timeoutMs != null ? timeoutMs : 10000L,
                    maxRetries != null ? maxRetries : 0,
                    concurrency != null ? concurrency : 1,
                    stopOnFirstFailure != null ? stopOnFirstFailure : false
            );
        }

        public static Settings defaultSettings() {
            return of(null, null, null, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobTestCase(
            UUID id,
            String operationId,
            String name,
            String testType,
            int orderIndex,
            TestCaseRequest request,
            List<AssertionRule> assertions,
            List<ExtractionRule> extractions
    ) {
        public JobTestCase {
            if (id == null || name == null || name.isBlank() || testType == null || testType.isBlank() || request == null) {
                throw new IllegalArgumentException("JobTestCase requires id, name, testType and request");
            }
            assertions = assertions == null ? List.of() : List.copyOf(assertions);
            extractions = extractions == null ? List.of() : List.copyOf(extractions);
        }

        public SequentialExecution.Step toStep() {
            return new SequentialExecution.Step(
                    id.toString(),
                    orderIndex,
                    request,
                    assertions,
                    extractions
            );
        }
    }
}

