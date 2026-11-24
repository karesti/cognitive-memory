# Testing Guide: Cognitive Memory Processes

## Overview
The cognition processor extracts structured memories (facts, preferences, procedures, decisions, problem solutions) from conversation transcripts. It runs as an event-driven pipeline with a 1-minute debounce window.

## Architecture: Two Services

```
┌─────────────────────────────────────────────────────────────┐
│ Memory Service (port 8082)                                  │
│ - Stores conversations & entries                            │
│ - Emits events via gRPC stream                              │
│ - Provides chat UI (port 8080)                              │
└─────────────────────────────────────────────────────────────┘
                        │
                        │ gRPC event stream
                        ↓
┌─────────────────────────────────────────────────────────────┐
│ Cognition Processor (port 8090)                             │
│ - Subscribes to memory-service events                       │
│ - Extracts & verifies memories                              │
│ - Writes memories back to memory-service                    │
└─────────────────────────────────────────────────────────────┘
```

## Environment Configuration

The cognition processor requires environment variables for configuration.

### Configuration File

**File**: `env.example`

A single configuration template is provided for both local and Docker-based runs:

```bash
cd cognition-processor-quarkus
cp env.example .env
# Edit .env with your API keys and configuration
```

**For local development** (non-containerized):
```bash
./mvnw quarkus:dev
```

**For Docker Compose** (containerized):
```bash
docker compose --profile cognition up -d
```

### Configuration Variables

The env.example file contains these core variables:

| Variable | Purpose | Example |
|----------|---------|---------|
| `MEMORY_SERVICE_API_KEY` | Auth for memory-service (required, no default) | `cognition-processor-key-123` |
| `COGNITION_MEMORY_MODEL_PROVIDER` | LLM provider for extraction/verification (`openai` default, `ollama`, `vertex-ai-gemini`) | `openai` |
| `COGNITION_OPENAI_BASE_URL` | LLM endpoint | `https://api.openai.com/v1` |
| `COGNITION_OPENAI_API_KEY` | OpenAI API key | `<your-api-key>` |
| `COGNITION_OPENAI_MODEL_NAME` | Model for extraction/verification | `gpt-4o` |
| `OPENAI_*`, `MEMORY_SERVICE_EMBEDDING_*` | Used by the memory-service stack (chat sample app, embeddings), not by the processor | — |

**Note**: Replace all `<your-api-key>` placeholders with your actual keys before running. The contradiction detector uses its own LLM settings (`cognition.contradiction.llm.*`, default Ollama `llama3.2`), independent of the `memory` model above. Point it at a reachable LLM or disable the contradiction processes if you do not run Ollama.

## Currently Implemented Processes

`GET /api/processes` is the authoritative list. This guide walks through the two main ones; the others are:

| Process ID | What it does | How to use |
|---|---|---|
| `contradiction-on-insert` | Checks each newly written memory against semantically close existing memories and resolves contradictions (marks superseded) | Automatic; `POST /api/processes/contradiction-on-insert/disable` to turn off |
| `contradiction-resolution` | Batch pass over existing memories per namespace | `POST /api/processes/contradiction-resolution/start` |
| `metadata-enrichment` | Adds entity/topic metadata to existing memories | `POST /api/processes/metadata-enrichment/start` |
| `temporal-metadata-enrichment` | Backfills `observed_at` / `effective_at` | `POST /api/processes/temporal-metadata-enrichment/start` |
| `store-all` | Stores every entry verbatim, no LLM (benchmark baseline); **disabled by default** | `POST /api/processes/store-all/enable` |

### 1. Durable Memory Extraction (Automatic)

**Process ID**: `durable-memory-extraction`
- **State**: ENABLED
- **Trigger**: Automatic (event-driven with 1-minute debounce)
- **Pipeline**: Load Evidence → Extract Memories → Verify Citations → Write to Memory Service
- **Concurrency**: Singleton per conversation, parallel across conversations
- **LLM Calls**: 2 per job (extraction + verification)

### 2. Profile Context Consolidation (Manual)

**Process ID**: `profile-context-consolidation`
- **State**: ENABLED
- **Trigger**: Manual only (REST API call)
- **Pipeline**: Query User Memories → Consolidate via LLM → Write Profile Snapshot
- **Purpose**: Consolidates extracted memories into a structured user profile
- **LLM Calls**: 1 per consolidation (higher temperature 0.3 for creative synthesis)
- **Output**: Profile snapshot at namespace `["user", userId, "cognition.v1", "profile_context"]`

