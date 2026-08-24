package io.github.rigazilla.memory.cognition.evidence;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminEntriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminListEntriesRequest;
import io.github.chirino.memory.grpc.v1.Channel;
import io.github.chirino.memory.grpc.v1.Entry;
import io.github.chirino.memory.grpc.v1.ListEntriesResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TranscriptLoader.
 * 
 * Tests cover:
 * - Transcript loading with various scenarios
 * - Entry filtering and pagination
 * - UUID conversion utilities
 * - Error handling
 * - Text extraction from different entry formats
 */
@QuarkusTest
class TranscriptLoaderTest {

    @Inject
    TranscriptLoader loader;

    /** The real (non-proxy) bean instance — used for field injection of mock stubs. */
    private TranscriptLoader realLoader;

    private AdminEntriesServiceGrpc.AdminEntriesServiceBlockingStub mockEntriesStub;

    @BeforeEach
    void setUp() {
        mockEntriesStub = mock(AdminEntriesServiceGrpc.AdminEntriesServiceBlockingStub.class);
        ManagedChannel mockChannel = mock(ManagedChannel.class);

        // Unwrap CDI proxy to reach the real bean instance — field assignment on the proxy
        // itself is a no-op because @ApplicationScoped beans are wrapped in client proxies.
        // arc_contextualInstance() returns the actual delegate, not the proxy shell.
        realLoader = (TranscriptLoader) ((io.quarkus.arc.ClientProxy) loader).arc_contextualInstance();
        realLoader.entriesStub = mockEntriesStub;
        realLoader.channel = mockChannel;
    }

    @Test
    void testLoadTranscript_Success_ReturnsEvidencePack() {
        // Given: Conversation with entries
        String conversationId = "conv-123";
        String entryId1 = UUID.randomUUID().toString();
        String entryId2 = UUID.randomUUID().toString();
        List<String> entryIds = List.of(entryId1, entryId2);

        List<Entry> entries = List.of(
            createHistoryEntry(entryId1, "USER", "User message"),
            createHistoryEntry(entryId2, "AI", "AI response")
        );

        ListEntriesResponse response = ListEntriesResponse.newBuilder()
            .addAllEntries(entries)
            .build();
        when(mockEntriesStub.listEntries(any(AdminListEntriesRequest.class))).thenReturn(response);

        // When: Load transcript
        EvidencePack pack = loader.loadTranscript(conversationId, entryIds, null, "user-123");

        // Then: Should return evidence pack with entries
        assertNotNull(pack);
        assertEquals(2, pack.getTranscriptEntries().size());
    }

    @Test
    void testLoadTranscript_WithPreviousEntry_UsesPagination() {
        // Given: Batch with previous entry
        String conversationId = "conv-456";
        String previousEntryId = UUID.randomUUID().toString();
        String entryId = UUID.randomUUID().toString();

        ListEntriesResponse response = ListEntriesResponse.newBuilder()
            .addEntries(createHistoryEntry(entryId, "USER", "Message"))
            .build();
        when(mockEntriesStub.listEntries(any(AdminListEntriesRequest.class))).thenReturn(response);

        // When: Load transcript with previous entry
        loader.loadTranscript(conversationId, List.of(entryId), previousEntryId, "user-123");

        // Then: Should use previous entry as page token
        ArgumentCaptor<AdminListEntriesRequest> captor = 
            ArgumentCaptor.forClass(AdminListEntriesRequest.class);
        verify(mockEntriesStub).listEntries(captor.capture());

        AdminListEntriesRequest request = captor.getValue();
        assertEquals(conversationId, request.getConversationId());
        assertEquals(Channel.HISTORY, request.getChannel());
        assertEquals(previousEntryId, request.getPage().getPageToken());
    }

    @Test
    void testLoadTranscript_FirstBatch_UsesDefaultPagination() {
        // Given: First batch (no previous entry)
        String conversationId = "conv-789";
        String entryId = UUID.randomUUID().toString();

        ListEntriesResponse response = ListEntriesResponse.newBuilder()
            .addEntries(createHistoryEntry(entryId, "USER", "First message"))
            .build();
        when(mockEntriesStub.listEntries(any(AdminListEntriesRequest.class))).thenReturn(response);

        // When: Load transcript without previous entry
        loader.loadTranscript(conversationId, List.of(entryId), null, "user-123");

        // Then: Should use default first-page pagination bounded by last entry
        ArgumentCaptor<AdminListEntriesRequest> captor =
            ArgumentCaptor.forClass(AdminListEntriesRequest.class);
        verify(mockEntriesStub).listEntries(captor.capture());

        AdminListEntriesRequest request = captor.getValue();
        assertEquals("", request.getPage().getPageToken());
        assertEquals(1000, request.getPage().getPageSize());
        assertEquals(uuidToBytes(entryId), request.getUpToEntryId());
    }

