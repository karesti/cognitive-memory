# Cognition Processor - Quarkus Implementation

A Quarkus-based implementation of the Memory Service cognition layer that processes conversation events to extract and organize memories.

## What is this?

This project implements the **cognition layer** of a two-layer memory architecture:
- **Substrate layer** ([memory-service](https://github.com/chirino/memory-service)) - stores raw conversation data and manages access control
- **Cognition layer** (this project) - processes events to extract topics, facts, preferences, and other derived memories

Think of it as the "intelligence" that turns raw conversation transcripts into structured, searchable memories.

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.9+ (or use the bundled `./mvnw`)
- Memory Service running - see [../memory-service](../memory-service) for local setup instructions
- An LLM endpoint: OpenAI-compatible API key (default) or a local Ollama

### Run It

1. **Create your environment file** (Quarkus reads `.env` from the working directory; it is git-ignored):
   ```bash
   cp env.example .env
   ```
   Then edit `.env`. At minimum:
   - `MEMORY_SERVICE_API_KEY` - API key the processor uses to talk to memory-service (no default; startup fails without it). The value in `env.example` is the quickstart key; it must match a key configured in your memory-service instance.
   - `COGNITION_OPENAI_API_KEY` - needed with the default `openai` provider.
   - To use Ollama instead, set `COGNITION_MEMORY_MODEL_PROVIDER=ollama`.

2. **Start the cognition processor**:
   ```bash
   ./mvnw quarkus:dev
   ```

3. **Verify it's running**:
   - Check logs for "Successfully connected to gRPC event stream" and "Subscribed to admin event stream"
   - Health checks:
     - Liveness: http://localhost:8090/q/health/live
     - Readiness: http://localhost:8090/q/health/ready (includes gRPC connection status)
     - Full health: http://localhost:8090/q/health

The processor will automatically:
- Subscribe to memory-service events
- Process new conversations
- Extract and verify memories
- Write them back to memory-service

## How It Works

```
Memory Service Events → Debounce → Evidence Packs → 
Extract (LLM) → Verify → Write Memories
```

See [AGENTS.md](./AGENTS.md) for architecture details and [Enhancement 099](https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md) for full specification.

## Configuration

Defaults target a local development setup (memory-service on `localhost:8082`, processor on port `8090`), but you must supply the secrets listed under [Run It](#run-it). Key settings in `src/main/resources/application.properties`:
- Memory Service connection: `memory-service.grpc.host` / `memory-service.grpc.port`
- LLM provider for the `memory` model (extraction/verification): `quarkus.langchain4j.memory.chat-model.provider`, set through `COGNITION_MEMORY_MODEL_PROVIDER` (default: `openai`; alternatives: `ollama`, `vertex-ai-gemini`)
- OpenAI settings: `COGNITION_OPENAI_BASE_URL`, `COGNITION_OPENAI_API_KEY`, `COGNITION_OPENAI_MODEL_NAME` (default `gpt-4o`)
- Debounce and batching: `cognition.scheduler.*` (default: 1 minute quiet period, 5 minute max age, 24 entries per batch, 8 concurrent jobs)
- LLM retries: `cognition.llm.retry.*`
- Contradiction detection LLM: `cognition.contradiction.llm.*` (default: Ollama `llama3.2`; independent of the `memory` model)
- Verification stage: `cognition.durable-memory-extraction.verifier.enabled`

All `cognition.*` and `memory-service.*` keys are typed in [`CognitionConfig`](src/main/java/io/github/rigazilla/memory/cognition/config/CognitionConfig.java) and [`MemoryServiceConfig`](src/main/java/io/github/rigazilla/memory/cognition/config/MemoryServiceConfig.java), which document each key and default.

Environment variable template: [`env.example`](env.example) (used for both local runs and Docker; copy to `.env` for local, `.env.docker` for Docker Compose).

### Salience filtering

Low-salience events (greetings, filler words, etc.) are scored and dropped before the extraction
pipeline at two points: when the event arrives (`handleEvent`) and again at extraction time
(`filterEvidenceForBatch`) to catch context entries the first gate never saw.

All keys live under the `salience.*` prefix.

| Key | Default | Description |
|-----|---------|-------------|
| `salience.enabled` | `true` | Enable/disable the filter entirely. |
| `salience.threshold` | `0.3` | Events scoring at or below this value are dropped. |
| `salience.min-length` | `10` | Events shorter than this (chars) score 0.1 and are dropped. |
| `salience.metrics.enabled` | `true` | Expose `eventsFiltered` / `eventsKept` counters. |
| `salience.pattern.greeting-enabled` | `true` | Drop events that match the greeting pattern. |
| `salience.pattern.acknowledgment-enabled` | `true` | Drop events that match the acknowledgment pattern. |
| `salience.pattern.farewell-enabled` | `true` | Drop events that match the farewell pattern. |
| `salience.pattern.filler-enabled` | `true` | Drop events that match the filler pattern. |
| `salience.pattern.thanks-enabled` | `true` | Drop events that match the thanks pattern. |
| `salience.pattern.greetings` | `hi,hello,hey,…` | Comma-separated greeting terms. Override to localise. |
| `salience.pattern.acknowledgments` | `ok,understood,…` | Comma-separated acknowledgment terms. |
| `salience.pattern.farewells` | `bye,goodbye,…` | Comma-separated farewell terms. |
| `salience.pattern.fillers` | `um,hmm,…` | Comma-separated filler terms. |
| `salience.pattern.thanks` | `thanks,thank you,…` | Comma-separated thanks terms. |
| `salience.keywords.list` | _(absent)_ | Inline comma-separated keywords. Highest priority — overrides file and bundled defaults. |
| `salience.keywords.file` | _(absent)_ | Path to an external keyword file (one per line, `#` comments ignored). Overrides bundled defaults. |

#### Keyword source priority and fallback

Keywords are loaded from the first source that is configured, in priority order:

1. `salience.keywords.list` — inline list in config (highest priority)
2. `salience.keywords.file` — path to an external file
3. Bundled classpath resource `salience/default-keywords.txt` (ships with the jar)

If an external source (`list` or `file`) is configured but parses to zero keywords (empty, or
contains only blank lines and `#` comments), a WARN is logged and the service falls back to the
bundled defaults. This preserves recall while signalling the misconfiguration. A missing or empty
bundled resource aborts startup.

The bundled file ships 17 keywords across four categories (technical, preference, decision,
procedure). To inspect or replace them: `src/main/resources/salience/default-keywords.txt`.

To localise pattern terms (e.g. Spanish), supply overrides in `application.properties` or via
a separate file pointed to by `smallrye.config.locations`:

```properties
# application.properties — Spanish override example
salience.pattern.greetings=hola,buenos días,buenas tardes
salience.pattern.acknowledgments=vale,entendido
salience.pattern.farewells=adiós,hasta luego
salience.pattern.fillers=pues,bueno
salience.pattern.thanks=gracias,muchas gracias
```

## Docker Dev/Test

A JVM-based Docker setup is included for local integration testing.

### Prerequisites
- Docker
- Memory Service running on the host at `http://localhost:8082`
- Optional Ollama running on the host at `http://localhost:11434` if you use Ollama-backed models

### Build the application
```bash
./mvnw package
```

### Configure Docker runtime
`docker-compose.yml` reads `.env.docker` (git-ignored):
```bash
cp env.example .env.docker
```

Then edit `.env.docker` as needed:
- set `COGNITION_OPENAI_API_KEY` if using the default `openai` provider
- or set `COGNITION_MEMORY_MODEL_PROVIDER=ollama` to use the host Ollama

### Start the container
```bash
docker compose up --build
```

The containerized cognition processor will be available on:
- `http://localhost:8090`

### Logs
Container logs:
```bash
docker compose logs -f cognition-processor
```

Persisted Quarkus log file:
```bash
tail -f logs/quarkus.log
```

### Notes
- The compose file maps `host.docker.internal` so the container can reach host services on Linux
- The container expects Memory Service on `host.docker.internal:8082`
- The compose file mounts `./logs` to `/deployments/logs` so file logging remains available

## Project Status

**Currently Implemented** (design notes in [DONE/](./DONE/)):
- ✅ gRPC event stream client with checkpointing (stored in memory-service)
- ✅ Salience filtering, debounce windows and batching
- ✅ Job processing pipeline with per-conversation queues
- ✅ Durable memory extraction (5 types: fact, preference, procedure, problem_solution, decision) with citation verification (can be disabled)
- ✅ Provenance tracking and memory justification API
- ✅ Contradiction detection and resolution (on insert, and batch backfill)
- ✅ Duplicate detection and merging of extracted memories (`ConsolidationService`, run in the job pipeline)
- ✅ Profile context consolidation (manual trigger)
- ✅ Metadata and temporal metadata enrichment (manual backfill)
- ✅ `store-all` verbatim baseline process (disabled by default; type `verbatim`)
- ✅ Process management API (list/inspect/start/enable/disable/config)
- ✅ LLM retry with backoff, token usage tracking, health and metrics endpoints

**Not Yet Implemented / Open** (see [TODO/](./TODO/) for the current list):
- ❌ Job-level retry / dead-letter handling (only individual LLM calls are retried)
- ❌ Topic summary extraction
- ❌ Scheduled (nightly) profile consolidation
- ❌ Cache-only notes (bridge, topic) and prompt caching optimization

## For New Contributors

- 📖 Start with [AGENTS.md](./AGENTS.md) for project context
- 📝 Browse [TODO/](./TODO/) folder for work items
- ✅ Check [DONE/](./DONE/) folder to understand what's completed

## Learn More

### About This Project
- [AGENTS.md](./AGENTS.md) - Project overview and guidelines for AI assistants
- [Enhancement 099](https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md) - Implementation specification
- [docs/testing-guide.md](./docs/testing-guide.md) - Hands-on end-to-end walkthrough with curl
- [API reference site](../site/README.md) - OpenAPI-based docs (`site/`)

### About Memory Service
- [Memory Service Repository](https://github.com/chirino/memory-service)
- [Core Concepts](https://chirino.github.io/memory-service/docs/concepts/) - Conversations, entries, memories, access control
- [Quarkus Guide](https://chirino.github.io/memory-service/docs/quarkus/) - Quarkus integration patterns
- [Memory Cognition Architecture](https://github.com/chirino/memory-service/blob/main/docs/memory-cognition.md) - Two-layer design

### Technologies
- [Quarkus](https://quarkus.io/) - Application framework
- [LangChain4j](https://docs.langchain4j.dev/) - LLM integration
- [Ollama](https://ollama.ai) - Local LLM runtime

## License

Apache License 2.0