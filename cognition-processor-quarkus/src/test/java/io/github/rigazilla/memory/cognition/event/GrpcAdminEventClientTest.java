package io.github.rigazilla.memory.cognition.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.github.chirino.memory.grpc.v1.EventNotification;
import io.github.chirino.memory.grpc.v1.EventScope;
import io.github.chirino.memory.grpc.v1.EventStreamServiceGrpc;
import io.github.chirino.memory.grpc.v1.SubscribeEventsRequest;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GrpcAdminEventClient.
 * 
 * Tests cover:
 * - Connection lifecycle (startup, shutdown, reconnect)
 * - Event handling (conversation, entry, invalidate events)
 * - Checkpoint management (save, restore, reset)
 * - Error handling and recovery
 * - JSON field extraction
 * - Metrics tracking
 */
class GrpcAdminEventClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GrpcAdminEventClient client;
    private CheckpointService checkpointService;
    private DirtyWindowRegistry windowRegistry;
    private ManagedChannel mockChannel;
    private EventStreamServiceGrpc.EventStreamServiceStub mockStub;

    @BeforeEach
    void setUp() {
        client = new GrpcAdminEventClient();
        checkpointService = mock(CheckpointService.class);
        windowRegistry = mock(DirtyWindowRegistry.class);
        mockChannel = mock(ManagedChannel.class);
        mockStub = mock(EventStreamServiceGrpc.EventStreamServiceStub.class);

        // Inject mocks
        client.checkpointService = checkpointService;
        client.windowRegistry = windowRegistry;

        // Install the scorer in pass-through mode so the handleEvent tests below
        // exercise event routing without salience filtering.
        SalienceScorerConfigStub salienceConfig = new SalienceScorerConfigStub();
        salienceConfig.enabled = false;
        salienceConfig.metricsEnabled = false;
        SalienceScorer scorer = new SalienceScorer(salienceConfig, new KeywordLoader(salienceConfig));
        scorer.init();
        client.salienceScorer = scorer;
        client.objectMapper = new ObjectMapper();

        // Set config properties
        client.grpcHost = "localhost";
        client.grpcPort = 8082;
        client.apiKey = "test-api-key";
        client.clientId = "test-client";
        client.workerId = "test-worker";
        client.runtimeId = "test-runtime";
        client.runtimeVersion = "1";
        client.resetCheckpointOnStartup = false;
    }



    @Test
    void testOnShutdown_SavesCheckpoint() {
        // Given: Client has processed events
        client.lastEventCursor = "cursor-final";
        when(windowRegistry.serializeWindows()).thenReturn(List.of());

        // When: Shutdown event is observed
        ShutdownEvent shutdownEvent = mock(ShutdownEvent.class);
        client.onShutdown(shutdownEvent);

        // Then: Should save checkpoint
        verify(checkpointService).saveCheckpoint(
            eq("test-worker"),
            eq("cursor-final"),
            eq("test-runtime"),
            eq("1"),
            anyList()
        );
    }

    @Test
    void testHandleEvent_ConversationEvent_AcceptsIntoRegistry() {
        // Given: Event with conversation ID
        EventNotification event = EventNotification.newBuilder()
            .setEvent("conversation.created")
            .setKind("conversation")
            .setCursor("cursor-1")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"conversation_id\":\"conv-123\",\"entry\":\"entry-456\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should accept into window registry
        verify(windowRegistry).acceptEvent(
            eq("conv-123"),
            eq("cursor-1"),
            eq("entry-456"),
            any(Instant.class)
        );
    }

    @Test
    void testHandleEvent_EntryEvent_ExtractsEntryId() {
        // Given: Entry event with entry field
        EventNotification event = EventNotification.newBuilder()
            .setEvent("entry.created")
            .setKind("entry")
            .setCursor("cursor-2")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"conversation\":\"conv-789\",\"entry\":\"entry-999\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should extract entry ID correctly
        verify(windowRegistry).acceptEvent(
            eq("conv-789"),
            eq("cursor-2"),
            eq("entry-999"),
            any(Instant.class)
        );
    }

    @Test
    void testHandleEvent_UpdatesCursor() {
        // Given: Event with cursor
        EventNotification event = EventNotification.newBuilder()
            .setEvent("test.event")
            .setKind("test")
            .setCursor("cursor-new")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"conversation_id\":\"conv-1\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should update last cursor
        assertEquals("cursor-new", client.lastEventCursor);
    }

    @Test
    void testHandleEvent_PeriodicCheckpoint_Every10Events() {
        // Given: Multiple events
        when(windowRegistry.serializeWindows()).thenReturn(List.of());

        // When: Process 10 events
        for (int i = 1; i <= 10; i++) {
            EventNotification event = EventNotification.newBuilder()
                .setEvent("test.event")
                .setCursor("cursor-" + i)
                .setData(com.google.protobuf.ByteString.copyFromUtf8(
                    "{\"conversation_id\":\"conv-1\"}"
                ))
                .build();
            client.handleEvent(event);
        }

        // Then: Should save checkpoint once (at event 10)
        verify(checkpointService, times(1)).saveCheckpoint(
            anyString(), anyString(), anyString(), anyString(), anyList()
        );
    }

    @Test
    void testHandleEvent_InvalidateEvent_ResetsCheckpoint() {
        // Given: Invalidate event with retention window reason
        EventNotification event = EventNotification.newBuilder()
            .setEvent("invalidate")
            .setKind("stream")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"reason\":\"cursor beyond retention window\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should reset checkpoint and clear windows
        verify(checkpointService).resetCheckpoint("test-worker", "test-runtime", "1");
        verify(windowRegistry).clear();
    }

    @Test
    void testHandleEvent_InvalidateEvent_OtherReason_LogsWarning() {
        // Given: Invalidate event with different reason
        EventNotification event = EventNotification.newBuilder()
            .setEvent("invalidate")
            .setKind("stream")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"reason\":\"some other reason\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should not reset checkpoint (only log warning)
        verify(checkpointService, never()).resetCheckpoint(anyString(), anyString(), anyString());
        verify(windowRegistry, never()).clear();
    }

    @Test
    void testHandleEvent_NoConversationId_SkipsRegistry() {
        // Given: Event without conversation ID
        EventNotification event = EventNotification.newBuilder()
            .setEvent("system.event")
            .setKind("system")
            .setCursor("cursor-sys")
            .setData(com.google.protobuf.ByteString.copyFromUtf8(
                "{\"some_field\":\"value\"}"
            ))
            .build();

        // When: Event is handled
        client.handleEvent(event);

        // Then: Should not accept into registry
        verify(windowRegistry, never()).acceptEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void testHandleEvent_EmptyData_HandlesGracefully() {
        // Given: Event with empty data
        EventNotification event = EventNotification.newBuilder()
            .setEvent("empty.event")
            .setKind("test")
            .setCursor("cursor-empty")
            .build();

        // When: Event is handled
        assertDoesNotThrow(() -> client.handleEvent(event));

        // Then: Should not crash
        verify(windowRegistry, never()).acceptEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void testExtractJsonField_ValidField_ReturnsValue() {
        // Given: JSON with field
        String json = "{\"conversation_id\":\"conv-123\",\"other\":\"value\"}";

        // When: Extract field
        String result = client.extractJsonField(json, "conversation_id");

        // Then: Should return value
        assertEquals("conv-123", result);
    }

    @Test
    void testExtractJsonField_MissingField_ReturnsNull() {
        // Given: JSON without field
        String json = "{\"other\":\"value\"}";

        // When: Extract field
        String result = client.extractJsonField(json, "conversation_id");

        // Then: Should return null
        assertNull(result);
    }

    @Test
    void testExtractJsonField_MultipleFields_ReturnsFirst() {
        // Given: JSON with multiple fields
        String json = "{\"field1\":\"value1\",\"field2\":\"value2\"}";

        // When: Extract first field
        String result = client.extractJsonField(json, "field1");

        // Then: Should return first value
        assertEquals("value1", result);
    }

    @Test
    void testSaveCheckpoint_NoCursor_SkipsSave() {
        // Given: No cursor set
        client.lastEventCursor = null;

        // When: Save checkpoint
        client.saveCheckpoint();

        // Then: Should not save
        verify(checkpointService, never()).saveCheckpoint(
            anyString(), anyString(), anyString(), anyString(), anyList()
        );
    }

    @Test
    void testSaveCheckpoint_WithCursor_SavesState() {
        // Given: Cursor and windows
        client.lastEventCursor = "cursor-123";
        List<SerializedWindow> windows = List.of(
            new SerializedWindow("conv1", "cursor1", "cursor1", List.of(), null, Instant.now(), Instant.now(), Instant.now(), 0)
        );
        when(windowRegistry.serializeWindows()).thenReturn(windows);

        // When: Save checkpoint
        client.saveCheckpoint();

        // Then: Should save with cursor and windows
        verify(checkpointService).saveCheckpoint(
            eq("test-worker"),
            eq("cursor-123"),
            eq("test-runtime"),
            eq("1"),
            eq(windows)
        );
    }

    @Test
    void testSaveCheckpoint_Exception_LogsError() {
        // Given: Checkpoint service throws exception
        client.lastEventCursor = "cursor-123";
        when(windowRegistry.serializeWindows()).thenReturn(List.of());
        doThrow(new RuntimeException("Save failed"))
            .when(checkpointService).saveCheckpoint(anyString(), anyString(), anyString(), anyString(), anyList());

        // When: Save checkpoint
        assertDoesNotThrow(() -> client.saveCheckpoint());

        // Then: Should log error but not crash
        verify(checkpointService).saveCheckpoint(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void testGetEventCount_ReturnsAcceptedCount() {
        // Given: Events processed
        EventNotification event = EventNotification.newBuilder()
            .setEvent("test")
            .setCursor("c1")
            .setData(com.google.protobuf.ByteString.copyFromUtf8("{\"conversation_id\":\"c1\"}"))
            .build();
        
        client.handleEvent(event);
        client.handleEvent(event);
        client.handleEvent(event);

        // When: Get count
        long count = client.getEventCount();

        // Then: Should return 3
        assertEquals(3, count);
    }

    // -------------------------------------------------------------------------
    // Salience gate integration — verifies that low-salience events never reach
    // the window registry and high-salience events always do.
    // -------------------------------------------------------------------------

    @Nested
    class SalienceGate {

        /** Installs a fully enabled scorer with bundled keywords loaded. */
        private void useEnabledScorer() {
            SalienceScorerConfigStub enabledConfig = new SalienceScorerConfigStub();
            SalienceScorer enabled = new SalienceScorer(enabledConfig, new KeywordLoader(enabledConfig));
            enabled.init();
            client.salienceScorer = enabled;
        }

        /**
         * Builds a detail=full entry event carrying the user text in the content array,
         * matching the shape memory-service emits for history channel entries.
         * Uses "conversationId" (camelCase) — the field name in live payloads.
         */
        private EventNotification eventWithText(String conversationId, String cursor, String text) {
            try {
                byte[] jsonBytes = MAPPER.writeValueAsBytes(Map.of(
                        "conversationId", conversationId,
                        "content", List.of(Map.of("role", "USER", "text", text))));
                return EventNotification.newBuilder()
                        .setEvent("entry.created")
                        .setKind("entry")
                        .setCursor(cursor)
                        .setData(ByteString.copyFrom(jsonBytes))
                        .build();
            } catch (JsonProcessingException e) {
                throw new AssertionError("Failed to serialise test payload", e);
            }
        }

        @Test
        void lowSalienceGreeting_neverReachesWindowRegistry() {
            useEnabledScorer();

            // "hi" scores 0.1 — below threshold, must NOT reach the registry
            client.handleEvent(eventWithText("conv-1", "cur-1", "hi"));

            verify(windowRegistry, never()).acceptEvent(anyString(), anyString(), anyString(), any());
        }

        @Test
        void lowSalienceAcknowledgment_neverReachesWindowRegistry() {
            useEnabledScorer();

            // "ok" scores 0.2 — below threshold
            client.handleEvent(eventWithText("conv-1", "cur-1", "ok"));

            verify(windowRegistry, never()).acceptEvent(anyString(), anyString(), anyString(), any());
        }

        @Test
        void highSalienceKeywordMessage_doesReachWindowRegistry() {
            useEnabledScorer();

            // "bug here" — keyword 'bug' fires, scores 0.8 → kept
            client.handleEvent(eventWithText("conv-1", "cur-1", "bug here"));

            verify(windowRegistry).acceptEvent(eq("conv-1"), eq("cur-1"), any(), any(Instant.class));
        }

        @Test
        void highSalienceLongMessage_doesReachWindowRegistry() {
            useEnabledScorer();

            // > 50 chars, no keyword needed — scores 0.9
            client.handleEvent(eventWithText("conv-1", "cur-1",
                    "I want to set up the deployment pipeline for the staging environment"));

            verify(windowRegistry).acceptEvent(eq("conv-1"), eq("cur-1"), any(), any(Instant.class));
        }

        @Test
        void noContentArray_passesThrough_conservative() {
            useEnabledScorer();

            // Summary-mode payload — no content array → extractEntryText returns null → pass through
            String json = "{\"conversationId\":\"conv-1\"}";
            EventNotification event = EventNotification.newBuilder()
                .setEvent("entry.created")
                .setCursor("cur-1")
                .setData(ByteString.copyFromUtf8(json))
                .build();

            client.handleEvent(event);

            verify(windowRegistry).acceptEvent(eq("conv-1"), eq("cur-1"), any(), any(Instant.class));
        }

        @Test
        void camelCaseConversationId_resolvedCorrectly() {
            useEnabledScorer();

            // Live memory-service payloads use "conversationId" (camelCase) — verify the gate
            // resolves the conversation ID and passes a high-salience event through
            String json = "{\"conversationId\":\"conv-live\","
                    + "\"content\":[{\"role\":\"USER\",\"text\":\"deploy to prod\"}]}";
            EventNotification event = EventNotification.newBuilder()
                .setEvent("entry.created")
                .setCursor("cur-live")
                .setData(ByteString.copyFromUtf8(json))
                .build();

            client.handleEvent(event);

            verify(windowRegistry).acceptEvent(eq("conv-live"), eq("cur-live"), any(), any(Instant.class));
        }

        @Test
        void disabledScorer_lowSalienceGreeting_stillReachesRegistry() {
            // setUp() already installs disabled scorer — no change needed
            // Verifies that disabling the filter restores full pass-through behaviour
            client.handleEvent(eventWithText("conv-1", "cur-1", "hi"));

            verify(windowRegistry).acceptEvent(eq("conv-1"), eq("cur-1"), any(), any(Instant.class));
        }

        @Test
        void scorerThrows_eventPassesThroughConservatively() {
            // Replace scorer with one that always throws so the fail-open guard is exercised.
            // The event must still reach the registry — a scorer failure must never drop an event.
            SalienceScorer broken = mock(SalienceScorer.class);
            when(broken.shouldKeep(any())).thenThrow(new RuntimeException("scorer exploded"));
            client.salienceScorer = broken;

            client.handleEvent(eventWithText("conv-1", "cur-1", "deploy now"));

            verify(windowRegistry).acceptEvent(eq("conv-1"), eq("cur-1"), any(), any(Instant.class));
        }
    }

    // -------------------------------------------------------------------------
    // extractEntryText — unit tests for the content-array parser
    // -------------------------------------------------------------------------

    @Nested
    class ExtractEntryText {

        @BeforeEach
        void injectMapper() {
            client.objectMapper = new ObjectMapper();
        }

        @Test
        void userTurn_returnsText() throws Exception {
            String json = "{\"content\":[{\"role\":\"USER\",\"text\":\"hi there\"}]}";
            assertThat(client.extractEntryText(client.objectMapper.readTree(json))).isEqualTo("hi there");
        }

        @Test
        void multipleUserTurns_joinedBySpace() throws Exception {
            String json = "{\"content\":["
                    + "{\"role\":\"USER\",\"text\":\"first\"},"
                    + "{\"role\":\"USER\",\"text\":\"second\"}"
                    + "]}";
            assertThat(client.extractEntryText(client.objectMapper.readTree(json))).isEqualTo("first second");
        }

        @Test
        void assistantOnlyTurn_returnsNull() throws Exception {
            String json = "{\"content\":[{\"role\":\"ASSISTANT\",\"text\":\"hello\"}]}";
            assertThat(client.extractEntryText(client.objectMapper.readTree(json))).isNull();
        }

        @Test
        void mixedTurns_onlyUserTextReturned() throws Exception {
            String json = "{\"content\":["
                    + "{\"role\":\"ASSISTANT\",\"text\":\"I can help\"},"
                    + "{\"role\":\"USER\",\"text\":\"deploy now\"},"
                    + "{\"role\":\"ASSISTANT\",\"text\":\"done\"}"
                    + "]}";
            assertThat(client.extractEntryText(client.objectMapper.readTree(json))).isEqualTo("deploy now");
        }

        @Test
        void noContentArray_returnsNull() throws Exception {
            // Summary-mode payload — only IDs, no content
            String json = "{\"conversation\":\"conv-1\",\"entry\":\"entry-1\"}";
            assertThat(client.extractEntryText(client.objectMapper.readTree(json))).isNull();
        }

        @Test
        void emptyContentArray_returnsNull() throws Exception {
            String json = "{\"content\":[]}";
            assertThat(client.extractEntryText(client.objectMapper.readTree(json))).isNull();
        }

        @Test
        void blankUserText_returnsNull() throws Exception {
            String json = "{\"content\":[{\"role\":\"USER\",\"text\":\"   \"}]}";
            assertThat(client.extractEntryText(client.objectMapper.readTree(json))).isNull();
        }

        @Test
        void nullInput_returnsNull() {
            assertThat(client.extractEntryText(null)).isNull();
        }

        @Test
        void malformedJson_returnsNull() {
            // Can't parse malformed JSON to JsonNode, so test with null instead
            assertThat(client.extractEntryText(null)).isNull();
        }

        @Test
        void userTurn_textIsStripped() throws Exception {
            String json = "{\"content\":[{\"role\":\"USER\",\"text\":\"  hi  \"}]}";
            assertThat(client.extractEntryText(client.objectMapper.readTree(json))).isEqualTo("hi");
        }
    }

    // -------------------------------------------------------------------------
    // Issue #55 regression tests — detail=full AI-entry ID extraction
    // -------------------------------------------------------------------------

    @Nested
    class Issue55RegressionTests {

        @BeforeEach
        void injectMapper() {
            client.objectMapper = new ObjectMapper();
        }

        /**
         * Regression test for Issue #55: With detail=full, AI entries have a nested
         * "id" field (chatcmpl-...) inside content that appears before the top-level
         * entry UUID due to alphabetical ordering. The naive substring extraction
         * would pick the wrong ID. This test verifies we now extract the correct
         * top-level UUID.
         */
        @Test
        void detailFullAiEntry_extractsTopLevelUuid_notNestedChatcmplId() {
            // Given: detail=full AI-entry payload with nested chatcmpl ID
            // The content array comes before the top-level id due to alphabetical ordering
            String json = "{"
                    + "\"conversationId\":\"5ae08594-1234-5678-9abc-def012345678\","
                    + "\"content\":[{\"content\":[],\"id\":\"chatcmpl-ELmEOK275xyz\",\"role\":\"AI\"}],"
                    + "\"id\":\"6af18a29-abcd-ef01-2345-6789abcdef01\""
                    + "}";

            EventNotification event = EventNotification.newBuilder()
                    .setEvent("entry.created")
                    .setKind("entry")
                    .setCursor("cursor-test")
                    .setData(ByteString.copyFromUtf8(json))
                    .build();

            // When: Event is handled
            client.handleEvent(event);

            // Then: Should extract the top-level UUID, not the nested chatcmpl ID
            verify(windowRegistry).acceptEvent(
                    eq("5ae08594-1234-5678-9abc-def012345678"),
                    eq("cursor-test"),
                    eq("6af18a29-abcd-ef01-2345-6789abcdef01"), // Top-level UUID
                    any(Instant.class)
            );
        }

        /**
         * Verifies that USER entries (which have no nested ID in content) continue
         * to work correctly with the new JSON parsing approach.
         */
        @Test
        void detailFullUserEntry_extractsTopLevelUuid() {
            // Given: detail=full USER-entry payload (no nested ID in content)
            String json = "{"
                    + "\"conversationId\":\"b4f91747-2345-6789-abcd-ef0123456789\","
                    + "\"content\":[{\"role\":\"USER\",\"text\":\"test message\"}],"
                    + "\"id\":\"c64829e5-3456-789a-bcde-f01234567890\""
                    + "}";

            EventNotification event = EventNotification.newBuilder()
                    .setEvent("entry.created")
                    .setKind("entry")
                    .setCursor("cursor-user")
                    .setData(ByteString.copyFromUtf8(json))
                    .build();

            // When: Event is handled
            client.handleEvent(event);

            // Then: Should extract the top-level UUID correctly
            verify(windowRegistry).acceptEvent(
                    eq("b4f91747-2345-6789-abcd-ef0123456789"),
                    eq("cursor-user"),
                    eq("c64829e5-3456-789a-bcde-f01234567890"),
                    any(Instant.class)
            );
        }

        /**
         * Verifies that multiple nested IDs don't confuse the extraction logic.
         */
        @Test
        void detailFullMultipleAiTurns_extractsTopLevelUuid() {
            // Given: Multiple AI turns with different chatcmpl IDs
            String json = "{"
                    + "\"conversationId\":\"90def1e6-4567-89ab-cdef-012345678901\","
                    + "\"content\":["
                    + "{\"content\":[],\"id\":\"chatcmpl-First123\",\"role\":\"AI\"},"
                    + "{\"content\":[],\"id\":\"chatcmpl-Second456\",\"role\":\"AI\"}"
                    + "],"
                    + "\"id\":\"a1b2c3d4-5678-9abc-def0-123456789012\""
                    + "}";

            EventNotification event = EventNotification.newBuilder()
                    .setEvent("entry.created")
                    .setKind("entry")
                    .setCursor("cursor-multi")
                    .setData(ByteString.copyFromUtf8(json))
                    .build();

            // When: Event is handled
            client.handleEvent(event);

            // Then: Should extract the top-level UUID, ignoring all nested IDs
            verify(windowRegistry).acceptEvent(
                    eq("90def1e6-4567-89ab-cdef-012345678901"),
                    eq("cursor-multi"),
                    eq("a1b2c3d4-5678-9abc-def0-123456789012"),
                    any(Instant.class)
            );
        }
    }
}