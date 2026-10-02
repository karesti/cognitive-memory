package io.github.rigazilla.memory.cognition.contradiction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Contradiction detection triggered at memory-insertion time.
 *
 * <h2>Algorithm (single batch LLM call per insertion)</h2>
 * <ol>
 *   <li>When a new memory is written, call {@code AdminSearchMemories} with its content
 *       as the query, scoped to the same user namespace.</li>
 *   <li>Exclude the new memory itself (same key) from the result set.</li>
 *   <li>Send all {@code k} semantically close candidates to the LLM in a <em>single</em>
 *       {@link ContradictionDetector#detectBatch} call instead of one call per candidate.</li>
 *   <li>For any candidate whose index is absent from the batch result (e.g. truncated by a
 *       smaller model), fall back to the single-pair {@link ContradictionDetector#detect}.</li>
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

        checkBatch(newItem, candidates, memoryType);
    }

    /**
     * Send all candidates to the LLM in a single batch call, then resolve any
     * contradictions found.
     *
     * <p>If the model returns fewer results than candidates (known truncation issue with
     * smaller models), each missing index is retried via the single-pair
     * {@link #checkPair} fallback so no candidate is silently skipped.
     */
    void checkBatch(AdminMemoryItem newItem, List<AdminMemoryItem> candidates, String memoryType) {
        String candidatesJson = buildCandidatesJson(candidates);

        String newContent    = fieldString(newItem, "content");
        String newObservedAt = nullSafe(fieldString(newItem, "observed_at"));

        List<ContradictionDetectionResponse> batchResults;
        try {
            ContradictionBatchResult batchResult = llmRetryHelper.withRetry(
                    "contradiction-detect-batch:" + newItem.getKey(),
                    () -> detector.detectBatch(memoryType, newContent, newObservedAt, candidatesJson));
            batchResults = (batchResult != null) ? batchResult.results() : null;
        } catch (Exception e) {
            LOG.warnf(e, "Batch contradiction detection failed for key=%s; falling back to per-pair",
                    newItem.getKey());
            // Full fallback: check each pair individually
            for (AdminMemoryItem neighbour : candidates) {
                checkPair(newItem, neighbour, memoryType);
            }
            return;
        }

        // Index batch results by their declared index field for O(1) lookup
        Map<Integer, ContradictionDetectionResponse> byIndex = new HashMap<>();
        if (batchResults != null) {
            for (ContradictionDetectionResponse r : batchResults) {
                byIndex.put(r.index(), r);
            }
        }

        for (int i = 0; i < candidates.size(); i++) {
            AdminMemoryItem neighbour = candidates.get(i);
            ContradictionDetectionResponse response = byIndex.get(i);

            if (response == null) {
                // The model truncated the array — fall back to single-pair for this candidate
                LOG.debugf(
                        "Batch result missing index=%d (key=%s); falling back to single-pair detect",
                        i, neighbour.getKey());
                checkPair(newItem, neighbour, memoryType);
                continue;
            }

            applyDetectionResponse(newItem, neighbour, memoryType, response);
        }
    }

    /**
     * Ask the LLM whether the new memory and one neighbour contradict each other;
     * resolve if they do.  Used as a fallback when the batch result is truncated.
     */
    private void checkPair(AdminMemoryItem newItem, AdminMemoryItem neighbour, String memoryType) {
        try {
            String contentA    = fieldString(newItem,   "content");
            String observedAtA = fieldString(newItem,   "observed_at");
            double confidenceA = fieldDouble(newItem,   "confidence");
            String contentB    = fieldString(neighbour, "content");
            String observedAtB = fieldString(neighbour, "observed_at");
            double confidenceB = fieldDouble(neighbour, "confidence");

            ContradictionPair pair = new ContradictionPair(
                    newItem.getKey(),   contentA,   nullSafe(observedAtA), confidenceA,
                    neighbour.getKey(), contentB,   nullSafe(observedAtB), confidenceB,
                    memoryType);

            ContradictionDetectionResponse response = llmRetryHelper.withRetry(
                    "contradiction-detect:" + pair.keyA() + ":" + pair.keyB(),
                    () -> detector.detect(
                            pair.memoryType(),
                            pair.contentA(), nullSafe(pair.observedAtA()),
                            pair.contentB(), nullSafe(pair.observedAtB())));

            applyDetectionResponse(newItem, neighbour, memoryType, response);

        } catch (Exception e) {
            LOG.warnf(e, "Error checking pair on insert keyA=%s keyB=%s: %s",
                    newItem.getKey(), neighbour.getKey(), e.getMessage());
        }
    }

    /**
     * Apply a single {@link ContradictionDetectionResponse}: log, skip coexistence,
     * or resolve the contradiction.
     */
    private void applyDetectionResponse(AdminMemoryItem newItem, AdminMemoryItem neighbour,
                                        String memoryType, ContradictionDetectionResponse response) {
        if (!response.contradicts()) {
            return;
        }

        LOG.infof("Contradiction detected on insert [%s]: keyA=%s keyB=%s type=%s strategy=%s — %s",
                memoryType, newItem.getKey(), neighbour.getKey(),
                response.contradictionType().value(), response.recommendedStrategy().value(),
                response.rationale());

        if (response.isCoexistence()) {
            LOG.infof("Coexistence: keeping both memories active (keyA=%s, keyB=%s)",
                    newItem.getKey(), neighbour.getKey());
            return;
        }

        String contentA    = fieldString(newItem,   "content");
        String observedAtA = fieldString(newItem,   "observed_at");
        double confidenceA = fieldDouble(newItem,   "confidence");
        String contentB    = fieldString(neighbour, "content");
        String observedAtB = fieldString(neighbour, "observed_at");
        double confidenceB = fieldDouble(neighbour, "confidence");

        ContradictionPair pair = new ContradictionPair(
                newItem.getKey(),   contentA,   nullSafe(observedAtA), confidenceA,
                neighbour.getKey(), contentB,   nullSafe(observedAtB), confidenceB,
                memoryType);

        ContradictionResolution resolution = resolveConflict(pair, response);
        applyResolution(newItem, neighbour, resolution);
    }

    /**
     * Build the JSON array of candidate descriptors sent in the batch prompt.
     * Each element contains: {@code index}, {@code content}, {@code observed_at}.
     */
    private String buildCandidatesJson(List<AdminMemoryItem> candidates) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode array = mapper.createArrayNode();
        for (int i = 0; i < candidates.size(); i++) {
            AdminMemoryItem item = candidates.get(i);
            ObjectNode node = mapper.createObjectNode();
            node.put("index",       i);
            node.put("content",     fieldString(item, "content"));
            node.put("observed_at", nullSafe(fieldString(item, "observed_at")));
            array.add(node);
        }
        try {
            return mapper.writeValueAsString(array);
        } catch (JsonProcessingException e) {
            // Should never happen with a plain ObjectNode — rethrow as unchecked
            throw new IllegalStateException("Failed to serialize candidates JSON", e);
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

        // Update the winner with a supersedes reference — append to any existing list
        com.google.protobuf.ListValue.Builder supersedesBuilder =
                com.google.protobuf.ListValue.newBuilder();
        Value existingSupersedes = winner.getValue().getFieldsOrDefault("supersedes", null);
        if (existingSupersedes != null && existingSupersedes.hasListValue()) {
            supersedesBuilder.addAllValues(existingSupersedes.getListValue().getValuesList());
        }
        supersedesBuilder.addValues(Value.newBuilder().setStringValue(loser.getKey()).build());

        Struct winnerUpdated = winner.getValue().toBuilder()
                .putFields("supersedes",
                        Value.newBuilder().setListValue(supersedesBuilder.build()).build())
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
     * <p>The revision is set to 0; the actual revision is unknown here because
     * {@code MemoryWriteResult} does not return it. This affects two scenarios:
     * <ul>
     *   <li>When the new memory is the <b>loser</b>, the optimistic-lock {@code putMemory}
     *       call that marks it superseded will fail with {@code ABORTED}.</li>
     *   <li>When the new memory is the <b>winner</b>, the {@code putMemory} call that writes
     *       the {@code supersedes} reference back to it will also fail with {@code ABORTED}.</li>
     * </ul>
     * In both cases the exception is caught in {@link #checkPair} / {@link #checkBatch} and
     * logged — the neighbour will still be correctly marked as superseded, and the new memory
     * can be cleaned up by the next manual backfill run if needed.
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
