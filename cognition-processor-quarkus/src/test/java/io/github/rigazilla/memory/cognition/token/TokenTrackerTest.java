package io.github.rigazilla.memory.cognition.token;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TokenTracker}.
 *
 * <p>Pure JUnit 5 — no CDI container required.
 * {@link TokenTracker} is instantiated directly with a {@link SimpleMeterRegistry}
 * wired in to mirror the CDI contract (the container always satisfies
 * {@code @Inject MeterRegistry} in production).
 */
class TokenTrackerTest {

    private TokenTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new TokenTracker();
        tracker.meterRegistry = new SimpleMeterRegistry();
    }

    // -------------------------------------------------------------------------
    // snapshot() before any record
    // -------------------------------------------------------------------------

    @Test
    void snapshotBeforeAnyRecordReturnsZeros() {
        TokenUsage.Snapshot snap = tracker.snapshot("proc", "res");

        assertThat(snap).isNotNull();
        assertThat(snap.inputTokens()).isZero();
        assertThat(snap.outputTokens()).isZero();
        assertThat(snap.totalTokens()).isZero();
        assertThat(snap.requestCount()).isZero();
        assertThat(snap.averageTokensPerRequest()).isZero();
    }

    // -------------------------------------------------------------------------
    // record() + snapshot()
    // -------------------------------------------------------------------------

    @Test
    void recordedTokensAppearInSnapshot() {
        tracker.record("proc-a", "extractor", 100, 50);

        TokenUsage.Snapshot snap = tracker.snapshot("proc-a", "extractor");

        assertThat(snap.inputTokens()).isEqualTo(100);
        assertThat(snap.outputTokens()).isEqualTo(50);
        assertThat(snap.totalTokens()).isEqualTo(150);
        assertThat(snap.requestCount()).isEqualTo(1);
    }

    @Test
    void multipleRecordsAccumulate() {
        tracker.record("proc-a", "extractor", 100, 50);
        tracker.record("proc-a", "extractor", 200, 80);

        TokenUsage.Snapshot snap = tracker.snapshot("proc-a", "extractor");

        assertThat(snap.inputTokens()).isEqualTo(300);
        assertThat(snap.outputTokens()).isEqualTo(130);
        assertThat(snap.totalTokens()).isEqualTo(430);
        assertThat(snap.requestCount()).isEqualTo(2);
        assertThat(snap.averageTokensPerRequest()).isEqualTo(215);
    }

    // -------------------------------------------------------------------------
    // Different resources are tracked independently
    // -------------------------------------------------------------------------

    @Test
    void differentResourcesAreIndependent() {
        tracker.record("proc-a", "extractor", 100, 50);
        tracker.record("proc-a", "verifier", 30, 10);

        TokenUsage.Snapshot extractor = tracker.snapshot("proc-a", "extractor");
        TokenUsage.Snapshot verifier = tracker.snapshot("proc-a", "verifier");

        assertThat(extractor.totalTokens()).isEqualTo(150);
        assertThat(verifier.totalTokens()).isEqualTo(40);
    }

    // -------------------------------------------------------------------------
    // Different processes are tracked independently
    // -------------------------------------------------------------------------

    @Test
    void differentProcessesAreIndependent() {
        tracker.record("proc-a", "extractor", 100, 50);
        tracker.record("proc-b", "extractor", 200, 80);

        TokenUsage.Snapshot a = tracker.snapshot("proc-a", "extractor");
        TokenUsage.Snapshot b = tracker.snapshot("proc-b", "extractor");

        assertThat(a.totalTokens()).isEqualTo(150);
        assertThat(b.totalTokens()).isEqualTo(280);
    }

    // -------------------------------------------------------------------------
    // Null token counts are tolerated
    // -------------------------------------------------------------------------

    @Test
    void recordWithNullValuesDoesNotThrow() {
        tracker.record("proc", "res", null, null);

        TokenUsage.Snapshot snap = tracker.snapshot("proc", "res");

        assertThat(snap.totalTokens()).isZero();
        assertThat(snap.requestCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Process-level aggregation
    // -------------------------------------------------------------------------

    @Test
    void processSnapshotBeforeAnyRecordReturnsZeros() {
        TokenUsage.Snapshot snap = tracker.processSnapshot("proc");

        assertThat(snap.totalTokens()).isZero();
        assertThat(snap.requestCount()).isZero();
    }

    @Test
    void processSnapshotSumsAllResourcesOfTheProcess() {
        tracker.record("proc-a", "extractor", 100, 50);
        tracker.record("proc-a", "verifier", 20, 10);
        tracker.record("proc-b", "extractor", 999, 1);

        TokenUsage.Snapshot snap = tracker.processSnapshot("proc-a");

        assertThat(snap.inputTokens()).isEqualTo(120);
        assertThat(snap.outputTokens()).isEqualTo(60);
        assertThat(snap.totalTokens()).isEqualTo(180);
        assertThat(snap.requestCount()).isEqualTo(2);
        assertThat(snap.averageTokensPerRequest()).isEqualTo(90);
    }

    // -------------------------------------------------------------------------
    // Micrometer / Prometheus counters
    // -------------------------------------------------------------------------

    @Test
    void recordPublishesLabelledMicrometerCounters() {
        SimpleMeterRegistry registry = (SimpleMeterRegistry) tracker.meterRegistry;

        tracker.record("proc", "extractor", "OLLAMA", "llama3.2", 100, 50);
        tracker.record("proc", "extractor", "OLLAMA", "llama3.2", 10, 5);

        assertThat(counter(registry, "llm.tokens.input", "OLLAMA", "llama3.2")).isEqualTo(110);
        assertThat(counter(registry, "llm.tokens.output", "OLLAMA", "llama3.2")).isEqualTo(55);
        assertThat(counter(registry, "llm.tokens", "OLLAMA", "llama3.2")).isEqualTo(165);
        assertThat(counter(registry, "llm.requests", "OLLAMA", "llama3.2")).isEqualTo(2);
    }

    @Test
    void missingProviderAndModelAreLabelledUnknown() {
        SimpleMeterRegistry registry = (SimpleMeterRegistry) tracker.meterRegistry;

        tracker.record("proc", "extractor", 7, 3);

        assertThat(counter(registry, "llm.tokens", "unknown", "unknown")).isEqualTo(10);
    }

    @Test
    void differentModelsGetSeparateMicrometerSeries() {
        SimpleMeterRegistry registry = (SimpleMeterRegistry) tracker.meterRegistry;

        tracker.record("proc", "extractor", "OLLAMA", "llama3.2", 100, 0);
        tracker.record("proc", "extractor", "OPEN_AI", "gpt-4o", 5, 0);

        assertThat(counter(registry, "llm.tokens.input", "OLLAMA", "llama3.2")).isEqualTo(100);
        assertThat(counter(registry, "llm.tokens.input", "OPEN_AI", "gpt-4o")).isEqualTo(5);
    }

    private static double counter(SimpleMeterRegistry registry, String name, String provider, String model) {
        return registry.get(name)
                .tag("process", "proc")
                .tag("resource", "extractor")
                .tag("provider", provider)
                .tag("model", model)
                .counter()
                .count();
    }
}
