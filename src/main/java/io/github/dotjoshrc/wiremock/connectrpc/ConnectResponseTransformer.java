package io.github.dotjoshrc.wiremock.connectrpc;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.Urls;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.SubEvent;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.UninitializedMessageException;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Encodes unary Connect responses from protobuf JSON or a Connect error configuration. */
public final class ConnectResponseTransformer implements ResponseDefinitionTransformerV2 {
    private static final Map<String, Integer> ERROR_STATUSES = Map.ofEntries(
            Map.entry("canceled", 499), Map.entry("unknown", 500), Map.entry("invalid_argument", 400),
            Map.entry("deadline_exceeded", 504), Map.entry("not_found", 404), Map.entry("already_exists", 409),
            Map.entry("permission_denied", 403), Map.entry("resource_exhausted", 429),
            Map.entry("failed_precondition", 400), Map.entry("aborted", 409), Map.entry("out_of_range", 400),
            Map.entry("unimplemented", 501), Map.entry("internal", 500), Map.entry("unavailable", 503),
            Map.entry("data_loss", 500), Map.entry("unauthenticated", 401));
    private static final Set<String> BODY_HEADERS = Set.of(
            "content-type", "content-length", "content-encoding", "transfer-encoding");

    private final SchemaRegistry schemas;

    public ConnectResponseTransformer(SchemaRegistry schemas) {
        this.schemas = Objects.requireNonNull(schemas, "schemas");
    }

    @Override
    public String getName() {
        return "connect-rpc-response";
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }

    @Override
    public ResponseDefinition transform(ServeEvent event) {
        Request request = event.getRequest();
        String contentType = request.contentTypeHeader().mimeTypePart();
        boolean json = contentType != null && "application/json".equalsIgnoreCase(contentType.trim());
        if (!RequestMethod.POST.equals(request.getMethod())
                || (!json && (contentType == null || !"application/proto".equalsIgnoreCase(contentType.trim())))) {
            return error(event, "unimplemented", "Only unary Connect JSON or protobuf POST requests are supported");
        }
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding.trim())) {
            return error(event, "unimplemented", "Request compression is not supported");
        }
        try {
            MethodDescriptor method = schemas.resolveMethod(Urls.getPath(request.getUrl()));
            if (method.isClientStreaming() || method.isServerStreaming()) {
                return error(event, "unimplemented", "Streaming RPCs are not supported");
            }
            if (event.getResponseDefinition().getProxyBaseUrl() != null
                    || event.getResponseDefinition().getFault() != null) {
                throw new IllegalArgumentException("Generated responses cannot use proxyBaseUrl or fault");
            }
            Parameters parameters = event.getTransformerParameters();
            if (parameters.keySet().equals(Set.of("error"))) {
                return configuredError(event, parameters.get("error"));
            }
            if (!parameters.keySet().equals(Set.of("jsonBody"))) {
                throw new IllegalArgumentException("Configure exactly one transformer parameter: jsonBody or error");
            }
            TypeRegistry types = TypeRegistry.newBuilder().add(method.getOutputType()).build();
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(method.getOutputType());
            JsonFormat.parser().usingTypeRegistry(types).merge(Json.write(parameters.get("jsonBody")), builder);
            DynamicMessage message = builder.build();
            String canonical = JsonFormat.printer().usingTypeRegistry(types)
                    .omittingInsignificantWhitespace().print(message);
            byte[] body = json ? canonical.getBytes(StandardCharsets.UTF_8) : message.toByteArray();
            return response(event, 200, json ? "application/json" : "application/proto", body);
        } catch (InvalidProtocolBufferException | UninitializedMessageException | IllegalArgumentException exception) {
            String diagnostic = "Invalid connect-rpc response configuration: " + exception.getMessage();
            event.appendSubEvent(SubEvent.error(diagnostic));
            return error(event, "internal", diagnostic);
        }
    }

    private static ResponseDefinition configuredError(ServeEvent event, Object value) {
        if (!(value instanceof Map<?, ?> config)
                || !Set.of("code", "message").containsAll(config.keySet())
                || !(config.get("code") instanceof String code)
                || !ERROR_STATUSES.containsKey(code)) {
            throw new IllegalArgumentException("error requires a valid Connect code and an optional string message");
        }
        if (config.containsKey("message") && !(config.get("message") instanceof String)) {
            throw new IllegalArgumentException("error.message must be a string");
        }
        return error(event, code, (String) config.get("message"));
    }

    private static ResponseDefinition error(ServeEvent event, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        if (message != null) {
            body.put("message", message);
        }
        return response(event, ERROR_STATUSES.get(code), "application/json",
                Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseDefinition response(ServeEvent event, int status, String contentType, byte[] body) {
        ResponseDefinition original = event.getResponseDefinition();
        HttpHeaders headers = original.getHeaders() == null ? new HttpHeaders() : original.getHeaders();
        HttpHeaders retained = new HttpHeaders(headers.all().stream()
                .filter(header -> !BODY_HEADERS.contains(header.key().toLowerCase(java.util.Locale.ROOT)))
                .toList());
        return ResponseDefinitionBuilder.like(original)
                .withStatus(status)
                .withStatusMessage(null)
                .withHeaders(retained)
                .withHeader("Content-Type", contentType)
                .withFault(null)
                .proxiedFrom(null)
                .withBodyFile(null)
                .withBody(body)
                .build();
    }
}
