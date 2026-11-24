# AGENTS.md - AI Assistant Guide

## What is this project

This project implements the **cognition layer** of a two-layer memory architecture. The substrate layer (memory-service) handles durable storage, access control, and governance of raw conversation entries. This cognition layer interprets and organizes those entries into useful memory products like topics, summaries, and extracted facts. It runs as an event-driven system that subscribes to memory-service events, processes conversations asynchronously, and creates derived memories while preserving provenance and access control from the source data.

## Relation with memory-service

**Memory-service** is the substrate - the source of truth for raw conversation data, entries, and access control. This project is a **single Quarkus application** (base package `io.github.rigazilla.memory.cognition`) that talks to memory-service **only over gRPC** (default `localhost:8082`; see `MemoryServiceConfig` and `GrpcChannelFactory`):

- **Inbound**: `GrpcAdminEventClient` subscribes to the memory-service admin event stream. A checkpoint (`CheckpointService`) is stored in memory-service so processing can resume after restart.
- **Processing**: events are salience-filtered, grouped into debounce windows (`DirtyWindowRegistry`, `DebounceScheduler`), and turned into per-conversation jobs (`queue/`). `TranscriptLoader` reads the conversation context back from memory-service.
- **Outbound**: derived memories are written back through `MemoryWriter` with provenance and citations.
- **REST (port 8090)**: management and inspection only (`/api/processes`, `/api/events/status`, `/api/consolidate/{userId}`, `/api/memories/{id}/justify`, `/q/health`, `/q/metrics`). It is **not** a data-ingestion API; all conversation data enters through memory-service.

> Older revisions of this file described a separate `listener` package (SSE → REST) and a `cognitive` package. Neither exists; do not look for them.

### Package map

| Package | Responsibility |
|---|---|
| `event`, `queue`, `evidence` | Event stream, salience, debounce, checkpoint, job queue, transcript loading |
| `extraction`, `verification` | LLM-backed durable memory extraction and citation verification |
| `consolidation`, `contradiction` | Duplicate detection/merging; contradiction detection and resolution (batch and on-insert) |
| `profile`, `metadata`, `temporal`, `justify` | Profile snapshots, metadata/temporal enrichment, memory justification |
| `storeall` | Verbatim "store everything" baseline process (benchmark use) |
| `process`, `enablement` | Managed process registry, management API, enable/disable policy |
| `resource`, `config`, `token` | LLM/API resource config, retry helper, typed config, token tracking |

### Managed processes (registered in `CognitiveProcessManager`)

| ID | Trigger | `/start` | `/enable`, `/disable` | Default |
|---|---|---|---|---|
| `durable-memory-extraction` | Event-driven (debounced) | yes | yes | Enabled |
| `contradiction-on-insert` | On every memory write | no | yes | Enabled |
| `contradiction-resolution` | Manual batch backfill | yes | yes | Enabled |
| `profile-context-consolidation` | Manual only, via `POST /api/consolidate/{userId}` | no | no | Always on |
| `metadata-enrichment` | Manual backfill | yes | no | Always on |
| `temporal-metadata-enrichment` | Manual backfill | yes | no | Always on |
| `store-all` | Event-driven, no LLM | no | yes | **Disabled** |

Use `GET /api/processes` for the authoritative live list; this table can drift. Support flags come from `supportsStart/Enable/Disable()` on each `CognitiveProcess`.

## Goals

