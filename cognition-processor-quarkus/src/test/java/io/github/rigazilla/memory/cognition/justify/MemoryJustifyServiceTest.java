package io.github.rigazilla.memory.cognition.justify;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminEntriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminGetEntryRequest;
import io.github.chirino.memory.grpc.v1.AdminGetMemoryRequest;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.Entry;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MemoryJustifyService.
 * 
 * Tests cover:
 * - Memory retrieval with justification
 * - Entry fetching and conversion
 * - AI message text extraction
 * - Error handling (NOT_FOUND, general errors)
 * - UUID conversion utilities
 * - Missing entry placeholders
 */
@QuarkusTest
class MemoryJustifyServiceTest {

    @Inject
    MemoryJustifyService service;

    /** The real (non-proxy) bean instance — used for field injection of mock stubs. */
    private MemoryJustifyService realService;

    private AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub mockMemoriesStub;
    private AdminEntriesServiceGrpc.AdminEntriesServiceBlockingStub mockEntriesStub;

    @BeforeEach
    void setUp() {
        mockMemoriesStub = mock(AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub.class);
        mockEntriesStub = mock(AdminEntriesServiceGrpc.AdminEntriesServiceBlockingStub.class);
        ManagedChannel mockChannel = mock(ManagedChannel.class);

        // Unwrap CDI proxy to reach the real bean instance — field assignment on the proxy
        // itself is a no-op because @ApplicationScoped beans are wrapped in client proxies.
        // arc_contextualInstance() returns the actual delegate, not the proxy shell.
        realService = (MemoryJustifyService) ((io.quarkus.arc.ClientProxy) service).arc_contextualInstance();
        realService.memoriesStub = mockMemoriesStub;
        realService.entriesStub = mockEntriesStub;
        realService.channel = mockChannel;
    }

    @Test
    void testGetMemoryJustify_Success_ReturnsFullDetails() {
        // Given: Memory with provenance and entries
        String memoryId = UUID.randomUUID().toString();
        String conversationId = "conv-123";
        String entryId1 = UUID.randomUUID().toString();
        String entryId2 = UUID.randomUUID().toString();

        AdminMemoryItem memory = createMemory(memoryId, "Test memory content", 0.95,
                                             List.of("citation1"), conversationId,
                                             List.of(entryId1, entryId2));
        when(mockMemoriesStub.getMemory(any(AdminGetMemoryRequest.class))).thenReturn(memory);

        Entry entry1 = createUserEntry(entryId1, "User message");
        Entry entry2 = createAiEntry(entryId2, "AI response");
        when(mockEntriesStub.getEntry(any(AdminGetEntryRequest.class)))
            .thenReturn(entry1)
            .thenReturn(entry2);

        // When: Get memory justify
        MemoryJustifyResponse response = service.getMemoryJustify(memoryId);

        // Then: Should return full details
        assertNotNull(response);
        assertEquals(memoryId, response.id());
        assertEquals("Test memory content", response.content());
        assertEquals(0.95, response.confidence());
        assertEquals(List.of("citation1"), response.citations());
        assertEquals(conversationId, response.conversationId());
        assertEquals(2, response.sourceEntries().size());
        assertEquals("USER", response.sourceEntries().get(0).role());
        assertEquals("User message", response.sourceEntries().get(0).text());
        assertEquals("AI", response.sourceEntries().get(1).role());
        assertEquals("AI response", response.sourceEntries().get(1).text());
    }

