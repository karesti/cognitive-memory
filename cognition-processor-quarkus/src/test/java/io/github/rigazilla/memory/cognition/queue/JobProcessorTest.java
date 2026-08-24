package io.github.rigazilla.memory.cognition.queue;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminConversation;
import io.github.chirino.memory.grpc.v1.Entry;
import io.github.chirino.memory.grpc.v1.AdminConversationsServiceGrpc;
import io.github.rigazilla.memory.cognition.consolidation.ConsolidationService;
import io.github.rigazilla.memory.cognition.event.SalienceScorer;
import io.github.rigazilla.memory.cognition.evidence.EvidencePack;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.arc.Arc;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JobProcessor.
 * 
 * Focuses on:
 * - Queue management and locking
 * - Async processing
 * - Error handling
 * - Resource cleanup
 * 
 * Note: Full pipeline integration tests are in separate integration test suite.
 */
@QuarkusTest
class JobProcessorTest {

    @Inject
    JobProcessor processor;

    /** The real (non-proxy) bean instance — used for field injection of mock stubs. */
    private JobProcessor realProcessor;

    @InjectMock
    JobQueueRegistry registry;

    @InjectMock
    ConsolidationService consolidationService;

    private AdminConversationsServiceGrpc.AdminConversationsServiceBlockingStub conversationsStub;
    private ManagedChannel mockChannel;
    private SalienceScorer mockSalienceScorer;

    @BeforeEach
    void setUp() {
        conversationsStub = mock(AdminConversationsServiceGrpc.AdminConversationsServiceBlockingStub.class);
        mockChannel = mock(ManagedChannel.class);
        mockSalienceScorer = mock(SalienceScorer.class);

        // Unwrap CDI proxy to reach the real bean instance — field assignment on the proxy
        // itself is a no-op because @ApplicationScoped beans are wrapped in client proxies.
        // arc_contextualInstance() returns the actual delegate, not the proxy shell.
        realProcessor = (JobProcessor) ((io.quarkus.arc.ClientProxy) processor).arc_contextualInstance();
        realProcessor.conversationsStub = conversationsStub;
        realProcessor.channel = mockChannel;
        realProcessor.salienceScorer = mockSalienceScorer;
    }

    @Test
    void testStartProcessing_EmptyQueue_ReleasesLock() {
        // Given: Empty queue
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(true);
        when(queue.isEmpty()).thenReturn(true);

        // When: Start processing
        processor.startProcessing("conv-1");

        // Then: Should acquire lock, check empty, release lock, remove queue
        verify(queue).startProcessing();
        verify(queue, atLeastOnce()).isEmpty();
        verify(queue).stopProcessing();
        verify(registry).removeQueue("conv-1");
    }

    @Test
    void testStartProcessing_AlreadyProcessing_Skips() {
        // Given: Queue already processing
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(false);

        // When: Start processing
        processor.startProcessing("conv-1");

        // Then: Should skip without polling
        verify(queue).startProcessing();
        verify(queue, never()).poll();
        verify(queue, never()).stopProcessing();
    }

    @Test
    void testStartProcessing_NonEmptyQueue_PollsJobs() {
        // Given: Queue with jobs that returns null on poll (simulating empty after check)
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(true);
        when(queue.isEmpty()).thenReturn(false, true);
        when(queue.poll()).thenReturn(null); // Simulate interrupted/empty

        // When: Start processing
        processor.startProcessing("conv-1");

        // Then: Should attempt to poll
        verify(queue).poll();
        verify(queue).stopProcessing();
    }

    @Test
    void testStartProcessing_AlwaysReleasesLock() {
        // Given: Queue that throws exception during isEmpty check
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(true);
        when(queue.isEmpty()).thenThrow(new RuntimeException("Test exception"));

        // When: Start processing (should handle exception)
        assertThrows(RuntimeException.class, () -> processor.startProcessing("conv-1"));

        // Then: Should still release lock
        verify(queue).stopProcessing();
    }

    @Test
    void testStartProcessing_NonEmptyAfterProcessing_DoesNotRemoveQueue() {
        // Given: Queue still has items after processing
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(true);
        when(queue.isEmpty()).thenReturn(false, false); // Still not empty after poll
        when(queue.poll()).thenReturn(null); // Break loop

        // When: Start processing
        processor.startProcessing("conv-1");

        // Then: Should not remove queue
        verify(registry, never()).removeQueue("conv-1");
    }

    @Test
    void testStartProcessingAsync_ReturnsCompletableFuture() {
        // Given: Queue
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenReturn(true);
        when(queue.isEmpty()).thenReturn(true);

        // When: Start async
        CompletableFuture<Void> future = processor.startProcessingAsync("conv-1");

        // Then: Should return future that completes
        assertNotNull(future);
        assertDoesNotThrow(() -> future.get());
    }

