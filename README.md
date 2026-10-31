# libinfinistream

Small Kotlin/JVM foundation for the Infini library family. No Android dependency.

Build: `gradle :stream-core:build` (JDK 17+, Gradle 9.7.1, Kotlin 2.2.20).
Coordinate: `io.github.gretchycat.libinfinistream:stream-core:0.1.0`.
Use a pinned source submodule/composite build; no Maven publication is assumed.

## Contracts

- `ByteSource` / `ByteSink`: sequential I/O; positive short reads are valid, -1 means EOF, zero is reserved for zero-length reads. Errors propagate. Close is idempotent on supplied adapters.
- `PositionalReadable` / `PositionalWritable`: optional 64-bit operations; shared sequential positions never move. `SourceCursor` supplies independent borrowed cursors. Legacy BlockDevice EOF=0 is translated at its adapter boundary.
- Optional resize/truncate/flush/append/atomic commit/sparse capabilities are separate interfaces. Metadata must only advertise operations actually supported by that handle. Capabilities do not imply a transaction or concurrency guarantee.
- `StreamMetadata` is an immutable snapshot by contract; supplied collections must not be mutated. Content type, domain, encoding/order, optional length, finite/live/unknown, ownership, concurrency, domain attributes, provenance, verification/warnings and revision are explicit. Specialized frame/sample types belong in domain libraries. Metadata discovery does not start processing.
- `SourceResource` supplies reopened handles with explicit ownership metadata. `SharedPositionalResource` owns one known-length positional handle and returns borrowed views with independent cursors; closing a view retains the owner, while closing the resource invalidates all views. It serializes provider reads, rejects premature EOF/zero progress, and preserves the original metadata snapshot. Input/output wrappers explicitly own or borrow delegates; copy/push borrow both endpoints and never close them. Cancellation is cooperative between I/O calls; transport timeouts remain the transport's responsibility.
- `ProcessorDescriptor` declares named input/output ports, types, capabilities, cardinality, options and pull/push modes. Sessions expose actual output handles/metadata and versioned metadata/progress/warning events. Inputs are borrowed, outputs session-owned. One-to-many ZIP and many-to-one mixer bindings are representable without coercing typed frames to bytes.
- `ProcessorRegistry` validates input bindings and actual output metadata; bounded pull planning uses caller-supplied negotiation, never opens processors, and rejects cycles. Port attributes constrain metadata such as sample rate, dimensions or text representation. `openPipeline` explicitly executes a single-resource pull route, closes intermediate sessions in reverse order and cleans up on failure while borrowing its input. A zero-step route returns that borrowed input. Automatically finding arbitrary multi-input graphs is intentionally not implemented: those require explicit bindings. Backend allowlists prohibit transformations. Lost random access must be omitted from negotiated outputs.
- `push` is optional, synchronous and bounded to one chunk: consumer return provides backpressure; consumers must not retain the mutable chunk. No platform scheduler is part of the contract.
- `ProbeReader` bounds total requested bytes, calls, elapsed time and nesting, including nonzero/footer offsets. Signatures produce candidates/evidence, not certainty; structural domain probes refine them. Headerless data stays unknown. `ReplayableSource` supplies a bounded replayable prefix to sequential readers.

## Validation and limits

Tests exercise independent cursors, EOF/short reads, cancellation, errors, ownership,
large sparse positional access, signature ambiguity, bounded probing, replay,
metadata negotiation/capability loss, cycles, cardinality and push backpressure.
This JVM artifact supports JVM hosts; native iOS bindings are not provided.
Original-code licensing is pending owner selection: see LICENSE.md.

Sequential JVM wrappers normalize capabilities/ownership from their demonstrated interfaces, so supplied hints cannot accidentally advertise positional writes or seeking. File factories advertise reopening; individual open file handles advertise their own read/positional capabilities. All consumer/source errors remain errors, including invalid EOF values.

`StreamMetadataCarrier` keeps provenance and warnings across the ByteSource-to-InputStream compatibility adapter. Wrapping that stream back into InputStreamSource inherits the snapshot while recalculating sequential capabilities and ownership. Metadata derivation reads one immutable snapshot per operation rather than combining fields from different updates.

The current standalone build has 26 passing cases. Eight shared-positional cases cover independent borrowed cursors, one-snapshot metadata, bounded EOF/short reads, unsupported construction, invalid results/errors, owner/view closure, interruption and serialized provider access.
