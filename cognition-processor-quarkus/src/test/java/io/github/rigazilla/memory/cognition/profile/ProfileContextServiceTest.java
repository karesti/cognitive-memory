package io.github.rigazilla.memory.cognition.profile;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.AdminPutMemoryRequest;
import io.github.chirino.memory.grpc.v1.AdminSearchMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminSearchMemoriesResponse;
import io.github.chirino.memory.grpc.v1.MemoryItem;
import io.github.rigazilla.memory.cognition.writer.MemoryWriter;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.arc.Arc;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProfileContextService.
 * 
 * Tests cover:
 * - Profile consolidation orchestration
 * - Memory querying and conversion
 * - Snapshot writing
 * - Error handling
 * - gRPC interactions
 */
@QuarkusTest
class ProfileContextServiceTest {

    @Inject
    ProfileContextService service;

    /** The real (non-proxy) bean instance — used for field injection of mock stubs. */
    private ProfileContextService realService;

    @InjectMock
    ProfileConsolidationStrategy mockStrategy;

    @InjectMock
    MemoryWriter mockMemoryWriter;

    private AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub mockMemoriesStub;

    @BeforeEach
    void setUp() {
        mockMemoriesStub = mock(AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub.class);
        ManagedChannel mockChannel = mock(ManagedChannel.class);

        // Unwrap CDI proxy to reach the real bean instance — field assignment on the proxy
        // itself is a no-op because @ApplicationScoped beans are wrapped in client proxies.
        // arc_contextualInstance() returns the actual delegate, not the proxy shell.
        realService = (ProfileContextService)
                ((io.quarkus.arc.ClientProxy) service).arc_contextualInstance();
        realService.memoriesStub = mockMemoriesStub;
        realService.channel = mockChannel;
    }

