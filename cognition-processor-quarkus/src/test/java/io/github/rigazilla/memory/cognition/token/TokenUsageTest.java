package io.github.rigazilla.memory.cognition.token;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TokenUsage}.
 *
 * <p>Pure JUnit 5 — no CDI container required.
 */
class TokenUsageTest {

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    void allCountersStartAtZero() {
        TokenUsage usage = new TokenUsage();
        assertThat(usage.getInputTokensTotal()).isZero();
        assertThat(usage.getOutputTokensTotal()).isZero();
        assertThat(usage.getTokensTotal()).isZero();
        assertThat(usage.getRequestsTotal()).isZero();
        assertThat(usage.getAverageTokensPerRequest()).isZero();
    }

    // -------------------------------------------------------------------------
    // Single record
    // -------------------------------------------------------------------------

    @Test
    void recordIncrementsAllCounters() {
        TokenUsage usage = new TokenUsage();
        usage.record(100, 50);

        assertThat(usage.getInputTokensTotal()).isEqualTo(100);
        assertThat(usage.getOutputTokensTotal()).isEqualTo(50);
        assertThat(usage.getTokensTotal()).isEqualTo(150);
        assertThat(usage.getRequestsTotal()).isEqualTo(1);
    }

    @Test
    void averageTokensPerRequestAfterOneCall() {
        TokenUsage usage = new TokenUsage();
        usage.record(90, 30);

        assertThat(usage.getAverageTokensPerRequest()).isEqualTo(120);
    }

    @Test
    void averageTokensPerRequestIsZeroBeforeAnyCall() {
        TokenUsage usage = new TokenUsage();
        assertThat(usage.getAverageTokensPerRequest()).isZero();
    }

    // -------------------------------------------------------------------------
    // Null inputs treated as zero
    // -------------------------------------------------------------------------

    @Test
    void recordWithNullInputTreatsAsZero() {
        TokenUsage usage = new TokenUsage();
        usage.record(null, 40);

        assertThat(usage.getInputTokensTotal()).isZero();
        assertThat(usage.getOutputTokensTotal()).isEqualTo(40);
        assertThat(usage.getTokensTotal()).isEqualTo(40);
        assertThat(usage.getRequestsTotal()).isEqualTo(1);
    }

    @Test
    void recordWithNullOutputTreatsAsZero() {
        TokenUsage usage = new TokenUsage();
        usage.record(60, null);

        assertThat(usage.getInputTokensTotal()).isEqualTo(60);
        assertThat(usage.getOutputTokensTotal()).isZero();
        assertThat(usage.getTokensTotal()).isEqualTo(60);
    }

    @Test
    void recordWithBothNullStillIncrementsRequestCount() {
        TokenUsage usage = new TokenUsage();
        usage.record(null, null);

        assertThat(usage.getTokensTotal()).isZero();
        assertThat(usage.getRequestsTotal()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Multiple records accumulate
    // -------------------------------------------------------------------------

    @Test
    void multipleRecordsAccumulate() {
        TokenUsage usage = new TokenUsage();
        usage.record(100, 50);
        usage.record(200, 80);

        assertThat(usage.getInputTokensTotal()).isEqualTo(300);
        assertThat(usage.getOutputTokensTotal()).isEqualTo(130);
        assertThat(usage.getTokensTotal()).isEqualTo(430);
        assertThat(usage.getRequestsTotal()).isEqualTo(2);
        assertThat(usage.getAverageTokensPerRequest()).isEqualTo(215);
    }

    // -------------------------------------------------------------------------
    // Snapshot
    // -------------------------------------------------------------------------

    @Test
    void snapshotReflectsCurrentCounters() {
        TokenUsage usage = new TokenUsage();
        usage.record(100, 50);

        TokenUsage.Snapshot snap = usage.snapshot();

        assertThat(snap.inputTokens()).isEqualTo(100);
        assertThat(snap.outputTokens()).isEqualTo(50);
        assertThat(snap.totalTokens()).isEqualTo(150);
        assertThat(snap.requestCount()).isEqualTo(1);
        assertThat(snap.averageTokensPerRequest()).isEqualTo(150);
    }

    @Test
    void snapshotIsZeroedBeforeAnyRecord() {
        TokenUsage usage = new TokenUsage();
        TokenUsage.Snapshot snap = usage.snapshot();

        assertThat(snap.inputTokens()).isZero();
        assertThat(snap.outputTokens()).isZero();
        assertThat(snap.totalTokens()).isZero();
        assertThat(snap.requestCount()).isZero();
        assertThat(snap.averageTokensPerRequest()).isZero();
    }

    // -------------------------------------------------------------------------
    // Thread safety
    // -------------------------------------------------------------------------

    @Test
    void concurrentRecordsAreThreadSafe() throws InterruptedException {
        TokenUsage usage = new TokenUsage();
        int threads = 10;
        int recordsPerThread = 1000;
        CountDownLatch latch = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                for (int j = 0; j < recordsPerThread; j++) {
                    usage.record(1, 1);
                }
                latch.countDown();
            });
        }
        tasks.forEach(executor::execute);
        latch.await();
        executor.shutdown();

        long expectedRequests = (long) threads * recordsPerThread;
        assertThat(usage.getRequestsTotal())
                .as("request count must be exact under concurrent writes")
                .isEqualTo(expectedRequests);
        assertThat(usage.getInputTokensTotal())
                .as("input tokens must be exact under concurrent writes")
                .isEqualTo(expectedRequests);
        assertThat(usage.getTokensTotal())
                .as("total tokens must be exact under concurrent writes")
                .isEqualTo(expectedRequests * 2);
    }
}
