package com.platform.worker.runner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** R3 transport feasibility checks, not tests of a production runner. */
@Timeout(10)
class WebClientTransportProbeTest {

    private HttpServer server;
    private ExecutorService executor;
    private WebClient client;
    private final LinkedBlockingQueue<ReceivedRequest> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                HttpClient.newConnection().followRedirect(false).disableRetry(true).compress(false)
                        .responseTimeout(Duration.ofSeconds(2))))
                .build();
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "Server executor must terminate");
        }
    }

    @Test
    void preservesEncodedUriAndErrorResponseWithoutStatusException() throws Exception {
        byte[] responseBytes = "not JSON\n".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            try (exchange) {
                received.add(new ReceivedRequest(exchange.getRequestURI().toASCIIString(),
                        exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
                exchange.getResponseHeaders().add("Set-Cookie", "a=1");
                exchange.getResponseHeaders().add("Set-Cookie", "b=2");
                exchange.sendResponseHeaders(400, responseBytes.length);
                exchange.getResponseBody().write(responseBytes);
            }
        });

        ProbeResponse result = client.get().uri(target("/users/a%2Fb?q=%252e&tag=a&tag=b"))
                .exchangeToMono(response -> response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                        .map(body -> new ProbeResponse(response.statusCode().value(),
                                List.copyOf(response.headers().header("Set-Cookie")), body)))
                .block(Duration.ofSeconds(3));

        assertNotNull(result);
        assertEquals(400, result.status());
        assertEquals(List.of("a=1", "b=2"), result.cookies());
        assertArrayEquals(responseBytes, result.body());
        ReceivedRequest request = received.poll(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/users/a%2Fb?q=%252e&tag=a&tag=b", request.target());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABSENT", "null", "{\"age\":\"wrong-type\"}"})
    void bytePublisherPreservesJsonBytesWithoutAddingContentType(String json) throws Exception {
        server.createContext("/", exchange -> {
            try (exchange) {
                received.add(new ReceivedRequest(exchange.getRequestURI().toASCIIString(),
                        exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
                exchange.sendResponseHeaders(204, -1);
            }
        });
        WebClient.RequestBodySpec spec = client.post().uri(target("/"));
        WebClient.RequestHeadersSpec<?> request = "ABSENT".equals(json) ? spec
                : spec.bodyValue(json.getBytes(StandardCharsets.UTF_8));

        byte[] result = request.exchangeToMono(response -> {
            assertEquals(204, response.statusCode().value());
            return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]);
        }).block(Duration.ofSeconds(3));

        assertArrayEquals(new byte[0], result);
        ReceivedRequest actual = received.poll(1, TimeUnit.SECONDS);
        assertNotNull(actual);
        assertNull(actual.contentType(), "Missing Content-Type must not be silently repaired");
        assertArrayEquals("ABSENT".equals(json) ? new byte[0] : json.getBytes(StandardCharsets.UTF_8), actual.body());
    }

    @Test
    void doesNotFollowRedirect() {
        AtomicInteger redirectedCalls = new AtomicInteger();
        server.createContext("/start", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/destination");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/destination", exchange -> {
            try (exchange) {
                redirectedCalls.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
            }
        });

        Integer status = client.get().uri(target("/start"))
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()))
                .block(Duration.ofSeconds(3));

        assertEquals(302, status);
        assertEquals(0, redirectedCalls.get());
    }

    private URI target(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private record ReceivedRequest(String target, String contentType, byte[] body) {
    }

    private record ProbeResponse(int status, List<String> cookies, byte[] body) {
    }
}