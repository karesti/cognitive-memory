---
layout: ../../layouts/DocsLayout.astro
title: Overview
---

The Cognitive Memory processor is an intelligent cognition layer that sits between AI agents and [Memory Service](https://github.com/chirino/memory-service). It automatically extracts salient memories from conversations and stores them back with full provenance.

## Architecture

```
Agent  -->  Memory Service  -->  Events  -->  Cognition Processor
                ^                                    |
                |                                    |
                +-------- Extracted Memories ---------+
```

1. Agents interact with users through Memory Service conversations
2. Memory Service emits `entry.created` events via gRPC stream
3. The cognition processor receives events, buffers them in debounce windows, and extracts structured memories using an LLM
4. Extracted memories are written back to Memory Service with citations and provenance

## Memory Types

The default extraction process (`durable-memory-extraction`) produces five types of cognitive memories:

| Type | Description | Example |
|------|-------------|---------|
| **Fact** | Objective information about the user | "Works at Acme Corp as a senior engineer" |
| **Preference** | User likes, dislikes, or choices | "Prefers Go over Rust" |
| **Decision** | Choices the user has made | "Decided to use PostgreSQL for the project" |
| **Procedure** | How-to knowledge or workflows | "Deploys via CI/CD pipeline with staging gate" |
| **Problem/Solution** | Issues encountered and their fixes | "Fixed OOM by increasing heap to 4GB" |

The optional `store-all` baseline process (disabled by default) writes a sixth type, **verbatim**: every conversation entry stored as-is with no LLM involved. It exists for benchmarking against extracted memories.

## Cognitive Processes

Each capability is a managed process you can list, inspect and (where supported) start, enable or disable at runtime through `/api/processes`.

| Process ID | What it does | Trigger |
|---|---|---|
| `durable-memory-extraction` | Extracts and verifies memories from conversations | Automatic (debounced events) |
| `contradiction-on-insert` | Detects and resolves contradictions when a memory is written | Automatic |
| `contradiction-resolution` | Batch contradiction pass over existing memories | Manual start |
| `profile-context-consolidation` | Builds a profile snapshot for a user | Manual (`POST /api/consolidate/{userId}`) |
| `metadata-enrichment` | Adds entity and topic metadata to existing memories | Manual start |
| `temporal-metadata-enrichment` | Backfills `observed_at` / `effective_at` | Manual start |
| `store-all` | Verbatim baseline, no LLM (disabled by default) | Automatic when enabled |

`GET /api/processes` always returns the authoritative list and each process's live statistics.

## API Surface

The cognition processor exposes REST APIs for management and inspection. These are **not** data ingestion endpoints — all conversation data flows through Memory Service.

| API | Endpoints | Purpose |
|-----|-----------|---------|
| Process Management | `GET /api/processes`, `GET /api/processes/{id}`, `POST /api/processes/{id}/start`, `/enable`, `/disable`, `/config` | List, inspect and control cognitive processes |
| Event Status | `GET /api/events/status` | Monitor event stream and window status |
| Profile Consolidation | `POST /api/consolidate/{userId}` | Trigger user profile consolidation |
| Memory Justification | `GET /api/memories/{memoryId}/justify` | Get memory with full provenance |
| Health & Metrics | `/q/health`, `/q/metrics` | Liveness, readiness, Prometheus metrics |

See the full [API Reference](/docs/api/) for details, request/response schemas, and example `curl` commands.

## Data Flow

Data enters the system exclusively through Memory Service. To inject information for processing:

1. Create a conversation via Memory Service API
2. Add entries to the conversation
3. Memory Service emits events automatically
4. The cognition processor picks them up and extracts memories

There is no way to bypass this flow — the processor is a passive listener by design.
