package com.platform.worker.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.ResolvedHttpRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class WebClientHttpRunnerTest {

    private HttpServer server;
    private ExecutorService executor;
    private WebClientHttpRunner runner;
    private final CountDownLatch release = new CountDownLatch(1);

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
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    void getsTextWithStatusHeadersAndRawTarget() {
        server.createContext("/", exchange -> {
            try (exchange) {
                assertEquals("GET", exchange.getRequestMethod());
                assertEquals("/users/a%2Fb?q=%252e", exchange.getRequestURI().toASCIIString());
                assertEquals("original", exchange.getRequestHeaders().getFirst("X-Test"));
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getResponseHeaders().add("Set-Cookie", "a=1");
                exchange.getResponseHeaders().add("Set-Cookie", "b=2");
                byte[] body = "Xin chào\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });

        HttpResponseData result = runner.execute(request(), Duration.ofSeconds(3)).block(Duration.ofSeconds(5));

        assertNotNull(result);
        assertEquals(200, result.statusCode());
        assertEquals("Xin chào\n", result.body());
        assertEquals(List.of("a=1", "b=2"), result.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("Set-Cookie")).findFirst().orElseThrow().getValue());
        assertTrue(result.responseTimeMs() >= 0);
        assertNull(result.error());
    }

    @Test
    void isColdAndExecutesAgainForEachSubscription() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] body = Integer.toString(calls.incrementAndGet()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        Mono<HttpResponseData> execution = runner.execute(request(), Duration.ofSeconds(3));
        assertEquals(0, calls.get());
        assertEquals("1", execution.block(Duration.ofSeconds(5)).body());
        assertEquals("2", execution.block(Duration.ofSeconds(5)).body());
        assertEquals(2, calls.get());
    }

    @Test
    void waitsForBodyAndMeasuresUntilBodyCompletes() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 4);
                exchange.getResponseBody().write('d');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                awaitRelease();
                exchange.getResponseBody().write(new byte[]{'o', 'n', 'e'});
            }
        });
        var result = runner.execute(request(), Duration.ofSeconds(5)).toFuture();
        assertTrue(headersSent.await(3, TimeUnit.SECONDS));
        assertFalse(result.isDone());
        assertFalse(release.await(100, TimeUnit.MILLISECONDS));
        release.countDown();
        HttpResponseData response = result.get(3, TimeUnit.SECONDS);
        assertEquals("done", response.body());
        assertNull(response.error());
        assertTrue(response.responseTimeMs() >= 90, "Latency must include the held body read");
    }

    @Test
    void timesOutBeforeHeadersWithoutInventingResponse() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try (exchange) {
                arrived.countDown();
                awaitRelease();
            }
        });
        var result = runner.execute(request(), Duration.ofSeconds(1)).toFuture();
        assertTrue(arrived.await(3, TimeUnit.SECONDS));
        HttpResponseData response = result.get(3, TimeUnit.SECONDS);
        assertNull(response.statusCode());
        assertNull(response.body());
        assertTrue(response.headers().isEmpty());
        assertEquals("HTTP exchange timed out", response.error());
        assertTrue(response.responseTimeMs() >= 900);
    }

    @Test
    void timesOutDuringBodyAndPreservesKnownStatusAndHeaders() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("X-Received", "yes");
                exchange.sendResponseHeaders(200, 5);
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                awaitRelease();
            }
        });
        var result = runner.execute(request(), Duration.ofSeconds(1)).toFuture();
        assertTrue(headersSent.await(3, TimeUnit.SECONDS));
        HttpResponseData response = result.get(3, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertFalse(response.headers().isEmpty());
        assertNull(response.body());
        assertEquals("HTTP exchange timed out", response.error());
    }

    @Test
    void rejectsConfigurationBeforeNetworkWithoutLeakingValues() {
        assertThrows(IllegalArgumentException.class, () -> new WebClientHttpRunner(null));
        assertThrows(IllegalArgumentException.class, () -> runner.execute(null, Duration.ofSeconds(1)));
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThrows(IllegalArgumentException.class, () -> runner.execute(request(), timeout));
        }
        assertThrows(IllegalArgumentException.class, () -> runner.execute(request(), null));
        ResolvedHttpRequest invalid = new ResolvedHttpRequest(HttpMethod.GET, request().uri(),
                Map.of("Authorization", List.of("secret\r\nInjected: value")), null);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> runner.execute(invalid, Duration.ofSeconds(1)));
        assertFalse(error.getMessage().contains("secret"));
        assertNull(error.getCause());
    }

    @Test
    void rejectsInvalidMethodHeaderAndCustomFramingWithoutSending() {
        for (String name : List.of("Host", "content-length", "Transfer-Encoding", "bad header")) {
            ResolvedHttpRequest invalid = new ResolvedHttpRequest(HttpMethod.GET, request().uri(),
                    Map.of(name, List.of("sensitive")), null);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> runner.execute(invalid, Duration.ofSeconds(1)));
            assertFalse(error.getMessage().contains("sensitive"));
        }
        for (String method : List.of("CONNECT", "bad method")) {
            ResolvedHttpRequest invalid = new ResolvedHttpRequest(HttpMethod.valueOf(method), request().uri(), null, null);
            assertThrows(IllegalArgumentException.class, () -> runner.execute(invalid, Duration.ofSeconds(1)));
        }
    }

    private ResolvedHttpRequest request() {
        return new ResolvedHttpRequest(HttpMethod.GET,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/users/a%2Fb?q=%252e"),
                Map.of("X-Test", List.of("original")), null);
    }

    private void awaitRelease() {
        try {
            if (!release.await(7, TimeUnit.SECONDS)) {
                throw new AssertionError("Server gate was not released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}