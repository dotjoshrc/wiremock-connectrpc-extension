package io.github.dotjoshrc.wiremock.connectrpc;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConnectRequestMatcherTest {
    private static final String RPC_PATH = "/matching.v1.MatchingService/Match";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static SchemaRegistry schemas;
    private static WireMockServer server;

    @BeforeAll
    static void startServer() throws Exception {
        schemas = SchemaRegistry.load(Path.of("src/test/resources/descriptors/matching.binpb"));
        server = new WireMockServer(wireMockConfig().dynamicPort().extensions(new ConnectRequestMatcher(schemas)));
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
    void normalizesFieldNamesInt64BytesAndEnums(String encoding) throws Exception {
        stub("""
                {"displayName":"Josh","userId":"9223372036854775807","payload":"+/8=","status":"STATUS_ACTIVE"}
                """);
        HttpResponse<String> response = send(encoding, """
                {"display_name":"Josh","user_id":9223372036854775807,"payload":"-_8=","status":1}
                """);
        assertEquals(200, response.statusCode(), response.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void treatsImplicitDefaultsAsUnset(String encoding) throws Exception {
        stub("{\"displayName\":\"\",\"userId\":\"0\",\"status\":0,\"labels\":[]}");
        assertEquals(200, send(encoding, "{}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void preservesOptionalFieldPresence(String encoding) throws Exception {
        stub("{}");
        assertEquals(404, send(encoding, "{\"nickname\":\"\"}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void ignoresMapKeyOrderButPreservesRepeatedOrder(String encoding) throws Exception {
        stub("{\"counts\":{\"one\":1,\"two\":2},\"labels\":[\"a\",\"b\"]}");
        assertEquals(200, send(encoding, "{\"labels\":[\"a\",\"b\"],\"counts\":{\"two\":2,\"one\":1}}").statusCode());
        assertEquals(404, send(encoding, "{\"labels\":[\"b\",\"a\"],\"counts\":{\"one\":1,\"two\":2}}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void matchesImportedWellKnownTypesAndAny(String encoding) throws Exception {
        stub("""
                {"observedAt":"2026-10-25T00:00:00Z",
                 "detail":{"@type":"type.googleapis.com/matching.v1.Details","text":"hi"}}
                """);
        HttpResponse<String> response = send(encoding, """
                {"observedAt":"2026-10-25T01:00:00+01:00",
                 "detail":{"@type":"type.googleapis.com/matching.v1.Details","text":"hi"}}
                """);
        assertEquals(200, response.statusCode(), response.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void reportsDecodedMismatchAndRejectsAdditionalFields(String encoding) throws Exception {
        stub("{\"displayName\":\"Josh\"}");
        HttpResponse<String> response = send(encoding, "{\"displayName\":\"Other\"}");
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Decoded Connect request differs"), response.body());
        assertTrue(response.body().contains("Josh"), response.body());
        assertTrue(response.body().contains("Other"), response.body());
        assertEquals(404, send(encoding, "{\"displayName\":\"Josh\",\"status\":1}").statusCode());
        assertEquals(200, send(encoding, "{\"displayName\":\"Josh\"}").statusCode());
    }

    @Test
    void ignoresUnknownBinaryFields() throws Exception {
        stub("{}");
        assertEquals(200, raw(RPC_PATH, "application/proto", new byte[] {120, 1}, Map.of()).statusCode());
    }

    @Test
    void malformedPayloadsDoNotCrashWireMock() throws Exception {
        stub("{}");
        assertEquals(404, raw(RPC_PATH, "application/proto", new byte[] {10, 5, 1}, Map.of()).statusCode());
        assertEquals(404, raw(RPC_PATH, "application/json", "{".getBytes(UTF_8), Map.of()).statusCode());
        assertEquals(404, raw(RPC_PATH, "application/json", new byte[] {(byte) 0xff}, Map.of()).statusCode());
        assertEquals(404, send("json", "{\"unknown\":true}").statusCode());
        assertEquals(404, send("json", "{\"userId\":\"not-an-integer\"}").statusCode());
        assertEquals(200, send("json", "{}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"displayName\":\"Josh\"} garbage", "{\"displayName\":\"Josh\"}{}",
            "{displayName:'Josh'}", "{\"displayName\":\"Josh\",}", "{\"displayName\":\"Josh\"/*comment*/}"})
    void rejectsNonStrictOrTrailingJson(String body) throws Exception {
        stub("{\"displayName\":\"Josh\"}");
        HttpResponse<String> response = send("json", body);
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Cannot decode Connect request"), response.body());
    }

    @Test
    void rejectsInvalidBinaryWellKnownTypesWithoutCrashingWireMock() throws Exception {
        stub("{}");
        var requestType = schemas.resolveMethod(RPC_PATH).getInputType();
        var timestampField = requestType.findFieldByName("observed_at");
        var timestampType = timestampField.getMessageType();
        DynamicMessage timestamp = DynamicMessage.newBuilder(timestampType)
                .setField(timestampType.findFieldByName("seconds"), Long.MAX_VALUE)
                .build();
        byte[] body = DynamicMessage.newBuilder(requestType).setField(timestampField, timestamp).build().toByteArray();

        HttpResponse<String> response = raw(RPC_PATH, "application/proto", body, Map.of());
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Cannot decode Connect request"), response.body());
        assertEquals(200, send("json", "{}").statusCode());
    }

    @Test
    void rejectsUnsupportedContentTypesAndCompression() throws Exception {
        stub("{}");
        assertEquals(404, raw(RPC_PATH, "application/connect+proto", new byte[0], Map.of()).statusCode());
        assertEquals(404, raw(RPC_PATH, "application/json", "{}".getBytes(UTF_8),
                Map.of("Content-Encoding", "br")).statusCode());
        assertEquals(200, raw(RPC_PATH, "Application/JSON; charset=utf-8", "{}".getBytes(UTF_8), Map.of()).statusCode());
    }

    @Test
    void reportsInvalidExpectationWithoutMatchingEverything() throws Exception {
        stub("{\"unknown\":true}");
        HttpResponse<String> response = send("json", "{}");
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Invalid jsonBody configuration"), response.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "42", "[]"})
    void rejectsExpectationsThatAreNotRequestMessages(String json) throws Exception {
        stub(json);
        assertEquals(404, send("json", "{}").statusCode());
    }

    @Test
    void rejectsStreamingRpcDescriptors() throws Exception {
        String streaming = "/matching.v1.MatchingService/Watch";
        server.stubFor(post(urlPathEqualTo(streaming))
                .andMatching("connect-rpc", Parameters.one("jsonBody", Map.of())).willReturn(ok()));
        HttpResponse<String> response = raw(streaming, "application/proto", new byte[0], Map.of());
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Streaming RPCs are not supported"), response.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void choosesBetweenStubsByDecodedContents(String encoding) throws Exception {
        stub("{\"displayName\":\"Josh\"}");
        server.stubFor(post(urlPathEqualTo(RPC_PATH))
                .andMatching("connect-rpc", Parameters.one("jsonBody", Map.of("displayName", "Other")))
                .willReturn(ok("other stub")));

        assertEquals("matched", send(encoding, "{\"displayName\":\"Josh\"}").body());
        assertEquals("other stub", send(encoding, "{\"displayName\":\"Other\"}").body());
    }

    @Test
    void reportsMissingMatcherParameters() throws Exception {
        server.stubFor(post(urlPathEqualTo(RPC_PATH))
                .andMatching("connect-rpc", Parameters.empty()).willReturn(ok()));
        HttpResponse<String> response = send("json", "{}");
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("jsonBody"), response.body());
    }

    @Test
    void reportsUnknownRpc() throws Exception {
        String unknown = "/matching.v1.MatchingService/Unknown";
        server.stubFor(post(urlPathEqualTo(unknown))
                .andMatching("connect-rpc", Parameters.one("jsonBody", Map.of())).willReturn(ok()));
        HttpResponse<String> response = raw(unknown, "application/json", "{}".getBytes(UTF_8), Map.of());
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Unknown protobuf method"), response.body());
    }

    private void stub(String expectedJson) {
        Parameters parameters = Parameters.empty();
        parameters.put("jsonBody", Json.read(expectedJson, Object.class));
        server.stubFor(post(urlPathEqualTo(RPC_PATH))
                .andMatching("connect-rpc", parameters)
                .willReturn(ok("matched")));
    }

    private HttpResponse<String> send(String encoding, String json) throws Exception {
        byte[] body = json.getBytes(UTF_8);
        if (encoding.equals("proto")) {
            var type = schemas.resolveMethod(RPC_PATH).getInputType();
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(type);
            JsonFormat.parser().usingTypeRegistry(TypeRegistry.newBuilder().add(type).build()).merge(json, builder);
            body = builder.build().toByteArray();
        }
        return raw(RPC_PATH, "application/" + encoding, body, Map.of());
    }

    private HttpResponse<String> raw(String path, String contentType, byte[] body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", contentType)
                .header("Connect-Protocol-Version", "1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
