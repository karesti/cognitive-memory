package io.github.rigazilla.memory.cognition.justify;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminEntriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminGetEntryRequest;
import io.github.chirino.memory.grpc.v1.AdminGetMemoryRequest;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.Entry;
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

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for retrieving memory justify by expanding entry IDs into full entry content.
 * Fetches memories and their source entries from memory-service via gRPC.
 */
@ApplicationScoped
public class MemoryJustifyService {
    
    private static final Logger LOG = Logger.getLogger(MemoryJustifyService.class);
    
    @Inject
    MemoryServiceConfig memoryService;

    ManagedChannel channel;
    AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub memoriesStub;
    AdminEntriesServiceGrpc.AdminEntriesServiceBlockingStub entriesStub;
    
    @PostConstruct
    void init() {
        LOG.infof("Initializing MemoryJustifyService: %s:%d",
                memoryService.grpc().host(), memoryService.grpc().port());

        // Create gRPC channel with authentication interceptor
        channel = GrpcChannelFactory.create(
                memoryService.grpc().host(), memoryService.grpc().port(),
                memoryService.apiKey(), memoryService.clientId());
        
        memoriesStub = AdminMemoriesServiceGrpc.newBlockingStub(channel);
        entriesStub = AdminEntriesServiceGrpc.newBlockingStub(channel);
        
        LOG.info("MemoryJustifyService initialized successfully");
    }
    
    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down MemoryJustifyService gRPC channel");
            channel.shutdown();
        }
    }
    
    /**
     * Get memory with full justification (expanded entry details).
     *
     * @param memoryId Memory ID (UUID string)
     * @return Memory with expanded source entries
     * @throws MemoryNotFoundException if memory not found
     * @throws JustifyException if retrieval fails
     */
    public MemoryJustifyResponse getMemoryJustify(String memoryId) {
        try {
            LOG.infof("Fetching memory justify for: %s", memoryId);

            // 1. Fetch the memory
            AdminMemoryItem memory = fetchMemory(memoryId);

            // 2. Extract content, confidence, citations, and conversation ID
            Struct valueStruct = memory.getValue();
            Map<String, Value> fields = valueStruct.getFieldsMap();

            String content = fields.getOrDefault("content", Value.getDefaultInstance()).getStringValue();
            double confidence = fields.getOrDefault("confidence", Value.getDefaultInstance()).getNumberValue();

            List<String> citations = new ArrayList<>();
            if (fields.containsKey("citations")) {
                for (Value v : fields.get("citations").getListValue().getValuesList()) {
                    citations.add(v.getStringValue());
                }
            }

            // Extract conversation ID and entry IDs from provenance
            String conversationId = "";
            List<String> entryIds = List.of();
            if (fields.containsKey("provenance")) {
                Struct provenanceStruct = fields.get("provenance").getStructValue();
                Map<String, Value> provFields = provenanceStruct.getFieldsMap();
                conversationId = provFields.getOrDefault("conversation_id",
                        Value.getDefaultInstance()).getStringValue();

                if (provFields.containsKey("entry_ids")) {
                    List<String> ids = new ArrayList<>();
                    for (Value v : provFields.get("entry_ids").getListValue().getValuesList()) {
                        ids.add(v.getStringValue());
                    }
                    entryIds = ids;
                }
            }

            // 3. Fetch all source entries
            List<MemoryJustifyResponse.EntryDetail> sourceEntries = fetchSourceEntries(entryIds);

            // 4. Build simplified response
            return new MemoryJustifyResponse(
                bytesToUuid(memory.getId()),
                content,
                confidence,
                citations,
                conversationId,
                sourceEntries,
                Instant.ofEpochSecond(memory.getCreatedAt().getSeconds(), memory.getCreatedAt().getNanos())
            );

        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new MemoryNotFoundException("Memory not found: " + memoryId);
            }
            LOG.errorf(e, "Failed to fetch memory justify: %s", memoryId);
            throw new JustifyException("Failed to fetch memory justify", e);
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error fetching memory justify: %s", memoryId);
            throw new JustifyException("Unexpected error fetching memory justify", e);
        }
    }
    
    /**
     * Fetch memory from memory-service.
     */
    private AdminMemoryItem fetchMemory(String memoryId) {
        ByteString memoryIdBytes = uuidToBytes(memoryId);
        
        AdminGetMemoryRequest request = AdminGetMemoryRequest.newBuilder()
            .setId(memoryIdBytes)
            .setIncludeUsage(false)
            .setJustification("Cognition processor retrieving memory for justification API")
            .build();
        
        return memoriesStub.getMemory(request);
    }
    
    
    /**
     * Fetch all source entries by ID.
     */
    private List<MemoryJustifyResponse.EntryDetail> fetchSourceEntries(List<String> entryIds) {
        List<MemoryJustifyResponse.EntryDetail> entries = new ArrayList<>();
        
        for (String entryId : entryIds) {
            try {
                Entry entry = fetchEntry(entryId);
                entries.add(convertEntry(entry));
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                    LOG.warnf("Entry not found (may be archived): %s", entryId);
                    // Add placeholder for missing entry
                    entries.add(createMissingEntryPlaceholder(entryId));
                } else {
                    LOG.errorf(e, "Failed to fetch entry: %s", entryId);
                    throw e;
                }
            }
        }
        
        return entries;
    }
    
    /**
     * Fetch a single entry by ID.
     */
    private Entry fetchEntry(String entryId) {
        ByteString entryIdBytes = uuidToBytes(entryId);
        
        AdminGetEntryRequest request = AdminGetEntryRequest.newBuilder()
            .setEntryId(entryIdBytes)
            .setJustification("Cognition processor retrieving entry for memory justify")
            .build();
        
        return entriesStub.getEntry(request);
    }
    
    /**
     * Convert protobuf Entry to simplified EntryDetail.
     * Extracts only role, text, and timestamp.
     */
    private MemoryJustifyResponse.EntryDetail convertEntry(Entry entry) {
        // Extract first content block
        String text = "";
        String role = "";

        if (!entry.getContentList().isEmpty()) {
            Value contentValue = entry.getContent(0);
            if (contentValue.hasStructValue()) {
                Struct contentStruct = contentValue.getStructValue();
                Map<String, Value> contentFields = contentStruct.getFieldsMap();

                role = contentFields.getOrDefault("role", Value.getDefaultInstance()).getStringValue();

                // USER entries: text is directly in "text" field
                if (contentFields.containsKey("text")) {
                    text = contentFields.get("text").getStringValue();
                } else if (contentFields.containsKey("events")) {
                    text = extractAiMessageText(contentFields.get("events"));
                }
            }
        }

        return new MemoryJustifyResponse.EntryDetail(
            role,
            text,
            entry.getCreatedAt()
        );
    }

    /**
     * Extract AI message text from events array.
     * Looks for event with eventType="Completed" and extracts aiMessage.text.
     */
    private String extractAiMessageText(Value eventsValue) {
        if (!eventsValue.hasListValue()) {
            return "";
        }

        // Iterate through events array
        for (Value eventValue : eventsValue.getListValue().getValuesList()) {
            if (!eventValue.hasStructValue()) {
                continue;
            }

            Struct eventStruct = eventValue.getStructValue();
            Map<String, Value> eventFields = eventStruct.getFieldsMap();

            // Look for Completed event
            if (eventFields.containsKey("eventType") &&
                "Completed".equals(eventFields.get("eventType").getStringValue())) {

                // Extract aiMessage.text
                if (eventFields.containsKey("aiMessage")) {
                    Struct aiMessage = eventFields.get("aiMessage").getStructValue();
                    Map<String, Value> aiMessageFields = aiMessage.getFieldsMap();

                    if (aiMessageFields.containsKey("text")) {
                        return aiMessageFields.get("text").getStringValue();
                    }
                }
            }
        }

        return "";
    }
    
    /**
     * Create placeholder for missing/archived entry.
     */
    private MemoryJustifyResponse.EntryDetail createMissingEntryPlaceholder(String entryId) {
        return new MemoryJustifyResponse.EntryDetail(
            "SYSTEM",
            "[Entry not available - may be archived or deleted]",
            ""
        );
    }
    
    
    /**
     * Convert UUID string to protobuf ByteString (16-byte big-endian).
     */
    private ByteString uuidToBytes(String uuidString) {
        UUID uuid = UUID.fromString(uuidString);
        ByteBuffer buffer = ByteBuffer.wrap(new byte[16]);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return ByteString.copyFrom(buffer.array());
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
     * Exception thrown when memory is not found.
     */
    public static class MemoryNotFoundException extends RuntimeException {
        public MemoryNotFoundException(String message) {
            super(message);
        }
    }
    
    /**
     * Exception thrown when justification retrieval fails.
     */
    public static class JustifyException extends RuntimeException {
        public JustifyException(String message) {
            super(message);
        }
        
        public JustifyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

// Made with Bob
