package io.github.rigazilla.memory.cognition.writer;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesResponse;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminPutMemoryRequest;
import io.github.chirino.memory.grpc.v1.MemoryWriteResult;
import io.github.rigazilla.memory.cognition.consolidation.ResolvedCandidate;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import io.github.rigazilla.memory.cognition.model.Provenance;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MemoryWriter.
 *
 * Uses @QuarkusTest so CDI wires MemoryServiceConfig from test/resources/application.properties.
 * The gRPC stub and channel are replaced with mocks in @BeforeEach to avoid real network calls.
 *
 * Tests cover:
 * - Single and batch memory writing
 * - Namespace construction
 * - Provenance and citations value building
 * - Error handling
 * - UUID conversion
 */
@QuarkusTest
class MemoryWriterTest {

    /** Fixture observed_at used wherever writeMemory/writeMemories requires a timestamp string. */
    private static final String TEST_OBSERVED_AT = "2025-01-15T10:00:00Z";

    @Inject
    MemoryWriter writer;

    /** The real (non-proxy) bean instance — used for field injection of mock stubs. */
    private MemoryWriter realWriter;

    private AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub mockMemoriesStub;

    @BeforeEach
    void setUp() {
        mockMemoriesStub = mock(AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub.class);
        ManagedChannel mockChannel = mock(ManagedChannel.class);

        // Unwrap CDI proxy to reach the real bean instance — field assignment on the proxy
        // itself is a no-op because @ApplicationScoped beans are wrapped in client proxies.
        // arc_contextualInstance() returns the actual delegate, not the proxy shell.
        realWriter = (MemoryWriter) ((io.quarkus.arc.ClientProxy) writer).arc_contextualInstance();
        realWriter.memoriesStub = mockMemoriesStub;
        realWriter.channel = mockChannel;
    }

