package io.github.rigazilla.memory.cognition.event;

import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminCheckpoint;
import io.github.chirino.memory.grpc.v1.AdminCheckpointServiceGrpc;
import io.github.chirino.memory.grpc.v1.GetCheckpointRequest;
import io.github.chirino.memory.grpc.v1.PutCheckpointRequest;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CheckpointService.
 *
 * Uses @QuarkusTest so CDI wires MemoryServiceConfig from test/resources/application.properties.
 * The gRPC stub and channel are replaced with mocks in @BeforeEach to avoid real network calls.
 *
 * Tests cover:
 * - Checkpoint loading (success, not found, errors)
 * - Checkpoint saving (success, retry logic, errors)
 * - JSON serialization/deserialization
 * - Error handling for various gRPC status codes
 * - CheckpointState record validation
 */
@QuarkusTest
class CheckpointServiceTest {

    @Inject
    CheckpointService service;

    /** The real (non-proxy) bean instance — used for field injection of mock stubs. */
    private CheckpointService realService;

    private AdminCheckpointServiceGrpc.AdminCheckpointServiceBlockingStub mockStub;

    @BeforeEach
    void setUp() {
        mockStub = mock(AdminCheckpointServiceGrpc.AdminCheckpointServiceBlockingStub.class);
        ManagedChannel mockChannel = mock(ManagedChannel.class);

        // Unwrap CDI proxy to reach the real bean instance — field assignment on the proxy
        // itself is a no-op because @ApplicationScoped beans are wrapped in client proxies.
        // arc_contextualInstance() returns the actual delegate, not the proxy shell.
        realService = (CheckpointService) ((io.quarkus.arc.ClientProxy) service).arc_contextualInstance();
        realService.checkpointStub = mockStub;
        realService.channel = mockChannel;
    }

    @Test
    void testLoadCheckpoint_Success_ReturnsState() {
        // Given: Valid checkpoint exists
        String workerId = "worker-1";
        String jsonState = "{\"lastEventCursor\":\"cursor-123\",\"updatedAt\":\"2026-08-04T10:00:00Z\"," +
                          "\"runtimeId\":\"runtime-1\",\"runtimeVersion\":\"1.0\"," +
                          "\"highestEventTimestamp\":\"2026-08-04T10:00:00Z\",\"dirtyWindows\":[]}";
        
        AdminCheckpoint checkpoint = AdminCheckpoint.newBuilder()
            .setValue(Value.newBuilder().setStringValue(jsonState).build())
            .build();

        when(mockStub.getCheckpoint(any(GetCheckpointRequest.class))).thenReturn(checkpoint);

        // When: Load checkpoint
        CheckpointState state = service.loadCheckpoint(workerId);

        // Then: Should return deserialized state
        assertNotNull(state);
        assertEquals("cursor-123", state.lastEventCursor());
        assertEquals("runtime-1", state.runtimeId());
        assertEquals("1.0", state.runtimeVersion());
        assertTrue(state.dirtyWindows().isEmpty());
    }

