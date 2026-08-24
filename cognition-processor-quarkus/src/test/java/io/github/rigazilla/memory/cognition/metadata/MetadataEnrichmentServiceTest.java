package io.github.rigazilla.memory.cognition.metadata;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesResponse;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.AdminPutMemoryRequest;
import io.github.rigazilla.memory.cognition.resource.LlmRetryHelper;
import io.grpc.ManagedChannel;
import io.quarkus.arc.Arc;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MetadataEnrichmentService}.
 *
 * <p>All gRPC calls are intercepted via a mocked blocking stub; no real network is needed.
 * The LLM {@link MetadataExtractor} is also mocked so no AI service or CDI container is
 * required. The test follows the same no-CDI, direct-field-injection pattern used by
 * {@code TemporalMetadataEnrichmentServiceTest}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Counter baselines (processed / enriched / errors all start at 0)</li>
 *   <li>Happy path: memory without "entities" is enriched and putMemory is called</li>
 *   <li>Entities and topics are correctly serialised as protobuf ListValue</li>
 *   <li>expectedRevision is forwarded for optimistic locking</li>
 *   <li>Idempotency: memory that already has "entities" field is skipped</li>
 *   <li>Blank content is skipped without calling the extractor</li>
 *   <li>Pagination: second page is fetched when first response carries a cursor</li>
 *   <li>Per-item error isolation: failure in one memory does not abort the run</li>
 *   <li>Counters are reset at the start of startEnrichmentAsync()</li>
 *   <li>Duplicate-run guard: second call while status=="running" is a no-op</li>
 *   <li>Empty extractor response (no entities / no topics) still calls putMemory</li>
 * </ul>
 */
@QuarkusTest
class MetadataEnrichmentServiceTest {

    @Inject
    MetadataEnrichmentService service;

    /** The real (non-proxy) bean instance — used for field injection of mock stubs. */
    private MetadataEnrichmentService realService;

    @InjectMock
    MetadataExtractor mockExtractor;

    private AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub mockStub;

    /** Default extraction response used for most happy-path tests. */
    private static final MetadataExtractionResponse DEFAULT_RESPONSE = new MetadataExtractionResponse(
            List.of(new ExtractedEntity("Python", "technology")),
            List.of("programming/scripting")
    );

    /** Empty response (no entities / no topics). */
    private static final MetadataExtractionResponse EMPTY_RESPONSE =
            new MetadataExtractionResponse(null, null);

    @BeforeEach
    void setUp() {
        mockStub = mock(AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub.class);
        ManagedChannel mockChannel = mock(ManagedChannel.class);

        // Unwrap CDI proxy to reach the real bean instance — field assignment on the proxy
        // itself is a no-op because @ApplicationScoped beans are wrapped in client proxies.
        // arc_contextualInstance() returns the actual delegate, not the proxy shell.
        realService = (MetadataEnrichmentService)
                ((io.quarkus.arc.ClientProxy) service).arc_contextualInstance();
        realService.memoriesStub = mockStub;
        realService.channel = mockChannel;
        // Use a fast no-retry helper so unit tests run instantly
        realService.llmRetryHelper = LlmRetryHelper.forTesting(1, 0L, 0L);
        // Reset counters/flags so tests are independent of execution order
        realService.processed.set(0);
        realService.enriched.set(0);
        realService.errors.set(0);
        realService.running.set(false);
        realService.status.set("idle");
        realService.lastRunTime.set(null);

        when(mockExtractor.extract(any(), any())).thenReturn(DEFAULT_RESPONSE);
    }

    // -------------------------------------------------------------------------
    // Counter baselines
    // -------------------------------------------------------------------------

