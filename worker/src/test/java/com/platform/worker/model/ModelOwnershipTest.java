package com.platform.worker.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelOwnershipTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void templateOwnsBodyAndNestedQuerySnapshots() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree("{\"nested\":{\"age\":18}}");
        ArrayNode query = (ArrayNode) mapper.readTree("[\"original\"]");
        TestCaseRequest request = new TestCaseRequest("POST", "/", null, null, Map.of("tag", query), body);
        int originalHash = request.hashCode();
        ((ObjectNode) body.get("nested")).put("age", 99);
        query.add("external");
        ((ObjectNode) request.body().get("nested")).put("age", 50);
        ((ArrayNode) request.queryParams().get("tag")).add("accessor");

        assertEquals(18, request.body().get("nested").get("age").intValue());
        assertEquals(1, request.queryParams().get("tag").size());
        assertEquals(originalHash, request.hashCode());
    }

    @Test
    void resolvedRequestOwnsBodySnapshot() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree("{\"data\":[1]}");
        ResolvedHttpRequest request = new ResolvedHttpRequest(HttpMethod.POST,
                URI.create("https://example.com"), null, body);
        ((ArrayNode) body.get("data")).add(2);
        ((ArrayNode) request.body().get("data")).add(3);

        assertEquals(mapper.readTree("{\"data\":[1]}"), request.body());
    }

    @Test
    void serializationPreservesAbsentBodyVersusExplicitJsonNull() throws Exception {
        TestCaseRequest absent = mapper.readValue("{\"method\":\"GET\",\"path\":\"/\"}", TestCaseRequest.class);
        TestCaseRequest explicitNull = mapper.readValue(
                "{\"method\":\"POST\",\"path\":\"/\",\"body\":null}", TestCaseRequest.class);

        assertFalse(mapper.valueToTree(absent).has("body"));
        assertTrue(mapper.valueToTree(explicitNull).has("body"));
        assertTrue(explicitNull.body().isNull());
        assertNull(mapper.readValue(mapper.writeValueAsBytes(absent), TestCaseRequest.class).body());
        assertTrue(mapper.readValue(mapper.writeValueAsBytes(explicitNull), TestCaseRequest.class).body().isNull());
    }

    @Test
    void nullStringMapValueHasContextWithoutLeakingData() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", null);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new TestCaseRequest("GET", "/", headers, null, null, null));
        assertTrue(error.getMessage().contains("headers"));
        assertFalse(error.getMessage().contains("Authorization"));
    }

    @Test
    void queryJavaNullHasContextInsteadOfNullPointerException() {
        Map<String, com.fasterxml.jackson.databind.JsonNode> query = new HashMap<>();
        query.put("q", null);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new TestCaseRequest("GET", "/", null, null, query, null));
        assertTrue(error.getMessage().contains("queryParams"));
    }

    @Test
    void negativePayloadAndMissingAuthAreNotCorrected() throws Exception {
        TestCaseRequest request = mapper.readValue(
                "{\"method\":\"POST\",\"path\":\"/users\",\"body\":{\"age\":\"wrong-type\"}}",
                TestCaseRequest.class);
        assertTrue(request.headers().isEmpty());
        assertEquals("wrong-type", request.body().get("age").textValue());
    }

    @Test
    void responseRequiresStatusOrTransportErrorAndNonNegativeDuration() {
        assertThrows(IllegalArgumentException.class, () -> new HttpResponseData(null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new HttpResponseData(200, null, "", -1, null));
        assertThrows(IllegalArgumentException.class, () -> new HttpResponseData(99, null, "", 0, null));
        assertThrows(IllegalArgumentException.class, () -> new HttpResponseData(null, null, "invented", 0, "timeout"));
        assertThrows(IllegalArgumentException.class, () -> new HttpResponseData(200, null, null, 0, null));
    }

    @Test
    void partialResponseKeepsKnownStatusAndErrorWithoutInventingBody() {
        HttpResponseData partial = new HttpResponseData(200, Map.of("Content-Type", java.util.List.of("text/plain")),
                null, 100, "Body read failed");
        assertEquals(200, partial.statusCode());
        assertNull(partial.body());
        assertEquals("Body read failed", partial.error());
        assertEquals("", new HttpResponseData(204, null, "", 0, null).body());
    }

    @Test
    void resolvedRequestRejectsMissingMethodOrNonHttpTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedHttpRequest(null, URI.create("https://example.com"), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedHttpRequest(HttpMethod.GET, URI.create("/relative"), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedHttpRequest(HttpMethod.GET, URI.create("file:/tmp/data"), null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/relative", "file:/tmp/data", "https://user:secret@example.com/",
            "https://example.com/#fragment", "https://example.com:99999/", "https://example.com:/"})
    void invalidResolvedTargetDoesNotLeakUri(String uri) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ResolvedHttpRequest(HttpMethod.GET, URI.create(uri), null, null));
        assertFalse(error.getMessage().contains(uri));
        assertNull(error.getCause());
    }

    @Test
    void multiValueHeaderNullsHaveContext() {
        Map<String, java.util.List<String>> nullList = new HashMap<>();
        nullList.put("X-Test", null);
        Map<String, java.util.List<String>> nullElement = Map.of("X-Test", java.util.Arrays.asList("a", null));
        for (Map<String, java.util.List<String>> headers : java.util.List.of(nullList, nullElement)) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> new ResolvedHttpRequest(HttpMethod.GET, URI.create("https://example.com"), headers, null))
                    .getMessage().contains("headers"));
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> new HttpResponseData(200, headers, "", 0, null)).getMessage().contains("headers"));
        }
    }

    @Test
    void explicitNullQueryAndNullMapsFromJsonArePreserved() throws Exception {
        TestCaseRequest request = mapper.readValue("""
                {"method":"GET","path":"/","headers":null,"pathParams":null,"queryParams":{"q":null}}
                """, TestCaseRequest.class);
        assertTrue(request.queryParams().get("q").isNull());
        assertTrue(request.headers().isEmpty());
        assertTrue(request.pathParams().isEmpty());
        assertEquals(request, mapper.readValue(mapper.writeValueAsBytes(request), TestCaseRequest.class));
    }

    @Test
    void responseRejectsBlankErrorAndDataWithoutStatus() {
        assertThrows(IllegalArgumentException.class, () -> new HttpResponseData(null, null, null, 0, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpResponseData(null, Map.of("X-Test", java.util.List.of("a")), null, 0, "timeout"));
        assertThrows(IllegalArgumentException.class, () -> new HttpResponseData(1000, null, "", 0, null));
        assertEquals("partial", new HttpResponseData(200, null, "partial", 10, "read failed").body());
    }
}