    @Test
    void testLoadCheckpoint_NotFound_ReturnsNull() {
        // Given: Checkpoint does not exist
        String workerId = "worker-new";
        when(mockStub.getCheckpoint(any(GetCheckpointRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

        // When: Load checkpoint
        CheckpointState state = service.loadCheckpoint(workerId);

        // Then: Should return null
        assertNull(state);
    }

    @Test
    void testLoadCheckpoint_Unavailable_ReturnsNull() {
        // Given: Service is unavailable
        String workerId = "worker-1";
        when(mockStub.getCheckpoint(any(GetCheckpointRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        // When: Load checkpoint
        CheckpointState state = service.loadCheckpoint(workerId);

        // Then: Should return null and log warning
        assertNull(state);
    }

    @Test
    void testLoadCheckpoint_NoValue_ReturnsNull() {
        // Given: Checkpoint exists but has no value
        String workerId = "worker-1";
        AdminCheckpoint checkpoint = AdminCheckpoint.newBuilder().build();
        when(mockStub.getCheckpoint(any(GetCheckpointRequest.class))).thenReturn(checkpoint);

        // When: Load checkpoint
        CheckpointState state = service.loadCheckpoint(workerId);

        // Then: Should return null
        assertNull(state);
    }

    @Test
    void testLoadCheckpoint_InvalidJson_ReturnsNull() {
        // Given: Checkpoint has invalid JSON
        String workerId = "worker-1";
        AdminCheckpoint checkpoint = AdminCheckpoint.newBuilder()
            .setValue(Value.newBuilder().setStringValue("invalid-json").build())
            .build();
        when(mockStub.getCheckpoint(any(GetCheckpointRequest.class))).thenReturn(checkpoint);

        // When: Load checkpoint
        CheckpointState state = service.loadCheckpoint(workerId);

        // Then: Should return null and log error
        assertNull(state);
    }

    @Test
    void testSaveCheckpoint_Success_SavesState() {
        // Given: Valid checkpoint state
        String workerId = "worker-1";
        CheckpointState state = new CheckpointState(
            "cursor-456",
            Instant.parse("2026-08-04T10:00:00Z"),
            "runtime-1",
            "1.0",
            Instant.parse("2026-08-04T10:00:00Z"),
            List.of()
        );

        AdminCheckpoint response = AdminCheckpoint.newBuilder().build();
        when(mockStub.putCheckpoint(any(PutCheckpointRequest.class))).thenReturn(response);

        // When: Save checkpoint
        service.saveCheckpoint(workerId, state);

        // Then: Should call gRPC with serialized JSON
        ArgumentCaptor<PutCheckpointRequest> captor = ArgumentCaptor.forClass(PutCheckpointRequest.class);
        verify(mockStub).putCheckpoint(captor.capture());

        PutCheckpointRequest request = captor.getValue();
        assertEquals(workerId, request.getClientId());
        assertEquals("application/json", request.getContentType());
        assertTrue(request.getValue().getStringValue().contains("cursor-456"));
    }

    @Test
    void testSaveCheckpoint_NotFoundRetry_SucceedsOnSecondAttempt() {
        // Given: First attempt returns NOT_FOUND, second succeeds
        String workerId = "worker-new";
        CheckpointState state = new CheckpointState(
            "cursor-1",
            Instant.now(),
            "runtime-1",
            "1.0",
            Instant.now(),
            List.of()
        );

        AdminCheckpoint response = AdminCheckpoint.newBuilder().build();

        when(mockStub.putCheckpoint(any(PutCheckpointRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.NOT_FOUND))
            .thenReturn(response);

        // When: Save checkpoint
        service.saveCheckpoint(workerId, state);

        // Then: Should retry and succeed
        verify(mockStub, times(2)).putCheckpoint(any(PutCheckpointRequest.class));
    }

    @Test
    void testSaveCheckpoint_NotFoundRetryFails_LogsWarning() {
        // Given: Both attempts return NOT_FOUND
        String workerId = "worker-new";
        CheckpointState state = new CheckpointState(
            "cursor-1",
            Instant.now(),
            "runtime-1",
            "1.0",
            Instant.now(),
            List.of()
        );

        when(mockStub.putCheckpoint(any(PutCheckpointRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

        // When: Save checkpoint
        service.saveCheckpoint(workerId, state);

        // Then: Should retry twice and log warning
        verify(mockStub, times(2)).putCheckpoint(any(PutCheckpointRequest.class));
    }

    @Test
    void testSaveCheckpoint_Unavailable_DoesNotRetry() {
        // Given: Service is unavailable
        String workerId = "worker-1";
        CheckpointState state = new CheckpointState(
            "cursor-1",
            Instant.now(),
            "runtime-1",
            "1.0",
            Instant.now(),
            List.of()
        );

        when(mockStub.putCheckpoint(any(PutCheckpointRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        // When: Save checkpoint
        service.saveCheckpoint(workerId, state);

        // Then: Should not retry
        verify(mockStub, times(1)).putCheckpoint(any(PutCheckpointRequest.class));
    }

    @Test
    void testSaveCheckpoint_PermissionDenied_DoesNotRetry() {
        // Given: Permission denied
        String workerId = "worker-1";
        CheckpointState state = new CheckpointState(
            "cursor-1",
            Instant.now(),
            "runtime-1",
            "1.0",
            Instant.now(),
            List.of()
        );

        when(mockStub.putCheckpoint(any(PutCheckpointRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.PERMISSION_DENIED));

        // When: Save checkpoint
        service.saveCheckpoint(workerId, state);

        // Then: Should not retry
        verify(mockStub, times(1)).putCheckpoint(any(PutCheckpointRequest.class));
    }

    @Test
    void testSaveCheckpoint_ConvenienceMethod_BuildsState() {
        // Given: Checkpoint parameters
        String workerId = "worker-1";
        String cursor = "cursor-789";
        String runtimeId = "runtime-1";
        String runtimeVersion = "1.0";
        List<SerializedWindow> windows = List.of(
            new SerializedWindow("conv1", "c1", "c1", List.of(), null, 
                               Instant.now(), Instant.now(), Instant.now(), 0)
        );

        AdminCheckpoint response = AdminCheckpoint.newBuilder().build();
        when(mockStub.putCheckpoint(any(PutCheckpointRequest.class))).thenReturn(response);

        // When: Save checkpoint using convenience method
        service.saveCheckpoint(workerId, cursor, runtimeId, runtimeVersion, windows);

        // Then: Should build and save state
        ArgumentCaptor<PutCheckpointRequest> captor = ArgumentCaptor.forClass(PutCheckpointRequest.class);
        verify(mockStub).putCheckpoint(captor.capture());

        String json = captor.getValue().getValue().getStringValue();
        assertTrue(json.contains("cursor-789"));
        assertTrue(json.contains("runtime-1"));
        assertTrue(json.contains("conv1"));
    }

    @Test
    void testResetCheckpoint_SavesStartCursor() {
        // Given: Worker to reset
        String workerId = "worker-1";
        String runtimeId = "runtime-1";
        String runtimeVersion = "1.0";

        AdminCheckpoint response = AdminCheckpoint.newBuilder().build();
        when(mockStub.putCheckpoint(any(PutCheckpointRequest.class))).thenReturn(response);

        // When: Reset checkpoint
        service.resetCheckpoint(workerId, runtimeId, runtimeVersion);

        // Then: Should save checkpoint with "start" cursor
        ArgumentCaptor<PutCheckpointRequest> captor = ArgumentCaptor.forClass(PutCheckpointRequest.class);
        verify(mockStub).putCheckpoint(captor.capture());

        String json = captor.getValue().getValue().getStringValue();
        assertTrue(json.contains("\"lastEventCursor\":\"start\""));
        assertTrue(json.contains("\"dirtyWindows\":[]"));
    }

    @Test
    void testCheckpointState_NullDirtyWindows_InitializesEmptyList() {
        // Given: CheckpointState with null dirtyWindows
        CheckpointState state = new CheckpointState(
            "cursor-1",
            Instant.now(),
            "runtime-1",
            "1.0",
            Instant.now(),
            null  // null dirtyWindows
        );

        // Then: Should initialize to empty list
        assertNotNull(state.dirtyWindows());
        assertTrue(state.dirtyWindows().isEmpty());
    }
}
