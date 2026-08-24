package io.github.rigazilla.memory.cognition.metadata;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesResponse;
import io.github.chirino.memory.grpc.v1.AdminListMemoryNamespacesRequest;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.AdminPutMemoryRequest;
import io.github.chirino.memory.grpc.v1.MemoryNamespace;
import io.grpc.ManagedChannel;
import io.github.rigazilla.memory.cognition.config.CognitionConfig;
import io.github.rigazilla.memory.cognition.config.MemoryServiceConfig;
import io.github.rigazilla.memory.cognition.grpc.GrpcChannelFactory;
import io.github.rigazilla.memory.cognition.resource.LlmRetryHelper;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the metadata enrichment pass over existing memories.
 * Discovers all user memory namespaces, pages through each, and enriches
 * memories that lack entity/topic metadata by calling the LLM extractor.
 */
@ApplicationScoped
public class MetadataEnrichmentService {

    private static final Logger LOG = Logger.getLogger(MetadataEnrichmentService.class);
    private static final String COGNITION_VERSION = "cognition.v1";
    private static final String PROFILE_CONTEXT_TYPE = "profile_context";

    @Inject
    MemoryServiceConfig memoryService;

    @Inject
    CognitionConfig cognition;

    @Inject
    MetadataExtractor extractor;

    @Inject
    LlmRetryHelper llmRetryHelper;

    // Package-private for test injection (same pattern as TemporalMetadataEnrichmentService)
    ManagedChannel channel;
    AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub memoriesStub;

    // Progress state — exposed to MetadataEnrichmentProcess for inspect()
    // Package-private for direct counter inspection in unit tests
    final AtomicBoolean running = new AtomicBoolean(false);
    final AtomicReference<String> status = new AtomicReference<>("idle");
    final AtomicInteger processed = new AtomicInteger(0);
    final AtomicInteger enriched = new AtomicInteger(0);
    final AtomicInteger errors = new AtomicInteger(0);
    final AtomicReference<Instant> lastRunTime = new AtomicReference<>();