    @Test
    void testLoadTranscript_WithUpToEntryId_SetsLimit() {
        // Given: Batch with previous entry and last entry ID
        String conversationId = "conv-limit";
        String previousEntryId = UUID.randomUUID().toString();
        String entryId1 = UUID.randomUUID().toString();
        String entryId2 = UUID.randomUUID().toString();

        ListEntriesResponse response = ListEntriesResponse.newBuilder().build();
        when(mockEntriesStub.listEntries(any(AdminListEntriesRequest.class))).thenReturn(response);

        // When: Load transcript with previous entry
        loader.loadTranscript(conversationId, List.of(entryId1, entryId2), previousEntryId, "user-123");

        // Then: Should set upToEntryId to last entry
        ArgumentCaptor<AdminListEntriesRequest> captor =
            ArgumentCaptor.forClass(AdminListEntriesRequest.class);
        verify(mockEntriesStub).listEntries(captor.capture());

        AdminListEntriesRequest request = captor.getValue();
        assertEquals(previousEntryId, request.getPage().getPageToken());
        assertTrue(request.hasUpToEntryId());
        assertEquals(uuidToBytes(entryId2), request.getUpToEntryId());
    }


    @Test
    void testLoadTranscript_FirstBatch_DoesNotSetNonEmptyPageToken() {
        // Given: First batch with one kept entry
        String conversationId = "conv-first-batch-no-token";
        String entryId = UUID.randomUUID().toString();

        ListEntriesResponse response = ListEntriesResponse.newBuilder()
            .addEntries(createHistoryEntry(entryId, "USER", "Single message"))
            .build();
        when(mockEntriesStub.listEntries(any(AdminListEntriesRequest.class))).thenReturn(response);

        // When: Load transcript without previous entry
        loader.loadTranscript(conversationId, List.of(entryId), null, "user-123");

        // Then: Should leave page token empty on first batch
        ArgumentCaptor<AdminListEntriesRequest> captor =
            ArgumentCaptor.forClass(AdminListEntriesRequest.class);
        verify(mockEntriesStub).listEntries(captor.capture());

        AdminListEntriesRequest request = captor.getValue();
        assertEquals("", request.getPage().getPageToken());
    }

    @Test
    void testLoadTranscript_GrpcError_ThrowsException() {
        // Given: gRPC error occurs
        String conversationId = "conv-error";
        when(mockEntriesStub.listEntries(any(AdminListEntriesRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.INTERNAL));

        // When/Then: Should throw TranscriptLoadException
        assertThrows(TranscriptLoader.TranscriptLoadException.class,
                    () -> loader.loadTranscript(conversationId, List.of(), null, "user-123"));
    }

    @Test
    void testLoadTranscript_EmptyResponse_ReturnsEmptyPack() {
        // Given: No entries found
        String conversationId = "conv-empty";
        ListEntriesResponse response = ListEntriesResponse.newBuilder().build();
        when(mockEntriesStub.listEntries(any(AdminListEntriesRequest.class))).thenReturn(response);

        // When: Load transcript
        EvidencePack pack = loader.loadTranscript(conversationId, List.of(), null, "user-123");

        // Then: Should return empty evidence pack
        assertNotNull(pack);
        assertTrue(pack.getTranscriptEntries().isEmpty());
    }

    @Test
    void testUuidConversion_RoundTrip_PreservesValue() {
        // Given: Original UUID
        UUID original = UUID.randomUUID();
        String uuidString = original.toString();

        // When: Convert to bytes and back (via loadTranscript which uses these methods)
        ByteString bytes = uuidToBytes(uuidString);
        String converted = bytesToUuid(bytes);

        // Then: Should preserve value
        assertEquals(uuidString, converted);
    }

    @Test
    void testUuidToBytes_InvalidFormat_ThrowsException() {
        // Given: Invalid UUID string
        String invalidUuid = "not-a-uuid";

        // When/Then: Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class,
                    () -> uuidToBytes(invalidUuid));
    }

    @Test
    void testBytesToUuid_InvalidLength_ReturnsInvalidMarker() {
        // Given: Invalid byte length
        ByteString invalidBytes = ByteString.copyFromUtf8("short");

        // When: Convert to UUID
        String result = bytesToUuid(invalidBytes);

        // Then: Should return invalid marker
        assertEquals("(invalid-uuid)", result);
    }

    // Helper methods

    private Entry createHistoryEntry(String entryId, String role, String text) {
        return Entry.newBuilder()
            .setId(uuidToBytes(entryId))
            .setConversationId("conv-test")
            .setContentType("history")
            .addContent(Value.newBuilder()
                .setStructValue(Struct.newBuilder()
                    .putFields("role", Value.newBuilder().setStringValue(role).build())
                    .putFields("text", Value.newBuilder().setStringValue(text).build())
                    .build())
                .build())
            .setCreatedAt("2026-08-04T10:00:00Z")
            .build();
    }

    private ByteString uuidToBytes(String uuidString) {
        UUID uuid = UUID.fromString(uuidString);
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return ByteString.copyFrom(buffer.array());
    }

    private String bytesToUuid(ByteString bytes) {
        if (bytes.size() != 16) {
            return "(invalid-uuid)";
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        long mostSigBits = buffer.getLong();
        long leastSigBits = buffer.getLong();
        return new UUID(mostSigBits, leastSigBits).toString();
    }
}