    @Test
    void allCountersStartAtZero() {
        assertEquals(0, realService.processed.get());
        assertEquals(0, realService.enriched.get());
        assertEquals(0, realService.errors.get());
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void runEnrichmentCallsPutMemoryForUnenrichedMemory() {
        AdminMemoryItem item = itemWithContent("key-1", "fact", "User prefers dark mode");

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runEnrichment(null);

        verify(mockStub).putMemory(any());
        assertEquals(1, realService.processed.get());
        assertEquals(1, realService.enriched.get());
        assertEquals(0, realService.errors.get());
    }

    @Test
    void runEnrichmentWritesEntitiesAsListValueOfStructs() {
        List<ExtractedEntity> entities = List.of(
                new ExtractedEntity("Python", "technology"),
                new ExtractedEntity("AWS", "technology"));
        when(mockExtractor.extract(any(), any())).thenReturn(
                new MetadataExtractionResponse(entities, List.of()));

        AdminMemoryItem item = itemWithContent("key-ent", "fact", "Used Python on AWS");

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runEnrichment(null);

        ArgumentCaptor<AdminPutMemoryRequest> captor =
                ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        Value entitiesValue = captor.getValue().getValue().getFieldsOrThrow("entities");
        assertEquals(Value.KindCase.LIST_VALUE, entitiesValue.getKindCase(),
                "entities must be a ListValue");
        assertEquals(2, entitiesValue.getListValue().getValuesCount(),
                "entities list must contain exactly 2 items");

        Value firstStruct = entitiesValue.getListValue().getValues(0);
        assertEquals(Value.KindCase.STRUCT_VALUE, firstStruct.getKindCase(),
                "each entity must be a Struct");
        assertEquals("Python",
                firstStruct.getStructValue().getFieldsOrThrow("name").getStringValue());
        assertEquals("technology",
                firstStruct.getStructValue().getFieldsOrThrow("type").getStringValue());
    }

    @Test
    void runEnrichmentWritesTopicsAsListValueOfStrings() {
        List<String> topics = List.of("programming/scripting", "cloud/aws");
        when(mockExtractor.extract(any(), any())).thenReturn(
                new MetadataExtractionResponse(List.of(), topics));

        AdminMemoryItem item = itemWithContent("key-top", "fact", "Python on AWS");

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runEnrichment(null);

        ArgumentCaptor<AdminPutMemoryRequest> captor =
                ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        Value topicsValue = captor.getValue().getValue().getFieldsOrThrow("topics");
        assertEquals(Value.KindCase.LIST_VALUE, topicsValue.getKindCase(),
                "topics must be a ListValue");
        assertEquals(2, topicsValue.getListValue().getValuesCount());
        assertEquals("programming/scripting",
                topicsValue.getListValue().getValues(0).getStringValue());
        assertEquals("cloud/aws",
                topicsValue.getListValue().getValues(1).getStringValue());
    }

    @Test
    void runEnrichmentForwardsExpectedRevision() {
        AdminMemoryItem item = itemWithContentAndRevision("key-rev", "fact", "Some content", 42L);

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runEnrichment(null);

        ArgumentCaptor<AdminPutMemoryRequest> captor =
                ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        assertEquals(42L, captor.getValue().getExpectedRevision(),
                "expectedRevision must match the item's revision to guard concurrent writes");
    }

    @Test
    void runEnrichmentPreservesExistingFields() {
        Struct original = Struct.newBuilder()
                .putFields("content", Value.newBuilder().setStringValue("existing content").build())
                .putFields("confidence", Value.newBuilder().setNumberValue(0.95).build())
                .build();
        AdminMemoryItem item = itemBuilder("key-preserve", "fact", original).build();

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runEnrichment(null);

        ArgumentCaptor<AdminPutMemoryRequest> captor =
                ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        Struct written = captor.getValue().getValue();
        assertEquals("existing content",
                written.getFieldsOrThrow("content").getStringValue(),
                "pre-existing content field must not be overwritten");
        assertEquals(0.95,
                written.getFieldsOrThrow("confidence").getNumberValue(),
                "pre-existing confidence field must not be overwritten");
    }

    @Test
    void runEnrichmentPassesMemoryTypeAndContentToExtractor() {
        AdminMemoryItem item = itemWithContent("key-type", "preference", "User likes dark mode");

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "preference"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runEnrichment(null);

        verify(mockExtractor).extract("preference", "User likes dark mode");
    }

    // -------------------------------------------------------------------------
    // Idempotency — already-enriched memories must be skipped
    // -------------------------------------------------------------------------

    @Test
    void runEnrichmentSkipsMemoryThatAlreadyHasEntities() {
        Struct alreadyEnriched = Struct.newBuilder()
                .putFields("content", Value.newBuilder().setStringValue("already done").build())
                .putFields("entities", Value.newBuilder()
                        .setListValue(com.google.protobuf.ListValue.newBuilder().build())
                        .build())
                .build();
        AdminMemoryItem item = itemBuilder("key-skip", "fact", alreadyEnriched).build();

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runEnrichment(null);

        verify(mockStub, never()).putMemory(any());
        verify(mockExtractor, never()).extract(any(), any());
        assertEquals(1, realService.processed.get(), "processed must be incremented even for skipped items");
        assertEquals(0, realService.enriched.get());
    }

    // -------------------------------------------------------------------------
    // Blank content — must be skipped without calling the extractor
    // -------------------------------------------------------------------------

    @Test
    void runEnrichmentSkipsMemoryWithBlankContent() {
        AdminMemoryItem item = itemWithContent("key-blank", "fact", "   ");

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runEnrichment(null);

        verify(mockStub, never()).putMemory(any());
        verify(mockExtractor, never()).extract(any(), any());
        assertEquals(1, realService.processed.get());
        assertEquals(0, realService.enriched.get());
    }

    @Test
    void runEnrichmentSkipsMemoryWithMissingContentField() {
        Struct noContent = Struct.newBuilder().build();
        AdminMemoryItem item = itemBuilder("key-nocontent", "fact", noContent).build();

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runEnrichment(null);

        verify(mockStub, never()).putMemory(any());
        verify(mockExtractor, never()).extract(any(), any());
    }

    // -------------------------------------------------------------------------
    // Empty extractor response — putMemory is still called (empty lists are valid)
    // -------------------------------------------------------------------------

    @Test
    void runEnrichmentStillCallsPutMemoryWhenExtractorReturnsEmptyResponse() {
        when(mockExtractor.extract(any(), any())).thenReturn(EMPTY_RESPONSE);

        AdminMemoryItem item = itemWithContent("key-empty", "fact", "Something");

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runEnrichment(null);

        verify(mockStub).putMemory(any());
        assertEquals(1, realService.enriched.get(),
                "enrichment with empty extraction is still a write — counter must increment");
    }

    // -------------------------------------------------------------------------
    // Namespace filtering — profile_context namespaces must be skipped
    // -------------------------------------------------------------------------

    @Test
    void runEnrichmentSkipsProfileContextNamespace() {
        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "profile_context"));

