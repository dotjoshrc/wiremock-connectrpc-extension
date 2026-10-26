package io.github.dotjoshrc.wiremock.connectrpc;

import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/** Registers both Connect extensions with one configured schema snapshot. */
public final class ConnectRpcExtensionFactory implements ExtensionFactory {
    private static final String DESCRIPTOR_SET_PROPERTY = "wiremock.connectrpc.descriptorSet";

    @Override
    public boolean isLoadable() {
        return System.getProperty(DESCRIPTOR_SET_PROPERTY) != null;
    }

    @Override
    public List<Extension> create(WireMockServices services) {
        String descriptorSet = System.getProperty(DESCRIPTOR_SET_PROPERTY);
        if (descriptorSet == null || descriptorSet.isBlank()) {
            throw new IllegalArgumentException("Set -D" + DESCRIPTOR_SET_PROPERTY + "=<descriptor-set-path>");
        }
        try {
            SchemaRegistry schemas = SchemaRegistry.load(Path.of(descriptorSet));
            return List.of(new ConnectRequestMatcher(schemas), new ConnectResponseTransformer(schemas));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot load -D" + DESCRIPTOR_SET_PROPERTY + "=" + descriptorSet, exception);
        }
    }
}
