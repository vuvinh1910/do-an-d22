package com.platform.worker.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.ResolvedHttpRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Applies the shipped Boot logging levels without starting DB/RabbitMQ or a Spring context. */
@ExtendWith(OutputCaptureExtension.class)
@Timeout(15)
class HttpClientLoggingPolicyTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredSuppressionPreventsRawHttpFailureLogging(boolean suppressed, CapturedOutput output) throws Exception {
        var properties = new YamlPropertySourceLoader().load("worker", new ClassPathResource("application.yml")).get(0);
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        Map<String, LogLevel> previous = new LinkedHashMap<>();
        List<String> namespaces = List.of("reactor.netty", "io.netty",
                "org.springframework.web.reactive.function.client", "org.springframework.http.client.reactive");
        var executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket()) {
            for (String namespace : namespaces) {
                assertEquals("OFF", String.valueOf(properties.getProperty("logging.level." + namespace)));
                var configuration = logging.getLoggerConfiguration(namespace);
                previous.put(namespace, configuration == null ? null : configuration.getConfiguredLevel());
                LogLevel level = suppressed ? LogLevel.OFF : LogLevel.WARN;
                logging.setLogLevel(namespace, level);
                assertEquals(level, logging.getLoggerConfiguration(namespace).getEffectiveLevel());
            }
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            server.setSoTimeout(5000);
            var fixture = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    do {
                        line = reader.readLine();
                    } while (line != null && !line.isEmpty());
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: sensitive-response-marker\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                return null;
            });
            var request = new ResolvedHttpRequest(HttpMethod.GET,
                    URI.create("http://127.0.0.1:" + server.getLocalPort() + "/sensitive-path-marker?token=sensitive-query-marker"),
                    Map.of("Authorization", List.of("Bearer sensitive-auth-marker")), null);
            var result = new WebClientHttpRunner(new ObjectMapper()).execute(request, Duration.ofSeconds(3))
                    .block(Duration.ofSeconds(5));
            fixture.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals("HTTP transport failed", result.error());
            assertNull(result.statusCode());
            assertNull(result.body());
            if (!suppressed) {
                // Positive control: malformed Content-Length is included in Netty's raw exception log.
                assertTrue(output.getAll().contains("sensitive-response-marker"));
            }
            for (String marker : List.of("sensitive-response-marker", "sensitive-path-marker",
                    "sensitive-query-marker", "sensitive-auth-marker")) {
                if (suppressed) {
                    assertFalse(output.getAll().contains(marker), "Raw HTTP marker must not appear in console logs");
                }
                assertFalse(result.error().contains(marker));
            }
        } finally {
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            } finally {
                previous.forEach(logging::setLogLevel);
            }
        }
    }
}