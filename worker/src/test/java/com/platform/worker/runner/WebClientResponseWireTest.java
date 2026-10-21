package com.platform.worker.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.ResolvedHttpRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class WebClientResponseWireTest {

    private HttpServer server;
    private ExecutorService executor;
    private WebClientHttpRunner runner;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();
        runner = new WebClientHttpRunner(new ObjectMapper());
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
    @ValueSource(ints = {200, 201, 302, 400, 401, 403, 500})
    void statusIsResponseDataEvenWhenBodyIsNotValidJson(int status) {
        String body = "{invalid JSON\nXin chào";
        serve(status, "application/json", body.getBytes(StandardCharsets.UTF_8), false);
        HttpResponseData result = execute(HttpMethod.GET);
        assertEquals(status, result.statusCode());
        assertEquals(body, result.body());
        assertNull(result.error());
        assertEquals(List.of("a=1", "b=2"), result.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("Set-Cookie")).findFirst().orElseThrow().getValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-8", "ISO-8859-1", "UTF-16LE"})
    void decodesDeclaredCharset(String name) {
        String text = "café\n";
        serve(200, "text/plain; charset=" + name, text.getBytes(Charset.forName(name)), false);
        HttpResponseData result = execute(HttpMethod.GET);
        assertEquals(text, result.body());
        assertNull(result.error());
    }

    @Test
    void fallsBackToUtf8WithoutContentType() {
        serve(200, null, "Xin chào".getBytes(StandardCharsets.UTF_8), false);
        HttpResponseData result = execute(HttpMethod.GET);
        assertEquals("Xin chào", result.body());
        assertNull(result.error());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "204"})
    void emptyResponseHasEmptyStringBody(String kind) {
        serve("204".equals(kind) ? 204 : 200, "text/plain", new byte[0], false);
        HttpResponseData result = execute("HEAD".equals(kind) ? HttpMethod.HEAD : HttpMethod.GET);
        assertEquals("204".equals(kind) ? 204 : 200, result.statusCode());
        assertEquals("", result.body());
        assertNull(result.error());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain; charset=UTF-8", "text/plain; charset=US-ASCII"})
    void malformedOrUnmappableBytesAreErrorsNotSilentReplacement(String contentType) {
        serve(200, contentType, new byte[]{(byte) 0xc3, 0x28}, false);
        assertDecodeFailure(execute(HttpMethod.GET));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain; charset=secret-unsupported", "invalid media type"})
    void invalidCharsetOrMediaTypeDoesNotLeakHeaderValue(String contentType) {
        serve(200, contentType, new byte[]{65}, false);
        HttpResponseData result = execute(HttpMethod.GET);
        assertDecodeFailure(result);
        assertFalse(result.error().contains(contentType));
    }

    @ParameterizedTest
    @ValueSource(ints = {1048575, 1048576, 1048577})
    void defaultLimitIsMeasuredInBytesWithNoSilentTruncation(int size) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) 'x');
        serve(200, "text/plain", bytes, true);
        HttpResponseData result = execute(HttpMethod.GET);
        assertEquals(200, result.statusCode());
        assertFalse(result.headers().isEmpty());
        if (size > 1048576) {
            assertNull(result.body());
            assertEquals("HTTP response exceeded size limit", result.error());
        } else {
            assertEquals(size, result.body().length());
            assertNull(result.error());
        }
    }

    @Test
    void redirectIsNotFollowedByRunner() {
        AtomicInteger destinations = new AtomicInteger();
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/destination");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/destination", exchange -> {
            try (exchange) {
                destinations.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
            }
        });
        HttpResponseData result = execute(HttpMethod.GET);
        assertEquals(302, result.statusCode());
        assertEquals("", result.body());
        assertNull(result.error());
        assertEquals(0, destinations.get());
    }

    private void assertDecodeFailure(HttpResponseData result) {
        assertEquals(200, result.statusCode());
        assertFalse(result.headers().isEmpty());
        assertNull(result.body());
        assertEquals("HTTP response decoding failed", result.error());
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 8, 9})
    void customLimitCountsUtf8BytesNotCharacters(int size) {
        runner = new WebClientHttpRunner(new ObjectMapper(), 8);
        // Four two-byte characters at the boundary; a ninth byte exceeds the cap.
        byte[] bytes = java.util.Arrays.copyOf("éééé!".getBytes(StandardCharsets.UTF_8), size);
        serve(200, "text/plain; charset=UTF-8", bytes, false);
        HttpResponseData result = execute(HttpMethod.GET);
        if (size == 9) {
            assertNull(result.body());
            assertEquals("HTTP response exceeded size limit", result.error());
        } else if (size == 7) {
            assertDecodeFailure(result);
        } else {
            assertEquals("éééé", result.body());
            assertNull(result.error());
        }
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new WebClientHttpRunner(new ObjectMapper(), 0));
        assertThrows(IllegalArgumentException.class, () -> new WebClientHttpRunner(new ObjectMapper(), -1));
    }

    @Test
    void emptyBodyDoesNotRequireUsableCharset() {
        serve(204, "text/plain; charset=unsupported-fixture", new byte[0], false);
        HttpResponseData result = execute(HttpMethod.GET);
        assertEquals("", result.body());
        assertNull(result.error());
    }

    private void serve(int status, String contentType, byte[] bytes, boolean chunked) {
        server.createContext("/", exchange -> {
            try (exchange) {
                if (contentType != null) {
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                }
                exchange.getResponseHeaders().add("Set-Cookie", "a=1");
                exchange.getResponseHeaders().add("Set-Cookie", "b=2");
                boolean empty = bytes.length == 0 || "HEAD".equals(exchange.getRequestMethod()) || status == 204;
                exchange.sendResponseHeaders(status, empty ? -1 : chunked ? 0 : bytes.length);
                if (!empty) {
                    exchange.getResponseBody().write(bytes);
                }
            }
        });
    }

    private HttpResponseData execute(HttpMethod method) {
        ResolvedHttpRequest request = new ResolvedHttpRequest(method,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), null, null);
        HttpResponseData result = runner.execute(request, Duration.ofSeconds(3)).block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertTrue(result.responseTimeMs() >= 0);
        return result;
    }
}