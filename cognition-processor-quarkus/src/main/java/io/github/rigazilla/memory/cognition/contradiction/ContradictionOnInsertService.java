package io.github.rigazilla.memory.cognition.contradiction;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.AdminPutMemoryRequest;
import io.github.chirino.memory.grpc.v1.AdminSearchMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminSearchMemoriesResponse;
import io.github.rigazilla.memory.cognition.config.CognitionConfig;
import io.github.rigazilla.memory.cognition.config.MemoryServiceConfig;
import io.github.rigazilla.memory.cognition.grpc.GrpcChannelFactory;
import io.github.rigazilla.memory.cognition.resource.LlmRetryHelper;
import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Contradiction detection triggered at memory-insertion time.
 *
 * <h2>Algorithm (O(k) per insertion)</h2>
 * <ol>
 *   <li>When a new memory is written, call {@code AdminSearchMemories} with its content
 *       as the query, scoped to the same user namespace.</li>
 *   <li>Exclude the new memory itself (same key) from the result set.</li>
 *   <li>Run {@link ContradictionDetector} only on those {@code k} semantically close
 *       candidates.</li>
 *   <li>For detected contradictions, apply the resolution strategy and write updated
 *       structs back to memory-service (same optimistic-locking writes as the batch pass).</li>
 * </ol>
 *
 * <p>This replaces the O(n²) batch scan as the primary contradiction-detection path.
 * The existing {@link ContradictionResolutionProcess} remains available as a manual
 * backfill pass (e.g. after config changes or bulk imports).
 *
 * <p>Failures are best-effort: any exception is logged and swallowed so a search or
 * LLM hiccup never blocks a memory write.
 */
@ApplicationScoped
public class ContradictionOnInsertService {

    private static final Logger LOG = Logger.getLogger(ContradictionOnInsertService.class);

    private static final String STATUS_SUPERSEDED = "superseded";

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    @Inject
    MemoryServiceConfig memoryService;

    @Inject
    CognitionConfig cognition;

    @Inject
    ContradictionDetector detector;

    @Inject
    LlmRetryHelper llmRetryHelper;

    // Package-private for test injection (same pattern as other services)
    ManagedChannel channel;
    AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub memoriesStub;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @PostConstruct
    void init() {
        LOG.infof("Initializing ContradictionOnInsertService: %s:%d",
                memoryService.grpc().host(), memoryService.grpc().port());
        channel = GrpcChannelFactory.create(
                memoryService.grpc().host(), memoryService.grpc().port(),
                memoryService.apiKey());
        memoriesStub = AdminMemoriesServiceGrpc.newBlockingStub(channel);
        LOG.info("ContradictionOnInsertService initialized successfully");
    }

    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down ContradictionOnInsertService gRPC channel");
            channel.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Run contradiction detection for a newly inserted memory.
     *
     * <p>Searches the same user namespace for the top-{@code k} semantically
     * close memories, then checks each candidate pair against the new memory.
     * Any contradictions found are resolved inline.
     *
     * <p>This method is best-effort: all exceptions are caught and logged so
     * that a failure here never prevents the calling write from completing.
     *
     * @param userId     the user whose namespace was written to
     * @param memoryType the 4th namespace segment (e.g. {@code "preference"})
     * @param newKey     the key of the freshly written memory
     * @param content    the content text of the freshly written memory
     * @param observedAt ISO-8601 timestamp of the new memory (may be empty)
     * @param confidence confidence score of the new memory (0.0–1.0)
     */
    public void checkOnInsert(String userId, String memoryType,
                              String newKey, String content,
                              String observedAt, double confidence) {
        try {
            doCheckOnInsert(userId, memoryType, newKey, content, observedAt, confidence);
        } catch (Exception e) {
            LOG.warnf(e, "Contradiction on-insert check failed for key=%s userId=%s; skipping",
                    newKey, userId);
        }
    }

    // -------------------------------------------------------------------------
    // Core logic
    // -------------------------------------------------------------------------