    @Test
    void testStartProcessingAsync_HandlesException() {
        // Given: Queue that throws exception
        ConversationJobQueue queue = mock(ConversationJobQueue.class);
        when(registry.getOrCreateQueue("conv-1")).thenReturn(queue);
        when(queue.startProcessing()).thenThrow(new RuntimeException("Test error"));

        // When: Start async
        CompletableFuture<Void> future = processor.startProcessingAsync("conv-1");

        // Then: Future should complete exceptionally
        assertThrows(Exception.class, () -> future.get());
    }

    @Test
    void testCleanup_ShutsDownChannel() {
        // Given: Channel not shutdown
        when(mockChannel.isShutdown()).thenReturn(false);
        when(mockChannel.shutdown()).thenReturn(mockChannel);

        // When: Cleanup
        processor.cleanup();

        // Then: Should shutdown channel
        verify(mockChannel).shutdown();
    }

    @Test
    void testCleanup_AlreadyShutdown_DoesNothing() {
        // Given: Channel already shutdown
        when(mockChannel.isShutdown()).thenReturn(true);

        // When: Cleanup
        processor.cleanup();

        // Then: Should not call shutdown again
        verify(mockChannel, never()).shutdown();
    }

    @Test
    void testCleanup_NullChannel_DoesNotThrow() {
        // Given: Null channel
        realProcessor.channel = null;

        // When/Then: Should not throw
        assertDoesNotThrow(() -> processor.cleanup());
    }

    @Test
    void testInit_CreatesGrpcChannel() {
        // init() on the CDI-managed singleton creates a channel to the test gRPC address
        // (localhost:50051 from test application.properties). Verify the channel and stub
        // are non-null after the @PostConstruct lifecycle runs.
        // Replace the stub again to prove init() wires it correctly.
        realProcessor.channel = null;
        realProcessor.conversationsStub = null;

        processor.init();

        assertNotNull(realProcessor.channel);
        assertNotNull(realProcessor.conversationsStub);

        // Restore mocks so subsequent tests are not affected
        realProcessor.conversationsStub = conversationsStub;
        realProcessor.channel = mockChannel;
    }

