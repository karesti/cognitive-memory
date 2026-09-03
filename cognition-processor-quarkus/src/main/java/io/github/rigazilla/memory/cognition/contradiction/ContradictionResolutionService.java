package io.github.rigazilla.memory.cognition.contradiction;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesResponse;
import io.github.chirino.memory.grpc.v1.AdminListMemoryNamespacesRequest;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.AdminPutMemoryRequest;
import io.github.chirino.memory.grpc.v1.MemoryNamespace;
import io.github.rigazilla.memory.cognition.config.CognitionConfig;
import io.github.rigazilla.memory.cognition.config.MemoryServiceConfig;
import io.github.rigazilla.memory.cognition.grpc.GrpcChannelFactory;
import io.github.rigazilla.memory.cognition.resource.LlmRetryHelper;
import io.grpc.ManagedChannel;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Traverses existing user memories, detects contradictions between pairs using an LLM,
 * and resolves them by marking the superseded memory with status metadata.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Discover all 4-segment leaf namespaces under {@code ["user"]} (or a scoped prefix).</li>
 *   <li>For each namespace, load all non-superseded memories.</li>
 *   <li>For every pair within the namespace, call {@link ContradictionDetector}.</li>
 *   <li>For detected contradictions, apply the resolution strategy and write updated structs
 *       back to memory-service.</li>
 * </ol>
 *
 * <h2>Resolution strategies</h2>
 * <ul>
 *   <li><b>recency</b> — most-recently observed memory wins; older is superseded.</li>
 *   <li><b>confidence</b> — highest-confidence memory wins; lower is superseded.</li>
 *   <li><b>coexistence</b> — both memories stay active; no write-back needed.</li>
 * </ul>
 *
 * <p>All writes use {@code expected_revision} (optimistic locking) so concurrent processes
 * do not corrupt each other's data.
 */
@ApplicationScoped
public class ContradictionResolutionService {

    private static final Logger LOG = Logger.getLogger(ContradictionResolutionService.class);

    private static final String COGNITION_VERSION  = "cognition.v1";
    private static final String PROFILE_CONTEXT    = "profile_context";
    private static final String STATUS_SUPERSEDED  = "superseded";
    private static final int    PAGE_SIZE          = 50;

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

    // Package-private for test injection (same pattern as MetadataEnrichmentService)
    ManagedChannel channel;
    AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub memoriesStub;

    // -------------------------------------------------------------------------
    // Progress counters (package-private for test access)
    // -------------------------------------------------------------------------

    final AtomicBoolean  running        = new AtomicBoolean(false);
    final AtomicInteger  scanned        = new AtomicInteger(0);
    final AtomicInteger  pairsChecked   = new AtomicInteger(0);
    final AtomicInteger  contradictions = new AtomicInteger(0);
    final AtomicInteger  resolved       = new AtomicInteger(0);
    final AtomicInteger  errors         = new AtomicInteger(0);
    final AtomicReference<String>  status      = new AtomicReference<>("idle");
    final AtomicReference<Instant> lastRunTime = new AtomicReference<>();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @PostConstruct
    void init() {
        LOG.infof("Initializing ContradictionResolutionService: %s:%d",
                memoryService.grpc().host(), memoryService.grpc().port());
        channel = GrpcChannelFactory.create(
                memoryService.grpc().host(), memoryService.grpc().port(),
                memoryService.apiKey());
        memoriesStub = AdminMemoriesServiceGrpc.newBlockingStub(channel);
        LOG.info("ContradictionResolutionService initialized successfully");
    }

    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down ContradictionResolutionService gRPC channel");
            channel.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public String  getStatus()        { return status.get(); }
    public int     getScanned()       { return scanned.get(); }
    public int     getPairsChecked()  { return pairsChecked.get(); }
    public int     getContradictions(){ return contradictions.get(); }
    public int     getResolved()      { return resolved.get(); }
    public int     getErrors()        { return errors.get(); }
    public Instant getLastRunTime()   { return lastRunTime.get(); }