From the [Memory Cognition Architecture](https://github.com/chirino/memory-service/blob/main/docs/memory-cognition.md#goals):

1. **Preserve the substrate** - Keep current memory-service as the durable source of truth for raw entries, memories, access control, and lifecycle management

2. **Enable pluggability** - Allow multiple cognition implementations to run side-by-side for benchmarking and comparison

3. **Maintain asynchronous processing** - Keep cognition event-driven so extraction and consolidation don't block the agent's critical path

4. **Enforce governance** - Preserve provenance, scope, and access control for all derived memories

5. **Support flexible deployment** - Allow both external-process cognition runtimes (default) and optional embedded runtimes for low-latency scenarios

6. **Enable reproducibility** - Make cognition outputs replayable and rebuildable from substrate data and event history

## Non-Goals

From the [Memory Cognition Architecture](https://github.com/chirino/memory-service/blob/main/docs/memory-cognition.md#non-goals):

1. **Replacing substrate APIs** - Not replacing conversation, `context`, or `/v1/memories` APIs

2. **Hard-coding strategies** - Not locking into one cognition strategy, LLM provider, or prompt format

3. **Centralizing reasoning** - Not moving all reasoning into the memory-service process

4. **Bypassing governance** - Not treating cognition outputs as ungoverned side data that bypasses access control

## Whole picture

For a general description of the whole project see https://github.com/chirino/memory-service/blob/main/docs/memory-cognition.md. It's a very high level doc.

## Implementation guidelines

There are guidelines for the implementation at https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md. It's strongly reccomended to follow them when writing new code, but it's not mandatory. You must inform the user if you plan to not follow the guidelines.

## Implementing new cognitive processes

When adding a new managed cognitive process:

- it must be discoverable/registered so it appears in the process registry and management API
- it must provide a stable registration-time state using `ENABLED` or `DISABLED`
- it may expose `details` for live operational data
- if `details` contains runtime-dependent values, those values should be computed at inspection time

Do not require runtime `details` for every process. If a process has no meaningful live operational data yet, it can expose empty or minimal inspection details.

## Testing

For full testing guidelines see [AI-TEST.md](AI-TEST.md). For coding guidelines see [AI-CODE.md](AI-CODE.md).

Use **`@QuarkusTest`** for all tests involving CDI beans (`@ApplicationScoped`, `@RequestScoped`, etc.). This starts a real Quarkus container that resolves `@ConfigMapping` interfaces from `src/test/resources/application.properties` — no manual config wiring needed.

### Injection pattern

```java
@QuarkusTest
class MyServiceTest {

    @Inject
    MyService service;                 // real CDI bean — config wired automatically

    @InjectMock
    SomeCdiCollaborator collaborator;  // CDI bean replaced by a Mockito mock

    @BeforeEach
    void setUp() {
        // Replace only the gRPC stub/channel (infrastructure, not a CDI bean)
        service.memoriesStub = mock(AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub.class);
        service.channel       = mock(ManagedChannel.class);
    }
}
```

### Rules

| Scenario | Pattern |
|---|---|
| Service under test (CDI bean) | `@Inject` |
| CDI collaborator to control (e.g. `ConsolidationService`, `ProfileConsolidationStrategy`) | `@InjectMock` (Quarkus replaces the bean with a Mockito mock) |
| gRPC stub / `ManagedChannel` (not a CDI bean) | `mock()` in `@BeforeEach`, assigned to the package-private field |
| Config values (`MemoryServiceConfig`, `CognitionConfig`) | **Do not mock** — values come from `src/test/resources/application.properties` via CDI |
| Pure-logic classes with no CDI (`DirtyWindow`, `EvidencePack`, records) | Plain JUnit 5, no `@QuarkusTest` needed |

### `src/test/resources/application.properties`

All config properties required by `@ConfigMapping` interfaces must be present. When adding a new property to `MemoryServiceConfig` or `CognitionConfig`, add a test value here so `@QuarkusTest` container starts cleanly.

**Never** mock `MemoryServiceConfig` or `CognitionConfig` in a `@QuarkusTest` class — CDI already injects the real implementations populated from `application.properties`.

## Configuration

Use **`@ConfigMapping`** (SmallRye / Quarkus typed config) instead of `@ConfigProperty` for all new configuration in this project.

Two shared config mapping interfaces centralise the project's configuration:

- **[`MemoryServiceConfig`](src/main/java/io/github/rigazilla/memory/cognition/config/MemoryServiceConfig.java)** — prefix `memory-service` — gRPC host/port, API key, client ID.
- **[`CognitionConfig`](src/main/java/io/github/rigazilla/memory/cognition/config/CognitionConfig.java)** — prefix `cognition` — runtime identity, worker ID, checkpoint, scheduler, LLM retry/backfill, secrets provider.

**Usage pattern:**

```java
@Inject
MemoryServiceConfig memoryService;

channel = GrpcChannelFactory.create(
    memoryService.grpc().host(), memoryService.grpc().port(),
    memoryService.apiKey(), memoryService.clientId());
```

**Rules:**
- **Never** add new `@ConfigProperty` fields. Extend the existing `@ConfigMapping` interfaces or add a new one for a genuinely distinct configuration domain.
- Default values belong in the `@WithDefault` annotation on the interface method, **not** as `@ConfigProperty(defaultValue = ...)` on a field.
- Package new config mapping interfaces under `io.github.rigazilla.memory.cognition.config`.

## Code Style

Java code must pass **Checkstyle** and **SpotBugs** checks configured in the project root:

- **`checkstyle.xml`** — enforces: max line length 120 chars, no unused imports, no star imports, braces on all control structures, consistent `}` placement, trailing newline
- **`spotbugs-exclude.xml`** — excludes CDI/Quarkus false positives (EI_EXPOSE_REP, serialization noise, broad catch patterns); all other SpotBugs findings must be resolved

Run `./mvnw -B checkstyle:check spotbugs:check` before submitting changes. Both checks run automatically in CI on pull requests.

**Code Coverage** is collected via **JaCoCo**. `pom.xml` defines a minimum of 15% line / 10% branch coverage at the `verify` phase (`check-coverage` execution), and CI's PR coverage comment uses minimums of 35% overall / 50% on changed files (see [`.github/SETUP.md`](../.github/SETUP.md)); the long-term targets are 80% line / 70% branch. Run `./mvnw -B test jacoco:report` to generate a coverage report, then open `target/site/jacoco/index.html` to view it.

## Documentation

- **Core concepts**: https://chirino.github.io/memory-service/docs/concepts/ - Essential memory-service concepts (conversations, entries, memories, access control) that the cognition layer builds upon
- **Quarkus implementation**: https://chirino.github.io/memory-service/docs/quarkus/ - Quarkus-specific information and patterns for this implementation

## Security

Never read, print, summarize, parse, or transmit the `.env` file. It may contain secrets, API keys, tokens, or other credentials that must not be sent over the network by an AI assistant. If environment variable names are needed, inspect checked-in examples, documentation, application configuration, or ask the user to provide non-secret placeholders instead.

## Progress tracking

When a new feature is implemented and complete a design description document must be created in the DONE folder. Files there must be prefixed with "NNN-" where NNN is an increasing number.

Future work items, research tasks, and ideas are tracked in the TODO folder as individual markdown files. TODO documents should be written to be AI assistant friendly. When a TODO item is completed, move or replace it with a DONE document and remove the TODO file so [TODO/README.md](TODO/README.md) stays accurate.

User-facing behaviour changes (new process, config key, REST endpoint, default value) must also update the user docs in the same change: the [processor README](README.md), the process table above, and the site docs under `../site/src/pages/docs/`. REST changes regenerate `site/public/openapi.*` (see [site/README.md](../site/README.md)).
