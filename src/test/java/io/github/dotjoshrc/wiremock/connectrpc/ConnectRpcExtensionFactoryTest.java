package io.github.dotjoshrc.wiremock.connectrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ServiceLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ConnectRpcExtensionFactoryTest {
    private static final String PROPERTY = "wiremock.connectrpc.descriptorSet";
    private final ConnectRpcExtensionFactory factory = new ConnectRpcExtensionFactory();
    private String originalProperty;

    @BeforeEach
    void saveProperty() {
        originalProperty = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (originalProperty == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, originalProperty);
        }
    }

    @Test
    void isDiscoverableButInactiveWithoutConfiguration() {
        assertTrue(ServiceLoader.load(ExtensionFactory.class).stream()
                .anyMatch(provider -> provider.type().equals(ConnectRpcExtensionFactory.class)));
        assertFalse(factory.isLoadable());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> factory.create(null))
                .getMessage().contains(PROPERTY));
    }

    @Test
    void createsBothExtensionsFromConfiguredDescriptorSet() {
        System.setProperty(PROPERTY, "examples/descriptors/greeter.binpb");
        assertTrue(factory.isLoadable());
        var extensions = factory.create(null);
        assertEquals(2, extensions.size());
        assertInstanceOf(ConnectRequestMatcher.class, extensions.get(0));
        assertInstanceOf(ConnectResponseTransformer.class, extensions.get(1));
    }

    @Test
    void rejectsBlankConfiguration() {
        System.setProperty(PROPERTY, " ");
        assertTrue(factory.isLoadable());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> factory.create(null))
                .getMessage().contains(PROPERTY));
    }

    @Test
    void reportsUnreadableDescriptorSet(@TempDir Path directory) {
        Path missing = directory.resolve("missing.binpb");
        System.setProperty(PROPERTY, missing.toString());
        var exception = assertThrows(UncheckedIOException.class, () -> factory.create(null));
        assertTrue(exception.getMessage().contains(missing.toString()));
        assertTrue(exception.getCause().getMessage().contains(missing.toString()));
    }
}
