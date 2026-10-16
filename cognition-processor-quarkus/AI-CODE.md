# Coding Instructions

## Tech Stack

* **Java Version:** 21 (`maven.compiler.release` in `pom.xml`). CI uses Temurin 21.
* **Build Tool:** Maven via the wrapper (`./mvnw`), single module.
* **Key Frameworks:** Quarkus 3.37 (CDI/ArC, REST, Scheduler, SmallRye Config/Health/OpenAPI, Micrometer), Quarkus LangChain4j (OpenAI, Ollama, Gemini), gRPC + Protobuf.
* **Testing:** JUnit 5, Mockito, AssertJ. See [AI-TEST.md](AI-TEST.md).

## Project Architecture

Base package: `io.github.rigazilla.memory.cognition` under `src/main/java/`.

* **config:** `@ConfigMapping` interfaces (`MemoryServiceConfig`, `CognitionConfig`, `SalienceScorerConfig`).
* **grpc:** `GrpcChannelFactory`, the shared way to create channels to memory-service.
* **event:** gRPC admin event stream client, `DirtyWindow`/`DirtyWindowRegistry`, `SalienceScorer`, `CheckpointService`.
* **queue:** `ConversationJobQueue`, `JobQueueRegistry`, `JobProcessor`. Per-conversation job processing.
* **evidence:** `TranscriptLoader`, `EvidencePack`. Loads the conversation context that LLM steps read.
* **extraction / verification:** LLM-backed durable memory extraction and citation verification.
* **consolidation / contradiction:** duplicate detection, merging, and contradiction detection/resolution.
* **metadata / temporal / profile / justify:** metadata enrichment, temporal enrichment, profile context, and memory justification.
* **writer:** `MemoryWriter`, which writes derived memories back to memory-service with provenance.
* **enablement:** `ProcessEnablementService` and the policy types that decide whether a process is enabled.
* **process:** `CognitiveProcess`, `CognitiveProcessManager`, `ProcessManagementResource`. The managed process registry and management API.
* **resource:** LLM and API resource configuration, `LlmRetryHelper`, `CredentialResolver`.
* **token:** token usage tracking.
* **health:** `GrpcConnectionHealthCheck`.
* **model:** shared models such as `Provenance`.
* **`src/main/proto/memory`:** protobuf definitions for the memory-service gRPC API. Generated code is excluded from SpotBugs and JaCoCo.
* **`src/main/resources/prompts`, `salience`:** LLM prompts and salience keyword lists.

Project documentation:

* [AGENTS.md](AGENTS.md): project goals, implementation guidelines, security rules, progress tracking.
* [README.md](README.md): setup and running.
* `DONE/`: design descriptions of completed features. `TODO/`: AI-friendly task documents.

## Common Build Commands

Run from `cognition-processor-quarkus/`.

* **Dev mode:** `./mvnw quarkus:dev`
* **Build (skip tests):** `./mvnw -B clean package -DskipTests`
* **Run all tests:** `./mvnw -B test`
* **Run a single test class:** `./mvnw -B test -Dtest=SomeTest`
* **Run a single test method:** `./mvnw -B test -Dtest=SomeTest#testMethod`
* **Coverage report:** `./mvnw -B test jacoco:report`, then open `target/site/jacoco/index.html`
* **Style and static analysis:** `./mvnw -B checkstyle:check spotbugs:check`
* **Full verification:** `./mvnw -B verify`

## Concurrency and Distributed-Processing Checklist

Before writing or modifying code in the event, queue, process, or writer packages, you MUST evaluate the following. Not every change needs action on every point, but the check itself is mandatory.

### Event-driven and asynchronous processing
Cognition must never block the agent's critical path. Events are handled asynchronously.
- **Is the work idempotent?** Events can be replayed after a checkpoint reset or restart. Re-processing the same window must not create duplicate memories.
- **Is per-conversation ordering preserved?** Only one job per conversation may run at a time (`ConversationJobQueue`).
- **Does a failure stay contained?** A failing job, LLM call, or stub call is logged and must not stop the event stream or the worker.
- **Is the checkpoint advanced only after the work it covers?**

### Shared mutable state
- Registries (`DirtyWindowRegistry`, `JobQueueRegistry`) are accessed from the event stream, scheduler and worker threads. Use concurrent collections and atomic operations. Do not do check-then-act on shared state.
- Prefer immutable records for data passed between threads and pipeline stages.
- Do not capture non-thread-safe objects (mutable lists, builders, stubs shared without care) in lambdas that run on other threads.

