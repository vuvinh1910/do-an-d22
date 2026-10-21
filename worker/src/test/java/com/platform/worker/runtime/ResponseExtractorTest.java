package com.platform.worker.runtime;

import com.platform.worker.model.HttpResponseData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResponseExtractorTest {
    private final ResponseExtractor extractor = new ResponseExtractor();

    @Test
    void extractsTypedBodyNullAndCaseInsensitiveRawHeader() {
        var result = extractor.extract(List.of(body("id", "$.id"), body("nil", "$.nil"),
                new ExtractionRule("token", "HEADER", null, "X-TOKEN", null)),
                new HttpResponseData(201, Map.of("x-token", List.of("a,b")), "{\"id\":42,\"nil\":null}", 1, null));
        assertTrue(result.get("id").isIntegralNumber());
        assertTrue(result.get("nil").isNull());
        assertEquals("a,b", result.get("token").textValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.missing", "$.a[1]", "$.a[-2]", "$.a[*]", "$.a.length()", "invalid"})
    void rejectsMissingIndefiniteAndFunctionPaths(String path) {
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(List.of(body("v", path)),
                response("{\"a\":[null]}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{} {}", "{\"a\":1,\"a\":2}"})
    void rejectsInvalidJson(String json) {
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(List.of(body("v", "$")), response(json)));
    }

    @Test
    void rejectsAmbiguousOrMissingHeadersAndDuplicateVariables() {
        var rule = new ExtractionRule("v", "HEADER", null, "X-Token", null);
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(List.of(rule), response("{}")));
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(List.of(rule),
                new HttpResponseData(200, Map.of("x-token", List.of("a"), "X-TOKEN", List.of("b")), "", 1, null)));
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(List.of(body("v", "$"), body("v", "$")), response("{}")));
    }

    @Test
    void allowsParenthesesInQuotedPropertyAndRejectsExecutionFailure() {
        assertEquals(1, extractor.extract(List.of(body("v", "$['a()']")), response("{\"a()\":1}"))
                .get("v").intValue());
        assertThrows(IllegalStateException.class, () -> extractor.extract(List.of(),
                new HttpResponseData(null, Map.of(), null, 0, "private")));
    }

    @Test
    void rootNullAndEmptyHeaderArePresentValues() {
        assertTrue(extractor.extract(List.of(body("v", "$")), response("null")).get("v").isNull());
        var header = new ExtractionRule("v", "HEADER", null, "x-token", null);
        assertEquals("", extractor.extract(List.of(header),
                new HttpResponseData(200, Map.of("x-token", List.of("")), "", 0, null)).get("v").textValue());
    }

    @Test
    void rejectsWrongSourceAndMixedSelectorConfiguration() {
        for (var rule : List.of(new ExtractionRule("v", "OTHER", "$", null, null),
                new ExtractionRule("v", "BODY", "$", "x-token", null),
                new ExtractionRule("v", "HEADER", "$", "x-token", null),
                new ExtractionRule("v", "HEADER", null, "bad\r\nname", null))) {
            assertThrows(IllegalArgumentException.class, () -> extractor.extract(List.of(rule), response("{}")));
        }
    }

    private ExtractionRule body(String name, String path) {
        return new ExtractionRule(name, "BODY", path, null, null);
    }

    private HttpResponseData response(String body) {
        return new HttpResponseData(200, Map.of(), body, 1, null);
    }
}