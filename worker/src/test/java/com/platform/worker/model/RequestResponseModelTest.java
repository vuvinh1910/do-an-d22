package com.platform.worker.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestResponseModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsRequestFixtureUsingContractFieldNames() throws Exception {
        try (InputStream fixture = getClass().getResourceAsStream("/fixtures/create-user-request.json")) {
            assertNotNull(fixture);
            TestCaseRequest request = mapper.readValue(fixture, TestCaseRequest.class);

            assertEquals("POST", request.method());
            assertEquals("/api/v1/users", request.path());
            assertEquals("Bearer {{auth_token}}", request.headers().get("Authorization"));
            assertEquals(18, request.body().get("age").intValue());
            assertTrue(request.pathParams().isEmpty());
            assertTrue(request.queryParams().isEmpty());
            JsonNode serialized = mapper.valueToTree(request);
            assertEquals(6, serialized.size());
            assertFalse(serialized.has("url"));
            assertEquals(request, mapper.treeToValue(serialized, TestCaseRequest.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "[1,true,null]", "\"hello\"", "42", "1.5", "true", "null"})
    void preservesBodyJsonTypeOnRoundTrip(String body) throws Exception {
        TestCaseRequest request = mapper.readValue(
                "{\"method\":\"POST\",\"path\":\"/example\",\"body\":" + body + "}",
                TestCaseRequest.class);

        assertEquals(mapper.readTree(body), request.body());
        assertEquals(mapper.readTree(body), mapper.valueToTree(request).get("body"));
    }

    @Test
    void allowsAbsentBodyAndDefaultsMissingParameterMaps() throws Exception {
        TestCaseRequest request = mapper.readValue(
                "{\"method\":\"GET\",\"path\":\"/health\"}", TestCaseRequest.class);

        assertNull(request.body());
        assertEquals(Map.of(), request.headers());
        assertEquals(Map.of(), request.pathParams());
        assertEquals(Map.of(), request.queryParams());
    }

    @Test
    void preservesParameterValuesWithoutResolvingTemplates() throws Exception {
        TestCaseRequest request = mapper.readValue("""
                {"method":"GET","path":"/users/{id}",
                 "pathParams":{"id":"{{createdUserId}}"},
                 "queryParams":{"page":2,"enabled":true,"tag":["a","b"],"search":"{{term}}"}}
                """, TestCaseRequest.class);

        assertEquals("{{createdUserId}}", request.pathParams().get("id"));
        assertTrue(request.queryParams().get("page").isIntegralNumber());
        assertTrue(request.queryParams().get("enabled").booleanValue());
        assertEquals(2, request.queryParams().get("tag").size());
        assertEquals("{{term}}", request.queryParams().get("search").textValue());
    }

    @Test
    void requestCopiesParameterMaps() {
        Map<String, String> headers = new HashMap<>(Map.of("Authorization", "Bearer original"));
        TestCaseRequest request = new TestCaseRequest("GET", "/", headers, null, null, null);
        headers.put("Authorization", "changed");

        assertEquals("Bearer original", request.headers().get("Authorization"));
        assertThrows(UnsupportedOperationException.class, () -> request.headers().put("X-Test", "value"));
    }

    @Test
    void resolvedRequestCopiesMultiValueHeaders() {
        List<String> values = new ArrayList<>(List.of("a", "b"));
        Map<String, List<String>> headers = new HashMap<>(Map.of("X-Test", values));
        ResolvedHttpRequest request = new ResolvedHttpRequest(
                HttpMethod.GET, URI.create("https://example.com/users?page=2"), headers, null);
        values.clear();
        headers.clear();

        assertEquals(List.of("a", "b"), request.headers().get("X-Test"));
        assertEquals("page=2", request.uri().getQuery());
        assertThrows(UnsupportedOperationException.class, () -> request.headers().get("X-Test").add("c"));
        assertThrows(UnsupportedOperationException.class, () -> request.headers().clear());
    }

    @Test
    void responsePreservesRawBodyAndMultipleHeaderValues() {
        List<String> cookies = new ArrayList<>(List.of("a=1", "b=2"));
        HttpResponseData response = new HttpResponseData(400,
                Map.of("Set-Cookie", cookies), "not JSON\n", 12, null);
        cookies.clear();

        assertEquals(400, response.statusCode());
        assertEquals("not JSON\n", response.body());
        assertEquals(List.of("a=1", "b=2"), response.headers().get("Set-Cookie"));
        assertNull(response.error());
        assertThrows(UnsupportedOperationException.class, () -> response.headers().get("Set-Cookie").clear());
    }

    @Test
    void transportErrorDoesNotInventHttpStatusOrBody() {
        HttpResponseData response = new HttpResponseData(null, null, null, 1000, "Request timed out");

        assertNull(response.statusCode());
        assertNull(response.body());
        assertTrue(response.headers().isEmpty());
        assertEquals(1000, response.responseTimeMs());
        assertEquals("Request timed out", response.error());
    }
}