## How to Exercise the System

### 1. Start Both Services

**Option A: Using Docker Compose Profile**

> Unverified: this relies on a `cognition` Docker Compose profile in the memory-service repository. If `docker compose --profile cognition up` does not start the processor, use Option B.

Start both services together with a single command:
```bash
cd ~/git/memory-service  # wherever you cloned it
docker compose --profile cognition up -d
```

This starts:
- Memory Service (port 8082)
- Cognition Processor (port 8090)
- Chat UI (port 8080)
- All supporting services (PostgreSQL, etc.)

**Option B: Manual Start (For Development)**

Start memory service only:
```bash
cd ~/git/memory-service
docker compose up -d
```

Then start cognition processor in dev mode:
```bash
cd cognition-processor-quarkus
./mvnw quarkus:dev
```

Use Option B when you want hot reload and debugging for the cognition processor.

### 2. Create Test Conversations

**Option A: Use the Chat UI (Easiest)**

1. Open http://localhost:8080 in your browser
2. Login with test user: `alice` / `alice` (or `bob`/`bob`, `charlie`/`charlie`)
3. Start a conversation and send messages like:
   - "I work at Acme Corp as a senior engineer"
   - "I prefer Python over Java"
   - "My email is alice@example.com"

The cognition processor will automatically:
- Receive events from memory-service
- Wait 1 minute (debounce)
- Extract memories
- Store them back to memory-service

**Option B: Use the API Directly**

All these commands target **memory-service** (port 8082), not the cognition processor.

Authentication: send the API key in `X-API-Key` **only**. Do not add `Authorization: Bearer <api-key>`:
a memory-service with OIDC enabled (the default in its `compose.yaml`) rejects a non-JWT bearer token.
`cognition-processor-key-123` is the quickstart key of the `cognition_processor` client. That client is
trusted to assert a user with `X-User-ID`, which the user-scoped `/v1/...` endpoints need
(`MEMORY_SERVICE_TRUSTED_USER_ID_CLIENTS`). The admin endpoints (`/admin/v1/...`) need no user.

```bash
# 1. Create a conversation in memory-service
CONV_ID=$(curl -s -X POST http://localhost:8082/v1/conversations \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Performance Test",
    "members": [{"userId": "alice", "role": "OWNER"}]
  }' | jq -r '.id')

echo "Created conversation: $CONV_ID"

# 2. Add entries to the conversation (triggers cognition processing)
curl -X POST http://localhost:8082/v1/conversations/$CONV_ID/entries \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "HISTORY",
    "content": [
      {"role": "user", "text": "I work at Acme Corp as a senior engineer"},
      {"role": "assistant", "text": "That'\''s great! What technologies do you work with?"},
      {"role": "user", "text": "Mainly Java and Kubernetes"}
    ]
  }'
```

### 3. Monitor Cognition Processor Status

These commands target the **cognition processor** (port 8090):

```bash
# List all cognitive processes
curl http://localhost:8090/api/processes

# Inspect the durable extraction process
curl http://localhost:8090/api/processes/durable-memory-extraction

# Inspect the profile consolidation process
curl http://localhost:8090/api/processes/profile-context-consolidation
```

**Key Metrics in Durable Extraction Inspection Response**:
- `eventStreamConnected`: gRPC connection status
- `eventsAccepted`: Total events received
- `activeWindows`: Conversations waiting for debounce
- `pendingJobs`: Jobs queued for processing
- `activeQueues`: Conversations currently processing

**Key Metrics in Profile Consolidation Inspection Response**:
- `mode`: "manual_trigger" (Phase 0)
- `lastRunTime`: Timestamp of last consolidation
- `lastRunStatus`: "success", "error", or "never_run"
- `lastRunUserId`: User ID of last consolidation
- `resourceTypes`: LLM configuration and prompts used

### 4. Check Health Endpoints

```bash
# Cognition processor health (port 8090)
curl http://localhost:8090/q/health/live      # Liveness
curl http://localhost:8090/q/health/ready     # Readiness (includes gRPC)
curl http://localhost:8090/q/health           # Full health

# Memory service health (port 8082)
curl http://localhost:8082/v1/health
```

### 5. Verify Extracted Memories

