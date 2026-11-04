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

    @Test
    void extractsMultipleBodyPropertiesFromSameResponseEfficiently() {
        var rules = List.of(
                body("userId", "$.user.id"),
                body("userName", "$.user.name"),
                body("roles", "$.user.roles"),
                body("enabled", "$.user.enabled"),
                body("total", "$.stats.total")
        );
        String body = """
                {
                  "user": {
                    "id": "1001",
                    "name": "Nguyen Van A",
                    "roles": ["ADMIN", "USER"],
                    "enabled": true
                  },
                  "stats": {
                    "total": 99
                  }
                }
                """;
        var result = extractor.extract(rules, response(body));
        assertEquals(5, result.size());
        assertEquals("1001", result.get("userId").textValue());
        assertEquals("Nguyen Van A", result.get("userName").textValue());
        assertTrue(result.get("roles").isArray());
        assertEquals(2, result.get("roles").size());
        assertTrue(result.get("enabled").booleanValue());
        assertEquals(99, result.get("total").intValue());
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

    @Test
    void rejectsOversizedDocumentEvenWhenSelectedValueIsSmall() {
        String json = "{\"id\":1,\"data\":[" + "0,".repeat(9998) + "0]}";
        var error = assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(List.of(body("id", "$.id")), response(json)));
        assertEquals("BODY extraction could not resolve a definite JSON value", error.getMessage());
        assertNull(error.getCause());
    }

    private HttpResponseData response(String body) {
        return new HttpResponseData(200, Map.of(), body, 1, null);
    }
}
