package io.github.rigazilla.memory.cognition.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminConversation;
import io.github.chirino.memory.grpc.v1.AdminConversationsServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminGetConversationRequest;
import io.github.chirino.memory.grpc.v1.Entry;
import io.github.rigazilla.memory.cognition.event.SalienceScorer;
import io.github.rigazilla.memory.cognition.event.ScopeJob;
import io.github.rigazilla.memory.cognition.evidence.EvidencePack;
import io.github.rigazilla.memory.cognition.evidence.TranscriptLoader;
import io.github.rigazilla.memory.cognition.consolidation.ConsolidationService;
import io.github.rigazilla.memory.cognition.consolidation.ResolvedCandidate;
import io.github.rigazilla.memory.cognition.extraction.DurableExtractionResponse;
import io.github.rigazilla.memory.cognition.extraction.DurableMemoryExtractor;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import io.github.rigazilla.memory.cognition.config.CognitionConfig;
import io.github.rigazilla.memory.cognition.config.MemoryServiceConfig;
import io.github.rigazilla.memory.cognition.grpc.GrpcChannelFactory;
import io.github.rigazilla.memory.cognition.model.Provenance;
import io.github.rigazilla.memory.cognition.resource.LlmRetryHelper;
import io.github.rigazilla.memory.cognition.verification.DurableMemoryVerifier;
import io.github.rigazilla.memory.cognition.verification.DurableVerificationResponse;
import io.github.rigazilla.memory.cognition.writer.MemoryWriter;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Processes jobs from conversation queues on virtual threads.
 * Ensures singleton processing per conversation: only one job processes at a time.
 * Multiple conversations can process in parallel via separate virtual threads.
 *
 * Pipeline stages:
 * 0. Load conversation metadata (to get owner user ID)
 * 1. Load evidence (transcript entries)
 * 2. Extract memory candidates (all 5 types in one LLM call)
 * 3. Deduplicate candidates (within-batch + cross-batch exact match)
 * 4. Verify candidates (check citations)
 * 5. Write verified memories to memory-service
 *
 * Note: Memories are written to namespace ["user", <conversation_owner>, "cognition.v1", <memory_type>]
 *       where conversation_owner is the owner_user_id from the Conversation metadata.
 */
@ApplicationScoped
public class JobProcessor {

    private static final Logger LOG = Logger.getLogger(JobProcessor.class);

    @Inject
    MemoryServiceConfig memoryService;

    @Inject
    CognitionConfig cognition;

    @Inject
    JobQueueRegistry registry;

    @Inject
    TranscriptLoader transcriptLoader;

    @Inject
    DurableMemoryExtractor extractor;

    @Inject
    DurableMemoryVerifier verifier;

    @Inject
    MemoryWriter memoryWriter;

    @Inject
    LlmRetryHelper llmRetryHelper;

    @Inject
    ConsolidationService consolidationService;

    @Inject
    SalienceScorer salienceScorer;

    ManagedChannel channel;
    AdminConversationsServiceGrpc.AdminConversationsServiceBlockingStub conversationsStub;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Counts batches where no entry {@code created_at} was available and ingestion
     * time was substituted as {@code observed_at}. Surfaced via the temporal
     * enrichment process inspect endpoint so operators can assess data quality.
     */
    private final AtomicLong approximateObservedAtCount = new AtomicLong(0);

    /** Returns the number of batches written with an approximate {@code observed_at}. */
    public long getApproximateObservedAtCount() {
        return approximateObservedAtCount.get();
    }

