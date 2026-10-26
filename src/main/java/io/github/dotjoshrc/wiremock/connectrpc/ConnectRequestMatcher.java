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
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.UninitializedMessageException;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
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
        if (parameters == null || !parameters.keySet().equals(Set.of("jsonBody"))) {
            return mismatch("jsonBody parameter", String.valueOf(parameters),
                    "Configure connect-rpc with exactly one parameter: jsonBody");
        }
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
        String expected;
        try {
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(method.getInputType());
            parser.merge(Json.write(parameters.get("jsonBody")), builder);
            expected = printer.print(builder.build());
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
        if (Json.read(expected, JsonNode.class).equals(Json.read(actual, JsonNode.class))) {
            return MatchResult.exactMatch();
        }
        return mismatch(expected, actual, "Decoded Connect request differs from jsonBody");
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
