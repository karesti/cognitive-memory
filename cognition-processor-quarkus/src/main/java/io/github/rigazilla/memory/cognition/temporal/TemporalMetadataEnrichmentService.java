package io.github.rigazilla.memory.cognition.temporal;

import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesResponse;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.AdminPutMemoryRequest;
import io.github.rigazilla.memory.cognition.config.MemoryServiceConfig;
import io.github.rigazilla.memory.cognition.grpc.GrpcChannelFactory;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backfills {@code observed_at} and {@code effective_at} into cognition memories
 * that were written before temporal metadata was introduced.
 *
 * <p>For each memory whose value struct lacks {@code observed_at}, the service
 * derives the timestamp from the memory's own {@code created_at} field
 * (the time the memory-service row was written — the best proxy available for
 * historical rows) and re-writes the memory with the two temporal fields added.
 *
 * <p>Memories that already carry {@code observed_at} are skipped without touching
 * the memory-service.
 */
@ApplicationScoped
public class TemporalMetadataEnrichmentService {

    private static final Logger LOG = Logger.getLogger(TemporalMetadataEnrichmentService.class);
    /** Namespace prefix shared by all memories written by the cognition processor. */
    private static final String COGNITION_NS_USER = "user";

    /** Page size used when listing memories for backfill. */
    private static final int PAGE_SIZE = 100;

    @Inject
    MemoryServiceConfig memoryService;

    ManagedChannel channel;
    AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub memoriesStub;

    // Progress counters — package-private so tests can seed stale values via .set()
    final AtomicBoolean running = new AtomicBoolean(false);
    final AtomicLong scanned = new AtomicLong(0);
    final AtomicLong enriched = new AtomicLong(0);
    final AtomicLong skipped = new AtomicLong(0);
    final AtomicLong errors = new AtomicLong(0);
    /** Revision-conflict rejections (ABORTED) — distinct from genuine errors. */
    final AtomicLong conflicts = new AtomicLong(0);

    // -------------------------------------------------------------------------
    // Public read accessors — used by TemporalMetadataEnrichmentProcess.inspect()
    // -------------------------------------------------------------------------

    /** Returns {@code true} if a backfill run is currently in progress. */
    public boolean isRunning() { return running.get(); }

    /** Returns the total number of memories scanned in the current (or last) run. */
    public long getScanned() { return scanned.get(); }

    /** Returns the number of memories enriched with temporal metadata. */
    public long getEnriched() { return enriched.get(); }

    /** Returns the number of memories skipped (already enriched or no created_at). */
    public long getSkipped() { return skipped.get(); }

    /** Returns the number of memories that could not be written due to a non-conflict error. */
    public long getErrors() { return errors.get(); }

    /**
     * Returns the number of revision-conflict rejections (gRPC ABORTED) encountered.
     * These are self-healing: the next run will see the already-enriched memory and skip it.
     */
    public long getConflicts() { return conflicts.get(); }

