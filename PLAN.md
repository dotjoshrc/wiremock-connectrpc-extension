# WireMock Connect RPC extension plan

## Goal

Support unary Connect RPC stubs configured with readable JSON, including when
the client sends binary protobuf. Plain Connect JSON requests already fit much
of WireMock's HTTP matching; the extension's main value is translating protobuf
requests and responses.

## Initial scope

- Unary Connect RPC POST requests using JSON or binary protobuf.
- Schema loading from compiled protobuf descriptor sets, including imports.
- Request matching against JSON using protobuf JSON conventions.
- Responses authored as JSON and encoded for the client's supported format.
- Standard Connect error responses.
- A runnable example and a distributable extension JAR.

Streaming, gRPC/gRPC-Web compatibility, unary GET, and compression are deferred
until a concrete client need justifies them. Browser use will require checking
CORS requirements.

## Milestones

### 1. Establish a working baseline

- [x] Choose supported Java and WireMock versions and a build tool.
- [x] Create the Java project and test setup.
- [x] Add a small example protobuf service and a real Connect client.
- [x] Run a unary JSON call against a normal WireMock stub.

**Done when:** the example client successfully calls WireMock using Connect
JSON, establishing which behavior WireMock already provides.

**Completed:** Maven 3.9.16 via the Maven Wrapper, Java 17 compilation target,
WireMock 3.13.2, and a Go 1.26+ client using Connect-Go 1.21.1. Run
`./mvnw verify` from the repository root. The integration test builds the Go
client, loads the example JSON mapping, asserts the client response, and verifies the HTTP
request. Protobuf format and lint checks pass. Validation used JDK 25; runtime
compatibility with JDK 17 has not yet been tested.

### 2. Load protobuf schemas

- [x] Load a compiled descriptor set containing the service and its imports.
- [x] Resolve RPC paths, such as `/example.v1.GreeterService/Greet`, to request
      and response message descriptors.
- [x] Report clear errors for missing schemas, unresolved imports, and unknown
      services or methods.

**Done when:** the extension resolves an example RPC to its message types and
reports useful failures for invalid configuration.

**Completed:** `SchemaRegistry.load(Path)` reads a binary descriptor set with all
imports included and builds an immutable snapshot, independent of file order.
`resolveMethod(String)` returns protobuf's method descriptor with its request
and response types. The example includes imported messages and a checked-in
descriptor set. Tests cover valid and transitive imports, unreadable or invalid
sets, missing imports, cycles, duplicate files and services, invalid message
references, malformed or very long paths, and unknown services or methods. All 22 tests,
including the original Connect client baseline, pass on JDK 25 targeting 17.

### 3. Match requests

- [x] Add a WireMock request matcher that decodes binary protobuf.
- [x] Compare the decoded message against configured JSON using protobuf JSON
      conventions; define exact versus partial matching semantics.
- [x] Provide useful mismatch output for diagnosing failed stub matches.
- [x] Handle malformed payloads without crashing the server.

**Done when:** stubs distinguish requests by message contents for both JSON and
protobuf calls.

**Completed:** Register `ConnectRequestMatcher` with a loaded schema registry
and use `customMatcher.name: connect-rpc` with a `jsonBody` expectation. Matching
is exact after canonical protobuf JSON conversion, preserving optional presence
and repeated ordering while normalizing defaults and protobuf JSON forms.
Unknown JSON fields are rejected; unknown binary fields are ignored. Failures
include decoded diagnostics in WireMock's mismatch reports and sub-events.
The real Go client passes for JSON and binary protobuf, and HTTP integration
tests cover conversions, mismatches, invalid configuration, malformed data,
unsupported encodings, and streaming rejection. All 53 tests pass on JDK 25
targeting 17. A runnable WireMock example is available via
`./mvnw test-compile exec:java`, with schema-aware response generation added in milestone 4.

### 4. Generate responses

- [x] Add a WireMock response transformer that converts configured JSON into
      the RPC's response message.
- [x] Return the appropriate encoding and HTTP headers for supported requests.
- [x] Support standard Connect error responses and their HTTP status mapping.
- [x] Report invalid response configuration clearly.

**Done when:** a real Connect client receives successful JSON and protobuf
responses, as well as expected RPC errors.

