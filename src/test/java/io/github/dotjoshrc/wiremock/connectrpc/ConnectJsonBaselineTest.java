package io.github.dotjoshrc.wiremock.connectrpc;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectJsonBaselineTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void realConnectClientCallsOrdinaryJsonStub() throws Exception {
        Path clientDirectory = Path.of("examples/client");
        Path clientBinary = temporaryDirectory.resolve("connect-client.exe");
        assertCommandSucceeds(clientDirectory, 180,
                "go", "build", "-o", clientBinary.toString(), ".");

        WireMockServer server = new WireMockServer(wireMockConfig()
                .dynamicPort()
                .usingFilesUnderDirectory("examples/wiremock"));
        server.start();
        try {
            assertCommandSucceeds(clientDirectory, 20, clientBinary.toString(), server.baseUrl());

            server.verify(1, postRequestedFor(urlPathEqualTo("/example.v1.GreeterService/Greet"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withHeader("Connect-Protocol-Version", equalTo("1"))
                    .withRequestBody(equalToJson("{\"name\":\"Josh\"}")));
        } finally {
            server.stop();
        }
    }

    @Test
    void realConnectClientCallsMatcherUsingJsonAndProtobuf() throws Exception {
        Path clientDirectory = Path.of("examples/client");
        Path clientBinary = temporaryDirectory.resolve("connect-client.exe");
        assertCommandSucceeds(clientDirectory, 180,
                "go", "build", "-o", clientBinary.toString(), ".");
        SchemaRegistry schemas = SchemaRegistry.load(Path.of("examples/descriptors/greeter.binpb"));
        WireMockServer server = new WireMockServer(wireMockConfig()
                .dynamicPort()
                .extensions(new ConnectRequestMatcher(schemas), new ConnectResponseTransformer(schemas))
                .usingFilesUnderDirectory("examples/wiremock-connect"));
        server.start();
        try {
            assertCommandSucceeds(clientDirectory, 20, clientBinary.toString(), server.baseUrl(), "json");
            assertCommandSucceeds(clientDirectory, 20, clientBinary.toString(), server.baseUrl(), "proto");
            assertCommandSucceeds(clientDirectory, 20, clientBinary.toString(), "-expect-error", "permission_denied",
                    server.baseUrl(), "json", "Denied");
            assertCommandSucceeds(clientDirectory, 20, clientBinary.toString(), "-expect-error", "permission_denied",
                    server.baseUrl(), "proto", "Denied");
            server.verify(2, postRequestedFor(urlPathEqualTo("/example.v1.GreeterService/Greet"))
                    .withHeader("Content-Type", equalTo("application/json")));
            server.verify(2, postRequestedFor(urlPathEqualTo("/example.v1.GreeterService/Greet"))
                    .withHeader("Content-Type", equalTo("application/proto")));
        } finally {
            server.stop();
        }
    }

    private void assertCommandSucceeds(Path directory, int timeoutSeconds, String... command) throws Exception {
        Path output = Files.createTempFile(temporaryDirectory, "client-", ".log");
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS),
                    "Command timed out: " + String.join(" ", command));
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