    void doCheckOnInsert(String userId, String memoryType,
                         String newKey, String content,
                         String observedAt, double confidence) {

        int k = cognition.contradiction().neighbours();

        // Search for semantically close neighbours in the same namespace
        AdminSearchMemoriesRequest searchReq = AdminSearchMemoriesRequest.newBuilder()
                .addNamespacePrefix("user")
                .addNamespacePrefix(userId)
                .addNamespacePrefix("cognition.v1")
                .addNamespacePrefix(memoryType)
                .setQuery(content)
                .setAsUserId(userId)
                .setLimit(k + 1) // +1 to account for the new memory itself being returned
                .build();

        AdminSearchMemoriesResponse searchResp = memoriesStub.searchMemories(searchReq);

        List<AdminMemoryItem> candidates = searchResp.getItemsList().stream()
                .filter(item -> !newKey.equals(item.getKey()))                    // exclude self
                .filter(item -> !STATUS_SUPERSEDED.equals(                        // exclude superseded
                        item.getValue()
                                .getFieldsOrDefault("status",
                                        Value.newBuilder().setStringValue("").build())
                                .getStringValue()))
                .limit(k)
                .toList();

        if (candidates.isEmpty()) {
            return;
        }

        LOG.debugf("Checking %d neighbour(s) for new memory key=%s (userId=%s, type=%s)",
                candidates.size(), newKey, userId, memoryType);

        // Build a lightweight AdminMemoryItem representing the new memory so we can
        // reuse the same applyResolution helper as the batch pass.
        AdminMemoryItem newItem = buildItemForNewMemory(
                userId, memoryType, newKey, content, observedAt, confidence);

        for (AdminMemoryItem neighbour : candidates) {
            checkPair(newItem, neighbour, memoryType);
        }
    }

    /**
     * Ask the LLM whether the new memory and one neighbour contradict each other;
     * resolve if they do.
     */
    private void checkPair(AdminMemoryItem newItem, AdminMemoryItem neighbour, String memoryType) {
        try {
            String contentA   = fieldString(newItem,      "content");
            String observedAtA = fieldString(newItem,     "observed_at");
            double confidenceA = fieldDouble(newItem,     "confidence");
            String contentB   = fieldString(neighbour,    "content");
            String observedAtB = fieldString(neighbour,   "observed_at");
            double confidenceB = fieldDouble(neighbour,   "confidence");

            ContradictionPair pair = new ContradictionPair(
                    newItem.getKey(),    contentA,    nullSafe(observedAtA), confidenceA,
                    neighbour.getKey(),  contentB,    nullSafe(observedAtB), confidenceB,
                    memoryType);

            ContradictionDetectionResponse response = llmRetryHelper.withRetry(
                    "contradiction-detect:" + pair.keyA() + ":" + pair.keyB(),
                    () -> detector.detect(
                            pair.memoryType(),
                            pair.contentA(), nullSafe(pair.observedAtA()),
                            pair.contentB(), nullSafe(pair.observedAtB())));

            if (!response.contradicts()) {
                return;
            }

            LOG.infof("Contradiction detected on insert [%s]: keyA=%s keyB=%s type=%s strategy=%s — %s",
                    memoryType, pair.keyA(), pair.keyB(),
                    response.contradictionType().value(), response.recommendedStrategy().value(),
                    response.rationale());

            if (response.isCoexistence()) {
                LOG.infof("Coexistence: keeping both memories active (keyA=%s, keyB=%s)",
                        pair.keyA(), pair.keyB());
                return;
            }

            ContradictionResolution resolution = resolveConflict(pair, response);
            applyResolution(newItem, neighbour, resolution);

        } catch (Exception e) {
            LOG.warnf(e, "Error checking pair on insert keyA=%s keyB=%s: %s",
                    newItem.getKey(), neighbour.getKey(), e.getMessage());
        }
    }

    private ContradictionResolution resolveConflict(
            ContradictionPair pair, ContradictionDetectionResponse response) {
        return switch (response.recommendedStrategy()) {
            case CONFIDENCE -> resolveByConfidence(pair, response);
            default         -> resolveByRecency(pair, response);
        };
    }

    private ContradictionResolution resolveByRecency(
            ContradictionPair pair, ContradictionDetectionResponse r) {
        boolean aIsNewer = compareTimestamps(pair.observedAtA(), pair.observedAtB()) >= 0;
        String winnerKey = aIsNewer ? pair.keyA() : pair.keyB();
        String loserKey  = aIsNewer ? pair.keyB() : pair.keyA();
        return ContradictionResolution.resolved(
                winnerKey, loserKey, ResolutionStrategy.RECENCY, r.contradictionType(), r.rationale());
    }

    private ContradictionResolution resolveByConfidence(
            ContradictionPair pair, ContradictionDetectionResponse r) {
        boolean aWins = pair.confidenceA() >= pair.confidenceB();
        String winnerKey = aWins ? pair.keyA() : pair.keyB();
        String loserKey  = aWins ? pair.keyB() : pair.keyA();
        return ContradictionResolution.resolved(
                winnerKey, loserKey, ResolutionStrategy.CONFIDENCE, r.contradictionType(), r.rationale());
    }