    @PostConstruct
    void init() {
        LOG.infof("Initializing JobProcessor gRPC clients: %s:%d",
                memoryService.grpc().host(), memoryService.grpc().port());

        // Create gRPC channel with authentication interceptor
        channel = GrpcChannelFactory.create(
                memoryService.grpc().host(), memoryService.grpc().port(),
                memoryService.apiKey(), memoryService.clientId());

        conversationsStub = AdminConversationsServiceGrpc.newBlockingStub(channel);

        LOG.info("JobProcessor gRPC clients initialized successfully");
    }

    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down JobProcessor gRPC channel");
            channel.shutdown();
        }
    }

    /**
     * Start processing jobs for a conversation.
     * Runs on a virtual thread to avoid blocking platform threads.
     * Continues processing until the queue is empty.
     *
     * @param conversationId Conversation ID
     */
    public void startProcessing(String conversationId) {
        ConversationJobQueue queue = registry.getOrCreateQueue(conversationId);

        // Try to acquire processing lock
        if (!queue.startProcessing()) {
            LOG.debugf("Conversation %s already processing, skipping", conversationId);
            return;
        }

        try {
            LOG.infof("Started processing jobs for conversation: %s", conversationId);

            // Process jobs until queue is empty
            while (!queue.isEmpty()) {
                ScopeJob job = queue.poll();
                if (job == null) {
                    break; // Interrupted
                }

                try {
                    processJob(job);
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to process job for conversation %s: %s",
                        conversationId, job);
                    // Continue processing next job despite error
                }
            }

            LOG.infof("Finished processing jobs for conversation: %s", conversationId);

        } finally {
            queue.stopProcessing();

            // Clean up empty queue
            if (queue.isEmpty()) {
                registry.removeQueue(conversationId);
            }
        }
    }

    /**
     * Start processing asynchronously.
     * Returns immediately, processing happens on virtual thread.
     *
     * @param conversationId Conversation ID
     * @return CompletableFuture that completes when processing finishes
     */
    public CompletableFuture<Void> startProcessingAsync(String conversationId) {
        // Use virtual thread executor for non-blocking parallel processing
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        return CompletableFuture.runAsync(() -> startProcessing(conversationId), executor);
    }

    /**
     * Process a single job through the full pipeline.
     *
     * @param job The job to process
     */
    private void processJob(ScopeJob job) {
        // Manually activate request context for virtual thread
        ManagedContext requestContext = Arc.container().requestContext();
        if (!requestContext.isActive()) {
            requestContext.activate();
        }

        try {
            LOG.infof("▶ Processing job: %s", job);
            long startTime = System.currentTimeMillis();

            processJobInternal(job, startTime);

        } finally {
            if (requestContext.isActive()) {
                requestContext.terminate();
            }
        }
    }

    private void processJobInternal(ScopeJob job, long startTime) {
        try {
            // Stage 0: Load Conversation Metadata
            LOG.infof("  [0/6] Loading conversation metadata: %s", job.conversationId());
            String userId = getConversationOwner(job.conversationId());
            LOG.infof("  ✓ Conversation owner: %s", userId);

            // Stage 1: Load Evidence
            LOG.infof("  [1/6] Loading transcript for conversation: %s", job.conversationId());
            EvidencePack evidence = transcriptLoader.loadTranscript(
                job.conversationId(),
                job.entryIds(),
                job.previousEntryId(),
                userId  // Pass conversation owner for on-behalf-of authorization
            );
            LOG.infof("  ✓ Loaded %d transcript entries", evidence.size());

            if (!job.entryIds().isEmpty()) {
                evidence = filterEvidenceForBatch(job.entryIds(), evidence);
            }

            // Build provenance from ScopeJob for this batch
            Provenance provenance = Provenance.fromScopeJobMinimal(
                    job, cognition.runtime().id(), cognition.runtime().version());
            LOG.debugf("  ✓ Built provenance: batch=%d entries, trigger=%s",
                provenance.entryIds().size(), provenance.batchTrigger());

            // Stage 2: Extract Memories
            LOG.infof("  [2/6] Extracting memories from evidence");
            String evidenceText = evidence.formatAsText();

            // Debug log: show formatted evidence sent to LLM
            if (LOG.isDebugEnabled()) {
                LOG.debugf("  Evidence text sent to extractor (%d chars):", evidenceText.length());
                String preview = evidenceText.length() > 500
                    ? evidenceText.substring(0, 497) + "..."
                    : evidenceText;
                LOG.debugf("  %s", preview);
                if (evidenceText.length() > 500) {
                    LOG.debugf("  ... (truncated, full length: %d chars)", evidenceText.length());
                }
            }

            DurableExtractionResponse extraction = llmRetryHelper.withRetry(
                    "extraction:" + job.conversationId(),
                    () -> extractor.extract(evidenceText));

            int rawTotal = extraction.getTotalCount();
            List<MemoryCandidate> validCandidates = extraction.getAllCandidates();
            List<MemoryCandidate> invalidCandidates = extraction.getInvalidCandidates();
            int filteredCount = rawTotal - validCandidates.size();

            if (filteredCount > 0) {
                LOG.debugf("  ⚠ Filtered %d invalid candidates:", filteredCount);
                for (MemoryCandidate invalid : invalidCandidates) {
                    String reason = extraction.getInvalidReason(invalid);
                    String preview = invalid.content() != null && invalid.content().length() > 50
                        ? invalid.content().substring(0, 47) + "..."
                        : (invalid.content() != null ? invalid.content() : "(null)");
                    LOG.debugf("    - [%s] %s - reason: %s, confidence: %.2f, citations: %d",
                        invalid.type(),
                        preview,
                        reason,
                        invalid.confidence(),
                        invalid.citations() != null ? invalid.citations().size() : 0);
                }
            }

            LOG.infof("  ✓ Extracted %d valid memory candidates "
                    + "(raw=%d, filtered=%d): facts=%d, preferences=%d, "
                    + "procedures=%d, problemSolutions=%d, decisions=%d",
                validCandidates.size(),
                rawTotal,
                filteredCount,
                extraction.facts().size(),
                extraction.preferences().size(),
                extraction.procedures().size(),
                extraction.problemSolutions().size(),
                extraction.decisions().size());

            // Stage 3: Deduplicate candidates — collapses within-batch duplicates and merges
            // against existing memories before the expensive verification LLM call
            LOG.infof("  [3/6] Deduplicating %d candidates for user: %s",
                    validCandidates.size(), userId);
            List<ResolvedCandidate> resolvedCandidates =
                    consolidationService.deduplicate(userId, validCandidates);
            long updateCount = resolvedCandidates.stream().filter(ResolvedCandidate::isUpdate).count();
            LOG.infof("  ✓ Deduplication complete: %d → %d candidates (%d updates, %d fresh)",
                    validCandidates.size(), resolvedCandidates.size(),
                    updateCount, resolvedCandidates.size() - updateCount);

            // Stage 4: Verify Memories
            // Extract plain candidates for the verifier (takes JSON string of List<MemoryCandidate>)
            List<MemoryCandidate> deduplicatedCandidates = resolvedCandidates.stream()
                    .map(ResolvedCandidate::candidate)
                    .toList();
            // Content-keyed lookup so the write stage recovers key/revision after
            // verification filters down to verified candidates only
            Map<String, ResolvedCandidate> resolvedByContent = resolvedCandidates.stream()
                    .collect(Collectors.toMap(
                            r -> r.candidate().content(),
                            r -> r,
                            (a, b) -> a));

            LOG.infof("  [4/6] Verifying memory candidates");
            String candidatesJson = objectMapper.writeValueAsString(deduplicatedCandidates);
            DurableVerificationResponse verification = llmRetryHelper.withRetry(
                    "verification:" + job.conversationId(),
                    () -> verifier.verify(candidatesJson, evidenceText));
            LOG.infof("  ✓ Verification complete: verified=%d, rejected=%d",
                verification.verified().size(),
                verification.rejected().size());

            // Log rejected candidates with details
            if (!verification.rejected().isEmpty()) {
                LOG.debugf("  ⚠ Rejected %d candidates during verification:", verification.rejected().size());
                for (var rejected : verification.rejected()) {
                    String preview = rejected.candidate().content().length() > 50
                        ? rejected.candidate().content().substring(0, 47) + "..."
                        : rejected.candidate().content();
                    LOG.debugf("    - [%s] %s - reason: %s, confidence: %.2f",
                        rejected.candidate().type(),
                        preview,
                        rejected.reason(),
                        rejected.candidate().confidence());
                }
            }

            // Stage 5: Write Memories
            if (!verification.verified().isEmpty()) {
                LOG.infof("  [5/6] Writing %d verified memories to memory-service for user: %s",
                    verification.verified().size(), userId);

                // observed_at = earliest entry createdAt in this batch (when the facts were stated).
                // Falls back to provenance.processedAt() when no entry timestamps are available.
                // Log a warning: this fallback uses ingestion time, NOT the time facts were stated,
                // which violates the spec. Any memories written with this timestamp should be
                // treated as having an approximate observed_at.
                String observedAt = evidence.earliestCreatedAt().orElseGet(() -> {
                    String fallback = provenance.processedAt().toString();
                    LOG.warnf("No entry createdAt found in evidence for conversation %s; "
                        + "falling back to ingestion time %s — observed_at will be approximate",
                        provenance.conversationId(), fallback);
                    approximateObservedAtCount.incrementAndGet();
                    return fallback;
                });

                // Get entry ID mapping for citation parsing
                Map<String, String> entryMapping = evidence.getEntryIdMapping();

                // Write each memory with refined provenance based on citations
                int writtenCount = 0;
                int refinedCount = 0;
                for (MemoryCandidate candidate : verification.verified()) {
                    // Parse citations to extract referenced entry IDs
                    List<String> referencedEntryIds = parseEntryIdsFromCitations(
                        candidate.citations(), entryMapping);

                    // Create per-memory provenance with refined entry IDs
                    Provenance memoryProvenance;
                    if (!referencedEntryIds.isEmpty()) {
                        // Use refined entry IDs from citations
                        memoryProvenance = new Provenance(
                            provenance.conversationId(),
                            referencedEntryIds,
                            provenance.firstEventCursor(),
                            provenance.latestEventCursor(),
                            provenance.batchTrigger(),
                            provenance.sourceHash(),
                            provenance.evidenceBaseId(),
                            provenance.evidenceBaseHash(),
                            provenance.runtimeId(),
                            provenance.runtimeVersion(),
                            provenance.processedAt()
                        );
                        refinedCount++;
                        LOG.debugf("  Refined provenance for memory: %d entry IDs from citations",
                            referencedEntryIds.size());
                    } else {
                        // Fallback: use all entry IDs from batch
                        memoryProvenance = provenance;
                        LOG.debugf("  No entry IDs in citations, using batch-level provenance (%d entries)",
                            provenance.entryIds().size());
                    }

                    // Recover key/revision from dedup stage via exact-content lookup.
                    // A miss means the verifier's LLM round-trip mutated the content string;
                    // fall back to fresh insert and warn so the silent data loss is observable.
                    ResolvedCandidate resolved = resolvedByContent.get(candidate.content());
                    if (resolved == null) {
                        LOG.warnf("resolvedByContent miss for verified candidate [%s] — "
                                + "content may have been mutated by LLM round-trip; "
                                + "writing as fresh insert (duplicate possible)",
                                candidate.type());
                        resolved = ResolvedCandidate.fresh(candidate);
                    }
                    memoryWriter.writeResolved(userId, resolved, memoryProvenance, observedAt);
                    writtenCount++;
                }

                LOG.infof("  ✓ Successfully wrote %d memories to namespace: [\"user\", \"%s\", \"cognition.v1\", *]"
                        + " (observedAt=%s, refined=%d, batch-level=%d)",
                    writtenCount, userId, observedAt, refinedCount, writtenCount - refinedCount);
            } else {
                LOG.infof("  [5/6] No verified memories to write");
            }

            long duration = System.currentTimeMillis() - startTime;
            LOG.infof("✓ Job completed successfully in %dms: %s", duration, job);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LOG.errorf(e, "✗ Job failed after %dms: %s", duration, job);
            throw new JobProcessingException("Failed to process job for conversation " + job.conversationId(), e);
        }
    }

    /**
     * Parse entry IDs from LLM citations.
     * Citations are expected in format: "E1: quote text" or "E2: another quote"
     * 
     * @param citations List of citation strings from LLM
     * @param entryMapping Map of "E1" -> actual UUID
     * @return List of actual entry UUIDs referenced in citations
     */
    // Package-private for unit testing
    List<String> parseEntryIdsFromCitations(List<String> citations, Map<String, String> entryMapping) {
        LinkedHashSet<String> entryIds = new LinkedHashSet<>();

        for (String citation : citations) {
            if (citation == null || citation.isEmpty()) {
                continue;
            }

            // Match only "E<digits>:" pattern at start — e.g. E1:, E12:
            // Rejects "Error:", "En:", "E1abc:" etc.
            if (citation.startsWith("E")) {
                int colonIndex = citation.indexOf(":");
                if (colonIndex > 1) {
                    String between = citation.substring(1, colonIndex);
                    if (!between.isEmpty() && between.chars().allMatch(Character::isDigit)) {
                        String entryRef = citation.substring(0, colonIndex).trim();
                        String actualEntryId = entryMapping.get(entryRef);

                        if (actualEntryId != null) {
                            entryIds.add(actualEntryId);
                            LOG.debugf("    Parsed entry reference: %s -> %s", entryRef, actualEntryId);
                        } else {
                            LOG.debugf("    Unknown entry reference in citation: %s", entryRef);
                        }
                    }
                }
            }
        }

        return new ArrayList<>(entryIds);
    }

    EvidencePack filterEvidenceForBatch(List<String> batchEntryIds, EvidencePack evidence) {
        List<Entry> filteredEntries = evidence.getTranscriptEntries().stream()
            .filter(entry -> batchEntryIds.contains(bytesToUuid(entry.getId())) || shouldKeepTranscriptEntry(entry))
            .toList();

        if (filteredEntries.size() != evidence.size()) {
            LOG.infof("  ✓ Filtered transcript entries before extraction: %d -> %d",
                evidence.size(), filteredEntries.size());
        }

        return new EvidencePack(filteredEntries);
    }

    private boolean shouldKeepTranscriptEntry(Entry entry) {
        if (!"history".equals(entry.getContentType()) || entry.getContentCount() == 0) {
            return true;
        }

        var content = entry.getContent(0);
        if (!content.hasStructValue()) {
            return true;
        }

        var struct = content.getStructValue();
        String role = struct.getFieldsOrDefault("role",
            Value.newBuilder().setStringValue("").build())
            .getStringValue();
        if (!"USER".equals(role)) {
            return true;
        }

        String text = struct.getFieldsOrDefault("text",
            Value.newBuilder().setStringValue("").build())
            .getStringValue();
        return salienceScorer.shouldKeep(text);
    }

    private String bytesToUuid(ByteString bytes) {
        if (bytes.size() != 16) {
            throw new IllegalArgumentException("Expected 16 UUID bytes, got " + bytes.size());
        }
        ByteBuffer buffer = bytes.asReadOnlyByteBuffer();
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    /**
     * Get the owner user ID for a conversation by loading conversation metadata via gRPC.
     * Uses AdminConversationsService which provides admin access without requiring membership.
     *
     * @param conversationId Conversation UUID string
     * @return Owner user ID
     * @throws JobProcessingException if conversation metadata cannot be loaded
     */
    private String getConversationOwner(String conversationId) {
        try {
            AdminGetConversationRequest request = AdminGetConversationRequest.newBuilder()
                .setConversationId(conversationId)
                .build();

            AdminConversation conversation = conversationsStub.getConversation(request);

            String ownerId = conversation.getOwnerUserId();
            LOG.debugf("Loaded conversation %s owner: %s", conversationId, ownerId);

            return ownerId;

        } catch (StatusRuntimeException e) {
            Status status = e.getStatus();
            LOG.errorf(e, "Failed to load conversation metadata for %s: %s", conversationId, status);
            throw new JobProcessingException("Failed to load conversation metadata for " + conversationId, e);
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error loading conversation metadata for %s", conversationId);
            throw new JobProcessingException("Failed to load conversation metadata for " + conversationId, e);
        }
    }

    /**
     * Exception thrown when job processing fails.
     */
    public static class JobProcessingException extends RuntimeException {
        public JobProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
