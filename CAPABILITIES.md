# Runtime discovery and processing contracts

Updated 2026-11-04. All contracts live in `stream-core`, with no Android or
InfiniFolder dependency. Keep the existing Stream I/O and processor APIs: this
registry selects implementations, not a second decoder or pipeline engine.

## Registration

Implement `CapabilityHandler`, preferably a domain interface such as
`ContainerCapabilityHandler`, `ByteImageCapabilityHandler`, `ReadingCapabilityHandler`,
`EncodedImageExtractor` or `VideoCapabilityHandler`. A PCM decoder uses the
`PcmCapabilityHandler` adapter; an existing stream processor with a negotiator uses
`ProcessorCapabilityHandler`. These adapters invoke the real library implementation.

A cheap, public, zero-argument `CapabilityProvider` returns the module's handlers.
Put its qualified class name, one per line, in:

```
src/main/resources/META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider
```

Declare the actual library dependencies in Gradle (`api` for types exposed through
the public API, otherwise `implementation`). Link source repositories with
`includeBuild`, or use published artifacts when available. ServiceLoader does not
install or guess missing libraries. Android applications must merge this service
resource from their dependencies. Shrinker consumer rules preserve provider and
service contract names. A new viewer module also supplies its normal manifest.

`ViewerComponent` also lives in Stream and exposes the installed presentation class
name without depending on Android framework types or InfiniFolder's shared UI.
A component may use the optional LibraryViewerComponent adapter from viewer-common,
or implement this foundation interface with its own Activity and linked libraries.

For an already loaded component, `registry.register(handler)` returns a closeable
registration; closing it withdraws that handler. Duplicate IDs are rejected;
overlapping MIME/extension/signature claims remain separate candidates. Aggregation
never needs another format switch. This is classpath discovery, not downloaded code
loading or arbitrary DEX installation.

## Handler schema

`HandlerDescriptor` contains a stable namespaced `id`, `library`, `title`, one or
more `FormatSupport` profiles, open-string `operations`, output `metadata` keys,
`limitations`, integer `priority` and extensible string `attributes`. A profile
contains MIME types, extensions (without dots, including multipart suffixes), URI
schemes and `MagicSignature(hex, offset, description)` values. Use normalized lower
case selectors. Do not put credentials, source tokens or user preferences here.

`describe()` supports dynamic composites such as a viewer delegating to installed
decoders. Domain interfaces add their own presentation/container/extractor markers.
Metadata/signatures are evidence, not proof that a damaged input will parse.

`availability()` reports AVAILABLE, CONDITIONAL or UNAVAILABLE with a reason and
optional dependency booleans. AVAILABLE means the implementation is present, not
that arbitrary input is valid. CONDITIONAL identifies source/platform/native limits;
UNAVAILABLE entries are omitted by default. Exceptions and missing linkage during
availability evaluation become UNAVAILABLE. Reports are cached per revision;
call `refresh()` after installing/changing optional dependencies or configuration.

`probe(context)` returns NONE, CANDIDATE, SIGNATURE or VERIFIED, the actual usable
operation set, optional reason and discovered metadata. Only an actual backend
validation justifies VERIFIED. Generic probing removes WRITE when the supplied
resource metadata does not advertise WRITE; EDIT/ENCODE can instead produce a
separate output. Mutation still checks the opened provider/handle, revision and
publication policy. Protocol recognition is not a server connection or write grant.

## Queries, ownership and limits

- `RuntimeCapabilities.installed()` discovers installed providers once.
- `RuntimeCapabilities.library("scribe")` (or another library ID) is a filtered,
  live view of the same registrations. `discover(classLoader)` creates an isolated
  registry. Explicit `CapabilityRegistry()` instances also work without a host.
- `descriptors()` reads identity facts without initializing optional native codecs.
- `supportedFormats(operation)` exposes each format profile with its handler ID,
  library, operations, attributes and profile index. `supportedMimeTypes`,
  `supportedExtensions` and `supportedUriSchemes` provide flattened selectors.
  Calling these on `RuntimeCapabilities.library("vault")` returns only Vault's
  registrations; calling them on `installed()` retains overlapping providers.
