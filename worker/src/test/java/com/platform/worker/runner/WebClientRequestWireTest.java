package com.platform.worker.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.ResolvedHttpRequest;
import com.platform.worker.model.TestCaseRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Assertions run on the test thread against requests captured by a real loopback server. */
@Timeout(10)
class WebClientRequestWireTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LinkedBlockingQueue<CapturedRequest> captured = new LinkedBlockingQueue<>();
    private HttpServer server;
    private ExecutorService executor;
    private WebClientHttpRunner runner;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                HttpHeaders headers = new HttpHeaders();
                exchange.getRequestHeaders().forEach((key, values) -> headers.addAll(key, List.copyOf(values)));
                captured.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toASCIIString(),
                        headers, exchange.getRequestBody().readAllBytes()));
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        runner = new WebClientHttpRunner(mapper);
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "[1,true,null]", "\"Xin chào\\nworld\"", "42", "1.5", "true", "null",
            "{\"age\":\"wrong-type\",\"nested\":{\"present\":null}}"})
    void sendsExactJsonBytesWithoutRepairingPayloadOrMissingHeaders(String json) throws Exception {
        JsonNode body = mapper.readTree(json);
        ResolvedHttpRequest request = new ResolvedHttpRequest(HttpMethod.POST, target("/users"), null, body);
        CapturedRequest actual = execute(request);

        assertEquals("POST", actual.method());
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), actual.body());
        assertNull(actual.headers().getFirst("Content-Type"));
        assertNull(actual.headers().getFirst("Authorization"));
        assertEquals(body, request.body());
    }

    @Test
    void absentBodyHasNoBytesAndDoesNotAddContentType() throws Exception {
        CapturedRequest actual = execute(new ResolvedHttpRequest(HttpMethod.POST, target("/"), null, null));
        assertArrayEquals(new byte[0], actual.body());
        assertNull(actual.headers().getFirst("Content-Type"));
        assertNull(actual.headers().getFirst("Authorization"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void preservesMethodAndBodyIncludingGetAndDelete(String method) throws Exception {
        CapturedRequest actual = execute(new ResolvedHttpRequest(HttpMethod.valueOf(method), target("/"),
                Map.of("Content-Type", List.of("application/json")), mapper.readTree("{\"age\":\"bad\"}")));
        assertEquals(method, actual.method());
        assertArrayEquals("{\"age\":\"bad\"}".getBytes(StandardCharsets.UTF_8), actual.body());
        assertEquals("application/json", actual.headers().getFirst("content-type"));
    }

    @Test
    void sendsHeadAndRepresentsEmptyResponseAsEmptyString() throws Exception {
        CapturedRequest actual = execute(new ResolvedHttpRequest(HttpMethod.HEAD, target("/"), null, null));
        assertEquals("HEAD", actual.method());
        assertArrayEquals(new byte[0], actual.body());
    }

    @Test
    void combinesCaseInsensitiveHeaderNamesWithoutLosingValues() throws Exception {
        CapturedRequest actual = execute(new ResolvedHttpRequest(HttpMethod.GET, target("/"),
                Map.of("X-Test", List.of("a", "b"), "x-test", List.of("c"),
                        "Authorization", List.of("Bearer fixture-only")), null));
        List<String> values = actual.headers().get("X-TEST");
        assertNotNull(values);
        assertEquals(3, values.size());
        assertTrue(values.containsAll(List.of("a", "b", "c")));
        assertEquals("Bearer fixture-only", actual.headers().getFirst("authorization"));
    }

    @Test
    void builderToRunnerPreservesBaseQueryEncodingUnicodeAndRepeatedKeys() throws Exception {
        TestCaseRequest template = new TestCaseRequest("GET", "/users:search/{id}/café", null,
                Map.of("id", "a/b%"), Map.of("tag", mapper.readTree("[\"a b\",\"x/y\"]"),
                "empty", mapper.readTree("\"\""), "ignored", mapper.nullNode()), null);
        URI uri = new RequestUriBuilder().build(target("/root?tag=base%20value").toString(), template);
        CapturedRequest actual = execute(new ResolvedHttpRequest(HttpMethod.GET, uri, null, null));
        assertEquals("/root/users:search/a%2Fb%25/caf%C3%A9?tag=base%20value&empty=&tag=a%20b&tag=x%2Fy",
                actual.target());
        assertEquals("a/b%", template.pathParams().get("id"));
        assertEquals(2, template.queryParams().get("tag").size());
    }

    @ParameterizedTest
    @CsvSource(value = {"EMPTY|/root/items//end", ".|/root/items/./end", "..|/root/items/../end",
            "%2e|/root/items/%252e/end"}, delimiter = '|')
    void builderToRunnerDoesNotNormalizeNegativePathParameter(String value, String expected) throws Exception {
        TestCaseRequest template = new TestCaseRequest("GET", "/items/{id}/end", null,
                Map.of("id", "EMPTY".equals(value) ? "" : value), null, null);
        URI uri = new RequestUriBuilder().build(target("/root").toString(), template);
        assertEquals(expected, execute(new ResolvedHttpRequest(HttpMethod.GET, uri, null, null)).target());
    }

    @Test
    void builderToRunnerPreservesStaticDotAndEncodedSegments() throws Exception {
        TestCaseRequest template = new TestCaseRequest("GET", "/a/.././%2e/%2F", null, null, null, null);
        URI uri = new RequestUriBuilder().build(target("/root").toString(), template);
        assertEquals("/root/a/.././%2e/%2F",
                execute(new ResolvedHttpRequest(HttpMethod.GET, uri, null, null)).target());
    }

    private CapturedRequest execute(ResolvedHttpRequest request) throws Exception {
        HttpResponseData result = runner.execute(request, Duration.ofSeconds(3)).block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertNull(result.error());
        assertEquals(204, result.statusCode());
        assertEquals("", result.body());
        CapturedRequest actual = captured.poll(1, TimeUnit.SECONDS);
        assertNotNull(actual, "Server must receive the request");
        return actual;
    }

    private URI target(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private record CapturedRequest(String method, String target, HttpHeaders headers, byte[] body) {
    }
}