After ~70 seconds (60s debounce + ~10s processing), check memory-service for extracted memories:

```bash
# Search for all memories extracted by cognition processor
curl -X POST http://localhost:8082/v1/memories/search \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{"namespace_prefix": ["user", "alice", "cognition.v1"]}' | jq
```

### 6. Trigger Profile Consolidation (Manual)

Once memories have been extracted, you can manually trigger profile consolidation:

```bash
# Trigger consolidation for user 'alice'
curl -X POST http://localhost:8090/api/consolidate/alice \
  -H "Content-Type: application/json" | jq

# Expected response:
# {
#   "status": "success",
#   "message": "Profile consolidated successfully",
#   "userId": "alice",
#   "generatedAt": "2026-07-03T08:00:00Z",
#   "sectionsCount": 5
# }
```

### 7. Verify Profile Snapshot

After consolidation, check the profile snapshot in memory-service:

```bash
# Search for profile context snapshot
curl -X POST http://localhost:8082/v1/memories/search \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{"namespace_prefix": ["user", "alice", "cognition.v1", "profile_context"]}' | jq
```

Expected response structure for extracted memories:
```json
{
  "items": [
    {
      "namespace": ["user", "alice", "cognition.v1", "fact"],
      "key": "uuid-here",
      "value": {
        "content": "User works at Acme Corp as a senior engineer",
        "confidence": 0.95,
        "citations": ["User said: I work at Acme Corp..."],
        "provenance": {
          "conversation_id": "conv-123",
          "entry_ids": ["entry-1", "entry-2"],
          "batch_trigger": "debounce_delay"
        }
      }
    }
  ]
}
```

Expected response structure for profile snapshot:
```json
{
  "items": [
    {
      "namespace": ["user", "alice", "cognition.v1", "profile_context"],
      "key": "latest",
      "value": {
        "kind": "profile_context_snapshot",
        "version": "profile_context.v1",
        "user_id": "alice",
        "generated_at": "2026-07-03T08:00:00Z",
        "content": "# User Profile\n\n## Work\nAlice works at Acme Corp...",
        "sections": {
          "work": {
            "confidence": 0.95,
            "source_memory_keys": ["uuid-1", "uuid-2"]
          },
          "preferences": {
            "confidence": 0.90,
            "source_memory_keys": ["uuid-3"]
          }
        }
      }
    }
  ]
}
```

## Performance Testing Scenarios

### Load Test: Multiple Conversations (Durable Extraction)
```bash
# Create 100 conversations with 10 entries each
for i in {1..100}; do
  CONV_ID=$(curl -s -X POST http://localhost:8082/v1/conversations \
    -H "X-API-Key: cognition-processor-key-123" \
    -H "X-User-ID: alice" \
    -H "Content-Type: application/json" \
    -d "{\"title\": \"Test $i\", \"members\": [{\"userId\": \"alice\", \"role\": \"OWNER\"}]}" \
    | jq -r '.id')
  
  # Add entries
  curl -s -X POST http://localhost:8082/v1/conversations/$CONV_ID/entries \
    -H "X-API-Key: cognition-processor-key-123" \
    -H "X-User-ID: alice" \
    -H "Content-Type: application/json" \
    -d '{"channel": "HISTORY", "content": [{"role": "user", "text": "Test message '$i'"}]}' > /dev/null
done

# Monitor cognition processor metrics
watch -n 5 'curl -s http://localhost:8090/api/processes/durable-memory-extraction | jq ".details"'
```

### Stress Test: Large Batches (Durable Extraction)
```bash
# Single conversation with 24 entries (max batch size)
# Measure: LLM call duration, memory usage, processing time
```

### Profile Consolidation Performance Test
```bash
# Test consolidation with varying memory counts
for user in alice bob charlie; do
  echo "Testing consolidation for $user..."
  START=$(date +%s)
  
  curl -X POST http://localhost:8090/api/consolidate/$user \
    -H "Content-Type: application/json" | jq
  
  END=$(date +%s)
  echo "Consolidation time for $user: $((END - START)) seconds"
done

# Monitor last run status
curl -s http://localhost:8090/api/processes/profile-context-consolidation | jq '.details'
```

### Latency Test: End-to-End Timing
```bash
# Measure: Entry creation → Memory extraction → Storage
# Expected: ~60-90 seconds total
START=$(date +%s)
# Create entry...
# Wait for memory to appear...
END=$(date +%s)
echo "Total latency: $((END - START)) seconds"
```

