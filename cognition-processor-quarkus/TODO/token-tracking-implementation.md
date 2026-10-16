# Token Tracking Implementation

## Overview

Implement comprehensive token usage tracking for LLM-based cognitive processes to enable cost monitoring, performance optimization, debugging, and capacity planning.

## Primary Goals

1. **Cost Monitoring** - Track spending on paid LLM APIs (OpenAI, Anthropic, etc.)
2. **Performance Optimization** - Identify which processes/prompts consume most tokens
3. **Debugging** - Detect anomalies and unexpectedly large token consumption
4. **Capacity Planning** - Predict infrastructure needs as usage scales

**Prometheus Compatibility**

To enable future Prometheus integration without refactoring, metrics must follow Prometheus naming conventions and support dimensional labels:

- **Naming Convention**: Use snake_case with `_total` suffix for counters
  - `llm_tokens_input_total` (instead of `inputTokens`)
  - `llm_tokens_output_total` (instead of `outputTokens`)
  - `llm_tokens_total` (instead of `totalTokens`)
  - `llm_requests_total` (instead of `requestCount`)

- **Labels/Dimensions**: Structure data to support these labels for metric filtering and aggregation
  - `process` - Process ID (e.g., durable-memory-extraction, metadata-enrichment)
  - `resource` - Resource name (e.g., extractor, verifier)
  - `provider` - LLM provider (e.g., ollama, openai)
  - `model` - Model name (e.g., llama3.2, gpt-4)

- **Metric Types**: Use appropriate Prometheus metric types
  - Token counts and request counts: **Counter** (monotonically increasing)
  - Average tokens per request: **Gauge** (computed value)

This approach allows seamless Prometheus integration in future phases via Quarkus Micrometer without requiring metric name changes or API response format modifications.


## Phase 1: Process & Resource Level Tracking (Initial Implementation)

### Scope

**Per Process Tracking**
- Total token usage for each cognitive process
- Examples: `durable-memory-extraction`, `metadata-enrichment`
- Aggregated view of all LLM resources within that process

**Per Resource Tracking**
- Separate counters for each LLM resource within a process
- Examples: `extractor`, `verifier` within durable-memory-extraction
- Allows identifying which specific LLM component consumes most tokens

### Metrics to Track (Proposal)

**Basic Metrics**
- `inputTokens` - Tokens in prompts sent to LLM
- `outputTokens` - Tokens in LLM responses
- `totalTokens` - Sum of input + output
- `requestCount` - Number of LLM API calls
- `averageTokensPerRequest` - Total tokens / request count (computed)

### Success Criteria (Proposal)

Phase 1 is implemented, see `../DONE/026-token-tracking-implementation.md`.

- [x] Each cognitive process exposes token metrics via `inspect()` (per resource and per process)
- [x] Separate counters for each LLM resource (extractor, verifier)
- [x] Metrics include: input/output/total tokens, request count, average
- [x] Thread-safe implementation (use atomic counters)
- [ ] Works with both Ollama and OpenAI providers (listener is provider-agnostic; not yet verified against live providers)
- [ ] No performance degradation (not measured)
- [x] Token metrics appear in process inspection API response
- [x] Prometheus counters with `process`, `resource`, `provider`, `model` labels on `/q/metrics`

### Expected API Response Format (Proposal)

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

## Future Phases

### Per-Conversation Tracking

**Scope**
- Link token usage to specific conversation IDs being processed
- Enable cost attribution per conversation
- Useful for identifying expensive conversations

**Requirements**
- Track conversation ID with each LLM call
- Aggregate tokens per conversation
- Expose via conversation-specific API or query parameter

### Per-User and Per-Agent Tracking

**Per User Tracking**
- Aggregate token usage by conversation owner/user
- Enable user-level cost analysis and quotas
- Support multi-tenant cost allocation

**Per Agent Tracking**
- Track tokens consumed by different agent types
- Useful when multiple agents interact with the system
- Enable agent-specific optimization

## Far Future Phases

### Historical Data and Analytics

**Persistence**
- Store token metrics in time-series database (Prometheus, InfluxDB)
- Support time-range queries (last hour, day, week, month)
- Export capabilities for external analysis tools

**Extended Metrics**
- Last request tokens and timestamp
- Peak token usage (highest single request)
- Token usage trends over time
- Estimated cost based on provider pricing

**Aggregation Views**
- System-wide totals across all processes
- Per-provider totals (Ollama vs OpenAI)
- Per-model totals (llama3.2 vs gpt-4)

### Cost Management Features

**Cost Estimation**
- Configure cost-per-token for different providers/models
- Calculate estimated costs in real-time
- Display cost alongside token metrics

**Alerting**
- Set thresholds for unusual token consumption
- Alert on anomalies or budget overruns
- Integration with monitoring systems

## Out of Scope (For Now)

- Real-time alerting on token thresholds
- Automatic cost optimization recommendations
- Token usage prediction/forecasting
- Integration with billing systems
- Multi-region token tracking

## Technical Notes (Proposal)

**Testing Requirements**
- Unit tests for token counter thread safety
- Integration tests with mock LLM responses
- Verify token counts match expected values
- Test with both Ollama and OpenAI providers

**Documentation Updates**
- Update process inspection API documentation
- Add token tracking section to README
- Document configuration options
- Provide examples of querying token metrics

## Related Issues

- None yet (this is the initial requirements document)

## References

- LangChain4j documentation on listeners and callbacks
- OpenAI API token usage documentation
- Ollama API response format documentation
