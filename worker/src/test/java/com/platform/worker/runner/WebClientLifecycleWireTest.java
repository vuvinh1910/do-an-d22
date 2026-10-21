package com.platform.worker.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.ResolvedHttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Raw TCP fixtures intentionally return incomplete HTTP messages. No external network or services. */
@Timeout(15)
class WebClientLifecycleWireTest {
    private ServerSocket listener;
    private ExecutorService executor;
    private WebClientHttpRunner runner;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws Exception {
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        listener.setSoTimeout(5000);
        executor = Executors.newCachedThreadPool();
        runner = new WebClientHttpRunner(new ObjectMapper());
    }

    @AfterEach
    void stop() throws Exception {
        release.countDown();
        listener.close();
        for (Socket socket : sockets) {
            socket.close();
        }
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
    }

    @Test
    void refusedConnectionReturnsSanitizedTransportFailure() throws Exception {
        // Reserve and close an ephemeral port. There is a small unavoidable port-reuse race.
        URI target = request().uri();
        listener.close();
        HttpResponseData result = runner.execute(new ResolvedHttpRequest(HttpMethod.GET, target, null, null),
                Duration.ofSeconds(3)).block(Duration.ofSeconds(5));
        assertFailure(result, "HTTP transport failed", null);
        assertTrue(result.headers().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void prematureCloseDoesNotRetryOrReturnPartialBody(boolean afterHeaders) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Future<?> server = executor.submit(() -> {
            try {
                try (Socket socket = accept()) {
                    attempts.incrementAndGet();
                    readRequest(socket);
                    if (afterHeaders) {
                        write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 20\r\nX-Trace: fixture\r\n\r\npart");
                    }
                }
                // The runner's result must not depend on another accepted connection.
                listener.setSoTimeout(600);
                try (Socket ignored = accept()) {
                    attempts.incrementAndGet();
                } catch (SocketTimeoutException expected) {
                    // No implicit retry during the observation window.
                }
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        });
        HttpResponseData result = runner.execute(request(), Duration.ofSeconds(3)).block(Duration.ofSeconds(5));
        server.get(3, TimeUnit.SECONDS);
        assertEquals(1, attempts.get());
        assertFailure(result, afterHeaders ? "HTTP response read failed" : "HTTP transport failed",
                afterHeaders ? 200 : null);
        if (afterHeaders) {
            assertEquals(List.of("fixture"), result.headers().get("X-Trace"));
        } else {
            assertTrue(result.headers().isEmpty());
        }
    }

    @Test
    void chunksDoNotResetExchangeDeadlineAndTimeoutClosesSocket() throws Exception {
        CountDownLatch firstChunk = new CountDownLatch(1);
        AtomicInteger chunks = new AtomicInteger();
        Future<?> server = executor.submit(() -> {
            try (Socket socket = accept()) {
                readRequest(socket);
                write(socket, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nX-Trace: fixture\r\n\r\n");
                for (int i = 0; i < 10; i++) {
                    write(socket, "1\r\na\r\n");
                    chunks.incrementAndGet();
                    firstChunk.countDown();
                    Thread.sleep(150);
                }
                // A non-terminating body: deadline must expire despite ongoing progress.
                assertEquals(-1, socket.getInputStream().read());
            } catch (IOException | InterruptedException error) {
                throw new IllegalStateException(error);
            }
        });
        var future = runner.execute(request(), Duration.ofSeconds(2)).toFuture();
        assertTrue(firstChunk.await(3, TimeUnit.SECONDS));
        HttpResponseData result = future.get(4, TimeUnit.SECONDS);
        assertFailure(result, "HTTP exchange timed out", 200);
        assertEquals(10, chunks.get());
        assertTrue(result.responseTimeMs() >= 1800);
        // Last progress is about 1.35s after headers; a resettable 2s idle timer would finish later.
        assertTrue(result.responseTimeMs() < 2900, "Deadline must not restart on each chunk");
        assertEquals(List.of("fixture"), result.headers().get("X-Trace"));
        server.get(3, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingSubscriptionClosesSocketAndSameMonoCanExecuteAgain(boolean afterHeaders) throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        Future<?> server = executor.submit(() -> {
            try {
                try (Socket socket = accept()) {
                    readRequest(socket);
                    if (afterHeaders) {
                        write(socket, "HTTP/1.1 202 Accepted\r\nContent-Length: 20\r\n\r\na");
                    }
                    headers.countDown();
                    assertEquals(-1, socket.getInputStream().read());
                }
                try (Socket socket = accept()) {
                    readRequest(socket);
                    write(socket, "HTTP/1.1 201 Created\r\nContent-Length: 2\r\nX-Trace: fresh\r\n\r\nok");
                }
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        });
        AtomicInteger signals = new AtomicInteger();
        Mono<HttpResponseData> operation = runner.execute(request(), Duration.ofSeconds(5));
        var subscription = operation.subscribe(value -> signals.incrementAndGet(), error -> signals.incrementAndGet(),
                signals::incrementAndGet);
        try {
            assertTrue(headers.await(3, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        HttpResponseData result = operation.block(Duration.ofSeconds(5));
        server.get(3, TimeUnit.SECONDS);
        assertEquals(0, signals.get());
        assertNotNull(result);
        assertEquals(201, result.statusCode());
        assertEquals("ok", result.body());
        assertEquals(List.of("fresh"), result.headers().get("X-Trace"));
        assertNull(result.error());
    }

    @Test
    void concurrentSubscriptionsKeepTimeoutAndSuccessStateIsolated() throws Exception {
        CountDownLatch stalled = new CountDownLatch(1);
        Future<?> server = executor.submit(() -> {
            try {
                Socket slow = accept();
                readRequest(slow);
                write(slow, "HTTP/1.1 202 Accepted\r\nContent-Length: 10\r\nX-Trace: slow\r\n\r\na");
                stalled.countDown();
                try (Socket fast = accept()) {
                    readRequest(fast);
                    write(fast, "HTTP/1.1 201 Created\r\nContent-Length: 2\r\nX-Trace: fast\r\n\r\nok");
                }
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (IOException | InterruptedException error) {
                throw new IllegalStateException(error);
            }
        });
        // Same Mono and runner, two overlapping subscriptions with independent exchange state.
        Mono<HttpResponseData> operation = runner.execute(request(), Duration.ofSeconds(2));
        var slow = operation.toFuture();
        assertTrue(stalled.await(3, TimeUnit.SECONDS));
        HttpResponseData fast = operation.block(Duration.ofSeconds(4));
        assertNotNull(fast);
        assertEquals(201, fast.statusCode());
        assertEquals("ok", fast.body());
        assertEquals(List.of("fast"), fast.headers().get("X-Trace"));
        assertNull(fast.error());
        HttpResponseData timedOut = slow.get(4, TimeUnit.SECONDS);
        assertFailure(timedOut, "HTTP exchange timed out", 202);
        assertEquals(List.of("slow"), timedOut.headers().get("X-Trace"));
        release.countDown();
        server.get(3, TimeUnit.SECONDS);
    }

    private Socket accept() throws IOException {
        Socket socket = listener.accept();
        sockets.add(socket);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static void readRequest(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // GET fixtures have no body. Consume the complete request header before responding.
        }
        if (line == null) {
            throw new IOException("Client closed before request headers");
        }
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private ResolvedHttpRequest request() {
        return new ResolvedHttpRequest(HttpMethod.GET,
                URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/"), null, null);
    }

    private static void assertFailure(HttpResponseData result, String error, Integer status) {
        assertNotNull(result);
        assertEquals(status, result.statusCode());
        assertNull(result.body());
        assertEquals(error, result.error());
        assertTrue(result.responseTimeMs() >= 0);
    }
}