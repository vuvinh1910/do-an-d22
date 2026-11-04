package com.platform.worker.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.worker.model.TestCaseRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds HTTP URIs after runtime variable interpolation; performs no network access. */
@Component
public class RequestUriBuilder {

    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^{}]+)}");
    private static final Pattern ENCODED_OCTET = Pattern.compile("%25([0-9a-fA-F]{2})");
    private static final Pattern INVALID_PERCENT_ENCODING = Pattern.compile("%(?![0-9a-fA-F]{2})");
    private static final Pattern URI_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");

    public URI build(String baseUrl, TestCaseRequest request) {
        if (baseUrl == null || baseUrl.isBlank() || request == null) {
            throw new IllegalArgumentException("Base URL and request are required");
        }
        URI base = parseBaseUrl(baseUrl);
        String apiPath = expandPath(request);
        String joined = base.getRawPath().replaceAll("/+$", "")
                + (apiPath.startsWith("/") ? "" : "/") + apiPath;
        String query = buildQuery(base.getRawQuery(), request.queryParams());
        URI result = URI.create(base.getScheme() + "://" + base.getRawAuthority() + joined + query);
        if (!base.getRawAuthority().equals(result.getRawAuthority())) {
            throw new IllegalArgumentException("Request path must not change target authority");
        }
        return result;
    }

    private static URI parseBaseUrl(String baseUrl) {
        requireResolved(baseUrl);
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            // URI parser messages/causes include the original URL, which can contain credentials.
            throw new IllegalArgumentException("Base URL has invalid URI syntax");
        }
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getRawUserInfo() != null || base.getRawFragment() != null
                || base.getPort() > 65535 || base.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException("Base URL must be an HTTP(S) URL without credentials or fragment");
        }
        return base;
    }

    private static String expandPath(TestCaseRequest request) {
        String path = request.path();
        if (path == null || path.isBlank() || path.startsWith("//") || URI_SCHEME.matcher(path).find()
                || path.contains("?") || path.contains("#") || path.contains("\\")) {
            throw new IllegalArgumentException("Request path must be a relative API path without query or fragment");
        }
        requireResolved(path);
        Matcher matcher = PATH_PARAMETER.matcher(path);
        StringBuilder expanded = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            expanded.append(encodeLiteralPath(path.substring(end, matcher.start())));
            String value = request.pathParams().get(matcher.group(1));
            if (value == null) {
                throw new IllegalArgumentException("Missing path parameter: " + matcher.group(1));
            }
            requireResolved(value);
            expanded.append(encodeValue(value));
            end = matcher.end();
        }
        expanded.append(encodeLiteralPath(path.substring(end)));
        return expanded.toString();
    }

    private static String buildQuery(String baseQuery, Map<String, JsonNode> parameters) {
        List<String> query = new ArrayList<>();
        if (baseQuery != null && !baseQuery.isEmpty()) {
            query.add(baseQuery);
        }
        parameters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            requireResolved(entry.getKey());
            JsonNode value = entry.getValue();
            if (value.isArray()) {
                value.forEach(item -> appendQuery(query, entry.getKey(), item));
            } else {
                appendQuery(query, entry.getKey(), value);
            }
        });
        return query.isEmpty() ? "" : "?" + String.join("&", query);
    }

    private static void appendQuery(List<String> query, String key, JsonNode value) {
        if (value.isNull()) {
            return;
        }
        if (!value.isValueNode()) {
            throw new IllegalArgumentException("Query values must be scalars or arrays of scalars");
        }
        String text = value.asText();
        requireResolved(text);
        query.add(encodeValue(key) + "=" + encodeValue(text));
    }

    private static String encodeValue(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodeLiteralPath(String path) {
        if (path.contains("{") || path.contains("}") || INVALID_PERCENT_ENCODING.matcher(path).find()) {
            throw new IllegalArgumentException("Malformed path template or percent encoding");
        }
        // Preserve valid encoding already present in the static OpenAPI path.
        return ENCODED_OCTET.matcher(UriUtils.encodePath(path, StandardCharsets.UTF_8)).replaceAll("%$1");
    }

    private static void requireResolved(String value) {
        if (value.contains("{{") || value.contains("}}")) {
            throw new IllegalArgumentException("Runtime variables must be resolved before building the URI");
        }
    }
}