### Consolidation Latency Test
```bash
# Measure: Consolidation trigger → Profile snapshot written
# Expected: 5-30 seconds depending on memory count
START=$(date +%s)
curl -X POST http://localhost:8090/api/consolidate/alice > /dev/null
END=$(date +%s)
echo "Consolidation latency: $((END - START)) seconds"
```

## Key Performance Indicators

| Metric | Location | Target |
|--------|----------|--------|
| Debounce Delay | Config | 1 minute |
| LLM Calls per Job | Pipeline | 2 (extract + verify) |
| Processing Time | Logs | < 5 seconds/conversation |
| Concurrent Jobs | Config | 8 max |
| Memory Types | Extraction | 5 types supported |

## Monitoring Logs

```bash
# Cognition processor logs (real-time)
tail -f cognition-processor-quarkus/logs/quarkus.log

# Memory service logs
cd ~/git/memory-service
docker compose logs -f memory-service

# Look for in cognition processor logs:
# - "Event #N received" (event ingestion)
# - "Scope Job Dispatched" (debounce trigger)
# - "Processing job" (pipeline execution)
# - "Job completed successfully in Xms" (timing)
```

## Configuration Tuning

Edit `cognition-processor-quarkus/src/main/resources/application.properties`:
```properties
# Debounce timing
cognition.scheduler.debounce-delay=PT1M
cognition.scheduler.max-batch-age=PT5M
cognition.scheduler.max-batch-entries=24

# Concurrency
cognition.scheduler.max-concurrent-jobs=8

# LLM timeout (named model "memory"; use quarkus.langchain4j.openai.memory.timeout for OpenAI)
quarkus.langchain4j.ollama.memory.timeout=60s
```

## Port Reference

| Service | Port | Purpose |
|---------|------|---------|
| Memory Service (gRPC) | 8082 | API & event stream |
| Chat UI | 8080 | Web interface for testing |
| Cognition Processor | 8090 | Management API & health |
| Ollama | 11434 | LLM inference |

## Known Limitations
- Only individual LLM calls are retried (`cognition.llm.retry.*`). A job that still fails is logged and dropped (no job-level retry or dead-letter queue).
- Salience filtering is on by default, so greetings and filler are dropped. If your test messages are very short (under `salience.min-length`, default 10 chars) or generic, they may produce no memories; see the README "Salience filtering" section or set `salience.enabled=false` for testing.
- Profile consolidation is manual only (no scheduled run).
- Authentication requires conversation membership

## Testing Profile Consolidation

### Complete Workflow Test

```bash
# 1. Create conversation with rich content
CONV_ID=$(curl -s -X POST http://localhost:8082/v1/conversations \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{"title": "Profile Test", "members": [{"userId": "alice", "role": "OWNER"}]}' \
  | jq -r '.id')

# 2. Add multiple entries with different memory types
curl -X POST http://localhost:8082/v1/conversations/$CONV_ID/entries \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "HISTORY",
    "content": [
      {"role": "user", "text": "I work at Acme Corp as a senior engineer"},
      {"role": "assistant", "text": "Got it, I'\''ll remember that."},
      {"role": "user", "text": "I prefer Python over Java for scripting"},
      {"role": "assistant", "text": "Noted your preference."},
      {"role": "user", "text": "My email is alice@acme.com"}
    ]
  }'

# 3. Wait for automatic extraction (70 seconds)
echo "Waiting for automatic memory extraction..."
sleep 70

# 4. Verify memories were extracted
echo "Checking extracted memories..."
MEMORY_COUNT=$(curl -s -X POST http://localhost:8082/v1/memories/search \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{"namespace_prefix": ["user", "alice", "cognition.v1"]}' \
  | jq '.items | length')
echo "Found $MEMORY_COUNT memories"

# 5. Trigger profile consolidation
echo "Triggering profile consolidation..."
curl -X POST http://localhost:8090/api/consolidate/alice \
  -H "Content-Type: application/json" | jq

# 6. Verify profile snapshot was created
echo "Checking profile snapshot..."
curl -s -X POST http://localhost:8082/v1/memories/search \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{"namespace_prefix": ["user", "alice", "cognition.v1", "profile_context"]}' \
  | jq '.items[0].value.content'
```

### Consolidation Quality Test

