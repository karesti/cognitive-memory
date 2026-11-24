# TODO: Documentation Improvement Plan

**Priority**: MEDIUM
**Status**: P0 done (2026-11-24). P1 and P2 not started.

This file is the hand-off for continuing the documentation work in a new session. Read it fully before editing docs.

## Framing (decided with the maintainer)

- **Focus on this component** (the cognition processor), not on memory-service. Memory-service only needs enough explanation to place this component.
- **Positioning to use in all docs**: memory-service is the **source of truth and stores everything** (raw conversations, entries, memories, access control). The cognition processor is the part with **LLMs**: it reads what memory-service stores, uses LLMs to turn it into structured, verified, provenance-linked memories, and writes them back. It is a passive, event-driven listener, never an ingestion path.
- **Two audiences, keep them separate**:
  - *User-oriented*: what is it, why use it, how does it fit in the overall PRINCE architecture, tutorial, how to configure/operate.
  - *Technical/contributor-oriented*: architecture, package map, adding a process, testing, CI, design notes.
- **PRINCE context is missing from the whole repo** (zero mentions of "PRINCE"). A page explaining where this component sits in PRINCE is needed. Facts still required from the maintainer: which PRINCE components write conversations into memory-service, which read the extracted memories, and what PRINCE calls this layer. Do not invent them.

## Where the docs live

| Audience | Location |
|---|---|
| Entry points | Root `README.md`, `cognition-processor-quarkus/README.md` (keep short, link out) |
| User docs (target single source of truth) | Astro site: `site/src/pages/docs/*.md`, sidebar in `site/src/components/DocsSidebar.astro`; Mermaid supported via `site/remark-mermaid.mjs` |
| API reference | Generated: `site/public/openapi.*` (see `site/README.md`) |
| Contributor docs | `cognition-processor-quarkus/AGENTS.md`, `AI-CODE.md`, `AI-TEST.md`, `docs/testing-guide.md` |
| Design history | `cognition-processor-quarkus/DONE/NNN-*.md` (no index yet) |
| Open work | `cognition-processor-quarkus/TODO/` (index: `TODO/README.md`) |

## P0: done (2026-11-24)

Corrected inaccurate or stale docs: `AGENTS.md` (architecture, package map, process table, coverage), processor `README.md` (env setup, default provider, Docker, status, broken link, log line), `docs/testing-guide.md`, `TODO/README.md` (audited index), `site/src/pages/docs/index.md` (process catalogue, endpoints, verbatim type), `.github/SETUP.md` (all 6 workflows), `memory-service/README.md` (Ollama volume), `application.properties` (stale comment).

## P1: new docs to write (not started)

Add these to the Astro site and its sidebar. Reuse the working curl flow in `docs/testing-guide.md`.

1. **What is Cognitive Memory and where it fits in PRINCE** (blocked on the PRINCE facts above; write the component-only parts first).
2. **Tutorial: from chat to memories in about 15 minutes**:
   1. Story: a user tells an assistant they work at Acme and prefer Python.
   2. Start memory-service and the processor.
   3. Chat in the UI.
   4. Watch `GET /api/processes/durable-memory-extraction`.
   5. Search the extracted memories.
   6. Call `GET /api/memories/{id}/justify` to see provenance.
   7. Trigger `POST /api/consolidate/{userId}`.
   8. Add a contradicting statement and watch it get superseded.
   9. Toggle `store-all` to compare verbatim with extracted memories.