### Multiple workers
- The runtime has a worker ID (`cognition.worker.id`) and runtime ID. Several runtimes can run side by side against one memory-service, so writes must be safe when two workers see the same event.
- Use namespaces, deterministic keys, and provenance to make derived memories attributable and replaceable.

### LLM calls
- Route LLM calls through the retry helper (`LlmRetryHelper`) and the configured retry/backfill settings.
- Treat model output as untrusted. Validate and parse it defensively, and handle empty or malformed responses.
- Never hardcode prompts that include example data that can leak into outputs. Keep prompts in `src/main/resources/prompts`.
- Record token usage through the `token` package.

### Governance
- Derived memories MUST preserve provenance, scope and access control from the source entries.
- Never write derived data outside the namespace and ownership of the source conversation.

### Test coverage
- **Pure logic** must have plain JUnit tests.
- **CDI services** must have `@QuarkusTest` tests with mocked gRPC stubs and mocked LLM services.
- **Async and concurrent behaviour** must be tested for ordering, failure containment and replay.
- **LLM response handling** must be tested with valid, empty and malformed input.

See [AI-TEST.md](AI-TEST.md) for the detailed rules.

## Configuration

* Use **`@ConfigMapping`** for all new configuration. **Never** add new `@ConfigProperty` fields.
* Extend `MemoryServiceConfig` or `CognitionConfig`, or add a new interface for a genuinely distinct domain. Put it under `io.github.rigazilla.memory.cognition.config`.
* Defaults belong in `@WithDefault` on the interface method, not in `@ConfigProperty(defaultValue = ...)`.
* Every new property must also get a value in `src/test/resources/application.properties` and be documented in `env.example` or the README when it needs an environment variable.
* Never read, print or commit `.env`. Use `env.example` for variable names.

## Adding a Managed Cognitive Process

- It must be discoverable so it appears in the process registry and management API (`CognitiveProcessManager`).
- It must provide a stable registration-time state: `ENABLED` or `DISABLED`.
- It may expose `details` for live operational data. Compute runtime-dependent values at inspection time.
- It does not need runtime `details` when there is no meaningful live data.
- Add tests next to the existing `*ProcessTest` classes.

## Development Standards

* **Style:** Functional patterns where they help. Use records for DTOs and immutable data carriers.
* **Checkstyle** (`checkstyle.xml`): max line length 120, no unused imports, no star imports in main code, braces on all control structures, consistent `}` placement, trailing newline.
* **SpotBugs** (`spotbugs-exclude.xml`): CDI/Quarkus false positives are excluded. Resolve every other finding. Do not add exclusions to hide real problems.
* **Javadocs:** Required for public API classes and methods (such as the management REST resource and public contracts like `CognitiveProcess`). Optional for internal code but encouraged where it helps. Unnecessary for test classes and methods.
* **Logging:** Use structured, level-appropriate logging. Never log secrets, API keys, tokens, or full conversation content at INFO or above.
* **Errors:** Return generic messages from REST endpoints. Log details server-side only.
* **Dependencies:** Use the latest stable versions. Quarkus and LangChain4j versions come from their BOMs; do not pin transitive versions without a reason.
* **Testing:** Follow [AI-TEST.md](AI-TEST.md). Target about 80% line coverage as a guideline, not a hard target.
* **Implementation guidelines:** Follow the [Quarkus cognition processor enhancement](https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md). Tell the user if you plan to deviate.

## Before Submitting

1. Run `./mvnw -B test`.
2. Run `./mvnw -B checkstyle:check spotbugs:check`.
3. When a feature is complete, add a design description to `DONE/` with the next `NNN-` prefix. Track future work in `TODO/`.
4. Update `README.md` or `AGENTS.md` if setup, configuration or behaviour changed.

## Development Platform

* **CI:** `.github/workflows/` at the repository root. Pull requests run build, tests, coverage, Checkstyle and SpotBugs.
* **Branches and commits:** follow the conventions of the repository. Do not commit secrets or `.env`.

## Related Projects

* **memory-service:** the substrate this project builds on (https://github.com/chirino/memory-service). Local setup configuration is in `../memory-service/`.
