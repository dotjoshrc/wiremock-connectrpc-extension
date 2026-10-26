package io.github.dotjoshrc.wiremock.connectrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectRpcStandaloneIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedExtensionsAreDiscoveredAndServeRealConnectClients() throws Exception {
        Path client = temporaryDirectory.resolve("connect-client.exe");
        assertCommandSucceeds("go", "-C", "examples/client", "build", "-o", client.toString(), ".");
        Path log = temporaryDirectory.resolve("wiremock.log");
        String classpath = System.getProperty("wiremock.jar") + File.pathSeparator
                + System.getProperty("extension.jar");
        Process server = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dwiremock.connectrpc.descriptorSet=examples/descriptors/greeter.binpb",
                "-cp", classpath, "wiremock.Run", "--port", "0",
                "--root-dir", "examples/wiremock-connect")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            String baseUrl = awaitServer(server, log);
            for (String encoding : new String[] {"json", "proto"}) {
                assertCommandSucceeds(client.toString(), baseUrl, encoding);
                assertCommandSucceeds(client.toString(), "-expect-error", "permission_denied",
                        "-expect-error-detail", "Denied",
                        baseUrl, encoding, "Denied");
            }
        } finally {
            server.descendants().forEach(ProcessHandle::destroyForcibly);
            server.destroyForcibly();
            server.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private String awaitServer(Process server, Path log) throws Exception {
        Pattern port = Pattern.compile("(?m)^port:\\s+(\\d+)");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (server.isAlive() && System.nanoTime() < deadline) {
            var matcher = port.matcher(Files.readString(log));
            if (matcher.find()) {
                return "http://localhost:" + matcher.group(1);
            }
            Thread.sleep(100);
        }
        return fail("Standalone WireMock did not start: " + Files.readString(log));
    }

    private void assertCommandSucceeds(String... command) throws Exception {
        Path output = Files.createTempFile(temporaryDirectory, "client-", ".log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Command timed out: " + String.join(" ", command));
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