    @Test
    void testGetMemoryJustify_NotFound_ThrowsException() {
        // Given: Memory does not exist
        String memoryId = UUID.randomUUID().toString();
        when(mockMemoriesStub.getMemory(any(AdminGetMemoryRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

        // When/Then: Should throw MemoryNotFoundException
        assertThrows(MemoryJustifyService.MemoryNotFoundException.class,
                    () -> service.getMemoryJustify(memoryId));
    }

    @Test
    void testGetMemoryJustify_GrpcError_ThrowsJustifyException() {
        // Given: gRPC error occurs
        String memoryId = UUID.randomUUID().toString();
        when(mockMemoriesStub.getMemory(any(AdminGetMemoryRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.INTERNAL));

        // When/Then: Should throw JustifyException
        assertThrows(MemoryJustifyService.JustifyException.class,
                    () -> service.getMemoryJustify(memoryId));
    }

    @Test
    void testFetchSourceEntries_WithMissingEntry_CreatesPlaceholder() {
        // Given: Memory with two entries, one missing
        String memoryId = UUID.randomUUID().toString();
        String entryId1 = UUID.randomUUID().toString();
        String entryId2 = UUID.randomUUID().toString();

        AdminMemoryItem memory = createMemory(memoryId, "Content", 0.9,
                                             List.of(), "conv-1",
                                             List.of(entryId1, entryId2));
        when(mockMemoriesStub.getMemory(any(AdminGetMemoryRequest.class))).thenReturn(memory);

        Entry entry1 = createUserEntry(entryId1, "Available entry");
        when(mockEntriesStub.getEntry(any(AdminGetEntryRequest.class)))
            .thenReturn(entry1)
            .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

        // When: Get memory justify
        MemoryJustifyResponse response = service.getMemoryJustify(memoryId);

        // Then: Should include placeholder for missing entry
        assertEquals(2, response.sourceEntries().size());
        assertEquals("USER", response.sourceEntries().get(0).role());
        assertEquals("Available entry", response.sourceEntries().get(0).text());
        assertEquals("SYSTEM", response.sourceEntries().get(1).role());
        assertTrue(response.sourceEntries().get(1).text().contains("not available"));
    }

    @Test
    void testConvertEntry_UserRole_ExtractsText() {
        // Given: User entry with text field
        String entryId = UUID.randomUUID().toString();
        Entry entry = createUserEntry(entryId, "User message text");

        // When: Convert entry (via getMemoryJustify)
        String memoryId = UUID.randomUUID().toString();
        AdminMemoryItem memory = createMemory(memoryId, "Content", 0.9,
                                             List.of(), "conv-1", List.of(entryId));
        when(mockMemoriesStub.getMemory(any(AdminGetMemoryRequest.class))).thenReturn(memory);
        when(mockEntriesStub.getEntry(any(AdminGetEntryRequest.class))).thenReturn(entry);

        MemoryJustifyResponse response = service.getMemoryJustify(memoryId);

        // Then: Should extract text correctly
        assertEquals(1, response.sourceEntries().size());
        assertEquals("USER", response.sourceEntries().get(0).role());
        assertEquals("User message text", response.sourceEntries().get(0).text());
    }

    @Test
    void testConvertEntry_AiRole_ExtractsFromEvents() {
        // Given: AI entry with events array
        String entryId = UUID.randomUUID().toString();
        Entry entry = createAiEntry(entryId, "AI response text");

        // When: Convert entry
        String memoryId = UUID.randomUUID().toString();
        AdminMemoryItem memory = createMemory(memoryId, "Content", 0.9,
                                             List.of(), "conv-1", List.of(entryId));
        when(mockMemoriesStub.getMemory(any(AdminGetMemoryRequest.class))).thenReturn(memory);
        when(mockEntriesStub.getEntry(any(AdminGetEntryRequest.class))).thenReturn(entry);

        MemoryJustifyResponse response = service.getMemoryJustify(memoryId);

        // Then: Should extract AI message text from events
        assertEquals(1, response.sourceEntries().size());
        assertEquals("AI", response.sourceEntries().get(0).role());
        assertEquals("AI response text", response.sourceEntries().get(0).text());
    }

    @Test
    void testExtractAiMessageText_CompletedEvent_ReturnsText() {
        // Given: Events array with Completed event
        Value eventsValue = createCompletedEvent("Extracted AI text");

        // When: Extract text (tested via convertEntry in getMemoryJustify)
        String entryId = UUID.randomUUID().toString();
        Entry entry = Entry.newBuilder()
            .setId(uuidToBytes(entryId))
            .addContent(Value.newBuilder()
                .setStructValue(Struct.newBuilder()
                    .putFields("role", Value.newBuilder().setStringValue("AI").build())
                    .putFields("events", eventsValue)
                    .build())
                .build())
            .setCreatedAt("2026-08-04T10:00:00Z")
            .build();

        String memoryId = UUID.randomUUID().toString();
        AdminMemoryItem memory = createMemory(memoryId, "Content", 0.9,
                                             List.of(), "conv-1", List.of(entryId));
        when(mockMemoriesStub.getMemory(any(AdminGetMemoryRequest.class))).thenReturn(memory);
        when(mockEntriesStub.getEntry(any(AdminGetEntryRequest.class))).thenReturn(entry);

        MemoryJustifyResponse response = service.getMemoryJustify(memoryId);

        // Then: Should extract text from Completed event
        assertEquals("Extracted AI text", response.sourceEntries().get(0).text());
    }

    @Test
    void testUuidConversion_RoundTrip_PreservesValue() {
        // Given: Original UUID
        UUID original = UUID.randomUUID();
        String uuidString = original.toString();

        // When: Convert to bytes and back
        ByteString bytes = uuidToBytes(uuidString);
        String converted = bytesToUuid(bytes);

        // Then: Should preserve value
        assertEquals(uuidString, converted);
    }

    @Test
    void testCreateMissingEntryPlaceholder_ReturnsSystemMessage() {
        // Given: Memory with missing entry
        String memoryId = UUID.randomUUID().toString();
        String missingEntryId = UUID.randomUUID().toString();

        AdminMemoryItem memory = createMemory(memoryId, "Content", 0.9,
                                             List.of(), "conv-1", List.of(missingEntryId));
        when(mockMemoriesStub.getMemory(any(AdminGetMemoryRequest.class))).thenReturn(memory);
        when(mockEntriesStub.getEntry(any(AdminGetEntryRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

        // When: Get memory justify
        MemoryJustifyResponse response = service.getMemoryJustify(memoryId);

        // Then: Should create SYSTEM placeholder
        assertEquals(1, response.sourceEntries().size());
        MemoryJustifyResponse.EntryDetail placeholder = response.sourceEntries().get(0);
        assertEquals("SYSTEM", placeholder.role());
        assertTrue(placeholder.text().contains("not available"));
    }

    // Helper methods

    private AdminMemoryItem createMemory(String memoryId, String content, double confidence,
                                        List<String> citations, String conversationId,
                                        List<String> entryIds) {
        Struct.Builder valueBuilder = Struct.newBuilder()
            .putFields("content", Value.newBuilder().setStringValue(content).build())
            .putFields("confidence", Value.newBuilder().setNumberValue(confidence).build());

        // Add citations
        Value.Builder citationsBuilder = Value.newBuilder();
        for (String citation : citations) {
            citationsBuilder.getListValueBuilder().addValues(
                Value.newBuilder().setStringValue(citation).build()
            );
        }
        valueBuilder.putFields("citations", citationsBuilder.build());

        // Add provenance
        Struct.Builder provenanceBuilder = Struct.newBuilder()
            .putFields("conversation_id", Value.newBuilder().setStringValue(conversationId).build());

        Value.Builder entryIdsBuilder = Value.newBuilder();
        for (String entryId : entryIds) {
            entryIdsBuilder.getListValueBuilder().addValues(
                Value.newBuilder().setStringValue(entryId).build()
            );
        }
        provenanceBuilder.putFields("entry_ids", entryIdsBuilder.build());

        valueBuilder.putFields("provenance", Value.newBuilder().setStructValue(provenanceBuilder.build()).build());

        return AdminMemoryItem.newBuilder()
            .setId(uuidToBytes(memoryId))
            .setValue(valueBuilder.build())
            .setCreatedAt(Timestamp.newBuilder().setSeconds(1722758400).build())
            .build();
    }

    private Entry createUserEntry(String entryId, String text) {
        return Entry.newBuilder()
            .setId(uuidToBytes(entryId))
            .addContent(Value.newBuilder()
                .setStructValue(Struct.newBuilder()
                    .putFields("role", Value.newBuilder().setStringValue("USER").build())
                    .putFields("text", Value.newBuilder().setStringValue(text).build())
                    .build())
                .build())
            .setCreatedAt("2026-08-04T10:00:00Z")
            .build();
    }

    private Entry createAiEntry(String entryId, String aiText) {
        Value eventsValue = createCompletedEvent(aiText);

        return Entry.newBuilder()
            .setId(uuidToBytes(entryId))
            .addContent(Value.newBuilder()
                .setStructValue(Struct.newBuilder()
                    .putFields("role", Value.newBuilder().setStringValue("AI").build())
                    .putFields("events", eventsValue)
                    .build())
                .build())
            .setCreatedAt("2026-08-04T10:00:00Z")
            .build();
    }

    private Value createCompletedEvent(String aiText) {
        Struct completedEvent = Struct.newBuilder()
            .putFields("eventType", Value.newBuilder().setStringValue("Completed").build())
            .putFields("aiMessage", Value.newBuilder()
                .setStructValue(Struct.newBuilder()
                    .putFields("text", Value.newBuilder().setStringValue(aiText).build())
                    .build())
                .build())
            .build();

        return Value.newBuilder()
            .setListValue(com.google.protobuf.ListValue.newBuilder()
                .addValues(Value.newBuilder().setStructValue(completedEvent).build())
                .build())
            .build();
    }

    private ByteString uuidToBytes(String uuidString) {
        UUID uuid = UUID.fromString(uuidString);
        ByteBuffer buffer = ByteBuffer.wrap(new byte[16]);
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
