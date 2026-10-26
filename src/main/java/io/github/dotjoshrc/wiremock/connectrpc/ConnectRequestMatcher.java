package io.github.dotjoshrc.wiremock.connectrpc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.Urls;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.matching.MatchResult;
import com.github.tomakehurst.wiremock.matching.RequestMatcherExtension;
import com.github.tomakehurst.wiremock.stubbing.SubEvent;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.UninitializedMessageException;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Matches unary Connect JSON and protobuf bodies against a protobuf JSON expectation. */
public final class ConnectRequestMatcher extends RequestMatcherExtension {
    private static final ObjectReader STRICT_JSON = new ObjectMapper().readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final SchemaRegistry schemas;

    public ConnectRequestMatcher(SchemaRegistry schemas) {
        this.schemas = Objects.requireNonNull(schemas, "schemas");
    }

    @Override
    public String getName() {
        return "connect-rpc";
    }

    @Override
    public MatchResult match(Request request, Parameters parameters) {
        if (parameters == null || !parameters.containsKey("jsonBody")
                || !Set.of("jsonBody", "ignoreExtraFields").containsAll(parameters.keySet())
                || (parameters.containsKey("ignoreExtraFields")
                    && !(parameters.get("ignoreExtraFields") instanceof Boolean))) {
            return mismatch("jsonBody parameter", String.valueOf(parameters),
                    "Configure jsonBody and optionally a boolean ignoreExtraFields");
        }
        boolean partial = Boolean.TRUE.equals(parameters.get("ignoreExtraFields"));
        if (!RequestMethod.POST.equals(request.getMethod())) {
            return mismatch("POST", request.getMethod().getName(), "Only unary POST requests are supported");
        }
        MethodDescriptor method;
        try {
            method = schemas.resolveMethod(Urls.getPath(request.getUrl()));
        } catch (IllegalArgumentException exception) {
            return mismatch("Known RPC path", request.getUrl(), exception.getMessage());
        }
        if (method.isClientStreaming() || method.isServerStreaming()) {
            return mismatch("Unary RPC", method.getFullName(), "Streaming RPCs are not supported");
        }
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding.trim())) {
            return mismatch("Uncompressed request", encoding, "Request compression is not supported");
        }
        String contentType = request.contentTypeHeader().mimeTypePart();
        boolean json = contentType != null && "application/json".equalsIgnoreCase(contentType.trim());
        if (!json && (contentType == null || !"application/proto".equalsIgnoreCase(contentType.trim()))) {
            return mismatch("application/json or application/proto", String.valueOf(contentType),
                    "Unsupported Connect content type");
        }

        TypeRegistry types = TypeRegistry.newBuilder().add(method.getInputType()).build();
        JsonFormat.Parser parser = JsonFormat.parser().usingTypeRegistry(types);
        JsonFormat.Printer printer = JsonFormat.printer().usingTypeRegistry(types).omittingInsignificantWhitespace();
        if (partial) {
            printer = printer.alwaysPrintFieldsWithNoPresence();
        }
        String expected;
        try {
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(method.getInputType());
            // shortcut: nested proto2 required fields need values; use a partial-aware parser if subsets are needed there.
            parser.merge(Json.write(parameters.get("jsonBody")), builder);
            expected = printer.print(partial ? builder.buildPartial() : builder.build());
        } catch (InvalidProtocolBufferException | UninitializedMessageException | IllegalArgumentException exception) {
            return mismatch("Valid protobuf JSON expectation", Json.write(parameters.get("jsonBody")),
                    "Invalid jsonBody configuration: " + exception.getMessage());
        }

        String actual;
        try {
            byte[] body = request.getBody() == null ? new byte[0] : request.getBody();
            DynamicMessage message;
            if (json) {
                ByteString utf8 = ByteString.copyFrom(body);
                if (!utf8.isValidUtf8()) {
                    return mismatch(expected, "Invalid UTF-8", "Connect JSON bodies must be UTF-8");
                }
                String jsonBody = utf8.toStringUtf8();
                STRICT_JSON.readValue(jsonBody);
                DynamicMessage.Builder builder = DynamicMessage.newBuilder(method.getInputType());
                parser.merge(jsonBody, builder);
                message = builder.build();
            } else {
                message = DynamicMessage.parseFrom(method.getInputType(), body);
            }
            actual = printer.print(message);
        } catch (IOException | UninitializedMessageException | IllegalArgumentException exception) {
            return mismatch(expected, "Invalid request body", "Cannot decode Connect request: " + exception.getMessage());
        }
        JsonNode expectedJson = Json.read(expected, JsonNode.class);
        JsonNode actualJson = Json.read(actual, JsonNode.class);
        if (partial ? matchesPartial(method.getInputType(), Json.read(Json.write(parameters.get("jsonBody")), JsonNode.class),
                expectedJson, actualJson) : expectedJson.equals(actualJson)) {
            return MatchResult.exactMatch();
        }
        return mismatch(expected, actual, "Decoded Connect request differs from jsonBody");
    }

    private static boolean matchesPartial(Descriptor type, JsonNode selected, JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null) {
            return Objects.equals(expected, actual);
        }
        // Well-known types have custom JSON representations and are compared as complete values.
        if (!selected.isObject() || !expected.isObject() || type.getFullName().startsWith("google.protobuf.")) {
            return expected.equals(actual);
        }
        for (var entry : selected.properties()) {
            FieldDescriptor field = type.getFields().stream()
                    .filter(candidate -> candidate.getName().equals(entry.getKey())
                            || candidate.getJsonName().equals(entry.getKey()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown field " + entry.getKey()));
            JsonNode expectedValue = expected.get(field.getJsonName());
            JsonNode actualValue = actual.get(field.getJsonName());
            if (field.isMapField() && entry.getValue().isObject() && expectedValue != null && actualValue != null) {
                FieldDescriptor keyField = field.getMessageType().findFieldByName("key");
                FieldDescriptor valueField = field.getMessageType().findFieldByName("value");
                for (var key : entry.getValue().properties()) {
                    String canonicalKey = switch (keyField.getJavaType()) {
                        case INT, LONG -> new BigDecimal(key.getKey()).toBigIntegerExact().toString();
                        default -> key.getKey();
                    };
                    if (!matchesPartialValue(valueField, key.getValue(), expectedValue.get(canonicalKey),
                            actualValue.get(canonicalKey))) {
                        return false;
                    }
                }
            } else if (!matchesPartialValue(field, entry.getValue(), expectedValue, actualValue)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesPartialValue(FieldDescriptor field, JsonNode selected, JsonNode expected, JsonNode actual) {
        if (!field.isRepeated() && field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
            return matchesPartial(field.getMessageType(), selected, expected, actual);
        }
        return Objects.equals(expected, actual);
    }

    private static MatchResult mismatch(String expected, String actual, String message) {
        return new MatchResult(
                List.of(SubEvent.warning("connect-rpc: " + message + "; expected: " + expected + "; actual: " + actual)),
                new MatchResult.DiffDescription(expected, actual, message)) {
            @Override
            public boolean isExactMatch() {
                return false;
            }

            @Override
            public double getDistance() {
                return 1;
            }
        };
    }
}
