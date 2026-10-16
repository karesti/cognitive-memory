# 026 - Token Tracking for LLM-Based Cognitive Processes

## Summary

Implemented Phase 1 token usage tracking for LLM-based cognitive processes.
Each named LLM resource within a process (e.g. `extractor`, `verifier`) now accumulates
cumulative token counters that are exposed via the process inspection API
(`GET /api/processes/{id}`).

## Motivation

Token usage metrics are essential for cost monitoring, performance optimisation,
debugging, and capacity planning. Without visibility into how many tokens each
cognitive process consumes per resource, it is impossible to:

- attribute LLM costs to specific processes or components
- identify which prompts are unusually expensive
- detect anomalies (unexpectedly large token consumption)
- plan infrastructure capacity as usage scales

## Design

### New classes — `io.github.rigazilla.memory.cognition.token`

#### `TokenUsage`

Thread-safe per-resource counter backed by `AtomicLong`. Tracks:

| Field | Prometheus name | Type |
|---|---|---|
| `inputTokensTotal` | `llm_tokens_input_total` | Counter |
| `outputTokensTotal` | `llm_tokens_output_total` | Counter |
| `tokensTotal` | `llm_tokens_total` | Counter |
| `requestsTotal` | `llm_requests_total` | Counter |
| `averageTokensPerRequest` | — | Gauge (computed) |

`TokenUsage.Snapshot` is an immutable record returned by `snapshot()`, used
for JSON serialisation in inspect responses. Field names match the API response
format specified in the requirements document.

#### `TokenTracker`

`@ApplicationScoped` registry. Stores one `TokenUsage` per `(processId, resourceName)`
key and one aggregate `TokenUsage` per process, both in a `ConcurrentHashMap`. Created
lazily on first `record()` call. Every `record()` also publishes Micrometer counters
(see "Prometheus Compatibility").

```java
tokenTracker.record("durable-memory-extraction", "extractor", inputTokens, outputTokens);
TokenUsage.Snapshot resource = tokenTracker.snapshot("durable-memory-extraction", "extractor");
TokenUsage.Snapshot process  = tokenTracker.processSnapshot("durable-memory-extraction");
```

#### `TokenTrackingContext`

`ThreadLocal<Entry>` carrier. The LLM call site sets the active `(processId, resourceName)`
context before the call and clears it in a `finally` block. The listener reads this
context to route token counts to the correct counter.

```java
TokenTrackingContext.set(PROCESS_ID, "extractor");
try {
    result = llmRetryHelper.withRetry("extraction:...", () -> extractor.extract(evidence));
} finally {
    TokenTrackingContext.clear();
}
```

#### `TokenUsageListener`

`@ApplicationScoped` bean implementing LangChain4j `ChatModelListener`. Quarkus
LangChain4j automatically discovers all CDI beans implementing this interface
via `Instance<ChatModelListener>` and wires them into every registered chat model
(Ollama, OpenAI, etc.) — no additional configuration required.

On `onResponse()`:
1. Reads the active `TokenTrackingContext.Entry` from the `ThreadLocal`.
2. Extracts `TokenUsage` from `responseContext.chatResponse().tokenUsage()`.
3. Calls `tokenTracker.record(processId, resourceName, provider, model, inputTokens, outputTokens)`,
   where `provider` is `responseContext.modelProvider().name()` and `model` is
   `chatResponse().modelName()`.

If no context is active (LLM call outside tracked pipeline) the counts are silently
dropped. If the provider returns `null` token usage, a debug-level log is emitted.

### Wiring into existing services

#### `JobProcessor`

Two LLM calls wrapped with `TokenTrackingContext`:

- `extractor.extract(evidenceText)` → context `(durable-memory-extraction, extractor)`
- `verifier.verify(candidatesJson, evidenceText)` → context `(durable-memory-extraction, verifier)`

#### `MetadataEnrichmentService`

One LLM call wrapped:

- `extractor.extract(memoryType, content)` → context `(metadata-enrichment, extractor)`

#### `ContradictionOnInsertService` and `ContradictionResolutionService`

`detector.detect(...)` and `detector.detectBatch(...)` wrapped with
`TokenTrackingContext.call` → context `(contradiction-resolution, detector)`.

#### `LlmBasedConsolidationStrategy`

`consolidator.consolidate(...)` wrapped → context `(profile-context-consolidation, consolidator)`.

`TemporalMetadataEnrichmentProcess` makes no LLM calls and is not tracked.

### Inspection API changes

`DurableMemoryExtractionProcess`, `MetadataEnrichmentProcess`, `ContradictionResolutionProcess`
and `ProfileContextConsolidationProcess` now include:

- a `tokenUsage` object inside each LLM resource entry under `resourceTypes`
- a process-level `tokenUsage` object directly under `details`, summing all resources

**Example response (after 127 extraction calls):**