    @Test
    void testConsolidateProfile_Success_ReturnsSnapshot() {
        // Given: User with memories
        String userId = "user-123";
        List<AdminMemoryItem> adminMemories = List.of(
            createAdminMemory("mem-1", "Memory 1 content"),
            createAdminMemory("mem-2", "Memory 2 content")
        );

        AdminSearchMemoriesResponse searchResponse = AdminSearchMemoriesResponse.newBuilder()
            .addAllItems(adminMemories)
            .build();
        when(mockMemoriesStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
            .thenReturn(searchResponse);

        ProfileSnapshot expectedSnapshot = new ProfileSnapshot(
            userId,
            Instant.now(),
            "Consolidated profile content",
            Map.of("section1", new ProfileSnapshot.ProfileSection("Section content", 0.9, List.of("mem-1")))
        );
        when(mockStrategy.consolidate(anyList(), eq(userId))).thenReturn(expectedSnapshot);

        // When: Consolidate profile
        ProfileSnapshot result = service.consolidateProfile(userId);

        // Then: Should return snapshot
        assertNotNull(result);
        assertEquals(userId, result.userId());
        assertEquals("Consolidated profile content", result.content());
        verify(mockStrategy).consolidate(anyList(), eq(userId));
        verify(mockMemoriesStub).putMemory(any(AdminPutMemoryRequest.class));
    }

    @Test
    void testConsolidateProfile_QueryMemories_UsesCorrectNamespace() {
        // Given: User ID
        String userId = "user-456";
        AdminSearchMemoriesResponse searchResponse = AdminSearchMemoriesResponse.newBuilder().build();
        when(mockMemoriesStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
            .thenReturn(searchResponse);

        ProfileSnapshot snapshot = new ProfileSnapshot(userId, Instant.now(), "Content", Map.of());
        when(mockStrategy.consolidate(anyList(), eq(userId))).thenReturn(snapshot);

        // When: Consolidate profile
        service.consolidateProfile(userId);

        // Then: Should query with correct namespace
        ArgumentCaptor<AdminSearchMemoriesRequest> captor = 
            ArgumentCaptor.forClass(AdminSearchMemoriesRequest.class);
        verify(mockMemoriesStub).searchMemories(captor.capture());

        AdminSearchMemoriesRequest request = captor.getValue();
        assertEquals(List.of("user", userId, "cognition.v1"), request.getNamespacePrefixList());
        assertEquals(userId, request.getAsUserId());
        assertEquals(100, request.getLimit());
    }

    @Test
    void testConsolidateProfile_WriteSnapshot_UsesCorrectNamespace() {
        // Given: User with snapshot
        String userId = "user-789";
        AdminSearchMemoriesResponse searchResponse = AdminSearchMemoriesResponse.newBuilder().build();
        when(mockMemoriesStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
            .thenReturn(searchResponse);

        ProfileSnapshot snapshot = new ProfileSnapshot(
            userId,
            Instant.parse("2026-08-04T10:00:00Z"),
            "Profile content",
            Map.of("interests", new ProfileSnapshot.ProfileSection("Interests content", 0.95, List.of("mem-1", "mem-2")))
        );
        when(mockStrategy.consolidate(anyList(), eq(userId))).thenReturn(snapshot);

        // When: Consolidate profile
        service.consolidateProfile(userId);

        // Then: Should write snapshot with correct namespace and structure
        ArgumentCaptor<AdminPutMemoryRequest> captor = 
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub).putMemory(captor.capture());

        AdminPutMemoryRequest request = captor.getValue();
        assertEquals(List.of("user", userId, "cognition.v1", "profile_context"), 
                    request.getNamespaceList());
        assertEquals("latest", request.getKey());

        Struct value = request.getValue();
        assertEquals("profile_context_snapshot", 
                    value.getFieldsMap().get("kind").getStringValue());
        assertEquals(userId, value.getFieldsMap().get("user_id").getStringValue());
        assertEquals("Profile content", value.getFieldsMap().get("content").getStringValue());
    }

    @Test
    void testConsolidateProfile_QueryFails_ThrowsException() {
        // Given: Query fails
        String userId = "user-error";
        when(mockMemoriesStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.INTERNAL));

        // When/Then: Should throw ProfileConsolidationException
        assertThrows(ProfileContextService.ProfileConsolidationException.class,
                    () -> service.consolidateProfile(userId));
    }

    @Test
    void testConsolidateProfile_StrategyFails_ThrowsException() {
        // Given: Strategy fails
        String userId = "user-strategy-error";
        AdminSearchMemoriesResponse searchResponse = AdminSearchMemoriesResponse.newBuilder().build();
        when(mockMemoriesStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
            .thenReturn(searchResponse);
        when(mockStrategy.consolidate(anyList(), eq(userId)))
            .thenThrow(new RuntimeException("Strategy failed"));

        // When/Then: Should throw ProfileConsolidationException
        assertThrows(ProfileContextService.ProfileConsolidationException.class,
                    () -> service.consolidateProfile(userId));
    }

    @Test
    void testConsolidateProfile_WriteFails_ThrowsException() {
        // Given: Write fails
        String userId = "user-write-error";
        AdminSearchMemoriesResponse searchResponse = AdminSearchMemoriesResponse.newBuilder().build();
        when(mockMemoriesStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
            .thenReturn(searchResponse);

        ProfileSnapshot snapshot = new ProfileSnapshot(userId, Instant.now(), "Content", Map.of());
        when(mockStrategy.consolidate(anyList(), eq(userId))).thenReturn(snapshot);
        when(mockMemoriesStub.putMemory(any(AdminPutMemoryRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.PERMISSION_DENIED));

        // When/Then: Should throw ProfileConsolidationException
        assertThrows(ProfileContextService.ProfileConsolidationException.class,
                    () -> service.consolidateProfile(userId));
    }

    @Test
    void testQueryUserMemories_ConvertsAdminItemsToMemoryItems() {
        // Given: Admin memories with various fields
        String userId = "user-convert";
        AdminMemoryItem adminItem = AdminMemoryItem.newBuilder()
            .setId(com.google.protobuf.ByteString.copyFromUtf8("mem-id"))
            .addNamespace("user")
            .addNamespace(userId)
            .setKey("test-key")
            .setValue(Struct.newBuilder()
                .putFields("content", Value.newBuilder().setStringValue("Test content").build())
                .build())
            .setCreatedAt(Timestamp.newBuilder().setSeconds(1722758400).build())
            .setArchived(false)
            .setScore(0.95)
            .build();

        AdminSearchMemoriesResponse searchResponse = AdminSearchMemoriesResponse.newBuilder()
            .addItems(adminItem)
            .build();
        when(mockMemoriesStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
            .thenReturn(searchResponse);

        ProfileSnapshot snapshot = new ProfileSnapshot(userId, Instant.now(), "Content", Map.of());
        when(mockStrategy.consolidate(anyList(), eq(userId))).thenReturn(snapshot);

        // When: Consolidate profile (triggers query)
        service.consolidateProfile(userId);

        // Then: Should convert and pass to strategy
        ArgumentCaptor<List<MemoryItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockStrategy).consolidate(captor.capture(), eq(userId));

        List<MemoryItem> memories = captor.getValue();
        assertEquals(1, memories.size());
        MemoryItem converted = memories.get(0);
        assertEquals(List.of("user", userId), converted.getNamespaceList());
        assertEquals("test-key", converted.getKey());
        assertFalse(converted.getArchived());
        assertEquals(0.95, converted.getScore());
    }

    @Test
    void testWriteSnapshot_IncludesSectionMetadata() {
        // Given: Snapshot with multiple sections
        String userId = "user-sections";
        AdminSearchMemoriesResponse searchResponse = AdminSearchMemoriesResponse.newBuilder().build();
        when(mockMemoriesStub.searchMemories(any(AdminSearchMemoriesRequest.class)))
            .thenReturn(searchResponse);

        ProfileSnapshot snapshot = new ProfileSnapshot(
            userId,
            Instant.now(),
            "Multi-section profile",
            Map.of(
                "interests", new ProfileSnapshot.ProfileSection("Interests", 0.9, List.of("mem-1", "mem-2")),
                "preferences", new ProfileSnapshot.ProfileSection("Preferences", 0.85, List.of("mem-3"))
            )
        );
        when(mockStrategy.consolidate(anyList(), eq(userId))).thenReturn(snapshot);

        // When: Consolidate profile
        service.consolidateProfile(userId);

        // Then: Should include section metadata in written snapshot
        ArgumentCaptor<AdminPutMemoryRequest> captor = 
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockMemoriesStub).putMemory(captor.capture());

        Struct value = captor.getValue().getValue();
        Struct sections = value.getFieldsMap().get("sections").getStructValue();
        
        assertTrue(sections.getFieldsMap().containsKey("interests"));
        assertTrue(sections.getFieldsMap().containsKey("preferences"));
        
        Struct interestsSection = sections.getFieldsMap().get("interests").getStructValue();
        assertEquals(0.9, interestsSection.getFieldsMap().get("confidence").getNumberValue());
        assertEquals(2, interestsSection.getFieldsMap().get("source_memory_keys")
                    .getListValue().getValuesCount());
    }

    // Helper methods

    private AdminMemoryItem createAdminMemory(String key, String content) {
        return AdminMemoryItem.newBuilder()
            .setId(com.google.protobuf.ByteString.copyFromUtf8(key))
            .addNamespace("user")
            .addNamespace("test-user")
            .addNamespace("cognition.v1")
            .setKey(key)
            .setValue(Struct.newBuilder()
                .putFields("content", Value.newBuilder().setStringValue(content).build())
                .build())
            .setCreatedAt(Timestamp.newBuilder().setSeconds(1722758400).build())
            .setArchived(false)
            .build();
    }
}
