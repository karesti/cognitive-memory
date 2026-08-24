package io.github.rigazilla.memory.cognition.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chirino.memory.grpc.v1.EventNotification;
import io.github.chirino.memory.grpc.v1.EventScope;
import io.github.chirino.memory.grpc.v1.EventStreamServiceGrpc;
import io.github.chirino.memory.grpc.v1.SubscribeEventsRequest;
import io.github.rigazilla.memory.cognition.config.CognitionConfig;
import io.github.rigazilla.memory.cognition.config.MemoryServiceConfig;
import io.github.rigazilla.memory.cognition.grpc.GrpcChannelFactory;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.joining;

/**
 * gRPC client for Memory Service admin event stream.
 * Phase 2: Integrated with DirtyWindowRegistry for debounced processing.
 * 
 * Based on Enhancement 099: Quarkus + LangChain4j Cognition Processor
 * https://github.com/chirino/memory-service/blob/main/docs/enhancements/099-quarkus-cognition-processor.md
 */
@ApplicationScoped
public class GrpcAdminEventClient {

    private static final Logger LOG = Logger.getLogger(GrpcAdminEventClient.class);
    private static final String EVENT_DETAIL_FULL = "full";
    private static final Metadata.Key<String> API_KEY_HEADER =
            Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTHORIZATION_HEADER =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_ID_HEADER =
            Metadata.Key.of("x-client-id", Metadata.ASCII_STRING_MARSHALLER);

    @Inject
    MemoryServiceConfig memoryService;

    @Inject
    CognitionConfig cognition;

    @Inject
    CheckpointService checkpointService;

    @Inject
    DirtyWindowRegistry windowRegistry;

    @Inject
    SalienceScorer salienceScorer;

    @Inject
    ObjectMapper objectMapper;

    private ManagedChannel channel;
    final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    
    // Track last cursor for checkpointing
    volatile String lastEventCursor;
    // Package-private so tests can reset the counter between test methods
    final AtomicLong eventsAccepted = new AtomicLong(0);

    void onStart(@Observes StartupEvent event) {
        LOG.info("Starting GrpcAdminEventClient");
        connect();
    }

    void onShutdown(@Observes ShutdownEvent event) {
        LOG.info("Shutting down GrpcAdminEventClient");
        shouldReconnect.set(false);
        disconnect();
        
        // Save final checkpoint with open windows
        saveCheckpoint();
    }

    public synchronized void startIfNeeded() {
        if (connected.get()) {
            LOG.debug("GrpcAdminEventClient already connected");
            return;
        }

        shouldReconnect.set(true);
        connect();
    }

    private void connect() {
        try {
            String workerId = cognition.worker().id();
            String runtimeId = cognition.runtime().id();
            String runtimeVersion = cognition.runtime().version();

            if (cognition.checkpoint().resetOnStartup()) {
                LOG.warnf("Resetting checkpoint for worker %s before subscribing to events", workerId);
                checkpointService.resetCheckpoint(workerId, runtimeId, runtimeVersion);
            }

            // 1. Load checkpoint to determine resume position and restore windows
            CheckpointState checkpoint = checkpointService.loadCheckpoint(workerId);
            String afterCursor = null;

            if (checkpoint != null) {
                afterCursor = checkpoint.lastEventCursor();
                LOG.infof("Resuming from checkpoint cursor: %s", afterCursor);
                
                // Restore dirty windows
                if (!checkpoint.dirtyWindows().isEmpty()) {
                    windowRegistry.restoreWindows(checkpoint.dirtyWindows());
                    LOG.infof("Restored %d dirty windows from checkpoint", checkpoint.dirtyWindows().size());
                }
            } else {
                LOG.info("No checkpoint found, starting from beginning");
            }

            // 2. Create gRPC channel
            channel = GrpcChannelFactory.create(
                    memoryService.grpc().host(), memoryService.grpc().port(),
                    memoryService.apiKey(), memoryService.clientId());

            // 3. Subscribe to admin event stream
            subscribeToEvents(afterCursor);

            connected.set(true);
            reconnectAttempts.set(0);
            LOG.info("Successfully connected to gRPC event stream");

        } catch (Exception e) {
            LOG.errorf(e, "Failed to connect to gRPC event stream");
            scheduleReconnect();
        }
    }

    private void subscribeToEvents(String afterCursor) {
        // Create CallCredentials with X-API-Key, Authorization, and X-Client-ID headers
        // Memory Service requires:
        // - X-API-Key or Authorization: Bearer token for authentication
        // - X-Client-ID: to match against MEMORY_SERVICE_ROLES_ADMIN_CLIENTS for admin role
        CallCredentials credentials = new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
                Metadata metadata = new Metadata();
                    // X-API-Key header for API key authentication
                    metadata.put(API_KEY_HEADER, memoryService.apiKey());
                    // X-Client-ID header for role mapping to admin
                    metadata.put(CLIENT_ID_HEADER, memoryService.clientId());
                applier.apply(metadata);
            }