    @PostConstruct
    void init() {
        LOG.infof("Initializing TemporalMetadataEnrichmentService: %s:%d",
                memoryService.grpc().host(), memoryService.grpc().port());
        channel = GrpcChannelFactory.create(
                memoryService.grpc().host(), memoryService.grpc().port(),
                memoryService.apiKey(), memoryService.clientId());
        memoriesStub = AdminMemoriesServiceGrpc.newBlockingStub(channel);
        LOG.info("TemporalMetadataEnrichmentService initialized successfully");
    }

    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down TemporalMetadataEnrichmentService gRPC channel");
            channel.shutdown();
        }
    }

    /**
     * Run the backfill across all memories in the cognition namespace.
     * This method is blocking and intended to be called on a virtual thread
     * by {@link TemporalMetadataEnrichmentProcess#start()}.
     *
     * <p>If the backfill is already running this call returns immediately.
     */
    public void runBackfill() {
        if (!running.compareAndSet(false, true)) {
            LOG.info("Temporal metadata backfill already running, skipping duplicate start");
            return;
        }

        scanned.set(0);
        enriched.set(0);
        skipped.set(0);
        errors.set(0);
        conflicts.set(0);

        try {
            LOG.info("Starting temporal metadata backfill for cognition memories");
            backfillPage(null);
            LOG.infof("Temporal metadata backfill complete: scanned=%d, enriched=%d, "
                    + "skipped=%d, conflicts=%d, errors=%d",
                scanned.get(), enriched.get(), skipped.get(), conflicts.get(), errors.get());
        } finally {
            running.set(false);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Pages through all cognition memories and enriches each one.
     * Iterates rather than recurses to avoid stack overflow on large datasets.
     */
    private void backfillPage(String initialCursor) {
        String cursor = initialCursor;
        do {
            AdminListMemoriesRequest.Builder reqBuilder = AdminListMemoriesRequest.newBuilder()
                .addNamespacePrefix(COGNITION_NS_USER)
                .setLimit(PAGE_SIZE);
            if (cursor != null) {
                reqBuilder.setAfterCursor(cursor);
            }

            AdminListMemoriesResponse response = memoriesStub.listMemories(reqBuilder.build());

            for (AdminMemoryItem item : response.getItemsList()) {
                enrichItem(item);
            }

            cursor = response.hasAfterCursor() ? response.getAfterCursor() : null;
        } while (cursor != null);
    }

    /**
     * Enriches a single memory item if it does not already have {@code observed_at}.
     */
    private void enrichItem(AdminMemoryItem item) {
        scanned.incrementAndGet();

        // Resolve observed_at BEFORE entering the try/catch so a missing created_at is
        // handled explicitly (warn + skip) and never conflated with genuine errors.
        Optional<String> observedAtOpt = toIso8601(item.hasCreatedAt() ? item.getCreatedAt() : null);
        if (observedAtOpt.isEmpty()) {
            LOG.warnf("Memory %s has no created_at; skipping",
                bytesToHex(item.getId().toByteArray()));
            skipped.incrementAndGet();
            return;
        }
        String observedAt = observedAtOpt.get();

        try {
            Struct currentValue = item.getValue();

            // Skip if observed_at is already present and non-empty
            if (currentValue.containsFields("observed_at")
                    && !currentValue.getFieldsOrThrow("observed_at").getStringValue().isBlank()) {
                LOG.debugf("Skipping memory %s — observed_at already set",
                    bytesToHex(item.getId().toByteArray()));
                skipped.incrementAndGet();
                return;
            }

            // Build updated value struct — carry all existing fields forward, add temporal ones.
            // expires_at is set to null (not yet supported; placeholder for future TTL).
            Struct.Builder updatedValue = currentValue.toBuilder()
                .putFields("observed_at", Value.newBuilder().setStringValue(observedAt).build())
                .putFields("effective_at", Value.newBuilder().setStringValue(observedAt).build())
                .putFields("expires_at", Value.newBuilder().setNullValue(
                    NullValue.NULL_VALUE).build());

            // Set expected_revision so the server can reject concurrent writes on this row
            // (avoids creating a stale extra revision if the memory was already enriched in a
            // parallel run). On ABORTED the per-item catch block increments the errors counter.
            AdminPutMemoryRequest request = AdminPutMemoryRequest.newBuilder()
                .addAllNamespace(item.getNamespaceList())
                .setKey(item.getKey())
                .setValue(updatedValue.build())
                .setExpectedRevision(item.getRevision())
                .putIndex("observed_at", observedAt)
                .putIndex("effective_at", observedAt)
                .build();

            memoriesStub.putMemory(request);
            enriched.incrementAndGet();
            LOG.debugf("Enriched memory key=%s with observedAt=%s", item.getKey(), observedAt);

        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.ABORTED) {
                // Revision conflict: another concurrent run already enriched this memory.
                // This is expected and self-healing — next run will see observed_at present
                // and skip cleanly. Log at DEBUG, not ERROR, to keep error counts meaningful.
                conflicts.incrementAndGet();
                LOG.debugf("Revision conflict enriching key=%s; will be skipped on next run",
                    item.getKey());
            } else {
                errors.incrementAndGet();
                LOG.errorf(e, "Failed to enrich memory key=%s", item.getKey());
            }
        } catch (Exception e) {
            errors.incrementAndGet();
            LOG.errorf(e, "Failed to enrich memory key=%s", item.getKey());
        }
    }

    /**
     * Converts a protobuf {@link Timestamp} to an ISO-8601 UTC string.
     *
     * <p>Output format: {@code yyyy-MM-dd'T'HH:mm:ss[.nnn...]'Z'} — identical to
     * {@code Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()).toString()}.
     * Returns {@link Optional#empty()} for {@code null} input; never throws,
     * never substitutes a default. The caller decides the fallback policy.
     */
    private static Optional<String> toIso8601(Timestamp ts) {
        if (ts == null) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()).toString());
    }

    /**
     * Returns a short hex prefix of {@code bytes} for log correlation messages.
     * Uses {@link java.util.HexFormat} (Java 17+) — no manual formatting needed.
     */
    private static String bytesToHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, 4)) + "...";
    }

}
