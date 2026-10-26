# WireMock Connect RPC extension

A WireMock extension for unary Connect RPC. Load protobuf schemas, match JSON
or binary protobuf requests against JSON-authored expectations, and return
JSON-authored responses in either encoding, including standard Connect errors.
See [PLAN.md](PLAN.md) for the implementation milestones.

## Requirements

- JDK 17 or newer (Java compilation targets 17).
- Go 1.27 or newer for the example client and integration tests.
- Internet access on the first run to download Maven and dependencies.

The extension uses WireMock 3.13.2, Maven 3.9.16 via the Maven Wrapper, and
Connect-Go 1.21.1. It does not yet establish compatibility with other WireMock
versions.

Embedded and standalone loading were verified on JDK 25 targeting Java 17. Running
the test suite on JDK 17 remains to be verified.

## Build and verify

With [mise](https://mise.jdx.dev/) installed, run these commands from the
repository root to install the project's JDK and Go toolchain:

```sh
mise trust
mise install
mise exec -- ./mvnw verify
```

`mise.toml` selects Temurin JDK 17 and the Go toolchain. Mise sets `JAVA_HOME` and
`PATH` for the command, so no system-wide Java installation or shell activation
is needed. Maven is downloaded by the Maven Wrapper. Java includes the compiler
needed to build the project, as well as the runtime.

If your shell already activates mise, or you have the required tools installed,
you can run Maven directly instead.

From the repository root:

```sh
./mvnw verify
```

The baseline JUnit test starts WireMock on a random port, loads the mapping from
`examples/wiremock/mappings/greet.json`, and runs a compiled Go Connect client.
The test builds the client before starting the server; Go downloads its pinned
dependencies automatically on the first run.
The client sends `{"name":"Josh"}` to
`/example.v1.GreeterService/Greet` and checks that the response contains
`Hello, Josh!`. The test also verifies the request's method, path, Connect
protocol header, content type, and JSON body.

No extension is loaded for this test: unary Connect JSON uses normal HTTP
requests and responses. The schema is a small test fixture, not a production
API.

Additional integration tests register the matcher and transformer in a real WireMock server and
call it using both Go client encodings and Java HTTP requests. They cover
message contents, protobuf JSON conversions, mismatch diagnostics, and malformed
requests.

`verify` also builds the standalone extension JAR, downloads WireMock standalone
3.13.2 into `target/wiremock-standalone.jar`, and starts a separate Java process
with only those two JARs. A real Go client verifies JSON and protobuf success
and error responses through automatically discovered extensions.

On Windows, use `mvnw.cmd verify` in place of `./mvnw verify`.

## Try the extension in WireMock

After `./mvnw verify`, start standalone WireMock on port 8080 from the repository
root (with Java on your `PATH`, or prefix the command with `mise exec --`):

```sh
java -Dwiremock.connectrpc.descriptorSet=examples/descriptors/greeter.binpb \
  -cp 'target/wiremock-standalone.jar:target/wiremock-connectrpc-extension-0.1.0-SNAPSHOT-standalone.jar' \
  wiremock.Run --port 8080 --root-dir examples/wiremock-connect
```

On Windows, use `;` instead of `:` between the two JAR paths and adapt the
quoting and line continuations to your shell.

WireMock discovers `io.github.dotjoshrc.wiremock.connectrpc.ConnectRpcExtensionFactory`
through Java service-loader metadata. Setting `wiremock.connectrpc.descriptorSet`
enables the factory, which loads one schema snapshot shared by the matcher and
transformer. Relative descriptor paths resolve from the JVM's working directory.
A blank, unreadable, or invalid configured descriptor set fails startup with a
diagnostic. Omitting the property leaves automatic Connect registration disabled;
WireMock's extension scanning must remain enabled. No `--extensions` argument is
needed. Restart WireMock after changing the descriptor set.

For an embedded development launcher using the same schemas and mappings:

```sh
mise exec -- ./mvnw test-compile exec:java
```

Both commands load the mappings in `examples/wiremock-connect/mappings`.
In another terminal, from the repository root, try both encodings:

```sh
mise exec -- go -C examples/client run . http://localhost:8080 json
mise exec -- go -C examples/client run . http://localhost:8080 proto
```

Both calls print `Hello, Josh!` using the same JSON-authored response mapping.
To receive a typed Connect error in either encoding:

```sh
mise exec -- go -C examples/client run . -expect-error permission_denied http://localhost:8080 json Denied
mise exec -- go -C examples/client run . -expect-error permission_denied http://localhost:8080 proto Denied
```

Both calls print `permission_denied: Access denied`.

To see a mismatch, send a different name:

```sh
curl -X POST http://localhost:8080/example.v1.GreeterService/Greet \
  -H 'Content-Type: application/json' \
  -H 'Connect-Protocol-Version: 1' \
  --data '{"name":"Other"}'
```

WireMock returns 404 with the normalized expected and actual messages in its
mismatch report. Request history and diagnostic sub-events are available at
`http://localhost:8080/__admin/requests`. Stop the server with Ctrl-C and restart
it after editing the example mappings or schemas.

## Extension artifacts

`./mvnw package` produces two artifacts under `target/`:

- `wiremock-connectrpc-extension-0.1.0-SNAPSHOT.jar` is the normal library for
  embedded WireMock. Maven coordinates are
  `io.github.dotjoshrc:wiremock-connectrpc-extension:0.1.0-SNAPSHOT`; use
  `./mvnw install` to install it locally. Supply WireMock 3.13.2 as an application
  dependency and register instances as shown below.
- `wiremock-connectrpc-extension-0.1.0-SNAPSHOT-standalone.jar` bundles protobuf
  and its runtime dependencies for WireMock standalone 3.13.2. It uses WireMock's
  shaded Jackson classes and matching Google package relocations. WireMock itself
  is supplied separately. This artifact is intended for the standalone runtime.

The extension JAR has no executable `main` entrypoint: `wiremock.Run` belongs to
the WireMock standalone JAR. `ConnectRpcExample` is a development launcher under
the test sources. No artifacts have been published to Maven Central.

The supported protocol scope is unary Connect POST with JSON or binary protobuf,
exact message matching, and standard errors without error details. Streaming,
gRPC/gRPC-Web, unary GET, compression, partial matching, and schema hot reload
are unsupported. Browser CORS and WireMock versions other than 3.13.2 have not
been verified.

## Schema and example client

The example service lives in `examples/proto/example/v1/greeter.proto` and imports
its messages from `greet.proto`. Generated Go code and the compiled descriptor
set are checked in so running the tests does not require generation.
Schema regeneration requires Buf 1.75.0 on your `PATH`; it can be installed with
`go install github.com/bufbuild/buf/cmd/buf@v1.75.0` (the binary is placed in
`GOBIN`, or `$(go env GOPATH)/bin` by default). After changing the schema, run
these commands from the repository root:

```sh
buf format -w
buf lint
buf generate
buf build --as-file-descriptor-set --exclude-source-info -o examples/descriptors/greeter.binpb
./mvnw verify
```

To call an existing WireMock instance with the example mapping loaded:

```sh
cd examples/client
go run . http://localhost:8080
```

Generation uses version-pinned Go protobuf and Connect plugins defined in
`buf.gen.yaml`; no separate plugin installation is needed.

The optional second argument selects `json` (the default) or `proto`. The
original baseline mapping supports JSON; use the extension example above for both
encodings. The optional third argument supplies the request name (default `Josh`). Streaming and compression are outside the current scope.

The matcher test schema is separate from the Go example. To regenerate its
checked-in descriptor set, run:

```sh
cd src/test/proto
buf format -w
buf lint
buf build --as-file-descriptor-set --exclude-source-info -o ../resources/descriptors/matching.binpb
```

## Load protobuf schemas

`SchemaRegistry` accepts a binary `google.protobuf.FileDescriptorSet` containing
the service definitions and all their imports. The Buf command above includes
imports by default; when using `protoc`, pass `--include_imports` alongside
`--descriptor_set_out`.

```java
import io.github.dotjoshrc.wiremock.connectrpc.SchemaRegistry;
import java.nio.file.Path;

SchemaRegistry schemas = SchemaRegistry.load(Path.of("examples/descriptors/greeter.binpb"));
var method = schemas.resolveMethod("/example.v1.GreeterService/Greet");
var requestType = method.getInputType();   // example.v1.GreetRequest
var responseType = method.getOutputType(); // example.v1.GreetResponse
```

Loading builds an immutable snapshot: load once during configuration and reuse
it across requests. Descriptor files can appear in any order. All imports,
including any Google well-known types, must be present in the descriptor set.
No `.proto` compiler is needed at runtime, and changes on disk take effect only
when a new registry is loaded.

Unreadable files produce an `IOException` with the descriptor-set path. Invalid
descriptor data, missing imports, import cycles, duplicate files or services,
and invalid RPC paths produce an `IllegalArgumentException` describing the
problem. Unknown service and method errors identify the missing name.

Register both extensions in the WireMock configuration:

```java
.extensions(new ConnectRequestMatcher(schemas), new ConnectResponseTransformer(schemas))
```
The example launcher shows the full setup. These instances need no JVM property;
standalone loading uses the factory configuration described above.

## Configure request matching

Inside a WireMock mapping's `request`, use the named matcher:

```json
{
  "method": "POST",
  "urlPath": "/example.v1.GreeterService/Greet",
  "customMatcher": {
    "name": "connect-rpc",
    "parameters": {
      "jsonBody": { "name": "Josh" }
    }
  }
}
```

The request path selects the schema's method and its input type. `jsonBody` is
protobuf JSON, not a WireMock JSON pattern. It is the only matcher parameter.
Keep routing, header constraints, scenarios, and delays in ordinary WireMock
configuration. The matcher accepts unary POST bodies with `application/json`
or `application/proto`, including content-type parameters.

Matching is exact after parsing both messages and converting them to canonical
protobuf JSON:

- JSON field order and map key order do not matter; repeated field order does.
- Extra populated known fields cause a mismatch. Partial matching and JSON
  pattern placeholders are not supported.
- Implicit default values compare as unset. Explicit optional presence is
  preserved.
- Protobuf JSON field aliases, enum names/numbers, bytes, 64-bit integers, and
  well-known types use the protobuf library's conversion rules. `Any` types
  must be defined in the request's file or its imports.
- Unknown JSON fields are rejected. Unknown binary fields are ignored during
  JSON conversion, allowing clients to send newer protobuf fields.

Malformed requests, invalid expectations, unknown RPCs, unsupported content
types, streaming methods, and compressed requests do not match. Diagnostics
appear in WireMock's 404 report and request-journal sub-events. The matcher
itself does not turn these failures into Connect error responses.


## Configure responses

Inside a WireMock mapping's `response`, opt into the transformer:

```json
{
  "transformers": ["connect-rpc-response"],
  "transformerParameters": {
    "jsonBody": { "greeting": "Hello, Josh!" }
  }
}
```

The request path selects the RPC output descriptor. `jsonBody` uses protobuf
JSON conventions, including imported well-known types and `Any` types defined
in the output's file or its imports. Success returns HTTP 200 with
`application/json` or `application/proto`, matching the request's encoding.
`Accept` does not change that choice.

For errors, replace `jsonBody` with `error`:

```json
{
  "transformers": ["connect-rpc-response"],
  "transformerParameters": {
    "error": { "code": "permission_denied", "message": "Access denied" }
  }
}
```

Supply exactly one of `jsonBody` or `error`. Errors require a standard Connect
code and accept an optional string message. They always use `application/json`,
even for protobuf requests. Error details are not yet supported.

| Connect code | HTTP status |
| --- | --- |
| canceled | 499 |
| unknown, internal, data_loss | 500 |
| invalid_argument, failed_precondition, out_of_range | 400 |
| deadline_exceeded | 504 |
| not_found | 404 |
| already_exists, aborted | 409 |
| permission_denied | 403 |
| resource_exhausted | 429 |
| unimplemented | 501 |
| unavailable | 503 |
| unauthenticated | 401 |

The transformer sets status, body, and content framing headers; it preserves
custom headers and WireMock delays. Do not combine it with proxy or fault
responses. Invalid configuration produces a Connect `internal` error and an
error diagnostic in the request journal. Unsupported methods, content types,
compression, or streaming produce `unimplemented` if the request reaches the
transformer. Request matcher failures still produce WireMock's normal 404.
