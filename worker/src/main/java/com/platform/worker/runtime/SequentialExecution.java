package com.platform.worker.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.worker.assertion.AssertionEngine;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.ResolvedHttpRequest;
import com.platform.worker.model.TestCaseRequest;
import com.platform.worker.runner.RequestUriBuilder;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Internal D3 orchestration, supporting both batch collection and real-time step streaming. */
public final class SequentialExecution {
    private final BiFunction<ResolvedHttpRequest, Duration, Mono<HttpResponseData>> runner;
    private final VariableResolver resolver = new VariableResolver();
    private final ResponseExtractor extractor = new ResponseExtractor();
    private final RequestUriBuilder uris = new RequestUriBuilder();
    private final AssertionEngine assertions = new AssertionEngine();

    public SequentialExecution(BiFunction<ResolvedHttpRequest, Duration, Mono<HttpResponseData>> runner) {
        if (runner == null) {
            throw new IllegalArgumentException("HTTP runner is required");
        }
        this.runner = runner;
    }

    public Mono<List<StepResult>> execute(String baseUrl, Map<String, JsonNode> variables,
                                         List<Step> steps, Duration timeout, boolean stopOnFirstFailure) {
        return executeStream(baseUrl, variables, steps, timeout, stopOnFirstFailure)
                .collectList()
                .map(List::copyOf);
    }

    /** Stream step results in real-time as each HTTP exchange and assertion completes. */
    public Flux<StepResult> executeStream(String baseUrl, Map<String, JsonNode> variables,
                                          List<Step> steps, Duration timeout, boolean stopOnFirstFailure) {
        if (baseUrl == null || baseUrl.isBlank() || steps == null || timeout == null
                || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofNanos(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Execution requires base URL, steps and a valid timeout");
        }
        var initial = new RuntimeContext(variables);
        var variableNames = List.copyOf(variables.keySet());
        var ids = new HashSet<String>();
        var indexes = new HashSet<Integer>();
        var snapshot = List.copyOf(steps);
        for (var step : snapshot) {
            if (step.id() == null || step.id().isBlank() || !ids.add(step.id()) || !indexes.add(step.orderIndex())) {
                throw new IllegalArgumentException("Steps require unique non-blank ids and unique order indexes");
            }
        }
        var ordered = snapshot.stream().sorted(Comparator.comparingInt(Step::orderIndex)).toList();
        return Flux.defer(() -> {
            // Construct a fresh context on every subscription, including repeated runs of this Flux.
            var contextValues = new HashMap<String, JsonNode>();
            variableNames.forEach(key -> contextValues.put(key, initial.require(key)));
            var context = new RuntimeContext(contextValues);
            boolean[] stopped = {false};
            return Flux.fromIterable(ordered).concatMap(step -> {
                if (stopped[0]) {
                    return Mono.just(new StepResult(step.id(), Status.SKIPPED, null, null, null,
                            "Skipped after previous step failure"));
                }
                return runStep(baseUrl, step, context, timeout).doOnNext(result -> {
                    if (stopOnFirstFailure && result.status() != Status.PASSED) {
                        stopped[0] = true;
                    }
                });
            });
        });
    }

    private Mono<StepResult> runStep(String baseUrl, Step step, RuntimeContext context, Duration timeout) {
        return Mono.defer(() -> {
            var template = resolver.resolve(step.request(), context);
            var headers = new HashMap<String, List<String>>();
            template.headers().forEach((key, value) -> headers.put(key, List.of(value)));
            var request = new ResolvedHttpRequest(HttpMethod.valueOf(template.method()),
                    uris.build(resolver.text(baseUrl, context), template), headers, template.body());
            return runner.apply(request, timeout)
                    .switchIfEmpty(Mono.error(new IllegalStateException("HTTP runner returned no response")))
                    .map(response -> {
                        if (response.error() != null) {
                            return new StepResult(step.id(), Status.ERROR, request, response, null,
                                    "HTTP execution failed");
                        }
                        try {
                            var evaluation = assertions.evaluate(step.assertions(), response);
                            if (!evaluation.passed()) {
                                return new StepResult(step.id(), Status.FAILED, request, response, evaluation, null);
                            }
                            context.putAll(extractor.extract(step.extractions(), response));
                            return new StepResult(step.id(), Status.PASSED, request, response, evaluation, null);
                        } catch (RuntimeException error) {
                            return new StepResult(step.id(), Status.ERROR, request, response, null,
                                    "Assertion or extraction could not complete");
                        }
                    }).onErrorResume(RuntimeException.class, error -> Mono.just(new StepResult(step.id(),
                            Status.ERROR, request, null, null, "HTTP execution could not complete")));
        }).onErrorResume(RuntimeException.class, error -> Mono.just(new StepResult(step.id(), Status.ERROR,
                null, null, null, "Request could not be resolved or executed")));
    }

    public record Step(String id, int orderIndex, TestCaseRequest request,
                       List<AssertionRule> assertions, List<ExtractionRule> extractions) {
        public Step {
            if (request == null || assertions == null || extractions == null) {
                throw new IllegalArgumentException("Step requires request, assertions and extractions");
            }
            assertions = List.copyOf(assertions);
            extractions = List.copyOf(extractions);
        }
    }

    public enum Status { PASSED, FAILED, ERROR, SKIPPED }

    /** Raw snapshots are internal and must be redacted before persistence/publication. */
    public record StepResult(String stepId, Status status, ResolvedHttpRequest request,
                             HttpResponseData response, AssertionEngine.Evaluation evaluation, String error) {
    }
}
