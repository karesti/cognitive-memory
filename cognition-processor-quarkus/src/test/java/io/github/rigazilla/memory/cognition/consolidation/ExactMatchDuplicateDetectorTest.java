package io.github.rigazilla.memory.cognition.consolidation;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.AdminSearchMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminSearchMemoriesResponse;
import io.github.chirino.memory.grpc.v1.MemoryItem;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ExactMatchDuplicateDetector.
 */
@QuarkusTest
class ExactMatchDuplicateDetectorTest {

    @Inject
    ExactMatchDuplicateDetector detector;

    /** The real (non-proxy) bean instance — used for field injection of mock stubs. */
    private ExactMatchDuplicateDetector realDetector;

    private AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub mockStub;

    @BeforeEach
    void setUp() {
        mockStub = mock(AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub.class);

        // Unwrap CDI proxy to reach the real bean instance — field assignment on the proxy
        // itself is a no-op because @ApplicationScoped beans are wrapped in client proxies.
        // arc_contextualInstance() returns the actual delegate, not the proxy shell.
        realDetector = (ExactMatchDuplicateDetector) ((io.quarkus.arc.ClientProxy) detector).arc_contextualInstance();
        realDetector.memoriesStub = mockStub;
        realDetector.channel = mock(ManagedChannel.class);
    }

    @Test
    void findDuplicates_NoneFound_ReturnsEmptyList() {
        // Given: search returns no items
        when(mockStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
                .thenReturn(AdminSearchMemoriesResponse.newBuilder().build());

        MemoryCandidate candidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("E1: citation"));

        // When
        List<MemoryItem> result = detector.findDuplicates("user-1", candidate);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void findDuplicates_ExactMatch_ReturnsItem() {
        // Given: search returns an item whose content exactly matches the candidate
        String content = "User prefers Python";

        Struct value = Struct.newBuilder()
                .putFields("content", Value.newBuilder().setStringValue(content).build())
                .putFields("confidence", Value.newBuilder().setNumberValue(0.8).build())
                .putFields("citations", Value.newBuilder()
                        .setListValue(ListValue.newBuilder().build()).build())
                .build();

        AdminMemoryItem adminItem = AdminMemoryItem.newBuilder()
                .setKey("existing-key-1")
                .setValue(value)
                .setRevision(3)
                .setArchived(false)
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1000).build())
                .build();

        when(mockStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
                .thenReturn(AdminSearchMemoriesResponse.newBuilder()
                        .addItems(adminItem).build());

        MemoryCandidate candidate = new MemoryCandidate(
                "fact", content, 0.9, List.of("E1: citation"));

        // When
        List<MemoryItem> result = detector.findDuplicates("user-1", candidate);

        // Then: exact match returned
        assertEquals(1, result.size());
        assertEquals("existing-key-1", result.get(0).getKey());
        assertEquals(3, result.get(0).getRevision());
    }

    @Test
    void findDuplicates_FuzzyMatchDifferentContent_FilteredOut() {
        // Given: search returns an item whose content does NOT exactly match
        Struct value = Struct.newBuilder()
                .putFields("content", Value.newBuilder()
                        .setStringValue("User likes Python").build()) // similar but not equal
                .build();

        AdminMemoryItem adminItem = AdminMemoryItem.newBuilder()
                .setKey("other-key")
                .setValue(value)
                .setRevision(1)
                .setArchived(false)
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1000).build())
                .build();

        when(mockStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
                .thenReturn(AdminSearchMemoriesResponse.newBuilder()
                        .addItems(adminItem).build());

        MemoryCandidate candidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("E1: citation"));

        // When
        List<MemoryItem> result = detector.findDuplicates("user-1", candidate);

        // Then: fuzzy match filtered out — exact match only
        assertTrue(result.isEmpty());
    }

    @Test
    void findDuplicates_SearchThrows_ReturnsEmptyList() {
        // Given: gRPC call fails
        when(mockStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
                .thenThrow(new RuntimeException("network error"));

        MemoryCandidate candidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("E1: citation"));

        // When: dedup is best-effort — must not propagate the exception
        List<MemoryItem> result = detector.findDuplicates("user-1", candidate);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void findDuplicates_PermissionDenied_ReturnsEmptyList() {
        // PERMISSION_DENIED is a permanent config error — must still fail-open
        // (return empty, not throw) while the escalated log level is tested implicitly
        // (no assertion on log level in unit tests — verified in integration).
        when(mockStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.PERMISSION_DENIED));

        MemoryCandidate candidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("E1: citation"));

        List<MemoryItem> result = detector.findDuplicates("user-1", candidate);

        assertTrue(result.isEmpty(), "PERMISSION_DENIED must still fail-open (return empty list)");
    }

    @Test
    void findDuplicates_Unavailable_ReturnsEmptyList() {
        // Transient UNAVAILABLE (e.g. server restart) must also fail-open.
        when(mockStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        MemoryCandidate candidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("E1: citation"));

        List<MemoryItem> result = detector.findDuplicates("user-1", candidate);

        assertTrue(result.isEmpty(), "UNAVAILABLE must still fail-open (return empty list)");
    }
}
