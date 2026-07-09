package io.github.rigazilla.memory.cognition.writer;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.MemoryWriteResult;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.PutMemoryRequest;
import io.github.chirino.memory.grpc.v1.RequestActor;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import io.github.rigazilla.memory.cognition.model.Provenance;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.CallOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
    
    @ConfigProperty(name = "memory-service.grpc.host")
    String grpcHost;
    
    @ConfigProperty(name = "memory-service.grpc.port")
    int grpcPort;
    
    @ConfigProperty(name = "memory-service.api-key")
    String apiKey;
    
    private ManagedChannel channel;
    private AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub memoriesStub;
    
    @PostConstruct
    void init() {
        LOG.infof("Initializing MemoryWriter: %s:%d", grpcHost, grpcPort);
        
        // Create gRPC channel with authentication interceptor
        channel = ManagedChannelBuilder
            .forAddress(grpcHost, grpcPort)
            .usePlaintext()
            .intercept(new AuthInterceptor(apiKey))
            .build();
        
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
     *
     * @param userId User ID for namespace
     * @param candidate Memory candidate to write
     * @param provenance Provenance information for audit and replay
     * @return Memory write result with ID and metadata
     */
    public MemoryWriteResult writeMemory(String userId, MemoryCandidate candidate, Provenance provenance) {
        try {
            LOG.debugf("Writing memory: type=%s, userId=%s, content='%s'",
                candidate.type(), userId,
                candidate.content().length() > 50 ?
                    candidate.content().substring(0, 47) + "..." :
                    candidate.content());

            // Build namespace: ["user", userId, "cognition.v1", memoryType]
            List<String> namespace = List.of("user", userId, COGNITION_VERSION, candidate.type());

            // Generate unique key for this memory
            String key = UUID.randomUUID().toString();

            // Build value struct with memory content, metadata, and provenance
            Struct value = Struct.newBuilder()
                .putFields("content", Value.newBuilder().setStringValue(candidate.content()).build())
                .putFields("confidence", Value.newBuilder().setNumberValue(candidate.confidence()).build())
                .putFields("citations", buildCitationsValue(candidate.citations()))
                .putFields("provenance", buildProvenanceValue(provenance))
                .build();

            // Build request with RequestActor for on-behalf-of authorization
            PutMemoryRequest request = PutMemoryRequest.newBuilder()
                .addAllNamespace(namespace)
                .setKey(key)
                .setValue(value)
                .putIndex("content", candidate.content())
                .putIndex("type", candidate.type())
                .setActor(RequestActor.newBuilder()
                    .setOnBehalfOfUserId(userId)
                    .build())
                .build();

            // Call gRPC service
            MemoryWriteResult result = memoriesStub.putMemory(request);

            LOG.infof("Memory written successfully: id=%s, type=%s, key=%s",
                bytesToUuid(result.getId()), candidate.type(), key);

            return result;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to write memory: type=%s, userId=%s", candidate.type(), userId);
            throw new MemoryWriteException("Failed to write memory for user " + userId, e);
        }
    }
    
    /**
     * Write multiple memory candidates in batch.
     *
     * @param userId User ID for namespace
     * @param candidates List of memory candidates to write
     * @param provenance Provenance information shared by all memories in this batch
     * @return List of write results
     */
    public List<MemoryWriteResult> writeMemories(String userId, List<MemoryCandidate> candidates, Provenance provenance) {
        LOG.infof("Writing %d memories for user %s", candidates.size(), userId);

        return candidates.stream()
            .map(candidate -> writeMemory(userId, candidate, provenance))
            .toList();
    }
    
    /**
     * Build protobuf Value for citations array.
     */
    private Value buildCitationsValue(List<String> citations) {
        com.google.protobuf.ListValue.Builder listBuilder = com.google.protobuf.ListValue.newBuilder();
        for (String citation : citations) {
            listBuilder.addValues(Value.newBuilder().setStringValue(citation).build());
        }
        return Value.newBuilder().setListValue(listBuilder.build()).build();
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
     * Interceptor that adds authentication headers to all gRPC calls.
     */
    private static class AuthInterceptor implements ClientInterceptor {
        private final String apiKey;
        
        AuthInterceptor(String apiKey) {
            this.apiKey = apiKey;
        }
        
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method,
                CallOptions callOptions,
                io.grpc.Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    headers.put(Metadata.Key.of("X-API-Key", Metadata.ASCII_STRING_MARSHALLER), apiKey);
                    super.start(responseListener, headers);
                }
            };
        }
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
