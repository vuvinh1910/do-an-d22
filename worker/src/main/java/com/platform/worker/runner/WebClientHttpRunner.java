package com.platform.worker.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.ResolvedHttpRequest;
import io.netty.channel.ChannelOption;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.BodyExtractors;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One cold HTTP exchange per subscription. Does not decide assertion/test-result status. */
public final class WebClientHttpRunner {

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final ObjectMapper mapper;
    private final WebClient client;
    private final int maxResponseBytes;

    public WebClientHttpRunner(ObjectMapper mapper) {
        this(mapper, MAX_RESPONSE_BYTES);
    }

    public WebClientHttpRunner(ObjectMapper mapper, int maxResponseBytes) {
        if (mapper == null) {
            throw new IllegalArgumentException("ObjectMapper is required");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("Response byte limit must be positive");
        }
        this.mapper = mapper;
        this.maxResponseBytes = maxResponseBytes;
        HttpClient transport = HttpClient.newConnection()
                .followRedirect(false).disableRetry(true).compress(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000);
        client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseBytes))
                .build();
    }

    /** Configuration errors throw immediately; execution failures are returned as response data. */
    public Mono<HttpResponseData> execute(ResolvedHttpRequest request, Duration timeout) {
        if (request == null) {
            throw new IllegalArgumentException("Resolved request is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofNanos(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Timeout must be positive and representable in nanoseconds");
        }
        validateRequest(request);
        byte[] body = serializeBody(request.body());
        return Mono.defer(() -> {
            long start = System.nanoTime();
            ExchangeState state = new ExchangeState();
            WebClient.RequestBodySpec spec = client.method(request.method()).uri(request.uri())
                    .headers(headers -> request.headers().forEach(headers::addAll));
            WebClient.RequestHeadersSpec<?> outgoing = body == null ? spec : spec.bodyValue(body);
            return outgoing.exchangeToMono(response -> {
                state.headers = response.headers().asHttpHeaders();
                state.status = response.statusCode().value();
                // Read raw buffers: codec selection parses Content-Type before byte[] decoding,
                // which would prevent us handling invalid charset and empty bodies consistently.
                return DataBufferUtils.join(response.body(BodyExtractors.toDataBuffers()), maxResponseBytes)
                        .map(buffer -> {
                            try {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                return bytes;
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        }).defaultIfEmpty(new byte[0])
                        .map(bytes -> state.result(decode(bytes, response.headers().asHttpHeaders()),
                                elapsedMs(start), null));
            }).timeout(timeout)
                    .onErrorResume(Exception.class, error -> Mono.just(state.result(null, elapsedMs(start),
                            error instanceof TimeoutException ? "HTTP exchange timed out"
                                    : error instanceof DataBufferLimitException ? "HTTP response exceeded size limit"
                                    : error instanceof ResponseDecodingException ? "HTTP response decoding failed"
                                    : state.status == null ? "HTTP transport failed" : "HTTP response read failed")));
        });
    }

    private static String decode(byte[] bytes, HttpHeaders headers) {
        // No bytes (including HEAD/204) means no text to decode, regardless of declared charset.
        if (bytes.length == 0) {
            return "";
        }
        try {
            MediaType contentType = headers.getContentType();
            Charset charset = contentType == null || contentType.getCharset() == null
                    ? StandardCharsets.UTF_8 : contentType.getCharset();
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException | IllegalArgumentException error) {
            throw new ResponseDecodingException();
        }
    }

    private static final class ResponseDecodingException extends RuntimeException {
        private ResponseDecodingException() {
            super("HTTP response decoding failed");
        }
    }

    private void validateRequest(ResolvedHttpRequest request) {
        if (!request.method().name().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                || "CONNECT".equalsIgnoreCase(request.method().name())) {
            throw new IllegalArgumentException("Unsupported HTTP method");
        }
        request.headers().forEach((name, values) -> {
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                throw new IllegalArgumentException("Invalid HTTP header name");
            }
            if (HttpHeaders.HOST.equalsIgnoreCase(name) || HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)
                    || HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("Custom HTTP authority or framing headers are unsupported");
            }
            if (values.stream().anyMatch(value -> value.chars()
                    .anyMatch(c -> (c < 32 && c != '\t') || c == 127))) {
                throw new IllegalArgumentException("Invalid HTTP header value");
            }
        });
    }

    private byte[] serializeBody(JsonNode body) {
        if (body == null) {
            return null;
        }
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Request body cannot be serialized as JSON");
        }
    }

    private static long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /** Confined to one subscription, never shared between executions. */
    private static final class ExchangeState {
        private volatile Integer status;
        private Map<String, List<String>> headers = Map.of();

        private HttpResponseData result(String body, long elapsed, String error) {
            // Volatile status publishes the headers to the timeout scheduler as one known response.
            Integer knownStatus = status;
            return new HttpResponseData(knownStatus, knownStatus == null ? Map.of() : headers, body, elapsed, error);
        }
    }
}