- `query(operation, includeUnavailable)` evaluates only relevant handlers.
- `probe(CapabilityResource(...), operation, budget, cancellation)` preserves all
  matching candidates, ordered by evidence, selector specificity, priority and ID.
  MIME exact matches precede MIME wildcards, suffixes and scheme hints.
- `subscribe` reports revision changes; close the subscription. Callbacks run
  outside locks. `revision` invalidates process-local caches; `fingerprint()` hashes
  descriptive content for caches that survive restart. Registry revisions do not
  replace source mtime/revision/length or user/theme cache keys.
- A resource carries immutable `StreamMetadata`, URI, optional `SourceResource` and
  container depth. Metadata-only queries do no source I/O. Explicit probes share
  one prefix, default 64 KiB/100 ms, and cache failed reads as well as successful
  ones. Inputs are opened/closed by the probe; no hidden staging or target traversal.
  Cancellation is cooperative; transport-level open/read timeouts remain necessary.
  Default nesting limit is 8. A sequential, non-reopenable input needs a caller-owned
  replay resource; do not probe away its bytes and then reuse an exhausted handle.
- Bad input/decoder failure falls through; interruption propagates. Provider loading
  failures are observable via `discoveryFailures`; build catalog generation rejects
  them. A successful registry lookup never substitutes for executing the real API.

## Pipes, streams and typed processing

A handler's `service(SomeLibraryApi::class.java)` supplies a real, typed library API.
There is no application singleton or string-based invocation bridge.

`ProcessorCapabilityHandler` exposes the same `StreamProcessor` and authoritative
`ProcessorRegistration` used by its library facade. `RuntimeProcessors.registry()`
collects these registrations into the existing `ProcessorRegistry`. Its bounded
planner negotiates ports/metadata and `openPipeline` executes a series of pull
stages. New processor handlers become available without editing a host list.
Processor descriptors supply input/output kinds, formats, capabilities, attributes,
flow modes and options; actual session outputs carry their negotiated metadata.
Multi-input/output ambiguity needs explicit bindings. Sources are borrowed;
sessions own outputs and close stages in reverse order. No eager copying just to
cross a library boundary. Push remains opt-in through existing contracts.

Some backends still require positional sources, complete documents or local files.
The caller explicitly supplies the required access/staging budget; discovery does
not silently download or materialize. Typed text/table/image/frame outputs do not
need serialization back to bytes between stages. Not every legacy backend has a
negotiating `StreamProcessor` adapter yet; its typed domain service remains usable.

`EncodedImageExtractor` borrows a ByteSource and returns owned `ExtractedImage`
encoded content, or null if no artwork exists. Consumers can pipe that resource
directly into Canvas. Close all result readers before the owned result. Extraction
metadata preserves source provenance while describing the produced image resource.

## Export and standalone validation

`CapabilityJson.snapshot(registry)` exports schemaVersion 1 JSON, descriptors,
the flattened `supportedFormats` profiles, availability/dependencies and provider
failures. `evaluateAvailability=false`
exports identity with availability NOT_EVALUATED, suitable for build-time Android
intent filters. It is not a device acceptance report. `CapabilityCatalogMain`
exports this identity catalog from a dependency classpath without starting an app.

Run `gradle :stream-core:test :stream-core:assemble --offline` (JDK 17+, dependencies
cached). Tests execute real transformations, ServiceLoader registration, overlaps,
removal/callbacks, unavailable dependencies, prefix bounds/failure caching,
read-only filtering, nesting/cancellation and stable cache fingerprints. No Android
runtime or InfiniFolder initialization is needed.

## Host audit record

InfiniFolder's 2026-11-04 runtime-discovery audit exercises this repository through
its standalone Gradle test task, and also builds the main and combined standalone
viewer APKs. The host `docs/runtime-capabilities.md` records exact commands, fresh
counts, failures repaired during the gate and unverified device paths. This is
separate from the historical extraction evidence in SOURCE_PROVENANCE.md.
