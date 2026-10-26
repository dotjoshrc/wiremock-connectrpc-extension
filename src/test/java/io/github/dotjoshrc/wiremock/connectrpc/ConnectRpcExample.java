package io.github.dotjoshrc.wiremock.connectrpc;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Runs the in-progress extension locally using the checked-in example schemas and mappings. */
public final class ConnectRpcExample {
    public static void main(String[] args) throws Exception {
        SchemaRegistry schemas = SchemaRegistry.load(Path.of("examples/descriptors/greeter.binpb"));
        WireMockServer server = new WireMockServer(wireMockConfig()
                .port(8080)
                .extensions(new ConnectRequestMatcher(schemas), new ConnectResponseTransformer(schemas))
                .usingFilesUnderDirectory("examples/wiremock-connect"));
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Connect RPC example listening at " + server.baseUrl());
        new CountDownLatch(1).await();
    }
}
