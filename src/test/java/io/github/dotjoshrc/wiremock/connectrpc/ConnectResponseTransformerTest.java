package io.github.dotjoshrc.wiremock.connectrpc;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConnectResponseTransformerTest {
    private static final String RPC_PATH = "/matching.v1.MatchingService/Match";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static SchemaRegistry schemas;
    private static WireMockServer server;

    @BeforeAll
    static void startServer() throws Exception {
        schemas = SchemaRegistry.load(Path.of("src/test/resources/descriptors/matching.binpb"));
        server = new WireMockServer(wireMockConfig().dynamicPort().extensions(new ConnectResponseTransformer(schemas)));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void resetStubs() {
        server.resetAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void encodesRichResponseAndReplacesStaleBodyHeaders(String encoding) throws Exception {
        String json = """
                {"echo":{"display_name":"Josh","user_id":"9223372036854775807","payload":"+/8=",
                "status":1,"nickname":"","labels":["b","a"],"counts":{"two":2,"one":1},
                "observedAt":"2026-10-25T01:00:00+01:00",
                "detail":{"@type":"type.googleapis.com/matching.v1.Details","text":"hi"}}}
                """;
        stub(RPC_PATH, aResponse().withStatus(418).withBodyFile("missing.json")
                .withFixedDelay(1).withHeader("X-Custom", "kept")
                .withHeader("content-type", "text/plain").withHeader("Content-Length", "999")
                .withHeader("Content-Encoding", "br").withHeader("Transfer-Encoding", "chunked")
                .withTransformerParameter("jsonBody", Json.read(json, Object.class)));
        HttpResponse<byte[]> response = send(RPC_PATH, "POST", "Application/" + encoding + "; charset=utf-8", Map.of());
        assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("application/" + encoding, response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(1, response.headers().allValues("Content-Type").size());
        assertEquals("kept", response.headers().firstValue("X-Custom").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Encoding").isEmpty());
        var type = schemas.resolveMethod(RPC_PATH).getOutputType();
        var types = TypeRegistry.newBuilder().add(type).build();
        var expected = DynamicMessage.newBuilder(type);
        JsonFormat.parser().usingTypeRegistry(types).merge(json, expected);
        var actual = DynamicMessage.newBuilder(type);
        if (encoding.equals("proto")) {
            actual.mergeFrom(response.body());
        } else {
            JsonFormat.parser().usingTypeRegistry(types).merge(new String(response.body(), StandardCharsets.UTF_8), actual);
            assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("2026-10-25T00:00:00Z"));
        }
        assertEquals(expected.build(), actual.build());
        assertEquals(1, server.getAllServeEvents().get(0).getResponseDefinition().getFixedDelayMilliseconds());
    }

    @ParameterizedTest
    @CsvSource({"canceled,499", "unknown,500", "invalid_argument,400", "deadline_exceeded,504",
            "not_found,404", "already_exists,409", "permission_denied,403", "resource_exhausted,429",
            "failed_precondition,400", "aborted,409", "out_of_range,400", "unimplemented,501",
            "internal,500", "unavailable,503", "data_loss,500", "unauthenticated,401"})
    void mapsEveryErrorCodeForBothRequestCodecs(String code, int status) throws Exception {
        stub(RPC_PATH, aResponse().withTransformerParameter("error", Map.of("code", code, "message", "Denied ☃")));
        for (String encoding : new String[] {"json", "proto"}) {
            HttpResponse<byte[]> response = send(RPC_PATH, "POST", "application/" + encoding, Map.of());
            assertEquals(status, response.statusCode());
            assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(Map.of("code", code, "message", "Denied ☃"), body(response));
        }
    }

    @Test
    void permitsErrorWithoutMessageAndDoesNotApplyGlobally() throws Exception {
        stub(RPC_PATH, aResponse().withTransformerParameter("error", Map.of("code", "unavailable")));
        assertEquals(Map.of("code", "unavailable"), body(send(RPC_PATH, "POST", "application/proto", Map.of())));
        server.stubFor(any(urlPathEqualTo("/ordinary")).willReturn(aResponse().withBody("ordinary")));
        assertEquals("ordinary", new String(send("/ordinary", "GET", "text/plain", Map.of()).body(), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void encodesTypedErrorDetailsIncludingNestedAndWellKnownMessages(String encoding) throws Exception {
        stub(RPC_PATH, aResponse().withTransformerParameter("error", Map.of(
                "code", "invalid_argument", "message", "Invalid request", "details", List.of(
                        Map.of("type", "matching.v1.Details", "jsonBody", Map.of("text", "Denied ☃", "number", 42)),
                        Map.of("type", "matching.v1.Details.Nested", "jsonBody", Map.of("reason", "policy")),
                        Map.of("type", "google.protobuf.Timestamp", "jsonBody", "2026-10-25T00:00:00Z"),
                        Map.of("type", "google.protobuf.Any", "jsonBody", Map.of(
                                "@type", "type.googleapis.com/matching.v1.Details", "text", "nested"))))));

        HttpResponse<byte[]> response = send(RPC_PATH, "POST", "application/" + encoding, Map.of());
        assertEquals(400, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
        Map<?, ?> error = body(response);
        assertEquals("invalid_argument", error.get("code"));
        assertEquals("Invalid request", error.get("message"));
        List<?> details = (List<?>) error.get("details");
        assertEquals(4, details.size());
        Map<?, ?> first = (Map<?, ?>) details.get(0);
        assertEquals("matching.v1.Details", first.get("type"));
        DynamicMessage firstMessage = decodeDetail(first);
        assertEquals("Denied ☃", firstMessage.getField(firstMessage.getDescriptorForType().findFieldByName("text")));
        assertEquals(42, firstMessage.getField(firstMessage.getDescriptorForType().findFieldByName("number")));
        DynamicMessage nested = decodeDetail((Map<?, ?>) details.get(1));
        assertEquals("policy", nested.getField(nested.getDescriptorForType().findFieldByName("reason")));
        DynamicMessage timestamp = decodeDetail((Map<?, ?>) details.get(2));
        assertEquals("\"2026-10-25T00:00:00Z\"", JsonFormat.printer().print(timestamp));
        DynamicMessage any = decodeDetail((Map<?, ?>) details.get(3));
        assertTrue(JsonFormat.printer().usingTypeRegistry(schemas.typeRegistry()).print(any).contains("nested"));
    }

    @Test
    void permitsEmptyDetailsAndEmptyDetailMessages() throws Exception {
        stub(RPC_PATH, aResponse().withTransformerParameter("error", Map.of("code", "internal", "details", List.of())));
        assertEquals(Map.of("code", "internal"), body(send(RPC_PATH, "POST", "application/json", Map.of())));
        stub(RPC_PATH, aResponse().withTransformerParameter("error", Map.of("code", "internal", "details", List.of(
                Map.of("type", "matching.v1.Details", "jsonBody", Map.of())))));
        assertEquals(List.of(Map.of("type", "matching.v1.Details", "value", "")),
                body(send(RPC_PATH, "POST", "application/json", Map.of())).get("details"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "42", "[null]", "[{}]",
            "[{\"type\":\"missing.Type\",\"jsonBody\":{}}]",
            "[{\"type\":42,\"jsonBody\":{}}]",
            "[{\"type\":\"matching.v1.Details\"}]",
            "[{\"type\":\"matching.v1.Details\",\"jsonBody\":null}]",
            "[{\"type\":\"matching.v1.Details\",\"jsonBody\":{\"unknown\":1}}]",
            "[{\"type\":\"matching.v1.Details\",\"jsonBody\":{\"number\":\"bad\"}}]",
            "[{\"type\":\"matching.v1.Details\",\"jsonBody\":{},\"extra\":true}]"})
    void rejectsInvalidDetailsWithoutSendingPartialPayloads(String details) throws Exception {
        ResponseDefinitionBuilder response = aResponse().withTransformerParameter("error", Json.read(
                "{\"code\":\"invalid_argument\",\"details\":" + details + "}", Object.class));
        stub(RPC_PATH, response);
        HttpResponse<byte[]> actual = send(RPC_PATH, "POST", "application/proto", Map.of());
        assertInternal(actual);
        assertTrue(!body(actual).containsKey("details"));
        assertTrue(Json.write(server.getAllServeEvents().get(0).getSubEvents())
                .contains("Invalid connect-rpc response configuration"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"jsonBody\":{},\"error\":{\"code\":\"internal\"}}",
            "{\"extra\":true}", "{\"jsonBody\":{\"unknown\":true}}", "{\"jsonBody\":null}",
            "{\"jsonBody\":42}", "{\"jsonBody\":[]}", "{\"error\":null}", "{\"error\":{}}",
            "{\"error\":{\"code\":\"bogus\"}}", "{\"error\":{\"code\":\"internal\",\"message\":42}}",
            "{\"error\":{\"code\":\"internal\",\"extra\":true}}",
            "{\"jsonBody\":{\"echo\":{\"observedAt\":\"invalid\"}}}"})
    void invalidConfigurationReturnsInternalAndJournalDiagnostic(String config) throws Exception {
        ResponseDefinitionBuilder response = aResponse();
        Json.read(config, Map.class).forEach((key, value) -> response.withTransformerParameter((String) key, value));
        stub(RPC_PATH, response);
        assertInternal(send(RPC_PATH, "POST", "application/json", Map.of()));
        assertTrue(Json.write(server.getAllServeEvents().get(0).getSubEvents())
                .contains("Invalid connect-rpc response configuration"));
    }

    @Test
    void proxyAndFaultCannotOverrideGeneratedError() throws Exception {
        stub(RPC_PATH, aResponse().proxiedFrom("http://127.0.0.1:1")
                .withTransformerParameter("jsonBody", Map.of()));
        assertInternal(send(RPC_PATH, "POST", "application/json", Map.of()));
        stub(RPC_PATH, aResponse().withFault(Fault.EMPTY_RESPONSE)
                .withTransformerParameter("jsonBody", Map.of()));
        assertInternal(send(RPC_PATH, "POST", "application/json", Map.of()));
    }

    @Test
    void guardsUnsupportedRequestsAndUnknownMethods() throws Exception {
        for (String path : new String[] {RPC_PATH, "/matching.v1.MatchingService/Watch", "/matching.v1.MatchingService/Missing"}) {
            stub(path, aResponse().withTransformerParameter("jsonBody", Map.of()));
        }
        assertEquals(501, send(RPC_PATH, "GET", "application/json", Map.of()).statusCode());
        assertEquals(501, send(RPC_PATH, "POST", "application/connect+proto", Map.of()).statusCode());
        assertEquals(501, send(RPC_PATH, "POST", "application/json", Map.of("Content-Encoding", "br")).statusCode());
        assertEquals(501, send("/matching.v1.MatchingService/Watch", "POST", "application/proto", Map.of()).statusCode());
        assertInternal(send("/matching.v1.MatchingService/Missing", "POST", "application/json", Map.of()));
    }

    private void stub(String path, ResponseDefinitionBuilder response) {
        server.stubFor(any(urlPathEqualTo(path)).willReturn(response.withTransformers("connect-rpc-response")));
    }

    private HttpResponse<byte[]> send(String path, String method, String contentType, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString("{}"));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private Map<?, ?> body(HttpResponse<byte[]> response) {
        return Json.read(new String(response.body(), StandardCharsets.UTF_8), Map.class);
    }

    private DynamicMessage decodeDetail(Map<?, ?> detail) throws Exception {
        return DynamicMessage.parseFrom(schemas.resolveMessage((String) detail.get("type")),
                Base64.getDecoder().decode((String) detail.get("value")));
    }

    private void assertInternal(HttpResponse<byte[]> response) {
        assertEquals(500, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("internal", body(response).get("code"));
    }
}
