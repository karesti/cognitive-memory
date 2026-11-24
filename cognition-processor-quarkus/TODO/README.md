# TODO: Work Items

This directory holds AI-friendly task documents for work that is not finished. Completed work is documented in [`../DONE/`](../DONE/) (numbered `NNN-*.md`).

> **Maintenance rule**: when an item is finished, write a `DONE/NNN-*.md` design note and delete (or shrink) the TODO file, then update the table below. See "Progress tracking" in [`../AGENTS.md`](../AGENTS.md).
>
> This index was re-audited against the code. Statuses marked *verified* were checked in the source; *needs review* means the file still describes the old situation and a maintainer should confirm before deleting or rewriting it.

## Open items

| File | Topic | Status |
|---|---|---|
| [`documentation-improvement-plan.md`](documentation-improvement-plan.md) | Docs gap analysis, tutorial, PRINCE context, drift prevention | P0 done; P1 (new docs) and P2 (automation) open. Start here for documentation work. |
| [`retry-logic.md`](retry-logic.md) | Job-level retry / dead-letter queue | **Partly done.** Individual LLM calls are retried with backoff (`LlmRetryHelper`, `cognition.llm.retry.*`, *verified*). Failed jobs are still logged and dropped in `JobProcessor` (*verified*). Rewrite the file to cover only job-level retry. |
| [`profile-context-snapshots.md`](profile-context-snapshots.md) | Profile snapshots | **Partly done.** Manual consolidation exists (`/api/consolidate/{userId}`, `profile-context-consolidation`). Scheduled/nightly run does not (*verified*: process has no `start`/`enable`). |
| [`event-stream-invalidation.md`](event-stream-invalidation.md) | Handling the `invalidate` stream event after long downtime | Open, not re-verified. |
| [`llm-empty-responses.md`](llm-empty-responses.md) | Empty extraction candidates from small models | Open, not re-verified. |
| [`prompt-example-contamination.md`](prompt-example-contamination.md) | Prompt examples leaking into extracted memories | Open, not re-verified. |
| [`configuration-improvements.md`](configuration-improvements.md) | LangChain4j named-model config warnings | Needs review: config was refactored to `@ConfigMapping` since this was written. |
| [`ci-coverage-enforcement.md`](ci-coverage-enforcement.md) | Coverage gate in CI | Needs review: CI posts a coverage comment with a 35% overall minimum, and `pom.xml` defines a 15% / 10% gate at `verify` that CI does not run (*verified*). Decide which one should be the enforced gate. |
| [`memory-service-quarkus-extension.md`](memory-service-quarkus-extension.md) | Research: memory-service Quarkus extension | Open research note. |

## Likely obsolete (needs review, then delete or move to DONE)

| File | Why |
|---|---|
| [`consolidation.md`](consolidation.md) | `ConsolidationService`, `DuplicateDetector` and `MemoryMerger` exist and `JobProcessor` uses `ConsolidationService` (*verified*). The file still says consolidation is deferred. |
| [`testing.md`](testing.md), [`unit-test-framework-setup.md`](unit-test-framework-setup.md) | The file says there are no automated tests; `src/test` contains about 45 test classes and CI runs them. See [`../AI-TEST.md`](../AI-TEST.md). |
| [`integration-test-framework-setup.md`](integration-test-framework-setup.md) | `maven-failsafe-plugin` is configured, but no `*IT` classes were found (*verified*). Decide whether this is still wanted. |
| [`github-ci-workflow-setup.md`](github-ci-workflow-setup.md) | CI/CD workflows exist under `.github/workflows/` (see [`../../.github/SETUP.md`](../../.github/SETUP.md)). |
| [`token-tracking-implementation.md`](token-tracking-implementation.md) | Implemented; superseded by [`../DONE/026-token-tracking-implementation.md`](../DONE/026-token-tracking-implementation.md). |
| [`session-notes.md`](session-notes.md) | Dated working notes (2026-05-22/23), not a task. Move to DONE or delete. |

## Not implemented anywhere yet

- Topic summary extraction
- Cache-only notes (bridge, topic) and prompt caching optimization
- Scheduled (nightly) profile consolidation

## Where to look for what exists

- Feature design notes: [`../DONE/`](../DONE/)
- Current processes and architecture: [`../AGENTS.md`](../AGENTS.md)
- Running and configuring: [`../README.md`](../README.md)
- Hands-on walkthrough: [`../docs/testing-guide.md`](../docs/testing-guide.md)