3. **Concepts and architecture** (Mermaid): event, salience, debounce window, job, extract, verify, write; checkpoint and replay; memory model and namespace `["user", <id>, "cognition.v1", <type>]`; provenance and citations.
4. **Glossary**: dirty window, debounce, evidence pack, checkpoint, salience, provenance, substrate vs cognition, process.
5. **Process catalogue** (one page per or one table): trigger, default state, LLM calls, output namespace, metrics. Base it on the table in `AGENTS.md`.
6. **Configuration reference**: all `cognition.*`, `memory-service.*`, `salience.*`, LLM provider keys. Include per-process and per-resource overrides (`cognition.process.<id>.…`, `api-key-ref`), currently documented only in `DONE/017-resource-configuration-system.md`.
7. **LLM provider guide**: openai / ollama / vertex-ai-gemini; the separate contradiction-detector LLM (`cognition.contradiction.llm.*`, default Ollama `llama3.2`).
8. **Operations and troubleshooting**: health and `/q/metrics`, token tracking (`DONE/026`), checkpoint reset (`cognition.checkpoint.reset-on-startup`), common errors (gRPC not connected, 401, empty LLM responses, salience dropping short test messages).
9. **Security and auth model**: API key, admin client id, on-behalf-of authorization (`DONE/014`). Memory-service-side settings need verification (see below).
10. **Contributor docs**: how to add a process (expand the 5 bullets in `AGENTS.md`), index of `DONE/`, a `CONTRIBUTING.md`, a `CHANGELOG`.

## P2: keep docs from drifting (not started)

- Generate the config reference from the `@ConfigMapping` interfaces (`CognitionConfig`, `MemoryServiceConfig`, `SalienceScorerConfig`) and the process catalogue from `GET /api/processes`. Model it on `.github/workflows/verify-openapi.yml`.
- Add a link checker and markdownlint to CI. A simple Python relative-link check already passed on the edited files.
- Add a PR template line: "docs/site updated?". `AGENTS.md` now states the rule; enforce it.
- Run the quick-validation script from `docs/testing-guide.md` as a smoke test so the tutorial does not rot.

## Verified facts (checked in code, 2026-11-24)

- Single package `io.github.rigazilla.memory.cognition`; communicates with memory-service only via gRPC (default `localhost:8082`); REST on port 8090 is management only.
- Checkpoints are stored in memory-service (`CheckpointService` uses `AdminCheckpointService`).
- 7 processes registered: `durable-memory-extraction`, `contradiction-on-insert`, `contradiction-resolution`, `profile-context-consolidation`, `metadata-enrichment`, `temporal-metadata-enrichment`, `store-all` (`store-all` is the only one disabled by default). Start/enable/disable support per process is in the `AGENTS.md` table.
- Only individual LLM calls are retried (`LlmRetryHelper`); a failed job is logged and dropped (`JobProcessor`).
- Topic summary extraction does not exist in `src/main`.
- Coverage: `pom.xml` has a 15% line / 10% branch gate at `verify`; CI runs `test` only and posts a PR comment with a 35% minimum. Inconsistent; tracked in `ci-coverage-enforcement.md`.
- `.env` and `.env.docker` are git-ignored; `docker-compose.yml` reads `.env.docker`.

## Still unverified (need memory-service access or maintainer input)

- That `docker compose --profile cognition up` exists in the memory-service repo (marked "Unverified" in `docs/testing-guide.md`).
- (Resolved) `admin-api-key-1` does not exist; upstream commit `0039c6a` switched the curl examples to `cognition-processor-key-123` with `X-API-Key` only plus `X-User-ID` (OIDC-enabled memory-service rejects `Authorization: Bearer <api-key>`).
- The memory-service settings the processor depends on: `MEMORY_SERVICE_API_KEYS_COGNITION_PROCESSOR` and `MEMORY_SERVICE_ROLES_ADMIN_CLIENTS` (named in `application.properties` comments, not in `memory-service/.env.example`).
- Whether `quarkus.langchain4j.ollama.memory.chat-model.model-id` needs setting for Ollama runs.

## Open decisions for the maintainer

- Delete or archive the TODO files listed under "Likely obsolete" in `TODO/README.md`?
- Which coverage gate should be the enforced one (pom 15/10 at `verify`, or CI 35 comment)?
- PRINCE facts for the "where it fits" page (see Framing).

## Tooling note

In the session that produced this plan, Bob's tools were restricted to the `cognitive-memory` folder, so `~/IBM/PRINCE/memory-service` could not be read. If the workspace root is `~/IBM/PRINCE` in a new session, read memory-service's `docs/memory-cognition.md`, `docs/processors.md`, compose files and `.env.example` to resolve the "unverified" items. Keep memory-service coverage minimal; this component is the focus.
