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
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void partialMatchingChecksSelectedFieldsAndNestedMapValues(String encoding) throws Exception {
        partialStub("""
                {"display_name":"Josh","context":{"text":"hi"},"counts":{"one":1},
                 "contexts":{"first":{"text":"nested"}}}
                """);
        String body = """
                {"displayName":"Josh","status":1,"context":{"text":"hi","number":42},
                 "counts":{"one":1,"two":2},"contexts":{"first":{"text":"nested","number":3},"extra":{}}}
                """;
        assertEquals(200, send(encoding, body).statusCode());
        assertEquals(404, send(encoding, body.replace("nested", "different")).statusCode());
        assertEquals(404, send(encoding, body.replace("\"one\":1", "\"one\":2")).statusCode());
        assertEquals(404, send(encoding, "{\"displayName\":\"Josh\"}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void partialMatchingNormalizesNumericMapKeys(String encoding) throws Exception {
        partialStub("{\"numeric_contexts\":{\"01\":{\"text\":\"hi\"}}}");
        assertEquals(200, send(encoding, "{\"numericContexts\":{\"1\":{\"text\":\"hi\",\"number\":2},\"2\":{}}}").statusCode());
        assertEquals(404, send(encoding, "{}").statusCode());
        assertEquals(404, send(encoding, "{\"numericContexts\":{\"1\":{\"text\":\"different\"}}}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void partialExpectationMayOmitRequiredFieldsButRequestsMustRemainValid(String encoding, @TempDir Path temporary)
            throws Exception {
        FileDescriptorProto file = FileDescriptorProto.newBuilder().setName("required.proto").setSyntax("proto2")
                .setPackage("required.v1")
                .addMessageType(DescriptorProto.newBuilder().setName("RequiredRequest")
                        .addField(FieldDescriptorProto.newBuilder().setName("name").setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                .setLabel(FieldDescriptorProto.Label.LABEL_REQUIRED))
                        .addField(FieldDescriptorProto.newBuilder().setName("child").setNumber(2)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(".required.v1.Child")
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                .addMessageType(DescriptorProto.newBuilder().setName("Child")
                        .addField(FieldDescriptorProto.newBuilder().setName("id").setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                .setLabel(FieldDescriptorProto.Label.LABEL_REQUIRED)))
                .addService(ServiceDescriptorProto.newBuilder().setName("RequiredService")
                        .addMethod(MethodDescriptorProto.newBuilder().setName("Call")
                                .setInputType(".required.v1.RequiredRequest").setOutputType(".required.v1.RequiredRequest")))
                .build();
        Path descriptors = temporary.resolve("required.binpb");
        Files.write(descriptors, FileDescriptorSet.newBuilder().addFile(file).build().toByteArray());
        SchemaRegistry requiredSchemas = SchemaRegistry.load(descriptors);
        String path = "/required.v1.RequiredService/Call";
        WireMockServer requiredServer = new WireMockServer(wireMockConfig().dynamicPort()
                .extensions(new ConnectRequestMatcher(requiredSchemas)));
        requiredServer.start();
        try {
            for (boolean valid : new boolean[] {true, false}) {
                requiredServer.resetAll();
                requiredServer.stubFor(post(urlPathEqualTo(path)).andMatching("connect-rpc",
                        Parameters.from(Map.of("jsonBody", Map.of(), "ignoreExtraFields", true))).willReturn(ok()));
                String json = valid ? "{\"name\":\"Josh\",\"child\":{\"id\":\"child\"}}" : "{}";
                byte[] body = json.getBytes(UTF_8);
                if (encoding.equals("proto")) {
                    var builder = DynamicMessage.newBuilder(requiredSchemas.resolveMethod(path).getInputType());
                    JsonFormat.parser().merge(json, builder);
                    body = builder.buildPartial().toByteArray();
                }
                HttpRequest request = HttpRequest.newBuilder(URI.create(requiredServer.baseUrl() + path))
                        .header("Content-Type", "application/" + encoding)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(valid ? 200 : 404, response.statusCode(), response.body());
                if (valid) {
                    for (boolean complete : new boolean[] {false, true}) {
                        requiredServer.resetAll();
                        requiredServer.stubFor(post(urlPathEqualTo(path)).andMatching("connect-rpc", Parameters.from(Map.of(
                                "jsonBody", Map.of("child", complete ? Map.of("id", "child") : Map.of()),
                                "ignoreExtraFields", true))).willReturn(ok()));
                        var nestedResponse = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                        assertEquals(complete ? 200 : 404, nestedResponse.statusCode(), nestedResponse.body());
                        if (!complete) {
                            assertTrue(nestedResponse.body().contains("Invalid jsonBody configuration"));
                        }
                    }
                }
            }
        } finally {
            requiredServer.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void partialMatchingRetainsExplicitDefaultsAndOptionalPresence(String encoding) throws Exception {
        partialStub("{\"display_name\":\"\",\"nickname\":\"\",\"user_id\":\"0\"}");
        assertEquals(200, send(encoding, "{\"nickname\":\"\",\"status\":1}").statusCode());
        assertEquals(404, send(encoding, "{}").statusCode());
        assertEquals(404, send(encoding, "{\"nickname\":\"\",\"displayName\":\"Josh\"}").statusCode());
        assertEquals(404, send(encoding, "{\"nickname\":\"\",\"userId\":1}").statusCode());
        partialStub("{\"nickname\":null,\"context\":null}");
        assertEquals(200, send(encoding, "{\"status\":1}").statusCode());
        assertEquals(200, send(encoding, "{\"nickname\":\"\"}").statusCode());
        assertEquals(200, send(encoding, "{\"context\":{}}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void partialMatchingKeepsRepeatedValuesExactAndOrdered(String encoding) throws Exception {
        partialStub("{\"labels\":[\"a\",\"b\"],\"entries\":[{\"text\":\"hi\"}]}");
        assertEquals(200, send(encoding, "{\"labels\":[\"a\",\"b\"],\"entries\":[{\"text\":\"hi\"}],\"status\":1}").statusCode());
        assertEquals(404, send(encoding, "{\"labels\":[\"b\",\"a\"],\"entries\":[{\"text\":\"hi\"}]}").statusCode());
        assertEquals(404, send(encoding, "{\"labels\":[\"a\",\"b\",\"c\"],\"entries\":[{\"text\":\"hi\"}]}").statusCode());
        assertEquals(404, send(encoding, "{\"labels\":[\"a\",\"b\"],\"entries\":[{\"text\":\"hi\",\"number\":1}]}").statusCode());
        partialStub("{\"labels\":[]}");
        assertEquals(200, send(encoding, "{\"status\":1}").statusCode());
        assertEquals(404, send(encoding, "{\"labels\":[\"a\"]}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "proto"})
    void partialMatchingTreatsWellKnownTypesAsCompleteValues(String encoding) throws Exception {
        partialStub("""
                {"observedAt":"2026-10-25T00:00:00Z",
                 "detail":{"@type":"type.googleapis.com/matching.v1.Details","text":"hi"}}
                """);
        assertEquals(200, send(encoding, """
                {"status":1,"observedAt":"2026-10-25T01:00:00+01:00",
                 "detail":{"@type":"type.googleapis.com/matching.v1.Details","text":"hi"}}
                """).statusCode());
        assertEquals(404, send(encoding, """
                {"observedAt":"2026-10-25T00:00:00Z",
                 "detail":{"@type":"type.googleapis.com/matching.v1.Details","text":"hi","number":1}}
                """).statusCode());
    }

    @Test
    void partialMatchingRejectsInvalidOptionsAndUnknownFields() throws Exception {
        server.stubFor(post(urlPathEqualTo(RPC_PATH)).andMatching("connect-rpc",
                Parameters.from(Map.of("jsonBody", Map.of(), "ignoreExtraFields", "true"))).willReturn(ok()));
        assertEquals(404, send("json", "{}").statusCode());
        server.resetAll();
        partialStub("{\"unknown\":1}");
        assertEquals(404, send("json", "{}").statusCode());
        server.resetAll();
        partialStub("{}");
        assertEquals(200, send("json", "{\"status\":1}").statusCode());
        assertEquals(404, send("json", "{\"unknown\":1}").statusCode());
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
        stub(expectedJson, false);
    }

    private void partialStub(String expectedJson) {
        server.resetAll();
        stub(expectedJson, true);
    }

    private void stub(String expectedJson, boolean partial) {
        Parameters parameters = Parameters.empty();
        parameters.put("jsonBody", Json.read(expectedJson, Object.class));
        if (partial) {
            parameters.put("ignoreExtraFields", true);
        }
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