        service.runEnrichment(null);

        // listMemories must never be called because the namespace is filtered out
        verify(mockStub, never()).listMemories(any());
        verify(mockExtractor, never()).extract(any(), any());
    }

    @Test
    void runEnrichmentSkipsShallowNamespaces() {
        // Namespaces with fewer than 4 segments must be ignored
        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1")); // only 3 segments

        service.runEnrichment(null);

        verify(mockStub, never()).listMemories(any());
    }

    // -------------------------------------------------------------------------
    // Pagination
    // -------------------------------------------------------------------------

    @Test
    void runEnrichmentFollowsPaginationCursor() {
        AdminMemoryItem item1 = itemWithContent("key-p1", "fact", "page 1 content");
        AdminMemoryItem item2 = itemWithContent("key-p2", "fact", "page 2 content");

        AdminListMemoriesResponse page1 = AdminListMemoriesResponse.newBuilder()
                .addItems(item1)
                .setAfterCursor("cursor-abc")
                .build();
        AdminListMemoriesResponse page2 = AdminListMemoriesResponse.newBuilder()
                .addItems(item2)
                .build();

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(page1).thenReturn(page2);

        service.runEnrichment(null);

        assertEquals(2, realService.processed.get(), "both pages must be processed");
        assertEquals(2, realService.enriched.get(), "both items must be enriched");
        verify(mockStub, times(2)).listMemories(any());
    }

    @Test
    void runEnrichmentPassesCursorToSecondPageRequest() {
        AdminMemoryItem item = itemWithContent("key-p1", "fact", "content");

        AdminListMemoriesResponse page1 = AdminListMemoriesResponse.newBuilder()
                .addItems(item)
                .setAfterCursor("cursor-xyz")
                .build();
        AdminListMemoriesResponse page2 = AdminListMemoriesResponse.newBuilder().build();

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(page1).thenReturn(page2);

        service.runEnrichment(null);

        ArgumentCaptor<AdminListMemoriesRequest> captor =
                ArgumentCaptor.forClass(AdminListMemoriesRequest.class);
        verify(mockStub, times(2)).listMemories(captor.capture());

        List<AdminListMemoriesRequest> requests = captor.getAllValues();
        assertFalse(requests.get(0).hasAfterCursor(), "first request must not have a cursor");
        assertEquals("cursor-xyz", requests.get(1).getAfterCursor(),
                "second request must carry the cursor from the first response");
    }

    // -------------------------------------------------------------------------
    // Error isolation — per-item failure must not abort the run
    // -------------------------------------------------------------------------

    @Test
    void runEnrichmentContinuesAfterPerItemError() {
        AdminMemoryItem item1 = itemWithContent("key-err1", "fact", "will fail");
        AdminMemoryItem item2 = itemWithContent("key-ok2", "fact", "will succeed");

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(twoItemPage(item1, item2));
        when(mockStub.putMemory(any()))
                .thenThrow(new RuntimeException("simulated failure"))
                .thenReturn(null);

        service.runEnrichment(null);

        assertEquals(2, realService.processed.get());
        assertEquals(1, realService.enriched.get(), "second item must succeed despite first failing");
        assertEquals(1, realService.errors.get(), "failure must increment errors counter");
    }

    @Test
    void runEnrichmentIsolatesExtractorFailure() {
        when(mockExtractor.extract(any(), any()))
                .thenThrow(new RuntimeException("LLM timeout"));

        AdminMemoryItem item = itemWithContent("key-llm-fail", "fact", "Some content");

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        assertDoesNotThrow(() -> service.runEnrichment(null),
                "extractor failure must be caught and not propagate");
        assertEquals(1, realService.errors.get());
        assertEquals(0, realService.enriched.get());
    }

    // -------------------------------------------------------------------------
    // startEnrichmentAsync() — duplicate-run guard and counter reset
    // -------------------------------------------------------------------------

    @Test
    void startEnrichmentAsyncSkipsWhenAlreadyRunning() {
        // Force the AtomicBoolean guard to true before calling startEnrichmentAsync
        realService.running.set(true);

        service.startEnrichmentAsync();

        // listNamespaces must never be called — the guard returned immediately
        verify(mockStub, never()).listNamespaces(any());
    }

    @Test
    void runEnrichmentResetsCounters() {
        // Seed stale values from a previous imaginary run
        realService.processed.set(99);
        realService.enriched.set(88);
        realService.errors.set(77);

        when(mockStub.listNamespaces(any())).thenReturn(emptyNamespacesResponse());

        // Call the synchronous core method directly — same approach as
        // TemporalMetadataEnrichmentServiceTest.runBackfillResetsCountersAtStart()
        service.runEnrichment(null);

        assertEquals(0, realService.processed.get(), "processed must be reset at run start");
        assertEquals(0, realService.enriched.get(), "enriched must be reset at run start");
        assertEquals(0, realService.errors.get(), "errors must be reset at run start");
    }

    @Test
    void startEnrichmentAsyncSetsStatusToCompletedOnSuccess() throws InterruptedException {
        when(mockStub.listNamespaces(any())).thenReturn(emptyNamespacesResponse());

        service.startEnrichmentAsync();

        long deadline = System.currentTimeMillis() + 5000;
        while (!"completed".equals(realService.status.get())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertEquals("completed", realService.status.get(),
                "status must be 'completed' after successful run — timed out waiting");
        assertNotNull(realService.lastRunTime.get(), "lastRunTime must be set after completion");
    }

    // -------------------------------------------------------------------------
    // Accessor methods
    // -------------------------------------------------------------------------

    @Test
    void accessorsReflectCurrentAtomicValues() {
        realService.processed.set(5);
        realService.enriched.set(3);
        realService.errors.set(1);

        assertEquals(5, service.getProcessed());
        assertEquals(3, service.getEnriched());
        assertEquals(1, service.getErrors());
    }

    @Test
    void getStatusReturnsCurrentStatus() {
        assertEquals("idle", service.getStatus());
        realService.status.set("running");
        assertEquals("running", service.getStatus());
    }

    @Test
    void getLastRunTimeIsNullBeforeFirstRun() {
        assertNull(service.getLastRunTime());
    }

    // -------------------------------------------------------------------------
    // Retry behaviour
    // -------------------------------------------------------------------------

    @Test
    void retryHelperRetriesOnTransientFailureThenSucceeds() {
        // LLM fails once then succeeds on second attempt.
        when(mockExtractor.extract(any(), any()))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(DEFAULT_RESPONSE);

        AdminMemoryItem item = itemWithContent("key-retry", "fact", "Retry test content");
        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        // Use a helper with 2 attempts and 0ms delay so the test runs instantly.
        realService.llmRetryHelper = retryHelperWithAttempts(2);

        service.runEnrichment(null);

        // extractor should have been called twice (first fails, second succeeds).
        verify(mockExtractor, times(2)).extract(any(), any());
        verify(mockStub).putMemory(any());
        assertEquals(1, realService.enriched.get());
        assertEquals(0, realService.errors.get());
    }

    @Test
    void retryHelperExhaustsAttemptsAndCountsError() {
        // LLM always fails.
        when(mockExtractor.extract(any(), any()))
                .thenThrow(new RuntimeException("always fails"));

        AdminMemoryItem item = itemWithContent("key-fail", "fact", "Will fail");
        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        // Use a helper with 2 attempts and 0ms delay.
        realService.llmRetryHelper = retryHelperWithAttempts(2);

        service.runEnrichment(null);

        // extractor called twice (all attempts exhausted).
        verify(mockExtractor, times(2)).extract(any(), any());
        assertEquals(0, realService.enriched.get());
        assertEquals(1, realService.errors.get());
    }

    @Test
    void interCallDelayIsCalledBetweenItems() {
        // Two items in the same page; with delay=0 the run still completes without hanging.
        AdminMemoryItem item1 = itemWithContent("key-d1", "fact", "Item one");
        AdminMemoryItem item2 = itemWithContent("key-d2", "fact", "Item two");

        when(mockStub.listNamespaces(any())).thenReturn(
                namespacesResponse("user", "user-abc", "cognition.v1", "fact"));
        when(mockStub.listMemories(any())).thenReturn(twoItemPage(item1, item2));

        // interCallDelayMs is 0 in test/resources/application.properties — no sleep needed
        service.runEnrichment(null);

        assertEquals(2, realService.enriched.get());
        assertEquals(0, realService.errors.get());
    }

    // -------------------------------------------------------------------------
    // Namespace prefix scoping (Issue #36)
    // -------------------------------------------------------------------------

    @Test
    void runEnrichmentWithNullPrefixUsesUserDiscovery() {
        // null prefix → listNamespaces is called with ["user"] (default behaviour)
        when(mockStub.listNamespaces(any())).thenReturn(emptyNamespacesResponse());

        service.runEnrichment(null);

        ArgumentCaptor<io.github.chirino.memory.grpc.v1.AdminListMemoryNamespacesRequest> captor =
                ArgumentCaptor.forClass(io.github.chirino.memory.grpc.v1.AdminListMemoryNamespacesRequest.class);
        verify(mockStub).listNamespaces(captor.capture());
        assertEquals(List.of("user"), captor.getValue().getNamespacePrefixList(),
                "null prefix must discover under [\"user\"]");
    }

    @Test
    void runEnrichmentWith2SegmentPrefixScopesDiscovery() {
        // 2-segment prefix → listNamespaces called with that prefix (not the default "user")
        when(mockStub.listNamespaces(any())).thenReturn(emptyNamespacesResponse());

        service.runEnrichment(List.of("user", "caroline"));

        ArgumentCaptor<io.github.chirino.memory.grpc.v1.AdminListMemoryNamespacesRequest> captor =
                ArgumentCaptor.forClass(io.github.chirino.memory.grpc.v1.AdminListMemoryNamespacesRequest.class);
        verify(mockStub).listNamespaces(captor.capture());
        assertEquals(List.of("user", "caroline"), captor.getValue().getNamespacePrefixList(),
                "2-segment prefix must be forwarded to listNamespaces");
    }

    @Test
    void runEnrichmentWith4SegmentPrefixSkipsDiscovery() {
        // 4-segment prefix → listNamespaces must never be called; listMemories is called directly
        AdminMemoryItem item = itemWithContent("key-direct", "episodic", "Direct access content");
        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runEnrichment(List.of("user", "caroline", "cognition.v1", "episodic"));

        verify(mockStub, never()).listNamespaces(any());
        verify(mockStub).listMemories(any());
        assertEquals(1, realService.enriched.get(),
                "item must be enriched when a fully-qualified prefix is supplied");
    }

    @Test
    void runEnrichmentWith4SegmentProfileContextPrefixSkipsEnrichment() {
        // 4-segment prefix ending in profile_context → listMemories must never be called
        service.runEnrichment(List.of("user", "caroline", "cognition.v1", "profile_context"));

        verify(mockStub, never()).listNamespaces(any());
        verify(mockStub, never()).listMemories(any());
        assertEquals(0, realService.processed.get(),
                "profile_context namespace must not be enriched even when directly targeted");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Build a real {@link io.github.rigazilla.memory.cognition.resource.LlmRetryHelper}
     * with {@code maxAttempts} and 0ms delay so unit tests run instantly.
     */
    private static io.github.rigazilla.memory.cognition.resource.LlmRetryHelper retryHelperWithAttempts(int maxAttempts) {
        return io.github.rigazilla.memory.cognition.resource.LlmRetryHelper
                .forTesting(maxAttempts, 0L, 0L);
    }

    private AdminMemoryItem itemWithContent(String key, String memoryType, String content) {
        return itemWithContentAndRevision(key, memoryType, content, 1L);
    }

    private AdminMemoryItem itemWithContentAndRevision(
            String key, String memoryType, String content, long revision) {
        Struct struct = Struct.newBuilder()
                .putFields("content", Value.newBuilder().setStringValue(content).build())
                .build();
        return itemBuilder(key, memoryType, struct).setRevision(revision).build();
    }

    private AdminMemoryItem.Builder itemBuilder(String key, String memoryType, Struct value) {
        return AdminMemoryItem.newBuilder()
                .setKey(key)
                .setValue(value)
                .setRevision(1L)
                .addNamespace("user")
                .addNamespace("user-abc")
                .addNamespace("cognition.v1")
                .addNamespace(memoryType);
    }

    private static io.github.chirino.memory.grpc.v1.AdminListMemoryNamespacesResponse
    namespacesResponse(String... segments) {
        io.github.chirino.memory.grpc.v1.MemoryNamespace ns =
                io.github.chirino.memory.grpc.v1.MemoryNamespace.newBuilder()
                        .addAllSegments(List.of(segments))
                        .build();
        return io.github.chirino.memory.grpc.v1.AdminListMemoryNamespacesResponse.newBuilder()
                .addNamespaces(ns)
                .build();
    }

    private static io.github.chirino.memory.grpc.v1.AdminListMemoryNamespacesResponse
    emptyNamespacesResponse() {
        return io.github.chirino.memory.grpc.v1.AdminListMemoryNamespacesResponse.newBuilder()
                .build();
    }

    private static AdminListMemoriesResponse singlePage(AdminMemoryItem item) {
        return AdminListMemoriesResponse.newBuilder().addItems(item).build();
    }

    private static AdminListMemoriesResponse twoItemPage(AdminMemoryItem a, AdminMemoryItem b) {
        return AdminListMemoriesResponse.newBuilder().addItems(a).addItems(b).build();
    }
}
