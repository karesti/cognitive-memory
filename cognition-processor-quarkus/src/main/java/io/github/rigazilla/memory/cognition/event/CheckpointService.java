package io.github.rigazilla.memory.cognition.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminCheckpoint;
import io.github.chirino.memory.grpc.v1.AdminCheckpointServiceGrpc;
import io.github.chirino.memory.grpc.v1.GetCheckpointRequest;
import io.github.chirino.memory.grpc.v1.PutCheckpointRequest;
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

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing event stream checkpoints via gRPC AdminCheckpointService.
 * Replaced file-based storage with distributed gRPC checkpoint management.
 */
@ApplicationScoped
public class CheckpointService {

    private static final Logger LOG = Logger.getLogger(CheckpointService.class);
    private static final String CONTENT_TYPE = "application/json";

    @Inject
    MemoryServiceConfig memoryService;

    ManagedChannel channel;
    AdminCheckpointServiceGrpc.AdminCheckpointServiceBlockingStub checkpointStub;

    private final ObjectMapper objectMapper;

    // Track if we've ever successfully saved a checkpoint
    private volatile boolean hasSuccessfullySaved = false;

    public CheckpointService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    void init() {
        LOG.infof("Initializing CheckpointService with gRPC: %s:%d",
                memoryService.grpc().host(), memoryService.grpc().port());

        // Create gRPC channel with authentication interceptor
        channel = GrpcChannelFactory.create(
                memoryService.grpc().host(), memoryService.grpc().port(),
                memoryService.apiKey(), memoryService.clientId());

        // Create blocking stub for synchronous checkpoint operations
        checkpointStub = AdminCheckpointServiceGrpc.newBlockingStub(channel);

        LOG.info("CheckpointService initialized successfully");
    }

    /**
     * Load checkpoint state for a worker via gRPC.
     *
     * @param workerId Worker identifier
     * @return CheckpointState or null if not found or on error
     */
    public CheckpointState loadCheckpoint(String workerId) {
        try {
            GetCheckpointRequest request = GetCheckpointRequest.newBuilder()
                .setClientId(workerId)
                .build();

            AdminCheckpoint checkpoint = checkpointStub.getCheckpoint(request);

            // Check if checkpoint has value
            if (!checkpoint.hasValue()) {
                LOG.infof("No checkpoint value for worker: %s", workerId);
                return null;
            }

            // Extract JSON string from protobuf Value
            Value protoValue = checkpoint.getValue();
            if (!protoValue.hasStringValue()) {
                LOG.warnf("Checkpoint value is not a string for worker: %s", workerId);
                return null;
            }

            String jsonString = protoValue.getStringValue();

            // Deserialize JSON to CheckpointState
            CheckpointState state = objectMapper.readValue(jsonString, CheckpointState.class);

            LOG.infof("Loaded checkpoint for worker %s: cursor=%s, windows=%d, updated=%s",
                     workerId, state.lastEventCursor(), state.dirtyWindows().size(),
                     checkpoint.getUpdatedAt());

            return state;

        } catch (StatusRuntimeException e) {
            Status status = e.getStatus();

            if (status.getCode() == Status.Code.NOT_FOUND) {
                LOG.infof("No checkpoint found for worker: %s", workerId);
                return null;
            } else if (status.getCode() == Status.Code.UNAVAILABLE) {
                LOG.warnf("Memory service unavailable, cannot load checkpoint for worker: %s", workerId);
                return null;
            } else {
                LOG.errorf(e, "Failed to load checkpoint for worker %s: %s", workerId, status);
                return null;
            }

        } catch (IOException e) {
            LOG.errorf(e, "Failed to deserialize checkpoint for worker: %s", workerId);
            return null;
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error loading checkpoint for worker: %s", workerId);
            return null;
        }
    }

    /**
     * Save checkpoint state for a worker via gRPC.
     * Implements retry logic for NOT_FOUND errors which may occur on first save attempt.
     *
     * @param workerId Worker identifier
     * @param state Checkpoint state to save
     */
    public void saveCheckpoint(String workerId, CheckpointState state) {
        try {
            // Serialize CheckpointState to JSON
            String jsonString = objectMapper.writeValueAsString(state);

            // Wrap in protobuf Value
            Value protoValue = Value.newBuilder()
                .setStringValue(jsonString)
                .build();

            // Build request
            PutCheckpointRequest request = PutCheckpointRequest.newBuilder()
                .setClientId(workerId)
                .setContentType(CONTENT_TYPE)
                .setValue(protoValue)
                .build();

            // Try to save checkpoint with retry logic for NOT_FOUND
            AdminCheckpoint response = saveWithRetry(request, workerId);

            if (response != null) {
                hasSuccessfullySaved = true;
                LOG.infof("Saved checkpoint for worker %s: cursor=%s, windows=%d, updated=%s",
                         workerId, state.lastEventCursor(), state.dirtyWindows().size(),
                         response.getUpdatedAt());
            }

        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to serialize checkpoint for worker: %s", workerId);
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error saving checkpoint for worker: %s", workerId);
        }
    }