    /**
     * Start contradiction resolution asynchronously.
     *
     * @param namespacePrefix optional scope; {@code null} = all users
     */
    public void startAsync(List<String> namespacePrefix) {
        if (!running.compareAndSet(false, true)) {
            LOG.info("Contradiction resolution already running — skipping duplicate start");
            return;
        }
        status.set("running");

        CompletableFuture.runAsync(
                        () -> runResolution(namespacePrefix),
                        Executors.newVirtualThreadPerTaskExecutor())
                .whenComplete((v, ex) -> {
                    lastRunTime.set(Instant.now());
                    running.set(false);
                    if (ex != null) {
                        LOG.errorf(ex, "Contradiction resolution run failed");
                        status.set("error: " + ex.getMessage());
                    } else {
                        status.set("completed");
                    }
                });
    }

    // Package-private for direct test invocation
    void runResolution(List<String> namespacePrefix) {
        scanned.set(0);
        pairsChecked.set(0);
        contradictions.set(0);
        resolved.set(0);
        errors.set(0);

        var arcContainer = Arc.container();
        ManagedContext requestContext = arcContainer != null ? arcContainer.requestContext() : null;
        if (requestContext != null && !requestContext.isActive()) {
            requestContext.activate();
        }
        try {
            doRunResolution(namespacePrefix);
        } finally {
            if (requestContext != null && requestContext.isActive()) {
                requestContext.terminate();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Core logic
    // -------------------------------------------------------------------------

    private void doRunResolution(List<String> namespacePrefix) {
        LOG.info("Starting contradiction resolution pass");

        List<String> prefix = namespacePrefix != null
                ? namespacePrefix
                : Collections.singletonList("user");

        AdminListMemoryNamespacesRequest nsReq = AdminListMemoryNamespacesRequest.newBuilder()
                .addAllNamespacePrefix(prefix)
                .setMaxDepth(4)
                .build();

        List<MemoryNamespace> namespaces = memoriesStub
                .listNamespaces(nsReq)
                .getNamespacesList();

        LOG.infof("Discovered %d namespaces for contradiction resolution (prefix=%s)",
                namespaces.size(), prefix);

        for (MemoryNamespace ns : namespaces) {
            List<String> segments = ns.getSegmentsList();
            if (segments.size() < 4) {
                continue;
            }
            if (!COGNITION_VERSION.equals(segments.get(2))) {
                continue;
            }
            if (PROFILE_CONTEXT.equals(segments.get(3))) {
                continue;
            }
            processNamespace(segments);
        }

        LOG.infof("Contradiction resolution pass complete: scanned=%d, pairs=%d, "
                        + "contradictions=%d, resolved=%d, errors=%d",
                scanned.get(), pairsChecked.get(),
                contradictions.get(), resolved.get(), errors.get());
    }

    /**
     * Load all active memories in {@code namespace}, then check every unique pair.
     */
    private void processNamespace(List<String> namespace) {
        String memoryType = namespace.get(3);
        int limit = cognition.contradiction().maxMemoriesPerNamespace();
        List<AdminMemoryItem> memories = loadActiveMemories(namespace, limit);

        scanned.addAndGet(memories.size());

        // O(n²) pair iteration — acceptable for typical namespace sizes (< 200 memories).
        // For very large namespaces the quadratic cost is bounded by the page limit.
        for (int i = 0; i < memories.size(); i++) {
            for (int j = i + 1; j < memories.size(); j++) {
                checkPair(memories.get(i), memories.get(j), memoryType);
            }
        }
    }

    /**
     * Load active memories in the given namespace, skipping already-superseded ones.
     * Pages through results up to {@code maxMemories} items to bound LLM call cost.
     */
    private List<AdminMemoryItem> loadActiveMemories(List<String> namespace, int maxMemories) {
        List<AdminMemoryItem> result = new ArrayList<>();
        String cursor = null;
        do {
            AdminListMemoriesRequest.Builder req = AdminListMemoriesRequest.newBuilder()
                    .addAllNamespacePrefix(namespace)
                    .setLimit(PAGE_SIZE);
            if (cursor != null) {
                req.setAfterCursor(cursor);
            }
            AdminListMemoriesResponse resp = memoriesStub.listMemories(req.build());

            for (AdminMemoryItem item : resp.getItemsList()) {
                // Skip memories already marked superseded
                if (!STATUS_SUPERSEDED.equals(
                        item.getValue()
                                .getFieldsOrDefault("status",
                                        Value.newBuilder().setStringValue("").build())
                                .getStringValue())) {
                    result.add(item);
                }
            }
            if (result.size() >= maxMemories) {
                break;
            }
            cursor = resp.hasAfterCursor() ? resp.getAfterCursor() : null;
        } while (cursor != null);
        return result;
    }

    /**
     * Ask the LLM whether two memories contradict each other; resolve if they do.
     */
    private void checkPair(AdminMemoryItem a, AdminMemoryItem b, String memoryType) {
        try {
            ContradictionPair pair = toPair(a, b, memoryType);
            pairsChecked.incrementAndGet();

            ContradictionDetectionResponse response = llmRetryHelper.withRetry(
                    "contradiction-detect:" + pair.keyA() + ":" + pair.keyB(),
                    () -> detector.detect(
                            pair.memoryType(),
                            pair.contentA(), nullSafe(pair.observedAtA()),
                            pair.contentB(), nullSafe(pair.observedAtB())));

            if (!response.contradicts()) {
                return;
            }

            contradictions.incrementAndGet();
            LOG.infof("Contradiction detected [%s]: keyA=%s keyB=%s type=%s strategy=%s — %s",
                    memoryType, pair.keyA(), pair.keyB(),
                    response.contradictionType().value(), response.recommendedStrategy().value(),
                    response.rationale());

            if (response.isCoexistence()) {
                LOG.infof("Coexistence: keeping both memories active (keyA=%s, keyB=%s)",
                        pair.keyA(), pair.keyB());
                return;
            }

            ContradictionResolution resolution = resolveConflict(pair, response);
            applyResolution(a, b, resolution);
            resolved.incrementAndGet();

        } catch (Exception e) {
            errors.incrementAndGet();
            LOG.warnf(e, "Error checking pair keyA=%s keyB=%s: %s",
                    a.getKey(), b.getKey(), e.getMessage());
        }
    }

    /**
     * Determine the winner/loser based on the recommended (or default) strategy.
     */
    private ContradictionResolution resolveConflict(
            ContradictionPair pair, ContradictionDetectionResponse response) {

        return switch (response.recommendedStrategy()) {
            case CONFIDENCE -> resolveByConfidence(pair, response);
            default         -> resolveByRecency(pair, response);      // RECENCY is the default
        };
    }

    private ContradictionResolution resolveByRecency(
            ContradictionPair pair, ContradictionDetectionResponse r) {
        // If observedAt timestamps exist, the more-recent one wins.
        // Fall back to document order (A first) when timestamps are absent.
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

    /**
     * Write superseded metadata to the losing memory and a {@code supersedes} list
     * to the winning memory, both with optimistic locking.
     */
    private void applyResolution(AdminMemoryItem a, AdminMemoryItem b,
                                  ContradictionResolution resolution) {
        AdminMemoryItem winner = a.getKey().equals(resolution.winnerKey()) ? a : b;
        AdminMemoryItem loser  = a.getKey().equals(resolution.supersededKey()) ? a : b;

        String now = Instant.now().toString();

        // --- Mark the loser as superseded ---
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

        LOG.infof("Marked memory superseded: key=%s superseded_by=%s strategy=%s",
                loser.getKey(), resolution.winnerKey(), resolution.strategyApplied().value());

        // --- Update the winner with a supersedes reference ---
        // Build a list value containing the loser key
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

        LOG.debugf("Updated winner with supersedes reference: key=%s", winner.getKey());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ContradictionPair toPair(AdminMemoryItem a, AdminMemoryItem b, String memoryType) {
        return new ContradictionPair(
                a.getKey(), content(a), observedAt(a), confidence(a),
                b.getKey(), content(b), observedAt(b), confidence(b),
                memoryType);
    }

    private static String content(AdminMemoryItem item) {
        return item.getValue()
                .getFieldsOrDefault("content",
                        Value.newBuilder().setStringValue("").build())
                .getStringValue();
    }

    private static String observedAt(AdminMemoryItem item) {
        return item.getValue()
                .getFieldsOrDefault("observed_at",
                        Value.newBuilder().setStringValue("").build())
                .getStringValue();
    }

    private static double confidence(AdminMemoryItem item) {
        return item.getValue()
                .getFieldsOrDefault("confidence",
                        Value.newBuilder().setNumberValue(0.0).build())
                .getNumberValue();
    }

    /**
     * Compare two ISO-8601 timestamps lexicographically (ISO-8601 sorts correctly as strings).
     * Returns positive if {@code a} is newer, negative if older, 0 if equal or both blank.
     */
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