Test how well the consolidation handles different memory types:

```bash
# Create memories of all 5 types, then consolidate
# Expected: Profile should organize them into coherent sections

# Check consolidation quality
curl -s -X POST http://localhost:8082/v1/memories/search \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{"namespace_prefix": ["user", "alice", "cognition.v1", "profile_context"]}' \
  | jq '.items[0].value | {
    sections: .sections | keys,
    content_length: .content | length,
    generated_at: .generated_at
  }'
```

## Quick Validation Script

```bash
#!/bin/bash
set -e

echo "=== Cognition Processor Validation ==="
echo ""

echo "1. Checking both processes status..."
echo "  - Durable extraction:"
curl -s http://localhost:8090/api/processes/durable-memory-extraction | jq -r '.state'
echo "  - Profile consolidation:"
curl -s http://localhost:8090/api/processes/profile-context-consolidation | jq -r '.state'

echo ""
echo "2. Creating test conversation..."
CONV_ID=$(curl -s -X POST http://localhost:8082/v1/conversations \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{"title": "Quick Test", "members": [{"userId": "alice", "role": "OWNER"}]}' \
  | jq -r '.id')
echo "  Created: $CONV_ID"

echo ""
echo "3. Adding test entry..."
curl -s -X POST http://localhost:8082/v1/conversations/$CONV_ID/entries \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{"channel": "HISTORY", "content": [{"role": "user", "text": "I work at Acme Corp"}]}' > /dev/null
echo "  ✓ Entry added"

echo ""
echo "4. Waiting 70 seconds for automatic extraction..."
sleep 70

echo ""
echo "5. Checking for extracted memories..."
MEMORY_COUNT=$(curl -s -X POST http://localhost:8082/v1/memories/search \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{"namespace_prefix": ["user", "alice", "cognition.v1"]}' \
  | jq '.items | length')
echo "  ✓ Found $MEMORY_COUNT extracted memories"

echo ""
echo "6. Triggering profile consolidation..."
CONSOLIDATION_RESULT=$(curl -s -X POST http://localhost:8090/api/consolidate/alice \
  -H "Content-Type: application/json" | jq -r '.status')
echo "  ✓ Consolidation status: $CONSOLIDATION_RESULT"

echo ""
echo "7. Verifying profile snapshot..."
PROFILE_EXISTS=$(curl -s -X POST http://localhost:8082/v1/memories/search \
  -H "X-API-Key: cognition-processor-key-123" \
  -H "X-User-ID: alice" \
  -H "Content-Type: application/json" \
  -d '{"namespace_prefix": ["user", "alice", "cognition.v1", "profile_context"]}' \
  | jq '.items | length')

if [ "$PROFILE_EXISTS" -gt 0 ]; then
  echo "  ✓ Profile snapshot created successfully"
else
  echo "  ✗ Profile snapshot not found"
  exit 1
fi

echo ""
echo "=== All validations passed! ==="
```

## Process Comparison

| Aspect | Durable Memory Extraction | Profile Context Consolidation |
|--------|---------------------------|-------------------------------|
| **Trigger** | Automatic (event-driven) | Manual (REST API) |
| **Input** | Conversation entries | Extracted memories |
| **Output** | Individual memories (5 types) | Consolidated profile snapshot |
| **Timing** | 1-minute debounce | On-demand |
| **LLM Temperature** | 0.1 (precise extraction) | 0.3 (creative synthesis) |
| **Namespace** | `["user", userId, "cognition.v1", memoryType]` | `["user", userId, "cognition.v1", "profile_context"]` |
| **Use Case** | Extract facts from conversations | Organize memories into profile |

## Summary

**Key Points**: 

1. **Two-Service Architecture**: You interact with **memory-service** (port 8082) to create conversations and add entries. The **cognition processor** (port 8090) automatically subscribes to events, processes them, and writes memories back to memory-service.

2. **Two Cognitive Processes**:
   - **Durable Memory Extraction**: Runs automatically with 1-minute debounce, extracts 5 types of memories from conversations
   - **Profile Context Consolidation**: Runs on-demand via REST API, consolidates extracted memories into structured user profiles

3. **Testing Workflow**:
   - Create conversations → Wait for automatic extraction (~70s) → Manually trigger consolidation → Verify profile snapshot

4. **Monitoring**: Use the management API on port 8090 to inspect both processes and track their status.