            @Override
            public void thisUsesUnstableApi() {
                // Required by CallCredentials interface
            }
        };

        // Create stub with authentication
        EventStreamServiceGrpc.EventStreamServiceStub stub = EventStreamServiceGrpc.newStub(channel)
                .withCallCredentials(credentials);
        
        SubscribeEventsRequest.Builder requestBuilder = SubscribeEventsRequest.newBuilder()
                .setScope(EventScope.EVENT_SCOPE_ADMIN)
                .setDetail(EVENT_DETAIL_FULL);
        
        if (afterCursor != null) {
            requestBuilder.setAfterCursor(afterCursor);
        }

        stub.subscribeEvents(requestBuilder.build(), new StreamObserver<EventNotification>() {
            @Override
            public void onNext(EventNotification event) {
                handleEvent(event);
            }

            @Override
            public void onError(Throwable t) {
                handleError(t);
            }

            @Override
            public void onCompleted() {
                handleCompleted();
            }
        });

        LOG.infof("Subscribed to admin event stream (afterCursor: %s)", afterCursor);
    }

    void handleEvent(EventNotification event) {
        try {
            String eventType = event.getEvent();
            String kind = event.getKind();
            String cursor = event.hasCursor() ? event.getCursor() : null;
            
            // Parse JSON data from event
            final var rawData = event.getData();
            final String jsonData = rawData.isEmpty() ? null : rawData.toStringUtf8();
            String conversationId = null;
            String entryId = null;

            if (jsonData != null) {
                // Extract conversation ID — detail=full entry payloads use "conversationId" (model.Entry JSON tag);
                // summary payloads and conversation-kind events use "conversation" (outbox schema, doc 090).
                // "conversation_id" is retained as a defensive fallback; no documented source uses it.
                conversationId = firstNonNull(jsonData, "conversationId", "conversation_id", "conversation");

                // Extract entry ID (for entry events)
                entryId = firstNonNull(jsonData, "entry", "entry_id", "id");
            }
            
            // Update last cursor
            if (cursor != null) {
                lastEventCursor = cursor;
            }
            
            long eventNumber = eventsAccepted.incrementAndGet();
            
            // Enhanced logging with full event details
            LOG.infof("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            LOG.infof("Event #%d received", eventNumber);
            LOG.infof("  Cursor:          %s", cursor);
            LOG.infof("  Type:            %s", eventType);
            LOG.infof("  Kind:            %s", kind);
            LOG.infof("  Conversation ID: %s", conversationId != null ? conversationId : "(none)");
            LOG.infof("  Entry ID:        %s", entryId != null ? entryId : "(none)");
            LOG.infof("  Timestamp:       %s", Instant.now());
            
            if (jsonData != null) {
                // Pretty print JSON data (simple indentation)
                String prettyJson = jsonData
                    .replace("{", "{\n    ")
                    .replace(",", ",\n    ")
                    .replace("}", "\n  }");
                LOG.infof("  Data:\n    %s", prettyJson);
            } else {
                LOG.info("  Data:            (empty)");
            }
            LOG.infof("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            // Handle invalidate events (stale cursor, missed events)
            if ("invalidate".equals(eventType) && "stream".equals(kind)) {
                handleInvalidateEvent(jsonData);
                return;
            }

            // Accept event into dirty window registry (if it has a conversation ID)
            if (conversationId != null && cursor != null) {
                // Salience gate: filter low-salience events before opening a debounce window
                // Events with no extractable user text pass through conservatively
                // A scorer failure must never drop an event — default to keep on any exception
                boolean keep;
                try {
                    keep = salienceScorer.shouldKeep(extractEntryText(jsonData));
                } catch (Exception e) {
                    LOG.warnf(e, "salienceScorer.shouldKeep failed — passing event through conservatively");
                    keep = true;
                }
                if (keep) {
                    windowRegistry.acceptEvent(conversationId, cursor, entryId, Instant.now());
                }
            }
            
            // Periodic checkpoint (every 10 events)
            if (eventNumber % 10 == 0) {
                saveCheckpoint();
            }

        } catch (Exception e) {
            LOG.errorf(e, "Error handling event");
        }
    }

    /**
     * Handle invalidate stream events (cursor beyond retention window).
     * When the checkpoint cursor is older than the event retention window,
     * memory-service sends an invalidate event. We must reset and start fresh.
     */
    private void handleInvalidateEvent(String jsonData) {
        String reason = extractJsonField(jsonData, "reason");

        if ("cursor beyond retention window".equals(reason)) {
            LOG.warnf("⚠️  INVALIDATE EVENT: Checkpoint cursor is beyond retention window");
            LOG.warnf("⚠️  There is a gap in event coverage - some events were missed");
            LOG.warnf("⚠️  Resetting checkpoint and clearing dirty windows");
            LOG.warnf("⚠️  Will reconnect and start receiving new events from now forward");

            // Reset checkpoint - the stored cursor is no longer valid
            try {
                checkpointService.resetCheckpoint(
                        cognition.worker().id(), cognition.runtime().id(), cognition.runtime().version());
                LOG.info("✓ Checkpoint reset successful");
            } catch (Exception e) {
                LOG.errorf(e, "Failed to reset checkpoint");
            }

            // Clear dirty windows - any in-progress work is potentially incomplete
            try {
                windowRegistry.clear();
                LOG.info("✓ Dirty windows cleared");
            } catch (Exception e) {
                LOG.errorf(e, "Failed to clear dirty windows");
            }

            // Disconnect and reconnect without cursor to start fresh
            shouldReconnect.set(true);
            disconnect();
            scheduleReconnect();

        } else {
            LOG.warnf("⚠️  INVALIDATE EVENT: %s", reason != null ? reason : "(unknown reason)");
        }
    }

    /**
     * Extracts the text of all user turns from the entry payload's {@code content} array
     * and returns them joined by a space. Returns {@code null} when no user text is found,
     * which causes the salience gate to pass the event through conservatively.
     *
     * @param json raw JSON string from the event data field
     * @return concatenated user-turn text, or {@code null} if absent or unreadable
     */
    String extractEntryText(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode contentArray = objectMapper.readTree(json).get("content");
            if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
                return null;
            }
            String text = StreamSupport.stream(contentArray.spliterator(), false)
                    .filter(turn -> {
                        JsonNode role = turn.get("role");
                        return role != null && "USER".equals(role.asText(""));
                    })
                    .map(turn -> {
                        JsonNode textNode = turn.get("text");
                        return textNode != null ? textNode.asText("").strip() : "";
                    })
                    .filter(t -> !t.isEmpty())
                    .collect(joining(" "));
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            LOG.debugf("extractEntryText: could not parse content array (%s)", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a scalar string field value from a JSON string.
     * Returns {@code null} if the field is absent.
     */
    String extractJsonField(String json, String fieldName) {
        String searchPattern = "\"" + fieldName + "\":\"";
        int fieldIndex = json.indexOf(searchPattern);
        if (fieldIndex != -1) {
            int startIdx = fieldIndex + searchPattern.length();
            int endIdx = json.indexOf("\"", startIdx);
            if (endIdx != -1) {
                return json.substring(startIdx, endIdx);
            }
        }
        return null;
    }

    /**
     * Returns the value of the first field name in {@code fieldNames} present in
     * {@code json}, or {@code null} if none are found.
     */
    String firstNonNull(String json, String... fieldNames) {
        return Arrays.stream(fieldNames)
                .map(name -> extractJsonField(json, name))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    void saveCheckpoint() {
        if (lastEventCursor == null) {
            LOG.debug("No cursor to checkpoint");
            return;
        }

        try {
            // Serialize current dirty windows
            var dirtyWindows = windowRegistry.serializeWindows();
            
            checkpointService.saveCheckpoint(
                cognition.worker().id(),
                lastEventCursor,
                cognition.runtime().id(),
                cognition.runtime().version(),
                dirtyWindows
            );

            LOG.infof("✓ Checkpoint saved at cursor: %s (windows: %d, events: %d)",
                     lastEventCursor, dirtyWindows.size(), eventsAccepted.get());

        } catch (Exception e) {
            LOG.errorf(e, "Failed to save checkpoint");
        }
    }

    private void handleError(Throwable t) {
        connected.set(false);
        Status status = Status.fromThrowable(t);
        LOG.errorf(t, "Event stream error: %s", status);

        if (shouldReconnect.get()) {
            scheduleReconnect();
        }
    }

    private void handleCompleted() {
        connected.set(false);
        LOG.info("Event stream completed");

        if (shouldReconnect.get()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        int attempts = reconnectAttempts.incrementAndGet();
        long delaySeconds = Math.min(60, (long) Math.pow(2, attempts)); // Exponential backoff, max 60s

        LOG.infof("Scheduling reconnect attempt %d in %d seconds", attempts, delaySeconds);

        // Use simple thread for reconnection (TODO: use proper scheduler)
        new Thread(() -> {
            try {
                Thread.sleep(delaySeconds * 1000);
                if (shouldReconnect.get()) {
                    connect();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void disconnect() {
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public long getEventCount() {
        return eventsAccepted.get();
    }
    
    public int getWindowCount() {
        return windowRegistry.getWindowCount();
    }
    
    public String getHost() {
        return memoryService.grpc().host();
    }

    public int getPort() {
        return memoryService.grpc().port();
    }
}
