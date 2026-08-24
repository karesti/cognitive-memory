package io.github.rigazilla.memory.cognition.writer;

import com.google.protobuf.ByteString;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesResponse;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.MemoryWriteResult;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminPutMemoryRequest;
import io.github.rigazilla.memory.cognition.consolidation.ResolvedCandidate;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import io.github.rigazilla.memory.cognition.config.MemoryServiceConfig;
import io.github.rigazilla.memory.cognition.grpc.GrpcChannelFactory;
import io.github.rigazilla.memory.cognition.model.Provenance;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

/**
 * Writes verified memory candidates to Memory Service via gRPC.
 * Stores memories in the cognition namespace: ["user", userId, "cognition.v1", memoryType]
 */
@ApplicationScoped
public class MemoryWriter {
    
    private static final Logger LOG = Logger.getLogger(MemoryWriter.class);
    private static final String COGNITION_VERSION = "cognition.v1";
    
    @Inject
    MemoryServiceConfig memoryService;

    ManagedChannel channel;
    AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub memoriesStub;
    
    @PostConstruct
    void init() {
        LOG.infof("Initializing MemoryWriter: %s:%d",
                memoryService.grpc().host(), memoryService.grpc().port());

        // Create gRPC channel with authentication interceptor
        channel = GrpcChannelFactory.create(
                memoryService.grpc().host(), memoryService.grpc().port(),
                memoryService.apiKey());
        
        // Create admin stub for writing memories on behalf of users
        memoriesStub = AdminMemoriesServiceGrpc.newBlockingStub(channel);
        
        LOG.info("MemoryWriter initialized successfully");
    }
    
    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down MemoryWriter gRPC channel");
            channel.shutdown();
        }
    }
    
    /**
     * Write a single memory candidate to memory service.
     * Always performs a fresh insert with a new UUID key.
     *
     * @param userId     User ID for namespace
     * @param candidate  Memory candidate to write
     * @param provenance Provenance information for audit and replay
     * @param observedAt ISO-8601 timestamp when the fact was stated
     * @return Memory write result with ID and metadata
     */
    public MemoryWriteResult writeMemory(String userId, MemoryCandidate candidate,
            Provenance provenance, String observedAt) {
        return writeResolved(userId, ResolvedCandidate.fresh(candidate), provenance, observedAt);
    }

    /**
     * Write a resolved candidate to memory service.
     * <p>
     * If the candidate carries an {@code existingKey} (i.e. it is a merge of a
     * duplicate) the write is a guarded upsert: {@code AdminPutMemoryRequest} is
     * issued with the existing key and {@code expected_revision}. On
     * {@code ABORTED} (optimistic-lock conflict), the existing entry is re-fetched
     * via {@code AdminListMemories}, re-merged, and retried exactly once. If the
     * retry also fails the candidate is written as a fresh insert so the job is
     * never blocked.
     * <p>
     * Note: {@code AdminUpdateMemoryRequest} only updates the {@code archived} flag and
     * cannot update content. {@code AdminPutMemoryRequest.setExpectedRevision()}
     * is the correct proto field for a guarded upsert — confirmed at proto line 728.
     *
     * @param userId     User ID for namespace
     * @param resolved   Resolved candidate, optionally carrying existing key + revision
     * @param provenance Provenance information for audit and replay
     * @param observedAt ISO-8601 timestamp when the fact was stated
     * @return Memory write result with ID and metadata
     */
    public MemoryWriteResult writeResolved(String userId, ResolvedCandidate resolved,
            Provenance provenance, String observedAt) {
        try {
            return doWrite(userId, resolved, provenance, observedAt);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.ABORTED && resolved.isUpdate()) {
                // Optimistic-lock conflict: re-fetch current revision and retry once
                LOG.warnf("ABORTED writing memory key=%s (revision conflict); retrying once",
                        resolved.existingKey().orElse("?"));
                return retryAfterAborted(userId, resolved, provenance, observedAt);
            }
            LOG.errorf(e, "Failed to write memory: type=%s, userId=%s",
                    resolved.candidate().type(), userId);
            throw new MemoryWriteException("Failed to write memory for user " + userId, e);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to write memory: type=%s, userId=%s",
                    resolved.candidate().type(), userId);
            throw new MemoryWriteException("Failed to write memory for user " + userId, e);
        }
    }

    /**
     * On ABORTED: re-fetch the latest revision for the existing key via
     * {@code AdminListMemories} and retry the write once.
     * If the retry also fails, fall back to a fresh insert so the job is never blocked.
     */
    private MemoryWriteResult retryAfterAborted(String userId, ResolvedCandidate resolved,
            Provenance provenance, String observedAt) {
        try {
            String existingKey = resolved.existingKey().orElseThrow();
            List<String> namespace = List.of(
                    "user", userId, COGNITION_VERSION, resolved.candidate().type());

            // Re-fetch the current revision for the conflicting key
            AdminListMemoriesRequest refetchRequest = AdminListMemoriesRequest.newBuilder()
                    .addAllNamespacePrefix(namespace)
                    .setKeyPrefix(existingKey)
                    .setLimit(1)
                    .build();

            AdminListMemoriesResponse refetchResponse = memoriesStub.listMemories(refetchRequest);

            if (refetchResponse.getItemsCount() == 0) {
                // Entry was deleted between our search and retry — treat as fresh insert
                LOG.warnf("Memory key=%s not found on retry re-fetch; writing as fresh insert", existingKey);
                return doWrite(userId, ResolvedCandidate.fresh(resolved.candidate()), provenance, observedAt);
            }

            AdminMemoryItem current = refetchResponse.getItems(0);

            // setKeyPrefix is a prefix scan — validate the returned item is an exact match
            // to guard against a key that is a prefix of another (e.g. "abc" vs "abcdef").
            if (!existingKey.equals(current.getKey())) {
                LOG.warnf("Re-fetch key mismatch: wanted=%s got=%s; writing as fresh insert",
                        existingKey, current.getKey());
                return doWrite(userId, ResolvedCandidate.fresh(resolved.candidate()), provenance, observedAt);
            }

            ResolvedCandidate retryResolved = ResolvedCandidate.merged(
                    resolved.candidate(), existingKey, current.getRevision());

            MemoryWriteResult result = doWrite(userId, retryResolved, provenance, observedAt);
            LOG.infof("Retry after ABORTED succeeded: key=%s rev=%d", existingKey, current.getRevision());
            return result;

        } catch (Exception retryEx) {
            // Retry also failed — fall back to fresh insert so the job is never blocked
            LOG.warnf(retryEx,
                    "Retry after ABORTED also failed for key=%s; falling back to fresh insert",
                    resolved.existingKey().orElse("?"));
            return doWrite(userId, ResolvedCandidate.fresh(resolved.candidate()), provenance, observedAt);
        }
    }

    /**
     * Core write logic shared by fresh insert and guarded upsert paths.
     */
    private MemoryWriteResult doWrite(String userId, ResolvedCandidate resolved,
            Provenance provenance, String observedAt) {
        MemoryCandidate candidate = resolved.candidate();

        LOG.debugf("Writing memory: type=%s, userId=%s, update=%s, content='%s'",
                candidate.type(), userId, resolved.isUpdate(),
                candidate.content().length() > 50
                        ? candidate.content().substring(0, 47) + "..."
                        : candidate.content());

        List<String> namespace = List.of("user", userId, COGNITION_VERSION, candidate.type());

        // Upsert reuses the existing key; fresh insert generates a new UUID
        String key = resolved.existingKey().orElseGet(() -> UUID.randomUUID().toString());
        String effectiveAt = observedAt;

        // Build value struct with memory content, metadata, temporal fields, and provenance.
        // expires_at is null — TTL not yet supported; placeholder for a future phase.
        Struct value = Struct.newBuilder()
                .putFields("content", Value.newBuilder().setStringValue(candidate.content()).build())
                .putFields("confidence", Value.newBuilder().setNumberValue(candidate.confidence()).build())
                .putFields("citations", buildCitationsValue(candidate.citations()))
                .putFields("observed_at", Value.newBuilder().setStringValue(observedAt).build())
                .putFields("effective_at", Value.newBuilder().setStringValue(effectiveAt).build())
                .putFields("expires_at", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build())
                .putFields("provenance", buildProvenanceValue(provenance))
                .build();

        AdminPutMemoryRequest.Builder requestBuilder = AdminPutMemoryRequest.newBuilder()
                .addAllNamespace(namespace)
                .setKey(key)
                .setValue(value)
                .putIndex("content", candidate.content())
                .putIndex("type", candidate.type())
                .putIndex("observed_at", observedAt)
                .putIndex("effective_at", effectiveAt);

        // Set expected_revision for guarded upsert (prevents lost-update conflicts)
        resolved.expectedRevision().ifPresent(requestBuilder::setExpectedRevision);

        MemoryWriteResult result = memoriesStub.putMemory(requestBuilder.build());

        LOG.infof("Memory written successfully: id=%s, type=%s, key=%s, update=%s",
                bytesToUuid(result.getId()), candidate.type(), key, resolved.isUpdate());

        return result;
    }

    /**
     * Write multiple resolved candidates in batch.
     *
     * @param userId     User ID for namespace
     * @param resolved   Resolved candidates from consolidation
     * @param provenance Provenance information shared by all memories in this batch
     * @param observedAt ISO-8601 timestamp when the facts were stated
     * @return List of write results
     */
    public List<MemoryWriteResult> writeResolved(String userId,
            List<ResolvedCandidate> resolved, Provenance provenance, String observedAt) {
        LOG.infof("Writing %d resolved memories for user %s (observedAt=%s)",
                resolved.size(), userId, observedAt);
        return resolved.stream()
                .map(r -> writeResolved(userId, r, provenance, observedAt))
                .toList();
    }

    /**
     * Write multiple memory candidates in batch.
     * Retained for backward compatibility — wraps each candidate as a fresh insert.
     *
     * @param userId     User ID for namespace
     * @param candidates List of memory candidates to write
     * @param provenance Provenance information shared by all memories in this batch
     * @param observedAt ISO-8601 timestamp when the facts were stated
     * @return List of write results
     */
    public List<MemoryWriteResult> writeMemories(String userId,
            List<MemoryCandidate> candidates, Provenance provenance, String observedAt) {
        LOG.infof("Writing %d memories for user %s (observedAt=%s)", candidates.size(), userId, observedAt);
        return candidates.stream()
                .map(candidate -> writeMemory(userId, candidate, provenance, observedAt))
                .toList();
    }
    
    /**
     * Build protobuf Value for citations array.
     * Strips entry reference prefixes (E1:, E2:, etc.) before storage.
     * The E prefix is used internally for provenance tracking but should not appear in stored citations.
     */
    private Value buildCitationsValue(List<String> citations) {
        com.google.protobuf.ListValue.Builder listBuilder = com.google.protobuf.ListValue.newBuilder();
        for (String citation : citations) {
            String cleanedCitation = stripEntryReference(citation);
            listBuilder.addValues(Value.newBuilder().setStringValue(cleanedCitation).build());
        }
        return Value.newBuilder().setListValue(listBuilder.build()).build();
    }
    
    /**
     * Strip entry reference prefix (E1:, E2:, etc.) from citation.
     * Returns the citation text without the entry reference.
     * 
     * @param citation Citation string, possibly with "E<number>: " prefix
     * @return Citation text without entry reference prefix
     */
    private String stripEntryReference(String citation) {
        if (citation == null || citation.isEmpty()) {
            return citation;
        }

        // Match only "E<digits>:" pattern at start — e.g. E1:, E12:
        // Rejects "Error:", "En:", "E1abc:" etc.
        if (citation.startsWith("E")) {
            int colonIndex = citation.indexOf(":");
            if (colonIndex > 1) {
                String between = citation.substring(1, colonIndex);
                if (!between.isEmpty() && between.chars().allMatch(Character::isDigit)) {
                    return citation.substring(colonIndex + 1).trim();
                }
            }
        }

        return citation;
    }

    /**
     * Build protobuf Value for provenance struct.
     * Converts Provenance record to nested protobuf Struct.
     */
    private Value buildProvenanceValue(Provenance provenance) {
        Struct.Builder provenanceStruct = Struct.newBuilder();

        // Batch identification
        provenanceStruct.putFields("conversation_id",
            Value.newBuilder().setStringValue(provenance.conversationId()).build());
        provenanceStruct.putFields("entry_ids",
            buildStringListValue(provenance.entryIds()));
        provenanceStruct.putFields("event_cursors",
            buildEventCursorsValue(provenance.firstEventCursor(), provenance.latestEventCursor()));
        provenanceStruct.putFields("batch_trigger",
            Value.newBuilder().setStringValue(provenance.batchTrigger()).build());

        // Evidence pack fingerprint (optional fields)
        if (provenance.sourceHash() != null) {
            provenanceStruct.putFields("source_hash",
                Value.newBuilder().setStringValue(provenance.sourceHash()).build());
        }
        if (provenance.evidenceBaseId() != null) {
            provenanceStruct.putFields("evidence_base_id",
                Value.newBuilder().setStringValue(provenance.evidenceBaseId()).build());
        }
        if (provenance.evidenceBaseHash() != null) {
            provenanceStruct.putFields("evidence_base_hash",
                Value.newBuilder().setStringValue(provenance.evidenceBaseHash()).build());
        }

        // Runtime attribution
        provenanceStruct.putFields("runtime_id",
            Value.newBuilder().setStringValue(provenance.runtimeId()).build());
        provenanceStruct.putFields("runtime_version",
            Value.newBuilder().setStringValue(provenance.runtimeVersion()).build());
        provenanceStruct.putFields("processed_at",
            Value.newBuilder().setStringValue(provenance.processedAt().toString()).build());

        return Value.newBuilder().setStructValue(provenanceStruct.build()).build();
    }

    /**
     * Build protobuf ListValue from string list.
     */
    private Value buildStringListValue(List<String> strings) {
        com.google.protobuf.ListValue.Builder listBuilder = com.google.protobuf.ListValue.newBuilder();
        for (String str : strings) {
            listBuilder.addValues(Value.newBuilder().setStringValue(str).build());
        }
        return Value.newBuilder().setListValue(listBuilder.build()).build();
    }

    /**
     * Build protobuf Struct for event cursors.
     */
    private Value buildEventCursorsValue(String first, String latest) {
        Struct cursors = Struct.newBuilder()
            .putFields("first", Value.newBuilder().setStringValue(first).build())
            .putFields("latest", Value.newBuilder().setStringValue(latest).build())
            .build();
        return Value.newBuilder().setStructValue(cursors).build();
    }
    
    /**
     * Convert protobuf ByteString (16-byte big-endian) to UUID string.
     */
    private String bytesToUuid(ByteString bytes) {
        if (bytes.size() != 16) {
            throw new IllegalArgumentException("Invalid UUID bytes length: " + bytes.size());
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        long mostSigBits = buffer.getLong();
        long leastSigBits = buffer.getLong();
        return new UUID(mostSigBits, leastSigBits).toString();
    }
    
    /**
     * Exception thrown when memory writing fails.
     */
    public static class MemoryWriteException extends RuntimeException {
        public MemoryWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