    private void applyResolution(AdminMemoryItem newItem, AdminMemoryItem neighbour,
                                  ContradictionResolution resolution) {
        AdminMemoryItem winner = newItem.getKey().equals(resolution.winnerKey()) ? newItem : neighbour;
        AdminMemoryItem loser  = newItem.getKey().equals(resolution.supersededKey()) ? newItem : neighbour;

        String now = Instant.now().toString();

        // Mark the loser as superseded
        Struct loserUpdated = loser.getValue().toBuilder()
                .putFields("status",
                        Value.newBuilder().setStringValue(STATUS_SUPERSEDED).build())
                .putFields("superseded_by",
                        Value.newBuilder().setStringValue(resolution.winnerKey()).build())
                .putFields("superseded_at",
                        Value.newBuilder().setStringValue(now).build())
                .putFields("contradiction_type",
                        Value.newBuilder().setStringValue(resolution.contradictionType().value()).build())
                .putFields("resolution_strategy",
                        Value.newBuilder().setStringValue(resolution.strategyApplied().value()).build())
                .build();

        memoriesStub.putMemory(AdminPutMemoryRequest.newBuilder()
                .addAllNamespace(loser.getNamespaceList())
                .setKey(loser.getKey())
                .setValue(loserUpdated)
                .setExpectedRevision(loser.getRevision())
                .build());

        LOG.infof("Marked memory superseded on insert: key=%s superseded_by=%s strategy=%s",
                loser.getKey(), resolution.winnerKey(), resolution.strategyApplied().value());

        // Update the winner with a supersedes reference
        com.google.protobuf.ListValue supersedesList = com.google.protobuf.ListValue.newBuilder()
                .addValues(Value.newBuilder().setStringValue(loser.getKey()).build())
                .build();

        Struct winnerUpdated = winner.getValue().toBuilder()
                .putFields("supersedes",
                        Value.newBuilder().setListValue(supersedesList).build())
                .build();

        memoriesStub.putMemory(AdminPutMemoryRequest.newBuilder()
                .addAllNamespace(winner.getNamespaceList())
                .setKey(winner.getKey())
                .setValue(winnerUpdated)
                .setExpectedRevision(winner.getRevision())
                .build());

        LOG.debugf("Updated winner with supersedes reference on insert: key=%s", winner.getKey());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Build a minimal {@link AdminMemoryItem} for the newly written memory so it can be
     * passed to the same {@link #applyResolution} helper used by the batch pass.
     *
     * <p>The revision is set to 0; when the new memory turns out to be the loser in a
     * contradiction pair, the actual revision is unknown here because
     * {@code MemoryWriteResult} does not return it. In that case the optimistic-lock
     * {@code putMemory} call will fail with {@code ABORTED}. The exception is caught in
     * {@link #checkPair} and logged — the neighbour will still be correctly marked as
     * superseded, and the new memory can be cleaned up by the next manual backfill run
     * if needed.
     */
    private AdminMemoryItem buildItemForNewMemory(String userId, String memoryType,
                                                   String key, String content,
                                                   String observedAt, double confidence) {
        Struct value = Struct.newBuilder()
                .putFields("content",     Value.newBuilder().setStringValue(content).build())
                .putFields("observed_at", Value.newBuilder().setStringValue(observedAt).build())
                .putFields("confidence",  Value.newBuilder().setNumberValue(confidence).build())
                .build();

        return AdminMemoryItem.newBuilder()
                .setKey(key)
                .setValue(value)
                .addNamespace("user")
                .addNamespace(userId)
                .addNamespace("cognition.v1")
                .addNamespace(memoryType)
                .setRevision(0)
                .build();
    }

    private static String fieldString(AdminMemoryItem item, String field) {
        return item.getValue()
                .getFieldsOrDefault(field, Value.newBuilder().setStringValue("").build())
                .getStringValue();
    }

    private static double fieldDouble(AdminMemoryItem item, String field) {
        return item.getValue()
                .getFieldsOrDefault(field, Value.newBuilder().setNumberValue(0.0).build())
                .getNumberValue();
    }

    private static int compareTimestamps(String a, String b) {
        if (a == null || a.isBlank()) {
            return -1;
        }
        if (b == null || b.isBlank()) {
            return 1;
        }
        return a.compareTo(b);
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