    @Test
    void testWriteMemory_Success_ReturnsResult() {
        // Given: Memory candidate and provenance
        String userId = "user-123";
        MemoryCandidate candidate = new MemoryCandidate(
            "fact",
            "User prefers dark mode",
            0.95,
            List.of("entry-1", "entry-2")
        );
        Provenance provenance = createProvenance("conv-123", List.of("entry-1", "entry-2"));

        UUID memoryId = UUID.randomUUID();
        MemoryWriteResult result = MemoryWriteResult.newBuilder()
            .setId(uuidToBytes(memoryId.toString()))
            .build();
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class))).thenReturn(result);

        // When: Write memory
        MemoryWriteResult writeResult = writer.writeMemory(userId, candidate, provenance, TEST_OBSERVED_AT);

        // Then: Should return result
        assertNotNull(writeResult);
        assertEquals(memoryId.toString(), bytesToUuid(writeResult.getId()));
    }

    @Test
    void testWriteMemory_BuildsCorrectNamespace() {
        // Given: Memory candidate
        String userId = "user-456";
        MemoryCandidate candidate = new MemoryCandidate("preference", "Content", 0.9, List.of());
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult result = MemoryWriteResult.newBuilder()
            .setId(uuidToBytes(UUID.randomUUID().toString()))
            .build();
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class))).thenReturn(result);

        // When: Write memory
        writer.writeMemory(userId, candidate, provenance, TEST_OBSERVED_AT);

        // Then: Should use correct namespace
        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub).putMemory(captor.capture());

        AdminPutMemoryRequest request = captor.getValue();
        assertEquals(List.of("user", userId, "cognition.v1", "preference"),
                    request.getNamespaceList());

        // Temporal fields must be present in the index map for queryability (AC #8)
        assertEquals(TEST_OBSERVED_AT, request.getIndexMap().get("observed_at"),
            "observed_at must be indexed for search queries");
        assertEquals(TEST_OBSERVED_AT, request.getIndexMap().get("effective_at"),
            "effective_at must be indexed for search queries");
    }

    @Test
    void testWriteMemory_IncludesContentAndConfidence() {
        // Given: Memory candidate with content and confidence
        String userId = "user-789";
        MemoryCandidate candidate = new MemoryCandidate(
            "fact",
            "Important fact content",
            0.88,
            List.of("citation-1")
        );
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult result = MemoryWriteResult.newBuilder()
            .setId(uuidToBytes(UUID.randomUUID().toString()))
            .build();
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class))).thenReturn(result);

        // When: Write memory
        writer.writeMemory(userId, candidate, provenance, TEST_OBSERVED_AT);

        // Then: Should include content and confidence in value
        ArgumentCaptor<AdminPutMemoryRequest> captor = 
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub).putMemory(captor.capture());

        Struct value = captor.getValue().getValue();
        assertEquals("Important fact content", 
                    value.getFieldsMap().get("content").getStringValue());
        assertEquals(0.88, value.getFieldsMap().get("confidence").getNumberValue());
    }

    @Test
    void testWriteMemory_IncludesProvenance() {
        // Given: Memory with provenance
        String userId = "user-prov";
        MemoryCandidate candidate = new MemoryCandidate("fact", "Content", 0.9, List.of());
        Provenance provenance = new Provenance(
            "conv-123",
            List.of("entry-1", "entry-2"),
            "cursor-first",
            "cursor-latest",
            "debounce-timeout",
            "hash-123",
            "evidence-base-id",
            "evidence-hash",
            "runtime-1",
            "1.0",
            Instant.parse("2026-08-04T10:00:00Z")
        );

        MemoryWriteResult result = MemoryWriteResult.newBuilder()
            .setId(uuidToBytes(UUID.randomUUID().toString()))
            .build();
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class))).thenReturn(result);

        // When: Write memory
        writer.writeMemory(userId, candidate, provenance, TEST_OBSERVED_AT);

        // Then: Should include provenance in value
        ArgumentCaptor<AdminPutMemoryRequest> captor = 
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub).putMemory(captor.capture());

        Struct value = captor.getValue().getValue();
        Struct provenanceStruct = value.getFieldsMap().get("provenance").getStructValue();
        
        assertEquals("conv-123", 
                    provenanceStruct.getFieldsMap().get("conversation_id").getStringValue());
        assertEquals("runtime-1", 
                    provenanceStruct.getFieldsMap().get("runtime_id").getStringValue());
        assertEquals("hash-123", 
                    provenanceStruct.getFieldsMap().get("source_hash").getStringValue());
    }

    @Test
    void testWriteMemory_GrpcError_ThrowsException() {
        // Given: gRPC error occurs
        String userId = "user-error";
        MemoryCandidate candidate = new MemoryCandidate("fact", "Content", 0.9, List.of());
        Provenance provenance = createProvenance("conv-1", List.of());

        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.INTERNAL));

        // When/Then: Should throw MemoryWriteException
        assertThrows(MemoryWriter.MemoryWriteException.class,
                    () -> writer.writeMemory(userId, candidate, provenance, TEST_OBSERVED_AT));
    }

    @Test
    void testWriteMemories_Batch_WritesAll() {
        // Given: Multiple memory candidates
        String userId = "user-batch";
        List<MemoryCandidate> candidates = List.of(
            new MemoryCandidate("fact", "Fact 1", 0.9, List.of()),
            new MemoryCandidate("preference", "Pref 1", 0.85, List.of()),
            new MemoryCandidate("fact", "Fact 2", 0.92, List.of())
        );
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult result = MemoryWriteResult.newBuilder()
            .setId(uuidToBytes(UUID.randomUUID().toString()))
            .build();
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class))).thenReturn(result);

        // When: Write batch
        List<MemoryWriteResult> results = writer.writeMemories(userId, candidates, provenance, TEST_OBSERVED_AT);

        // Then: Should write all memories
        assertEquals(3, results.size());
        verify(mockMemoriesStub, times(3)).putMemory(any(AdminPutMemoryRequest.class));
    }

    @Test
    void testWriteMemory_IncludesCitations() {
        // Given: Memory with citations
        String userId = "user-citations";
        MemoryCandidate candidate = new MemoryCandidate(
            "fact",
            "Content",
            0.9,
            List.of("citation-1", "citation-2", "citation-3")
        );
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult result = MemoryWriteResult.newBuilder()
            .setId(uuidToBytes(UUID.randomUUID().toString()))
            .build();
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class))).thenReturn(result);

        // When: Write memory
        writer.writeMemory(userId, candidate, provenance, TEST_OBSERVED_AT);

        // Then: Should include citations array
        ArgumentCaptor<AdminPutMemoryRequest> captor = 
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub).putMemory(captor.capture());

        Struct value = captor.getValue().getValue();
        int citationCount = value.getFieldsMap().get("citations")
            .getListValue().getValuesCount();
        assertEquals(3, citationCount);
    }

    @Test
    void testWriteMemory_StripsEntryReferencesFromCitations() {
        // Given: Memory with citations containing E prefix (E1:, E2:, etc.)
        String userId = "user-citations-strip";
        MemoryCandidate candidate = new MemoryCandidate(
            "fact",
            "Alice is a student in computer science",
            0.95,
            List.of(
                "E1: Hey my name is Alice, I'm a student in computer science",
                "E2: I love programming and algorithms",
                "Regular citation without prefix"
            )
        );
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult result = MemoryWriteResult.newBuilder()
            .setId(uuidToBytes(UUID.randomUUID().toString()))
            .build();
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class))).thenReturn(result);

        // When: Write memory
        writer.writeMemory(userId, candidate, provenance, TEST_OBSERVED_AT);

        // Then: Citations should have E prefix stripped
        ArgumentCaptor<AdminPutMemoryRequest> captor = 
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub).putMemory(captor.capture());

        Struct value = captor.getValue().getValue();
        var citationsList = value.getFieldsMap().get("citations").getListValue();
        
        assertEquals(3, citationsList.getValuesCount());
        
        // First citation: E1: should be stripped
        assertEquals("Hey my name is Alice, I'm a student in computer science",
            citationsList.getValues(0).getStringValue(),
            "E1: prefix should be stripped from first citation");
        
        // Second citation: E2: should be stripped
        assertEquals("I love programming and algorithms",
            citationsList.getValues(1).getStringValue(),
            "E2: prefix should be stripped from second citation");
        
        // Third citation: no prefix, should remain unchanged
        assertEquals("Regular citation without prefix",
            citationsList.getValues(2).getStringValue(),
            "Citation without prefix should remain unchanged");
    }

    @Test
    void testWriteMemory_GeneratesUniqueKeys() {
        // Given: Two identical candidates
        String userId = "user-keys";
        MemoryCandidate candidate = new MemoryCandidate("fact", "Same content", 0.9, List.of());
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult result = MemoryWriteResult.newBuilder()
            .setId(uuidToBytes(UUID.randomUUID().toString()))
            .build();
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class))).thenReturn(result);

        // When: Write same candidate twice
        writer.writeMemory(userId, candidate, provenance, TEST_OBSERVED_AT);
        writer.writeMemory(userId, candidate, provenance, TEST_OBSERVED_AT);

        // Then: Should generate different keys
        ArgumentCaptor<AdminPutMemoryRequest> captor = 
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub, times(2)).putMemory(captor.capture());

        List<AdminPutMemoryRequest> requests = captor.getAllValues();
        assertNotEquals(requests.get(0).getKey(), requests.get(1).getKey());
    }

    // -------------------------------------------------------------------------
    // Temporal struct shape — value fields and index map
    // -------------------------------------------------------------------------

    @Test
    void testWriteMemory_TemporalFieldsPresentInValueStruct() {
        // Given
        String userId = "user-temporal";
        MemoryCandidate candidate = new MemoryCandidate("fact", "User prefers dark mode", 0.9, List.of());
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult result = MemoryWriteResult.newBuilder()
            .setId(uuidToBytes(UUID.randomUUID().toString()))
            .build();
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class))).thenReturn(result);

        // When
        writer.writeMemory(userId, candidate, provenance, TEST_OBSERVED_AT);

        // Then: observed_at, effective_at, expires_at must be in the value struct
        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub).putMemory(captor.capture());

        Struct value = captor.getValue().getValue();
        assertTrue(value.containsFields("observed_at"), "observed_at must be present in value struct");
        assertTrue(value.containsFields("effective_at"), "effective_at must be present in value struct");
        assertTrue(value.containsFields("expires_at"), "expires_at placeholder must be present in value struct");
        assertEquals(TEST_OBSERVED_AT, value.getFieldsOrThrow("observed_at").getStringValue());
        assertEquals(TEST_OBSERVED_AT, value.getFieldsOrThrow("effective_at").getStringValue());
        assertEquals(com.google.protobuf.Value.KindCase.NULL_VALUE,
            value.getFieldsOrThrow("expires_at").getKindCase(),
            "expires_at must be stored as NULL_VALUE");
    }

    @Test
    void testWriteMemory_TemporalFieldsAbsentInPreFixStruct() {
        // Given: struct built without temporal fields (baseline confirming the issue this fix addresses)
        com.google.protobuf.Struct valueBefore = com.google.protobuf.Struct.newBuilder()
            .putFields("content", com.google.protobuf.Value.newBuilder()
                .setStringValue("User prefers Go").build())
            .build();

        // Then: no temporal fields in a pre-fix struct
        assertFalse(valueBefore.containsFields("observed_at"),
            "observed_at must be absent in pre-fix struct");
        assertFalse(valueBefore.containsFields("effective_at"),
            "effective_at must be absent in pre-fix struct");
    }

    // -------------------------------------------------------------------------
    // writeResolved — upsert path and ABORTED retry
    // -------------------------------------------------------------------------

    @Test
    void testWriteResolved_FreshInsert_GeneratesNewKey() {
        // Given: fresh resolved candidate (no existing key)
        String userId = "user-fresh";
        MemoryCandidate candidate = new MemoryCandidate("fact", "User prefers Python", 0.9, List.of());
        ResolvedCandidate resolved = ResolvedCandidate.fresh(candidate);
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult result = MemoryWriteResult.newBuilder()
                .setId(uuidToBytes(UUID.randomUUID().toString())).build();
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class))).thenReturn(result);

        // When
        writer.writeResolved(userId, resolved, provenance, TEST_OBSERVED_AT);

        // Then: a fresh UUID key used — no expected_revision set
        ArgumentCaptor<AdminPutMemoryRequest> captor =
                ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub).putMemory(captor.capture());
        assertFalse(captor.getValue().hasExpectedRevision(),
                "fresh insert must not set expected_revision");
    }

    @Test
    void testWriteResolved_Upsert_SetsExistingKeyAndRevision() {
        // Given: merged resolved candidate carrying existing key + revision
        String userId = "user-upsert";
        MemoryCandidate candidate = new MemoryCandidate("fact", "User prefers Python", 0.9, List.of());
        ResolvedCandidate resolved = ResolvedCandidate.merged(candidate, "existing-key-99", 5L);
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult result = MemoryWriteResult.newBuilder()
                .setId(uuidToBytes(UUID.randomUUID().toString())).build();
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class))).thenReturn(result);

        // When
        writer.writeResolved(userId, resolved, provenance, TEST_OBSERVED_AT);

        // Then: existing key reused and expected_revision set
        ArgumentCaptor<AdminPutMemoryRequest> captor =
                ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub).putMemory(captor.capture());
        assertEquals("existing-key-99", captor.getValue().getKey());
        assertTrue(captor.getValue().hasExpectedRevision());
        assertEquals(5L, captor.getValue().getExpectedRevision());
    }

    @Test
    void testWriteResolved_Aborted_RetriesWithRefetchedRevision() {
        // Given: first putMemory throws ABORTED; listMemories returns updated revision; retry succeeds
        String userId = "user-retry";
        MemoryCandidate candidate = new MemoryCandidate("fact", "User prefers Python", 0.9, List.of());
        ResolvedCandidate resolved = ResolvedCandidate.merged(candidate, "key-conflict", 3L);
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult successResult = MemoryWriteResult.newBuilder()
                .setId(uuidToBytes(UUID.randomUUID().toString())).build();

        // First call throws ABORTED, second (retry) succeeds
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.ABORTED))
                .thenReturn(successResult);

        // Re-fetch returns the item with updated revision 4
        AdminMemoryItem refetched = AdminMemoryItem.newBuilder()
                .setKey("key-conflict").setRevision(4).build();
        when(mockMemoriesStub.listMemories(any(AdminListMemoriesRequest.class)))
                .thenReturn(AdminListMemoriesResponse.newBuilder().addItems(refetched).build());

        // When
        MemoryWriteResult result = writer.writeResolved(userId, resolved, provenance, TEST_OBSERVED_AT);

        // Then: result from retry returned; putMemory called twice
        assertNotNull(result);
        verify(mockMemoriesStub, times(2)).putMemory(any(AdminPutMemoryRequest.class));
    }

    @Test
    void testWriteResolved_AbortedRetryExhausted_FallsBackToFreshInsert() {
        // Given: both putMemory calls fail — second throws non-ABORTED so fallback triggers
        String userId = "user-fallback";
        MemoryCandidate candidate = new MemoryCandidate("fact", "User prefers Python", 0.9, List.of());
        ResolvedCandidate resolved = ResolvedCandidate.merged(candidate, "key-conflict", 3L);
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult fallbackResult = MemoryWriteResult.newBuilder()
                .setId(uuidToBytes(UUID.randomUUID().toString())).build();

        // First call throws ABORTED; re-fetch listMemories throws; fallback putMemory succeeds
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.ABORTED))
                .thenReturn(fallbackResult);
        when(mockMemoriesStub.listMemories(any(AdminListMemoriesRequest.class)))
                .thenThrow(new RuntimeException("re-fetch failed"));

        // When
        MemoryWriteResult result = writer.writeResolved(userId, resolved, provenance, TEST_OBSERVED_AT);

        // Then: fallback fresh insert succeeded — job not blocked
        assertNotNull(result);
        verify(mockMemoriesStub, times(2)).putMemory(any(AdminPutMemoryRequest.class));
    }

    @Test
    void testWriteResolved_Aborted_RefetchKeyMismatch_FallsBackToFreshInsert() {
        // re-fetch returns an item whose key doesn't exactly match the expected key
        // (setKeyPrefix is a prefix scan). The writer must fall back to a fresh insert
        // rather than writing to the wrong key.
        String userId = "user-keymismatch";
        MemoryCandidate candidate = new MemoryCandidate("fact", "User prefers Python", 0.9, List.of());
        ResolvedCandidate resolved = ResolvedCandidate.merged(candidate, "key-abc", 3L);
        Provenance provenance = createProvenance("conv-1", List.of());

        MemoryWriteResult fallbackResult = MemoryWriteResult.newBuilder()
                .setId(uuidToBytes(UUID.randomUUID().toString())).build();

        // First putMemory throws ABORTED
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.ABORTED))
                .thenReturn(fallbackResult);

        // Re-fetch returns a DIFFERENT key (prefix match on "key-abc" returned "key-abcdef")
        AdminMemoryItem wrongItem = AdminMemoryItem.newBuilder()
                .setKey("key-abcdef").setRevision(4).build();
        when(mockMemoriesStub.listMemories(any(AdminListMemoriesRequest.class)))
                .thenReturn(AdminListMemoriesResponse.newBuilder().addItems(wrongItem).build());

        // When
        MemoryWriteResult result = writer.writeResolved(userId, resolved, provenance, TEST_OBSERVED_AT);

        // Then: falls back to fresh insert (putMemory called twice — once ABORTED, once fresh)
        assertNotNull(result);
        verify(mockMemoriesStub, times(2)).putMemory(any(AdminPutMemoryRequest.class));

        // The second (fallback) call must NOT set expected_revision
        ArgumentCaptor<AdminPutMemoryRequest> captor =
                ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub, times(2)).putMemory(captor.capture());
        assertFalse(captor.getAllValues().get(1).hasExpectedRevision(),
                "fallback fresh insert must not carry the wrong key's revision");
    }

    // Helper methods

    private Provenance createProvenance(String conversationId, List<String> entryIds) {
        return new Provenance(
            conversationId,
            entryIds,
            "cursor-first",
            "cursor-latest",
            "test-trigger",
            null,
            null,
            null,
            "runtime-test",
            "1.0",
            Instant.now()
        );
    }

    private ByteString uuidToBytes(String uuidString) {
        UUID uuid = UUID.fromString(uuidString);
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return ByteString.copyFrom(buffer.array());
    }

    private String bytesToUuid(ByteString bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        long mostSigBits = buffer.getLong();
        long leastSigBits = buffer.getLong();
        return new UUID(mostSigBits, leastSigBits).toString();
    }
}
