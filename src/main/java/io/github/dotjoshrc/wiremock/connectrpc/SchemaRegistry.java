package io.github.dotjoshrc.wiremock.connectrpc;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TypeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An immutable snapshot of the services in a protobuf descriptor set. */
public final class SchemaRegistry {
    private static final Pattern RPC_PATH = Pattern.compile(
            "/((?:[A-Za-z_][A-Za-z0-9_]*\\.)*+[A-Za-z_][A-Za-z0-9_]*)/([A-Za-z_][A-Za-z0-9_]*)");

    private final Map<String, ServiceDescriptor> services;
    private final TypeRegistry types;

    private SchemaRegistry(Map<String, ServiceDescriptor> services, TypeRegistry types) {
        this.services = Map.copyOf(services);
        this.types = types;
    }

    /** Loads a descriptor set with all imports included, without compiling any .proto files. */
    public static SchemaRegistry load(Path path) throws IOException {
        FileDescriptorSet descriptorSet;
        try (InputStream input = Files.newInputStream(path)) {
            descriptorSet = FileDescriptorSet.parseFrom(input);
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid protobuf descriptor set '" + path + "'", exception);
        } catch (IOException exception) {
            throw new IOException("Cannot read protobuf descriptor set '" + path + "': "
                    + exception.getMessage(), exception);
        }
        if (descriptorSet.getFileCount() == 0) {
            throw new IllegalArgumentException("Protobuf descriptor set '" + path + "' contains no files");
        }

        Map<String, ServiceDescriptor> services = new LinkedHashMap<>();
        TypeRegistry.Builder types = TypeRegistry.newBuilder();
        for (FileDescriptor file : buildFiles(descriptorSet.getFileList()).values()) {
            types.add(file.getMessageTypes());
            for (ServiceDescriptor service : file.getServices()) {
                if (services.putIfAbsent(service.getFullName(), service) != null) {
                    throw new IllegalArgumentException("Duplicate protobuf service '" + service.getFullName() + "'");
                }
            }
        }
        return new SchemaRegistry(services, types.build());
    }

    /** Resolves an error-detail message, including nested types and files without services. */
    public Descriptor resolveMessage(String fullName) {
        Descriptor descriptor = types.find(fullName);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown protobuf message '" + fullName + "'");
        }
        return descriptor;
    }

    /** All message types loaded from the descriptor set, for protobuf JSON Any resolution. */
    public TypeRegistry typeRegistry() {
        return types;
    }

    /** Resolves a Connect path to its method, including input and output message descriptors. */
    public MethodDescriptor resolveMethod(String rpcPath) {
        Matcher matcher = RPC_PATH.matcher(rpcPath == null ? "" : rpcPath);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid RPC path '" + rpcPath
                    + "'; expected /fully.qualified.Service/Method");
        }
        String serviceName = matcher.group(1);
        ServiceDescriptor service = services.get(serviceName);
        if (service == null) {
            throw new IllegalArgumentException("Unknown protobuf service '" + serviceName + "'");
        }
        MethodDescriptor method = service.findMethodByName(matcher.group(2));
        if (method == null) {
            throw new IllegalArgumentException("Unknown protobuf method '" + matcher.group(2)
                    + "' in service '" + serviceName + "'");
        }
        return method;
    }

    private static Map<String, FileDescriptor> buildFiles(List<FileDescriptorProto> files) {
        Map<String, FileDescriptorProto> pending = new LinkedHashMap<>();
        for (FileDescriptorProto file : files) {
            if (file.getName().isBlank()) {
                throw new IllegalArgumentException("Protobuf descriptor contains a file without a name");
            }
            if (pending.putIfAbsent(file.getName(), file) != null) {
                throw new IllegalArgumentException("Duplicate protobuf file '" + file.getName() + "'");
            }
        }
        for (FileDescriptorProto file : files) {
            for (String dependency : file.getDependencyList()) {
                if (!pending.containsKey(dependency)) {
                    throw new IllegalArgumentException("Missing protobuf import '" + dependency
                            + "' required by '" + file.getName() + "'; include all imports in the descriptor set");
                }
            }
        }

        Map<String, FileDescriptor> built = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            boolean progressed = false;
            Iterator<FileDescriptorProto> iterator = pending.values().iterator();
            while (iterator.hasNext()) {
                FileDescriptorProto file = iterator.next();
                if (!built.keySet().containsAll(file.getDependencyList())) {
                    continue;
                }
                FileDescriptor[] dependencies = file.getDependencyList().stream()
                        .map(built::get)
                        .toArray(FileDescriptor[]::new);
                try {
                    built.put(file.getName(), FileDescriptor.buildFrom(file, dependencies));
                } catch (DescriptorValidationException exception) {
                    throw new IllegalArgumentException("Invalid protobuf schema '" + file.getName()
                            + "': " + exception.getMessage(), exception);
                }
                iterator.remove();
                progressed = true;
            }
            if (!progressed) {
                throw new IllegalArgumentException("Cyclic protobuf imports among files: " + pending.keySet());
            }
        }
        return built;
    }
}
