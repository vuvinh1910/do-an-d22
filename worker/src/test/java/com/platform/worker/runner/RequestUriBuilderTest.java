package com.platform.worker.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.model.TestCaseRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestUriBuilderTest {

    private final RequestUriBuilder builder = new RequestUriBuilder();
    private final ObjectMapper mapper = new ObjectMapper();

    private TestCaseRequest request(String path) {
        return new TestCaseRequest("GET", path, null, null, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/api", "https://example.com/api/", "https://example.com/api///"})
    void preservesBasePathAndJoinsAtOneSlash(String base) {
        assertEquals("https://example.com/api/users", builder.build(base, request("/users")).toString());
        assertEquals("https://example.com/api/users", builder.build(base, request("users")).toString());
    }

    @Test
    void preservesPortIpv6AndRootPath() {
        assertEquals("http://[::1]:8088/", builder.build("http://[::1]:8088", request("/")).toString());
    }

    @Test
    void encodesPathParameterAsDataAndReplacesRepeatedOccurrences() {
        TestCaseRequest request = new TestCaseRequest("GET", "/users/{id}/copy/{id}", null,
                Map.of("id", "a/b ?#&+%"), null, null);

        URI uri = builder.build("https://example.com", request);

        assertEquals("/users/a%2Fb%20%3F%23%26%2B%25/copy/a%2Fb%20%3F%23%26%2B%25", uri.getRawPath());
        assertNull(uri.getRawQuery());
        assertNull(uri.getFragment());
        assertEquals("/users/{id}/copy/{id}", request.path());
        assertEquals("a/b ?#&+%", request.pathParams().get("id"));
    }

    @Test
    void preservesEncodedStaticPathWithoutDoubleEncodingAndEncodesUnicode() {
        assertEquals("https://example.com/base%20dir/t%C3%AAn/a%2Fb",
                builder.build("https://example.com/base%20dir", request("/tên/a%2Fb")).toASCIIString());
        TestCaseRequest request = new TestCaseRequest("GET", "/{id}", null, Map.of("id", "%2F"), null, null);
        assertEquals("/%252F", builder.build("https://example.com", request).getRawPath());
    }

    @Test
    void preservesExistingBaseQueryAndEncodesScalarAndArrayQuery() throws Exception {
        Map<String, JsonNode> query = Map.of(
                "search &", mapper.readTree("\"cà phê &+/#?\""),
                "tag", mapper.readTree("[\"a\",\"b\"]"),
                "page", mapper.readTree("2"),
                "active", mapper.readTree("true"),
                "empty", mapper.readTree("\"\""));
        TestCaseRequest request = new TestCaseRequest("GET", "/users", null, null, query, null);

        URI uri = builder.build("https://example.com/api?fixed=a%2Bb", request);

        assertEquals("fixed=a%2Bb&active=true&empty=&page=2&search%20%26=c%C3%A0%20ph%C3%AA%20%26%2B%2F%23%3F&tag=a&tag=b",
                uri.getRawQuery());
        assertEquals(2, request.queryParams().get("tag").size());
    }

    @Test
    void omitsNullAndEmptyArrayQueryValues() throws Exception {
        TestCaseRequest request = new TestCaseRequest("GET", "/", null, null,
                Map.of("missing", mapper.readTree("null"), "tags", mapper.readTree("[]")), null);
        assertNull(builder.build("https://example.com", request).getRawQuery());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[[1]]", "[{}]"})
    void rejectsUnsupportedQueryStructures(String json) throws Exception {
        TestCaseRequest request = new TestCaseRequest("GET", "/", null, null,
                Map.of("filter", mapper.readTree(json)), null);
        assertThrows(IllegalArgumentException.class, () -> builder.build("https://example.com", request));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "example.com", "ftp://example.com", "https:///users", "https://user:pass@example.com",
            "https://example.com/#section", "https://example.com:99999", "https://example.com:", "https://exa mple.com"})
    void rejectsInvalidBaseUrls(String base) {
        assertThrows(IllegalArgumentException.class, () -> builder.build(base, request("/users")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "https://other.example/users", "//other.example/users", "/users?q=a", "/users#section",
            "/users\\admin", "/users/{", "/users/}", "/users/%GG", "/users/%", "/{{userId}}"})
    void rejectsInvalidOrUnresolvedPaths(String path) {
        assertThrows(IllegalArgumentException.class, () -> builder.build("https://example.com", request(path)));
    }

    @Test
    void rejectsNullRequestAndMissingPathParameter() {
        assertThrows(IllegalArgumentException.class, () -> builder.build("https://example.com", null));
        assertThrows(IllegalArgumentException.class, () -> builder.build("https://example.com", request("/{id}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{userId}}"})
    void rejectsUnsafeOrUnresolvedPathParameter(String value) {
        TestCaseRequest request = new TestCaseRequest("GET", "/{id}", null, Map.of("id", value), null, null);
        assertThrows(IllegalArgumentException.class, () -> builder.build("https://example.com", request));
    }

    @Test
    void rejectsUnresolvedQueryValueAndKey() throws Exception {
        TestCaseRequest value = new TestCaseRequest("GET", "/", null, null,
                Map.of("q", mapper.readTree("\"{{term}}\"")), null);
        TestCaseRequest key = new TestCaseRequest("GET", "/", null, null,
                Map.of("{{key}}", mapper.readTree("1")), null);
        assertThrows(IllegalArgumentException.class, () -> builder.build("https://example.com", value));
        assertThrows(IllegalArgumentException.class, () -> builder.build("https://example.com", key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users:search", "/v1/users:batchGet", "v1/users:search", "/{id}:activate"})
    void allowsColonInPathWithoutChangingAuthority(String path) {
        TestCaseRequest request = new TestCaseRequest("GET", path, null, Map.of("id", "42"), null, null);
        URI uri = builder.build("https://example.com:8443/base", request);

        assertEquals("example.com:8443", uri.getRawAuthority());
        assertEquals("https", uri.getScheme());
        assertEquals("/base/" + path.replaceFirst("^/", "").replace("{id}", "42"), uri.getRawPath());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https:other", "mailto:test@example.com", "file:/tmp/data", "//other.example", "///other.example"})
    void rejectsSchemeAndAuthorityInApiPath(String path) {
        assertThrows(IllegalArgumentException.class, () -> builder.build("https://example.com", request(path)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "%2e%2e"})
    void preservesNegativePathValuesWithoutNormalizing(String value) {
        TestCaseRequest request = new TestCaseRequest("GET", "/users/{id}/details", null,
                Map.of("id", value), null, null);
        URI uri = builder.build("https://example.com/base", request);

        assertEquals("/base/users/" + value.replace("%", "%25") + "/details", uri.getRawPath());
        assertEquals("example.com", uri.getRawAuthority());
    }

    @Test
    void preservesStaticDotSegmentsAndAdjacentPlaceholders() {
        assertEquals("/base/a/../%2e/./b",
                builder.build("https://example.com/base", request("/a/../%2e/./b")).getRawPath());
        TestCaseRequest request = new TestCaseRequest("GET", "/{first}{second}", null,
                Map.of("first", ".", "second", "."), null, null);
        assertEquals("/..", builder.build("https://example.com", request).getRawPath());
    }

    @Test
    void appendsDuplicateQueryKeysAndPreservesScalarValues() throws Exception {
        TestCaseRequest request = new TestCaseRequest("GET", "/", null, null, Map.of(
                "tag", mapper.readTree("[null,\"new\",null]"),
                "", mapper.readTree("\"\""),
                "amount", mapper.readTree("-1.5")), null);

        assertEquals("tag=old&=&amount=-1.5&tag=new",
                builder.build("https://example.com?tag=old", request).getRawQuery());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://user:private-token@exa mple.com", "https://example.com?token=private-token%",
            "https://example.com/{{private-token}}"})
    void invalidBaseUrlDoesNotLeakInputInExceptionChain(String base) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> builder.build(base, request("/")));

        assertFalse(error.toString().contains("private-token"));
        assertNull(error.getCause());
    }

    @Test
    void encodesAuthorityLikeParameterWithoutChangingTarget() {
        TestCaseRequest request = new TestCaseRequest("GET", "/{id}", null,
                Map.of("id", "//other.example:8443/@admin"), null, null);
        URI uri = builder.build("https://example.com", request);
        assertEquals("example.com", uri.getRawAuthority());
        assertEquals("/%2F%2Fother.example%3A8443%2F%40admin", uri.getRawPath());
    }
}