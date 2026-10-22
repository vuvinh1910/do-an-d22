package com.platform.worker.service;

import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.ResolvedHttpRequest;
import com.platform.worker.model.TestExecutionJob;
import com.platform.worker.persistence.TestResultStore;
import com.platform.worker.runner.RetryingHttpRunner;
import com.platform.worker.runner.WebClientHttpRunner;
import com.platform.worker.runtime.SequentialExecution;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * High-performance, streaming job execution orchestrator.
 * Persists test results in real-time as each step completes, preventing data loss
 * if a job is cancelled or interrupted mid-flight.
 */
@Service
public class JobExecutionService {
    private final BiFunction<ResolvedHttpRequest, Duration, Mono<HttpResponseData>> runner;
    private final SequentialExecution customExecution;
    private final TestResultStore resultStore;

    public JobExecutionService(WebClientHttpRunner runner, TestResultStore resultStore) {
        this(runner::execute, resultStore);
    }

    public JobExecutionService(BiFunction<ResolvedHttpRequest, Duration, Mono<HttpResponseData>> runner, TestResultStore resultStore) {
        this.runner = Objects.requireNonNull(runner, "HTTP runner is required");
        this.resultStore = Objects.requireNonNull(resultStore, "TestResultStore is required");
        this.customExecution = null;
    }

    JobExecutionService(SequentialExecution execution, TestResultStore resultStore) {
        this.runner = null;
        this.customExecution = Objects.requireNonNull(execution, "SequentialExecution is required");
        this.resultStore = Objects.requireNonNull(resultStore, "TestResultStore is required");
    }

    public List<UUID> executeJob(TestExecutionJob job) {
        if (job == null) {
            throw new IllegalArgumentException("TestExecutionJob is required");
        }

        Map<String, TestExecutionJob.JobTestCase> testCasesById = job.testCases().stream()
                .collect(Collectors.toMap(tc -> tc.id().toString(), Function.identity()));

        Duration timeout = Duration.ofMillis(job.settings().timeoutMs());
        List<SequentialExecution.Step> steps = job.toSteps();

        SequentialExecution executor = customExecution != null
                ? customExecution
                : new SequentialExecution(new RetryingHttpRunner(runner, job.settings().maxRetries()));

        List<UUID> savedResultIds = new ArrayList<>();

        // Real-time streaming persistence: persist each step immediately upon completion
        executor.executeStream(
                job.environment().baseUrl(),
                job.environment().variables(),
                steps,
                timeout,
                job.settings().stopOnFirstFailure()
        ).doOnNext(stepResult -> {
            var testCase = testCasesById.get(stepResult.stepId());
            if (testCase == null) {
                throw new IllegalStateException("Step result id not found in job: " + stepResult.stepId());
            }
            UUID savedId = resultStore.save(
                    job.testRunId(),
                    testCase.id(),
                    testCase.name(),
                    testCase.testType(),
                    stepResult
            );
            savedResultIds.add(savedId);
        }).blockLast();

        return List.copyOf(savedResultIds);
    }
}
