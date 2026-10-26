package io.github.dotjoshrc.wiremock.connectrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.MethodDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SchemaRegistryTest {
    private static final Path EXAMPLE = Path.of("examples/descriptors/greeter.binpb");

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesExampleMethodAndImportedMessageTypes() throws IOException {
        MethodDescriptor method = SchemaRegistry.load(EXAMPLE)
                .resolveMethod("/example.v1.GreeterService/Greet");

        assertEquals("example.v1.GreeterService.Greet", method.getFullName());
        assertEquals("example.v1.GreetRequest", method.getInputType().getFullName());
        assertEquals("example.v1.GreetResponse", method.getOutputType().getFullName());
        assertEquals("example/v1/greet.proto", method.getInputType().getFile().getName());
        assertSame(method.getInputType().getFile(), method.getOutputType().getFile());
        assertNotNull(method.getInputType().findFieldByName("name"));
        assertNotNull(method.getOutputType().findFieldByName("greeting"));
    }

    @Test
    void loadsUnorderedAndTransitiveImports() throws IOException {
        FileDescriptorProto messages = exampleFile("example/v1/greet.proto").toBuilder()
                .addDependency("shared.proto")
                .build();
        FileDescriptorProto shared = FileDescriptorProto.newBuilder()
                .setName("shared.proto")
                .setSyntax("proto3")
                .build();
        SchemaRegistry registry = load(FileDescriptorSet.newBuilder()
                .addFile(exampleFile("example/v1/greeter.proto"))
                .addFile(messages)
                .addFile(shared));

        MethodDescriptor method = registry.resolveMethod("/example.v1.GreeterService/Greet");
        assertEquals("shared.proto", method.getInputType().getFile().getDependencies().get(0).getName());
    }

    @Test
    void reportsMissingDescriptorSet() {
        Path missing = temporaryDirectory.resolve("missing.binpb");
        IOException error = assertThrows(IOException.class, () -> SchemaRegistry.load(missing));
        assertTrue(error.getMessage().contains(missing.toString()));
        assertNotNull(error.getCause());
    }

    @Test
    void reportsMalformedDescriptorSet() throws IOException {
        Path malformed = temporaryDirectory.resolve("malformed.binpb");
        Files.write(malformed, new byte[] {10, 5, 1});

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SchemaRegistry.load(malformed));
        assertTrue(error.getMessage().contains("Invalid protobuf descriptor set"));
        assertTrue(error.getMessage().contains(malformed.toString()));
        assertNotNull(error.getCause());
    }

    @Test
    void rejectsEmptyDescriptorSet() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(FileDescriptorSet.newBuilder()));
        assertTrue(error.getMessage().contains("contains no files"));
    }

    @Test
    void reportsMissingImportAndImportingFile() throws IOException {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(FileDescriptorSet.newBuilder()
                        .addFile(exampleFile("example/v1/greeter.proto"))));

        assertTrue(error.getMessage().contains("Missing protobuf import 'example/v1/greet.proto'"));
        assertTrue(error.getMessage().contains("required by 'example/v1/greeter.proto'"));
    }

    @Test
    void rejectsDuplicateFiles() throws IOException {
        FileDescriptorSet.Builder descriptors = example().toBuilder();
        descriptors.addFile(descriptors.getFile(0));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load(descriptors));
        assertTrue(error.getMessage().contains("Duplicate protobuf file"));
    }

    @Test
    void rejectsUnnamedFiles() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(FileDescriptorSet.newBuilder().addFile(FileDescriptorProto.getDefaultInstance())));
        assertTrue(error.getMessage().contains("file without a name"));
    }

    @Test
    void rejectsImportCycles() {
        FileDescriptorSet.Builder descriptors = FileDescriptorSet.newBuilder()
                .addFile(FileDescriptorProto.newBuilder().setName("a.proto").addDependency("b.proto"))
                .addFile(FileDescriptorProto.newBuilder().setName("b.proto").addDependency("a.proto"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load(descriptors));
        assertTrue(error.getMessage().contains("Cyclic protobuf imports"));
        assertTrue(error.getMessage().contains("a.proto"));
        assertTrue(error.getMessage().contains("b.proto"));
    }

    @Test
    void reportsInvalidMessageReferences() throws IOException {
        FileDescriptorProto.Builder service = exampleFile("example/v1/greeter.proto").toBuilder();
        service.getServiceBuilder(0).getMethodBuilder(0).setInputType(".example.v1.DoesNotExist");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(FileDescriptorSet.newBuilder()
                        .addFile(exampleFile("example/v1/greet.proto"))
                        .addFile(service)));
        assertTrue(error.getMessage().contains("Invalid protobuf schema 'example/v1/greeter.proto'"));
        assertTrue(error.getMessage().contains("DoesNotExist"));
        assertNotNull(error.getCause());
    }

    @Test
    void rejectsAmbiguousServiceNames() throws IOException {
        FileDescriptorProto duplicate = exampleFile("example/v1/greeter.proto").toBuilder()
                .setName("duplicate.proto")
                .build();
        FileDescriptorSet.Builder descriptors = example().toBuilder().addFile(duplicate);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load(descriptors));
        assertTrue(error.getMessage().contains("Duplicate protobuf service 'example.v1.GreeterService'"));
    }

    @Test
    void reportsUnknownService() throws IOException {
        SchemaRegistry registry = SchemaRegistry.load(EXAMPLE);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.resolveMethod("/example.v1.UnknownService/Greet"));
        assertTrue(error.getMessage().contains("Unknown protobuf service 'example.v1.UnknownService'"));
    }

    @Test
    void handlesLongRpcPathsWithoutOverflowingTheStack() throws IOException {
        SchemaRegistry registry = SchemaRegistry.load(EXAMPLE);
        String path = "/" + "a.".repeat(4000) + "Service/Greet";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.resolveMethod(path));
        assertTrue(error.getMessage().startsWith("Unknown protobuf service"));
    }

    @Test
    void reportsUnknownMethod() throws IOException {
        SchemaRegistry registry = SchemaRegistry.load(EXAMPLE);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.resolveMethod("/example.v1.GreeterService/Unknown"));
        assertTrue(error.getMessage().contains("Unknown protobuf method 'Unknown'"));
        assertTrue(error.getMessage().contains("example.v1.GreeterService"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "/", "example.v1.GreeterService/Greet", "/example.v1.GreeterService/",
            "/example.v1.GreeterService/Greet/extra", "/example.v1.GreeterService/Greet?query=1"})
    void rejectsMalformedRpcPaths(String path) throws IOException {
        SchemaRegistry registry = SchemaRegistry.load(EXAMPLE);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.resolveMethod(path));
        assertTrue(error.getMessage().contains("expected /fully.qualified.Service/Method"));
    }

    private FileDescriptorSet example() throws IOException {
        return FileDescriptorSet.parseFrom(Files.readAllBytes(EXAMPLE));
    }

    private FileDescriptorProto exampleFile(String name) throws IOException {
        return example().getFileList().stream()
                .filter(file -> file.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private SchemaRegistry load(FileDescriptorSet.Builder descriptors) throws IOException {
        Path path = temporaryDirectory.resolve("schema.binpb");
        Files.write(path, descriptors.build().toByteArray());
        return SchemaRegistry.load(path);
    }
}
