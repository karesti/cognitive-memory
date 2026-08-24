package io.github.rigazilla.memory.cognition.event;

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
import io.quarkus.arc.Arc;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
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
@QuarkusTest
class GrpcAdminEventClientTest {

    @Inject
    GrpcAdminEventClient client;

    /** The real (non-proxy) bean instance — used for direct field access (lastEventCursor etc.). */
    private GrpcAdminEventClient realClient;

    @InjectMock
    CheckpointService checkpointService;

    @InjectMock
    DirtyWindowRegistry windowRegistry;

    @BeforeEach
    void setUp() {
        // Config is wired by CDI from test/resources/application.properties.
        // CheckpointService and DirtyWindowRegistry are replaced by @InjectMock above.
        // arc_contextualInstance() returns the actual delegate, not the proxy shell, so
        // direct field reads/writes (lastEventCursor, eventsAccepted) reach the real bean.
        realClient = (GrpcAdminEventClient) ((io.quarkus.arc.ClientProxy) client).arc_contextualInstance();
        // Reset mutable state so tests are independent of execution order
        realClient.lastEventCursor = null;
        realClient.eventsAccepted.set(0);
    }



    @Test
    void testOnShutdown_SavesCheckpoint() {
        // Given: Client has processed events
        realClient.lastEventCursor = "cursor-final";
        when(windowRegistry.serializeWindows()).thenReturn(List.of());

        // When: Shutdown event is observed
        ShutdownEvent shutdownEvent = mock(ShutdownEvent.class);
        client.onShutdown(shutdownEvent);

        // Then: Should save checkpoint
        verify(checkpointService).saveCheckpoint(
            eq("test-worker"),
            eq("cursor-final"),
            eq("test-runtime"),
            eq("1.0.0-test"),
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
        assertEquals("cursor-new", realClient.lastEventCursor);
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
        verify(checkpointService).resetCheckpoint("test-worker", "test-runtime", "1.0.0-test");
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
        realClient.lastEventCursor = null;

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
        realClient.lastEventCursor = "cursor-123";
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
            eq("1.0.0-test"),
            eq(windows)
        );
    }

    @Test
    void testSaveCheckpoint_Exception_LogsError() {
        // Given: Checkpoint service throws exception
        realClient.lastEventCursor = "cursor-123";
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

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @BeforeEach
        void installDisabledScorer() {
            // Install a disabled scorer so that pass-through behaviour is the default
            // for all SalienceGate tests. Tests that need filtering call useEnabledScorer().
            SalienceScorerConfigStub disabledConfig = new SalienceScorerConfigStub();
            disabledConfig.enabled = false;
            SalienceScorer disabled = new SalienceScorer(disabledConfig, new KeywordLoader(disabledConfig));
            disabled.init();
            realClient.salienceScorer = disabled;
        }

        /** Installs a fully enabled scorer with bundled keywords loaded. */
        private void useEnabledScorer() {
            SalienceScorerConfigStub enabledConfig = new SalienceScorerConfigStub();
            SalienceScorer enabled = new SalienceScorer(enabledConfig, new KeywordLoader(enabledConfig));
            enabled.init();
            realClient.salienceScorer = enabled;
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
            realClient.salienceScorer = broken;

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
        void userTurn_returnsText() {
            String json = "{\"content\":[{\"role\":\"USER\",\"text\":\"hi there\"}]}";
            assertThat(client.extractEntryText(json)).isEqualTo("hi there");
        }

        @Test
        void multipleUserTurns_joinedBySpace() {
            String json = "{\"content\":["
                    + "{\"role\":\"USER\",\"text\":\"first\"},"
                    + "{\"role\":\"USER\",\"text\":\"second\"}"
                    + "]}";
            assertThat(client.extractEntryText(json)).isEqualTo("first second");
        }

        @Test
        void assistantOnlyTurn_returnsNull() {
            String json = "{\"content\":[{\"role\":\"ASSISTANT\",\"text\":\"hello\"}]}";
            assertThat(client.extractEntryText(json)).isNull();
        }

        @Test
        void mixedTurns_onlyUserTextReturned() {
            String json = "{\"content\":["
                    + "{\"role\":\"ASSISTANT\",\"text\":\"I can help\"},"
                    + "{\"role\":\"USER\",\"text\":\"deploy now\"},"
                    + "{\"role\":\"ASSISTANT\",\"text\":\"done\"}"
                    + "]}";
            assertThat(client.extractEntryText(json)).isEqualTo("deploy now");
        }

        @Test
        void noContentArray_returnsNull() {
            // Summary-mode payload — only IDs, no content
            String json = "{\"conversation\":\"conv-1\",\"entry\":\"entry-1\"}";
            assertThat(client.extractEntryText(json)).isNull();
        }

        @Test
        void emptyContentArray_returnsNull() {
            String json = "{\"content\":[]}";
            assertThat(client.extractEntryText(json)).isNull();
        }

        @Test
        void blankUserText_returnsNull() {
            String json = "{\"content\":[{\"role\":\"USER\",\"text\":\"   \"}]}";
            assertThat(client.extractEntryText(json)).isNull();
        }

        @Test
        void nullInput_returnsNull() {
            assertThat(client.extractEntryText(null)).isNull();
        }

        @Test
        void blankInput_returnsNull() {
            assertThat(client.extractEntryText("  ")).isNull();
        }

        @Test
        void malformedJson_returnsNull() {
            assertThat(client.extractEntryText("{not valid json")).isNull();
        }

        @Test
        void userTurn_textIsStripped() {
            String json = "{\"content\":[{\"role\":\"USER\",\"text\":\"  hi  \"}]}";
            assertThat(client.extractEntryText(json)).isEqualTo("hi");
        }
    }
}
