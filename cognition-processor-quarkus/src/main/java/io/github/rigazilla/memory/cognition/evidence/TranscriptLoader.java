package io.github.rigazilla.memory.cognition.evidence;

import com.google.protobuf.ByteString;
import io.github.chirino.memory.grpc.v1.AdminEntriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminListEntriesRequest;
import io.github.chirino.memory.grpc.v1.Channel;
import io.github.chirino.memory.grpc.v1.Entry;
import io.github.chirino.memory.grpc.v1.ListEntriesResponse;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads conversation transcript from Memory Service via gRPC.
 * Uses AdminEntriesService.ListEntries to fetch history channel entries with admin permissions.
 */
@ApplicationScoped
public class TranscriptLoader {

    private static final Logger LOG = Logger.getLogger(TranscriptLoader.class);

    @ConfigProperty(name = "memory-service.grpc.host")
    String grpcHost;

    @ConfigProperty(name = "memory-service.grpc.port")
    int grpcPort;

    @ConfigProperty(name = "memory-service.api-key")
    String apiKey;

    private ManagedChannel channel;
    private AdminEntriesServiceGrpc.AdminEntriesServiceBlockingStub entriesStub;
    
    @PostConstruct
    void init() {
        LOG.infof("Initializing TranscriptLoader: %s:%d", grpcHost, grpcPort);
        
        // Create gRPC channel with authentication interceptor
        channel = ManagedChannelBuilder
            .forAddress(grpcHost, grpcPort)
            .usePlaintext()
            .intercept(new AuthInterceptor(apiKey))
            .build();

        // Create admin stub (no on-behalf-of needed, admin has full access)
        entriesStub = AdminEntriesServiceGrpc.newBlockingStub(channel);
        
        LOG.info("TranscriptLoader initialized successfully");
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

    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down TranscriptLoader gRPC channel");
            channel.shutdown();
        }
    }
    
    /**
     * Load transcript for a conversation batch.
     *
     * @param conversationId Conversation UUID
     * @param entryIds List of entry IDs in this batch (in chronological order)
     * @param previousEntryId Entry ID before the first entry in this batch (null for first batch)
     * @param actorUserId User ID to act on behalf of (conversation owner)
     * @return EvidencePack containing transcript entries
     */
    public EvidencePack loadTranscript(String conversationId, List<String> entryIds, String previousEntryId, String actorUserId) {
        try {
            LOG.debugf("Loading transcript for conversation: %s", conversationId);
            LOG.debugf("  Batch entry count: %d", entryIds.size());
            LOG.debugf("  Previous entry ID: %s", previousEntryId != null ? previousEntryId : "(none - first batch)");

            // Convert conversation ID string to UUID bytes
            ByteString conversationIdBytes = uuidToBytes(conversationId);

            // Build admin request for history channel entries with range filter
            AdminListEntriesRequest.Builder requestBuilder = AdminListEntriesRequest.newBuilder()
                .setConversationId(conversationIdBytes)
                .setChannel(Channel.HISTORY);

            // Set page_token to previous entry ID (empty string if null = start from beginning)
            String pageToken = previousEntryId != null ? previousEntryId : "";
            requestBuilder.setPage(io.github.chirino.memory.grpc.v1.PageRequest.newBuilder()
                .setPageToken(pageToken)
                .setPageSize(1000)  // Large enough for typical batch
            );

            // Set up_to_entry_id to last entry in batch (inclusive)
            if (!entryIds.isEmpty()) {
                String lastEntryId = entryIds.get(entryIds.size() - 1);
                ByteString lastEntryIdBytes = uuidToBytes(lastEntryId);
                requestBuilder.setUpToEntryId(lastEntryIdBytes);
            }

            // Call admin gRPC service (admin has full access, no on-behalf-of needed)
            LOG.debugf("Loading transcript with admin permissions for conversation owned by: %s", actorUserId);
            ListEntriesResponse response = entriesStub.listEntries(requestBuilder.build());
            List<Entry> entries = response.getEntriesList();

            LOG.infof("Loaded %d transcript entries for conversation %s (batch requested %d)",
                     entries.size(), conversationId, entryIds.size());

            // Debug log: show entry details
            if (LOG.isDebugEnabled() && !entries.isEmpty()) {
                LOG.debugf("Transcript entries for conversation %s:", conversationId);
                for (Entry entry : entries) {
                    logEntryDetails(entry);
                }
            }

            // Convert to EvidencePack
            return new EvidencePack(entries);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to load transcript for conversation %s", conversationId);
            throw new TranscriptLoadException("Failed to load transcript for conversation " + conversationId, e);
        }
    }
    
    /**
     * Log entry details for debugging.
     */
    private void logEntryDetails(Entry entry) {
        try {
            String entryId = entry.getId().isEmpty() ? "(no-id)" : bytesToUuid(entry.getId());
            String contentType = entry.getContentType();

            // Extract role and text from history content
            // Match "history" or "history/lc4j" or similar variants
            if (contentType != null && contentType.startsWith("history") && entry.getContentCount() > 0) {
                var content = entry.getContent(0);
                if (content.hasStructValue()) {
                    var struct = content.getStructValue();

                    String role = struct.getFieldsOrDefault("role",
                        com.google.protobuf.Value.newBuilder().setStringValue("UNKNOWN").build())
                        .getStringValue();

                    // Extract text - different structure for history vs history/lc4j
                    String text = extractTextFromStruct(struct);

                    String preview = text.length() > 100 ? text.substring(0, 97) + "..." : text;
                    LOG.debugf("  - Entry %s [%s]: %s", entryId, role, preview);
                } else {
                    LOG.debugf("  - Entry %s [%s]: (non-struct content)", entryId, contentType);
                }
            } else {
                LOG.debugf("  - Entry %s [%s]: (non-history content)", entryId, contentType);
            }
        } catch (Exception e) {
            LOG.debugf("  - Entry (error logging details): %s", e.getMessage());
        }
    }

    /**
     * Extract text from entry struct.
     * Handles both plain "history" format (text field) and "history/lc4j" format (events array).
     */
    private String extractTextFromStruct(com.google.protobuf.Struct struct) {
        // Try simple "text" field first (plain history entries)
        if (struct.containsFields("text")) {
            return struct.getFieldsOrDefault("text",
                com.google.protobuf.Value.newBuilder().setStringValue("").build())
                .getStringValue();
        }

        // Try "events" array (history/lc4j entries)
        if (struct.containsFields("events")) {
            var eventsValue = struct.getFieldsOrThrow("events");
            if (eventsValue.hasListValue()) {
                var eventsList = eventsValue.getListValue();

                // Look for "Completed" event with aiMessage.text
                for (var event : eventsList.getValuesList()) {
                    if (event.hasStructValue()) {
                        var eventStruct = event.getStructValue();

                        // Check if this is a Completed event
                        if (eventStruct.containsFields("eventType")) {
                            String eventType = eventStruct.getFieldsOrThrow("eventType").getStringValue();

                            if ("Completed".equals(eventType) && eventStruct.containsFields("aiMessage")) {
                                var aiMessage = eventStruct.getFieldsOrThrow("aiMessage");
                                if (aiMessage.hasStructValue()) {
                                    var aiMessageStruct = aiMessage.getStructValue();
                                    if (aiMessageStruct.containsFields("text")) {
                                        return aiMessageStruct.getFieldsOrThrow("text").getStringValue();
                                    }
                                }
                            }

                            // Fallback: use PartialResponse chunk if available
                            if ("PartialResponse".equals(eventType) && eventStruct.containsFields("chunk")) {
                                return eventStruct.getFieldsOrThrow("chunk").getStringValue();
                            }
                        }
                    }
                }
            }
        }

        return "";
    }

    /**
     * Convert protobuf ByteString (16-byte big-endian) to UUID string.
     */
    private String bytesToUuid(ByteString bytes) {
        if (bytes.size() != 16) {
            return "(invalid-uuid)";
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        long mostSigBits = buffer.getLong();
        long leastSigBits = buffer.getLong();
        return new UUID(mostSigBits, leastSigBits).toString();
    }

    /**
     * Convert UUID string to protobuf ByteString (16-byte big-endian).
     */
    private ByteString uuidToBytes(String uuidString) {
        try {
            UUID uuid = UUID.fromString(uuidString);
            ByteBuffer buffer = ByteBuffer.allocate(16);
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
            return ByteString.copyFrom(buffer.array());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID format: " + uuidString, e);
        }
    }
    
    /**
     * Exception thrown when transcript loading fails.
     */
    public static class TranscriptLoadException extends RuntimeException {
        public TranscriptLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