    @PostConstruct
    void init() {
        LOG.infof("Initializing MetadataEnrichmentService: %s:%d",
                memoryService.grpc().host(), memoryService.grpc().port());
        channel = GrpcChannelFactory.create(
                memoryService.grpc().host(), memoryService.grpc().port(),
                memoryService.apiKey());
        memoriesStub = AdminMemoriesServiceGrpc.newBlockingStub(channel);
        LOG.info("MetadataEnrichmentService initialized successfully");
    }

    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down MetadataEnrichmentService gRPC channel");
            channel.shutdown();
        }
    }

    public String getStatus() {
        return status.get();
    }

    public int getProcessed() {
        return processed.get();
    }

    public int getEnriched() {
        return enriched.get();
    }

    public int getErrors() {
        return errors.get();
    }

    public Instant getLastRunTime() {
        return lastRunTime.get();
    }

    /**
     * Start enrichment asynchronously over all user namespaces.
     * Silently skips if a run is already in progress.
     */
    public void startEnrichmentAsync() {
        startEnrichmentAsync(null);
    }

    /**
     * Start enrichment asynchronously, optionally scoped to a namespace prefix.
     *
     * <p>When {@code namespacePrefix} is {@code null} the run behaves exactly as
     * before: all namespaces under {@code ["user"]} are discovered and enriched.
     *
     * <p>When a prefix is supplied:
     * <ul>
     *   <li>Fewer than 4 segments — namespace discovery is scoped to that prefix.</li>
     *   <li>Exactly 4 segments — the discovery phase is skipped entirely and
     *       {@code listMemories} is called directly with the provided prefix.</li>
     * </ul>
     *
     * <p>Silently skips if a run is already in progress.
     *
     * @param namespacePrefix optional scoping prefix; {@code null} = all users
     */
    public void startEnrichmentAsync(List<String> namespacePrefix) {
        if (!running.compareAndSet(false, true)) {
            LOG.info("Enrichment already running — skipping duplicate start");
            return;
        }
        status.set("running");

        CompletableFuture.runAsync(
                        () -> runEnrichment(namespacePrefix),
                        Executors.newVirtualThreadPerTaskExecutor())
                .whenComplete((v, ex) -> {
                    lastRunTime.set(Instant.now());
                    running.set(false);
                    if (ex != null) {
                        LOG.errorf(ex, "Enrichment run failed");
                        status.set("error: " + ex.getMessage());
                    } else {
                        status.set("completed");
                    }
                });
    }

    // Package-private for direct invocation in unit tests
    void runEnrichment(List<String> namespacePrefix) {
        processed.set(0);
        enriched.set(0);
        errors.set(0);

        // Activate CDI request context once for the entire pass — required for
        // @RequestScoped LangChain4j AI services running on the CompletableFuture
        // virtual thread. Same pattern as JobProcessor.processJob().
        // Guard against null container (e.g. in plain unit tests without CDI runtime).
        var arcContainer = Arc.container();
        ManagedContext requestContext = arcContainer != null ? arcContainer.requestContext() : null;
        if (requestContext != null && !requestContext.isActive()) {
            requestContext.activate();
        }
        try {
            doRunEnrichment(namespacePrefix);
        } finally {
            if (requestContext != null && requestContext.isActive()) {
                requestContext.terminate();
            }
        }
    }

    private void doRunEnrichment(List<String> namespacePrefix) {
        LOG.info("Starting metadata enrichment pass");

        if (namespacePrefix != null && namespacePrefix.size() >= 4) {
            // Caller supplied a fully-qualified leaf namespace — skip the two-phase
            // discovery and call enrichNamespace directly. profile_context is still
            // filtered because it holds snapshots, not individual memories.
            String memoryType = namespacePrefix.get(3);
            if (PROFILE_CONTEXT_TYPE.equals(memoryType)) {
                LOG.debugf("Skipping fully-qualified prefix %s — profile_context is not enrichable",
                        namespacePrefix);
            } else {
                LOG.infof("Namespace prefix is fully-qualified (%d segments); skipping discovery",
                        namespacePrefix.size());
                enrichNamespace(namespacePrefix, memoryType);
            }
        } else {
            // Phase 1: discover all leaf namespaces under the given (or default) prefix.
            List<String> prefix = namespacePrefix != null ? namespacePrefix : Collections.singletonList("user");
            AdminListMemoryNamespacesRequest nsReq = AdminListMemoryNamespacesRequest.newBuilder()
                    .addAllNamespacePrefix(prefix)
                    .setMaxDepth(4)   // segments: user / userId / cognition.v1 / memoryType
                    .build();

            List<MemoryNamespace> namespaces = memoriesStub
                    .listNamespaces(nsReq)
                    .getNamespacesList();

            LOG.infof("Discovered %d namespaces for enrichment (prefix=%s)", namespaces.size(), prefix);

            for (MemoryNamespace ns : namespaces) {
                List<String> segments = ns.getSegmentsList();
                // Only process 4-segment cognition.v1 namespaces; skip profile_context snapshots
                if (segments.size() < 4) {
                    continue;
                }
                if (!COGNITION_VERSION.equals(segments.get(2))) {
                    continue;
                }
                String memoryType = segments.get(3);
                if (PROFILE_CONTEXT_TYPE.equals(memoryType)) {
                    continue;
                }
                enrichNamespace(segments, memoryType);
            }
        }

        LOG.infof("Enrichment pass complete: processed=%d, enriched=%d, errors=%d",
                processed.get(), enriched.get(), errors.get());
    }

    private void enrichNamespace(List<String> namespace, String memoryType) {
        String cursor = null;
        do {
            AdminListMemoriesRequest.Builder reqBuilder = AdminListMemoriesRequest.newBuilder()
                    .addAllNamespacePrefix(namespace)
                    .setLimit(50);
            if (cursor != null) {
                reqBuilder.setAfterCursor(cursor);
            }

            AdminListMemoriesResponse resp = memoriesStub.listMemories(reqBuilder.build());

            for (AdminMemoryItem item : resp.getItemsList()) {
                enrichMemory(item, memoryType);
                sleepInterCallDelay();
            }

            cursor = resp.hasAfterCursor() ? resp.getAfterCursor() : null;
        } while (cursor != null);
    }

    private void enrichMemory(AdminMemoryItem item, String memoryType) {
        try {
            Struct value = item.getValue();

            // Idempotent: skip memories already enriched
            if (value.containsFields("entities")) {
                return;
            }

            String content = value.getFieldsOrDefault(
                    "content",
                    Value.newBuilder().setStringValue("").build()
            ).getStringValue();

            if (content.isBlank()) {
                return;
            }

            // Call LLM to extract entities and topics — wrapped with retry/backoff
            MetadataExtractionResponse extraction = llmRetryHelper.withRetry(
                    "metadata-extraction:" + item.getKey(),
                    () -> extractor.extract(memoryType, content));

            // Write back: copy all existing fields and add entities + topics
            Struct updatedValue = value.toBuilder()
                    .putFields("entities", buildEntitiesValue(extraction.entities()))
                    .putFields("topics", buildTopicsValue(extraction.topics()))
                    .build();

            AdminPutMemoryRequest putReq = AdminPutMemoryRequest.newBuilder()
                    .addAllNamespace(item.getNamespaceList())
                    .setKey(item.getKey())
                    .setValue(updatedValue)
                    .setExpectedRevision(item.getRevision())
                    .build();

            memoriesStub.putMemory(putReq);
            enriched.incrementAndGet();

            LOG.debugf("Enriched memory: key=%s, entities=%d, topics=%d",
                    item.getKey(), extraction.entities().size(), extraction.topics().size());

        } catch (Exception e) {
            errors.incrementAndGet();
            LOG.warnf("Failed to enrich memory key=%s: %s", item.getKey(), e.getMessage());
        } finally {
            processed.incrementAndGet();
        }
    }

    private Value buildEntitiesValue(List<ExtractedEntity> entities) {
        ListValue.Builder list = ListValue.newBuilder();
        for (ExtractedEntity e : entities) {
            Struct entityStruct = Struct.newBuilder()
                    .putFields("name", Value.newBuilder().setStringValue(e.name()).build())
                    .putFields("type", Value.newBuilder().setStringValue(e.type()).build())
                    .build();
            list.addValues(Value.newBuilder().setStructValue(entityStruct).build());
        }
        return Value.newBuilder().setListValue(list.build()).build();
    }

    private void sleepInterCallDelay() {
        long delay = cognition.llm().backfill().interCallDelayMs();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private Value buildTopicsValue(List<String> topics) {
        ListValue.Builder list = ListValue.newBuilder();
        for (String t : topics) {
            list.addValues(Value.newBuilder().setStringValue(t).build());
        }
        return Value.newBuilder().setListValue(list.build()).build();
    }

}