    /**
     * Save checkpoint with retry logic for NOT_FOUND errors.
     * The memory-service may return NOT_FOUND on first PutCheckpoint call,
     * requiring a retry to actually create the checkpoint.
     */
    private AdminCheckpoint saveWithRetry(PutCheckpointRequest request, String workerId) {
        int maxAttempts = 2;
        StatusRuntimeException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return checkpointStub.putCheckpoint(request);

            } catch (StatusRuntimeException e) {
                Status status = e.getStatus();
                lastException = e;

                // Handle specific error codes
                if (status.getCode() == Status.Code.UNAVAILABLE) {
                    LOG.warnf("Memory service unavailable, cannot save checkpoint for worker: %s", workerId);
                    return null;

                } else if (status.getCode() == Status.Code.PERMISSION_DENIED) {
                    LOG.errorf("Permission denied saving checkpoint for worker %s: %s", workerId, status);
                    return null;

                } else if (status.getCode() == Status.Code.NOT_FOUND) {
                    // NOT_FOUND might occur on first save - retry once
                    if (attempt < maxAttempts) {
                        if (!hasSuccessfullySaved) {
                            LOG.infof("Checkpoint not found for worker %s on first save attempt,"
                                    + " retrying... (attempt %d/%d)",
                                    workerId, attempt, maxAttempts);
                        } else {
                            LOG.warnf("Checkpoint not found for worker %s, retrying... (attempt %d/%d)",
                                     workerId, attempt, maxAttempts);
                        }
                        // Small delay before retry
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                        continue; // Retry
                    } else {
                        // Final attempt failed
                        if (!hasSuccessfullySaved) {
                            LOG.warnf("Failed to create checkpoint for worker %s after %d attempts. " +
                                     "Checkpoint may not be supported or requires manual initialization. Error: %s",
                                     workerId, maxAttempts, status);
                        } else {
                            LOG.errorf("Checkpoint lost for worker %s after %d attempts: %s",
                                      workerId, maxAttempts, status);
                        }
                        return null;
                    }

                } else {
                    // Other errors
                    LOG.errorf(e, "Failed to save checkpoint for worker %s: %s", workerId, status);
                    return null;
                }
            }
        }

        // Should not reach here, but handle gracefully
        if (lastException != null) {
            LOG.errorf(lastException, "Failed to save checkpoint for worker %s after %d attempts",
                      workerId, maxAttempts);
        }
        return null;
    }

    /**
     * Save checkpoint with cursor and dirty windows.
     * Convenience method that builds CheckpointState.
     *
     * @param workerId Worker identifier
     * @param cursor Event cursor
     * @param runtimeId Runtime identifier
     * @param runtimeVersion Runtime version
     * @param dirtyWindows List of serialized windows
     */
    public void saveCheckpoint(String workerId, String cursor, String runtimeId, String runtimeVersion,
                               List<SerializedWindow> dirtyWindows) {
        CheckpointState state = new CheckpointState(
            cursor,
            Instant.now(),
            runtimeId,
            runtimeVersion,
            Instant.now(), // highestEventTimestamp
            dirtyWindows
        );

        saveCheckpoint(workerId, state);
    }

    public void resetCheckpoint(String workerId, String runtimeId, String runtimeVersion) {
        saveCheckpoint(workerId, new CheckpointState(
            "start",  // "start" cursor signals to send the oldest event known
            Instant.now(),
            runtimeId,
            runtimeVersion,
            Instant.now(),
            List.of()
        ));
    }

    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down CheckpointService gRPC channel");
            channel.shutdown();
        }
    }
}

/**
 * Checkpoint state with embedded dirty windows.
 *
 * @param lastEventCursor Last processed event cursor
 * @param updatedAt When this checkpoint was saved
 * @param runtimeId Runtime identifier
 * @param runtimeVersion Runtime version
 * @param highestEventTimestamp Highest event timestamp observed
 * @param dirtyWindows List of open dirty windows
 */
record CheckpointState(
    String lastEventCursor,
    Instant updatedAt,
    String runtimeId,
    String runtimeVersion,
    Instant highestEventTimestamp,
    List<SerializedWindow> dirtyWindows
) {
    public CheckpointState {
        // Ensure dirtyWindows is never null
        if (dirtyWindows == null) {
            dirtyWindows = new ArrayList<>();
        }
    }
}