```json
{
  "id": "durable-memory-extraction",
  "state": "ENABLED",
  "details": {
    "eventStreamConnected": true,
    "resourceTypes": {
      "extractor": {
        "type": "LLM",
        "provider": "ollama",
        "modelName": "memory",
        "model": "llama3.2",
        "tokenUsage": {
          "inputTokens": 45230,
          "outputTokens": 12450,
          "totalTokens": 57680,
          "requestCount": 127,
          "averageTokensPerRequest": 454
        }
      },
      "verifier": {
        "type": "LLM",
        "provider": "ollama",
        "modelName": "memory",
        "model": "llama3.2",
        "tokenUsage": {
          "inputTokens": 8920,
          "outputTokens": 2340,
          "totalTokens": 11260,
          "requestCount": 89,
          "averageTokensPerRequest": 126
        }
      }
    }
  }
}
```

## Prometheus Compatibility

`TokenTracker.record()` registers Micrometer counters with the injected `MeterRegistry`
(`quarkus-micrometer-registry-prometheus` is already a dependency). They are exposed on
`/q/metrics` with `_total` suffixes added by the Prometheus registry:

| Micrometer name | Prometheus name |
|---|---|
| `llm.tokens.input` | `llm_tokens_input_total` |
| `llm.tokens.output` | `llm_tokens_output_total` |
| `llm.tokens` | `llm_tokens_total` |
| `llm.requests` | `llm_requests_total` |

Labels: `process`, `resource`, `provider` (e.g. `OLLAMA`, `OPEN_AI`) and `model`.
Missing provider or model values are labelled `unknown`. The in-memory snapshots served by
the inspection API and the Micrometer counters are fed from the same `record()` call.

## Implementation Notes

- **Thread safety**: all counters use `AtomicLong`; no synchronisation blocks required.
- **Null safety**: `null` token counts from providers (common with Ollama streaming) are
  treated as zero — the request is still counted.
- **No performance impact**: the `ThreadLocal` set/clear adds nanoseconds per LLM call;
  the `AtomicLong.addAndGet` path is lock-free.
- **Context isolation**: `ThreadLocal.remove()` is always called in `finally` — no leakage
  across virtual threads or thread-pool reuse.

## Files Added

- `src/main/java/io/github/rigazilla/memory/cognition/token/TokenUsage.java`
- `src/main/java/io/github/rigazilla/memory/cognition/token/TokenTracker.java`
- `src/main/java/io/github/rigazilla/memory/cognition/token/TokenTrackingContext.java`
- `src/main/java/io/github/rigazilla/memory/cognition/token/TokenUsageListener.java`
- `src/test/java/io/github/rigazilla/memory/cognition/token/TokenUsageTest.java`
- `src/test/java/io/github/rigazilla/memory/cognition/token/TokenTrackerTest.java`
- `src/test/java/io/github/rigazilla/memory/cognition/token/TokenTrackingContextTest.java`
- `src/test/java/io/github/rigazilla/memory/cognition/token/TokenUsageListenerTest.java`

## Files Modified

Follow-up (process totals, Micrometer, remaining processes):

- `TokenTracker` — process-level aggregate, `processSnapshot()`, Micrometer counters
- `TokenUsageListener` — pass provider and model to the tracker
- `TokenTrackingContext` — add `call(...)` helper
- `ContradictionOnInsertService`, `ContradictionResolutionService`, `LlmBasedConsolidationStrategy`
  — wrap LLM calls with `TokenTrackingContext.call`
- `ContradictionResolutionProcess`, `ProfileContextConsolidationProcess` — `tokenUsage` per
  resource and per process in `inspect()`
- `DurableMemoryExtractionProcess`, `MetadataEnrichmentProcess` — process-level `tokenUsage`

Initial implementation:

- `src/main/java/io/github/rigazilla/memory/cognition/queue/JobProcessor.java`
  — inject `TokenTracker`; wrap extractor/verifier calls with `TokenTrackingContext`
- `src/main/java/io/github/rigazilla/memory/cognition/metadata/MetadataEnrichmentService.java`
  — inject `TokenTracker`; wrap extractor call with `TokenTrackingContext`
- `src/main/java/io/github/rigazilla/memory/cognition/process/DurableMemoryExtractionProcess.java`
  — inject `TokenTracker`; change `resourceInfo` map type to `Map<String, Object>`;
  add `tokenUsage` snapshot per LLM resource in `inspect()`
- `src/main/java/io/github/rigazilla/memory/cognition/process/MetadataEnrichmentProcess.java`
  — inject `TokenTracker`; change `resourceInfo` map type to `Map<String, Object>`;
  add `tokenUsage` snapshot per LLM resource in `inspect()`
- `src/test/java/io/github/rigazilla/memory/cognition/process/DurableMemoryExtractionProcessTest.java`
  — add `inspectResourceTypesContainTokenUsageForExtractorAndVerifier()`
- `src/test/java/io/github/rigazilla/memory/cognition/process/MetadataEnrichmentProcessTest.java`
  — inject `new TokenTracker()` into process; add `inspectExtractorResourceContainsTokenUsage()`

## Validation

```
./mvnw -B test checkstyle:check spotbugs:check
```

- Tests run: 393, Failures: 0, Errors: 0, Skipped: 0
- Checkstyle violations: 0
- SpotBugs findings: 0

## Out of Scope (Future Phases)

- Per-conversation and per-user token tracking
- Historical persistence and time-series queries
- Cost estimation based on provider pricing
- Token threshold alerting
