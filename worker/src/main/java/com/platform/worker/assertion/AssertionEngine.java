package com.platform.worker.assertion;

import com.platform.worker.model.AssertionResult;
import com.platform.worker.model.AssertionRule;
import com.platform.worker.model.HttpResponseData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ordered, exhaustive evaluation of valid rules; execution/configuration errors are not FAIL results. */
public final class AssertionEngine {
    private static final Set<String> TYPES = Set.of(
            "STATUS_CODE", "RESPONSE_TIME", "JSON_PATH", "HEADER", "JSON_SCHEMA");
    private final StatusCodeAssertion status = new StatusCodeAssertion();
    private final ResponseTimeAssertion latency = new ResponseTimeAssertion();
    private final JsonPathAssertion jsonPath = new JsonPathAssertion();
    private final HeaderAssertion header = new HeaderAssertion();
    private final JsonSchemaAssertion schema = new JsonSchemaAssertion();

    public Evaluation evaluate(List<AssertionRule> rules, HttpResponseData response) {
        if (rules == null) {
            throw new IllegalArgumentException("Assertion rules are required; use an empty list explicitly");
        }
        var snapshot = new ArrayList<>(rules);
        var ids = new HashSet<String>();
        for (var rule : snapshot) {
            if (rule == null || rule.id() == null || rule.id().isBlank() || !ids.add(rule.id())) {
                throw new IllegalArgumentException("Assertion rules require unique non-blank ids");
            }
            if (rule.type() == null || !TYPES.contains(rule.type())) {
                throw new IllegalArgumentException("Unsupported assertion rule type");
            }
        }
        if (response == null) {
            throw new IllegalArgumentException("HTTP response is required");
        }
        if (response.error() != null || response.statusCode() == null) {
            throw new IllegalStateException("Cannot evaluate assertions after HTTP execution failure");
        }
        var results = new ArrayList<AssertionResult>(snapshot.size());
        for (var rule : snapshot) {
            results.add(switch (rule.type()) {
                case "STATUS_CODE" -> status.evaluate(rule, response);
                case "RESPONSE_TIME" -> latency.evaluate(rule, response);
                case "JSON_PATH" -> jsonPath.evaluate(rule, response);
                case "HEADER" -> header.evaluate(rule, response);
                case "JSON_SCHEMA" -> schema.evaluate(rule, response);
                default -> throw new IllegalArgumentException("Unsupported assertion rule type");
            });
        }
        return new Evaluation(results);
    }

    /** Internal aggregation, not a new wire DTO. No assertions means no assertion failures. */
    public record Evaluation(List<AssertionResult> assertionResults) {
        public Evaluation {
            assertionResults = List.copyOf(assertionResults);
        }

        public boolean passed() {
            return assertionResults.stream().allMatch(AssertionResult::passed);
        }
    }
}