    @Test
    void testJobProcessingException_PreservesMessage() {
        // Given: Exception with message and cause
        RuntimeException cause = new RuntimeException("Root cause");
        
        // When: Create JobProcessingException
        JobProcessor.JobProcessingException exception =
            new JobProcessor.JobProcessingException("Processing failed", cause);

        // Then: Should preserve message and cause
        assertEquals("Processing failed", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    // -------------------------------------------------------------------------
    // approximateObservedAtCount counter test (JP-N1)
    // -------------------------------------------------------------------------

    @Test
    void approximateObservedAtCountStartsAtZero() {
        // JP-N1: baseline — freshly constructed processor, no jobs run yet.
        // Verifies the public accessor is wired correctly and the counter
        // initialises to zero before any job has been processed.
        // The increment path (fallback when no entry createdAt is present) is
        // covered by the end-to-end integration test suite where Arc CDI is live.
        assertEquals(0L, processor.getApproximateObservedAtCount(),
            "counter must start at zero before any job is processed");
    }

    // -------------------------------------------------------------------------
    // parseEntryIdsFromCitations — citation parsing unit tests
    // -------------------------------------------------------------------------

    @Test
    void parseEntryIds_HappyPath_ReturnsReferencedUuids() {
        Map<String, String> mapping = Map.of("E1", "uuid-1", "E2", "uuid-2", "E3", "uuid-3");
        List<String> citations = List.of("E1: user prefers dark mode", "E3: user likes Python");

        List<String> result = processor.parseEntryIdsFromCitations(citations, mapping);

        assertEquals(List.of("uuid-1", "uuid-3"), result);
    }

    @Test
    void parseEntryIds_NoPrefixCitations_ReturnsEmpty() {
        Map<String, String> mapping = Map.of("E1", "uuid-1");
        List<String> citations = List.of("user prefers dark mode", "another citation");

        List<String> result = processor.parseEntryIdsFromCitations(citations, mapping);

        assertTrue(result.isEmpty(), "citations without E<n>: prefix must return empty list");
    }

    @Test
    void parseEntryIds_UnknownEntryRef_IsIgnored() {
        Map<String, String> mapping = Map.of("E1", "uuid-1");
        List<String> citations = List.of("E99: some text not in mapping");

        List<String> result = processor.parseEntryIdsFromCitations(citations, mapping);

        assertTrue(result.isEmpty(), "unknown E<n> reference must be ignored");
    }

    @Test
    void parseEntryIds_DuplicateCitations_Deduplicated() {
        Map<String, String> mapping = Map.of("E1", "uuid-1");
        List<String> citations = List.of("E1: first mention", "E1: second mention");

        List<String> result = processor.parseEntryIdsFromCitations(citations, mapping);

        assertEquals(List.of("uuid-1"), result, "duplicate entry references must be deduplicated");
    }

    @Test
    void parseEntryIds_NullCitationInList_IsSkipped() {
        Map<String, String> mapping = Map.of("E1", "uuid-1");
        List<String> citations = new java.util.ArrayList<>();
        citations.add(null);
        citations.add("E1: valid citation");

        List<String> result = processor.parseEntryIdsFromCitations(citations, mapping);

        assertEquals(List.of("uuid-1"), result, "null citations must be skipped without error");
    }

    @Test
    void parseEntryIds_NonDigitsBetweenEAndColon_IsIgnored() {
        // "Error:", "En:", "E1abc:" must NOT be treated as entry references
        Map<String, String> mapping = Map.of("E1", "uuid-1");
        List<String> citations = List.of("Error: something failed", "En: note", "E1abc: mixed");

        List<String> result = processor.parseEntryIdsFromCitations(citations, mapping);

        assertTrue(result.isEmpty(), "non-digit E-prefix patterns must not match");
    }

    @Test
    void parseEntryIds_MixedCitations_OnlyPrefixedResolved() {
        Map<String, String> mapping = Map.of("E1", "uuid-1", "E2", "uuid-2");
        List<String> citations = List.of(
                "E1: cited entry",
                "no prefix here",
                "E2: another cited entry",
                "Error: not an entry ref"
        );

        List<String> result = processor.parseEntryIdsFromCitations(citations, mapping);

        assertEquals(List.of("uuid-1", "uuid-2"), result);
    }

    @Test
    void parseEntryIds_EmptyCitationsList_ReturnsEmpty() {
        Map<String, String> mapping = Map.of("E1", "uuid-1");

        List<String> result = processor.parseEntryIdsFromCitations(Collections.emptyList(), mapping);

        assertTrue(result.isEmpty());
    }

    @Test
    void parseEntryIds_PreservesInsertionOrder() {
        Map<String, String> mapping = Map.of("E1", "uuid-1", "E2", "uuid-2", "E3", "uuid-3");
        List<String> citations = List.of("E3: third", "E1: first", "E2: second");

        List<String> result = processor.parseEntryIdsFromCitations(citations, mapping);

        assertEquals(List.of("uuid-3", "uuid-1", "uuid-2"), result,
                "insertion order of first occurrence must be preserved");
    }

    @Test
    void filterEvidenceForBatch_RemovesLowSalienceNonBatchEntries() {
        when(mockSalienceScorer.shouldKeep("thanks")).thenReturn(false);

        EvidencePack filtered = processor.filterEvidenceForBatch(
            List.of("00000000-0000-0000-0000-000000000002"),
            new EvidencePack(List.of(
                historyEntry("00000000-0000-0000-0000-000000000001", "USER", "thanks"),
                historyEntry("00000000-0000-0000-0000-000000000002", "USER", "Production rollback is needed")
            ))
        );

        assertEquals(1, filtered.size());
        assertTrue(filtered.formatAsText().contains("Production rollback is needed"));
        assertFalse(filtered.formatAsText().contains("thanks"));
    }

    @Test
    void filterEvidenceForBatch_KeepsBatchEntriesEvenWhenLowSalience() {
        EvidencePack filtered = processor.filterEvidenceForBatch(
            List.of("00000000-0000-0000-0000-000000000001"),
            new EvidencePack(List.of(
                historyEntry("00000000-0000-0000-0000-000000000001", "USER", "thanks")
            ))
        );

        assertEquals(1, filtered.size());
        assertTrue(filtered.formatAsText().contains("thanks"));
        verify(mockSalienceScorer, never()).shouldKeep(any());
    }

    private Entry historyEntry(String id, String role, String text) {
        Struct struct = Struct.newBuilder()
            .putFields("role", Value.newBuilder().setStringValue(role).build())
            .putFields("text", Value.newBuilder().setStringValue(text).build())
            .build();

        return Entry.newBuilder()
            .setId(uuidToBytes(id))
            .setContentType("history")
            .addContent(Value.newBuilder().setStructValue(struct).build())
            .build();
    }

    private ByteString uuidToBytes(String id) {
        java.util.UUID uuid = java.util.UUID.fromString(id);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return ByteString.copyFrom(buffer.array());
    }
}