**Completed:** `ConnectResponseTransformer` converts JSON-authored output using
method descriptors and returns the request's JSON or protobuf encoding. All 16
Connect error codes use their standard HTTP status and JSON body. Invalid
configuration returns `internal` with a request-journal diagnostic. The example
uses shared success and error mappings for both codecs, verified by a real Go
Connect client. HTTP tests cover rich protobuf types, invalid configuration,
header replacement, custom headers/delays, and unsupported features. All 87
tests pass on JDK 25 targeting 17; Go vet and Buf format/lint also pass. Error
details, streaming, compression, and standalone packaging remain deferred.

### 5. Package and document

- [x] Produce an extension JAR with the dependencies needed for its supported
      WireMock runtime.
- [x] Document extension loading, schema configuration, and stub mappings.
- [x] Include runnable schemas, mappings, and client examples.
- [x] Document supported versions, protocol features, and known limitations.

**Done when:** someone can run the example by following the README.

**Completed:** The normal library JAR supports embedded WireMock, and a shaded
`standalone` classifier bundles protobuf dependencies with WireMock-compatible
package relocations. `ConnectRpcExtensionFactory` is discovered via Java service
loading when `wiremock.connectrpc.descriptorSet` is set; it loads one schema
snapshot shared by both extensions. Missing configuration leaves automatic
registration disabled, and invalid configured paths or schemas fail startup.
The README covers build artifacts, standalone startup, embedded registration,
schema configuration, mappings, and protocol limitations. `./mvnw verify` passes
91 tests plus a packaged integration test that starts WireMock standalone
3.13.2 with only the two JARs and checks success and errors through a real Go
client in both encodings. Validation used JDK 25 targeting Java 17; JDK 17
runtime verification remains outstanding. Artifacts have not been published.

## Minimal design

| Component | Responsibility |
| --- | --- |
| Schema registry | Load descriptors and resolve RPC methods to message types. |
| Request matcher | Decode requests and check message contents. |
| Response transformer | Encode configured responses and apply protocol headers. |
| Extension factory | Load configured schemas and register both extensions for service discovery. |

Keep routing, scenarios, delays, request history, and ordinary header matching
in WireMock. Reuse its existing extension interfaces and matching facilities
where practical.

## Validation

Use a real Connect client for end-to-end tests covering:

- Successful unary JSON and protobuf calls.
- Request-content mismatches and their diagnostics.
- Connect error responses.
- Malformed request payloads and invalid stub configuration.
- Protobuf JSON behavior for enums, bytes, 64-bit integers, and imported types.
- Missing schemas, unresolved imports, and unknown RPC methods.

Add focused tests for schema resolution and message conversion where those
failures would be difficult to diagnose through the end-to-end tests alone.

## Settled baseline decisions

- Java 17 compilation target, WireMock 3.13.2, and Maven 3.9.16.
- Go 1.26+ and Connect-Go 1.21.1 for integration tests and the example client.
- Generated Go checked in; Buf with pinned Go protobuf and Connect plugins used
  for regeneration.
- Unary JSON POST over HTTP/1.1, with `Connect-Protocol-Version: 1`.
- Schema loading uses a local binary descriptor-set path, requires all imports,
  and takes a snapshot for reuse across requests. A new load is needed to pick
  up schema changes.
- Register the matcher programmatically with the schema snapshot. Stub
  expectations use protobuf JSON in `customMatcher.parameters.jsonBody`, with
  exact matching of normalized known fields.
- Standalone loading uses a service-loaded factory enabled by the JVM property
  `wiremock.connectrpc.descriptorSet`. Relative paths resolve from the JVM's
  working directory; schema changes require restarting the server. The
  standalone artifact matches WireMock 3.13.2's dependency relocations.

## Decisions to settle for the extension

- Runtime verification on JDK 17 and any wider WireMock version support.
- Required Connect headers, content-type handling, and behavior for unsupported
  protocol features.

Verify extension APIs against official documentation before implementation.
The baseline client APIs and WireMock release were checked against official
source and Maven metadata; the end-to-end test verifies their interoperability.
WireMock extension loading documentation and official standalone build
configuration were checked during milestone 5; the packaged integration test
verifies the resulting runtime compatibility.

## First delivery target

One example unary RPC working end to end through WireMock, followed by the same
call using binary protobuf with a JSON-authored stub.
