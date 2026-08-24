package io.github.rigazilla.memory.cognition.temporal;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminListMemoriesResponse;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminMemoryItem;
import io.github.chirino.memory.grpc.v1.AdminPutMemoryRequest;
import io.github.chirino.memory.grpc.v1.MemoryWriteResult;
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
 * Unit tests for {@link TemporalMetadataEnrichmentService}.
 *
 * <p>All gRPC calls are intercepted via a mocked blocking stub; no real network is needed.
 * The test follows the same no-CDI, direct-field-injection pattern used by MemoryWriterTest
 * and JobProcessorTest.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Counter baselines (scanned / enriched / skipped / errors / conflicts all start at 0)</li>
 *   <li>Happy path: memory without observed_at is enriched with timestamp from created_at</li>
 *   <li>Skip: memory that already has a non-blank observed_at is not re-written</li>
 *   <li>Skip: memory whose observed_at field exists but is blank triggers re-enrichment</li>
 *   <li>Skip (no-op): memory with no created_at — warning logged, skipped counter incremented</li>
 *   <li>Pagination: second page is fetched when first response carries an afterCursor</li>
 *   <li>Duplicate-run guard: second runBackfill() call while running returns immediately</li>
 *   <li>Counter reset: counters are zeroed at the start of each run</li>
 *   <li>Conflict handling: ABORTED status increments conflicts, not errors</li>
 *   <li>Error handling: non-ABORTED gRPC error increments errors counter</li>
 *   <li>observed_at value: derived correctly from created_at Timestamp (epoch math)</li>
 *   <li>effective_at mirrors observed_at in the written struct</li>
 *   <li>expires_at is written as NULL_VALUE placeholder</li>
 *   <li>Existing fields are preserved when new temporal fields are added</li>
 *   <li>expectedRevision is forwarded to putMemory to guard against concurrent writes</li>
 * </ul>
 */
@QuarkusTest
class TemporalMetadataEnrichmentServiceTest {

    @Inject
    TemporalMetadataEnrichmentService service;

    /** The real (non-proxy) bean instance — used for field injection of mock stubs. */
    private TemporalMetadataEnrichmentService realService;

    private AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub mockStub;

    // Reusable empty write result
    private static final MemoryWriteResult WRITE_OK = MemoryWriteResult.newBuilder()
        .setId(uuidBytes(UUID.randomUUID().toString()))
        .build();

    @BeforeEach
    void setUp() {
        mockStub = mock(AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub.class);
        ManagedChannel mockChannel = mock(ManagedChannel.class);

        // Unwrap CDI proxy to reach the real bean instance — field assignment on the proxy
        // itself is a no-op because @ApplicationScoped beans are wrapped in client proxies.
        // arc_contextualInstance() returns the actual delegate, not the proxy shell.
        realService = (TemporalMetadataEnrichmentService)
                ((io.quarkus.arc.ClientProxy) service).arc_contextualInstance();
        realService.memoriesStub = mockStub;
        realService.channel = mockChannel;
        // Reset counters and flags so tests are independent of execution order
        realService.running.set(false);
        realService.scanned.set(0);
        realService.enriched.set(0);
        realService.skipped.set(0);
        realService.errors.set(0);
        realService.conflicts.set(0);
    }

    // -------------------------------------------------------------------------
    // Counter baselines
    // -------------------------------------------------------------------------

    @Test
    void allCountersStartAtZero() {
        assertEquals(0L, realService.scanned.get());
        assertEquals(0L, realService.enriched.get());
        assertEquals(0L, realService.skipped.get());
        assertEquals(0L, realService.errors.get());
        assertEquals(0L, realService.conflicts.get());
        assertFalse(realService.running.get());
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void runBackfillEnrichesMemoryMissingObservedAt() {
        // Memory has a valid created_at but no observed_at in its value struct
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-1", createdAt, structWithContent("hello")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        assertEquals(1L, realService.scanned.get());
        assertEquals(1L, realService.enriched.get());
        assertEquals(0L, realService.skipped.get());
        assertEquals(0L, realService.errors.get());
    }

    @Test
    void runBackfillWritesCorrectObservedAt() {
        // 1749562200 seconds → 2025-06-10T13:30:00Z
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-ts", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        AdminPutMemoryRequest req = captor.getValue();
        Struct written = req.getValue();
        assertEquals("2025-06-10T13:30:00Z",
            written.getFieldsOrThrow("observed_at").getStringValue());

        // Temporal fields must also be in the index map for queryability (AC #8)
        assertEquals("2025-06-10T13:30:00Z", req.getIndexMap().get("observed_at"),
            "observed_at must be indexed for search queries");
        assertEquals("2025-06-10T13:30:00Z", req.getIndexMap().get("effective_at"),
            "effective_at must be indexed for search queries");
    }

    @Test
    void runBackfillWritesEffectiveAtMirroringObservedAt() {
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-eff", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        Struct written = captor.getValue().getValue();
        String observedAt = written.getFieldsOrThrow("observed_at").getStringValue();
        String effectiveAt = written.getFieldsOrThrow("effective_at").getStringValue();
        assertEquals(observedAt, effectiveAt, "effective_at must mirror observed_at");
    }

    @Test
    void runBackfillWritesExpiresAtAsNullValue() {
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-exp", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        Struct written = captor.getValue().getValue();
        assertEquals(com.google.protobuf.Value.KindCase.NULL_VALUE,
            written.getFieldsOrThrow("expires_at").getKindCase(),
            "expires_at must be NULL_VALUE placeholder");
    }

    @Test
    void runBackfillPreservesExistingContentField() {
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        Struct original = Struct.newBuilder()
            .putFields("content", Value.newBuilder().setStringValue("original content").build())
            .putFields("confidence", Value.newBuilder().setNumberValue(0.95).build())
            .build();
        AdminMemoryItem item = itemBuilder("key-preserve", createdAt, original).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        Struct written = captor.getValue().getValue();
        assertEquals("original content",
            written.getFieldsOrThrow("content").getStringValue(),
            "pre-existing content field must not be overwritten");
        assertEquals(0.95,
            written.getFieldsOrThrow("confidence").getNumberValue(),
            "pre-existing confidence field must not be overwritten");
    }

    @Test
    void runBackfillForwardsExpectedRevision() {
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-rev", createdAt, structWithContent("fact"))
            .setRevision(42L)
            .build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        assertEquals(42L, captor.getValue().getExpectedRevision(),
            "expectedRevision must match the item's current revision to guard concurrent writes");
    }

    // -------------------------------------------------------------------------
    // Skip conditions
    // -------------------------------------------------------------------------

    @Test
    void runBackfillSkipsMemoryWithNonBlankObservedAt() {
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        Struct alreadyEnriched = Struct.newBuilder()
            .putFields("content", Value.newBuilder().setStringValue("already done").build())
            .putFields("observed_at",
                Value.newBuilder().setStringValue("2025-06-10T13:30:00Z").build())
            .build();
        AdminMemoryItem item = itemBuilder("key-skip", createdAt, alreadyEnriched).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runBackfill();

        assertEquals(1L, realService.scanned.get());
        assertEquals(0L, realService.enriched.get());
        assertEquals(1L, realService.skipped.get());
        verify(mockStub, never()).putMemory(any());
    }

    @Test
    void runBackfillEnrichesMemoryWithBlankObservedAt() {
        // observed_at field present but blank — must NOT be skipped
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        Struct blankObservedAt = Struct.newBuilder()
            .putFields("content", Value.newBuilder().setStringValue("fact").build())
            .putFields("observed_at", Value.newBuilder().setStringValue("").build())
            .build();
        AdminMemoryItem item = itemBuilder("key-blank", createdAt, blankObservedAt).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        assertEquals(1L, realService.enriched.get(),
            "memory with blank observed_at must be re-enriched, not skipped");
    }

    @Test
    void runBackfillSkipsMemoryWithNoCreatedAt() {
        // No created_at available — service must warn and skip, not throw
        AdminMemoryItem item = AdminMemoryItem.newBuilder()
            .setId(uuidBytes(UUID.randomUUID().toString()))
            .setKey("key-no-ts")
            .setValue(structWithContent("fact"))
            .setRevision(1L)
            .build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runBackfill();

        assertEquals(1L, realService.scanned.get());
        assertEquals(0L, realService.enriched.get());
        assertEquals(1L, realService.skipped.get());
        verify(mockStub, never()).putMemory(any());
    }

    // -------------------------------------------------------------------------
    // Pagination
    // -------------------------------------------------------------------------

    @Test
    void runBackfillFollowsPaginationCursor() {
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem page1Item = itemBuilder("key-p1", createdAt, structWithContent("p1")).build();
        AdminMemoryItem page2Item = itemBuilder("key-p2", createdAt, structWithContent("p2")).build();

        AdminListMemoriesResponse page1 = AdminListMemoriesResponse.newBuilder()
            .addItems(page1Item)
            .setAfterCursor("cursor-abc")
            .build();
        AdminListMemoriesResponse page2 = AdminListMemoriesResponse.newBuilder()
            .addItems(page2Item)
            // no afterCursor → last page
            .build();

        when(mockStub.listMemories(any()))
            .thenReturn(page1)
            .thenReturn(page2);
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        assertEquals(2L, realService.scanned.get(), "both pages must be scanned");
        assertEquals(2L, realService.enriched.get(), "both items must be enriched");
        verify(mockStub, times(2)).listMemories(any());
    }

    @Test
    void runBackfillPassesCursorToSecondPageRequest() {
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-p1", createdAt, structWithContent("p1")).build();

        AdminListMemoriesResponse page1 = AdminListMemoriesResponse.newBuilder()
            .addItems(item)
            .setAfterCursor("cursor-xyz")
            .build();
        AdminListMemoriesResponse page2 = AdminListMemoriesResponse.newBuilder().build();

        when(mockStub.listMemories(any()))
            .thenReturn(page1)
            .thenReturn(page2);
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminListMemoriesRequest> captor =
            ArgumentCaptor.forClass(AdminListMemoriesRequest.class);
        verify(mockStub, times(2)).listMemories(captor.capture());

        List<AdminListMemoriesRequest> requests = captor.getAllValues();
        assertFalse(requests.get(0).hasAfterCursor(), "first request must not have a cursor");
        assertEquals("cursor-xyz", requests.get(1).getAfterCursor(),
            "second request must carry the cursor from the first response");
    }

    // -------------------------------------------------------------------------
    // Duplicate-run guard and counter reset
    // -------------------------------------------------------------------------

    @Test
    void runBackfillRejectsSecondConcurrentInvocation() {
        // Force running=true before the call
        realService.running.set(true);

        service.runBackfill();

        // listMemories must never be called — the guard returned immediately
        verify(mockStub, never()).listMemories(any());
        // running flag must still be true (we set it; the guard did not reset it)
        assertTrue(realService.running.get());
    }

    @Test
    void runBackfillResetsCountersAtStart() {
        // Seed stale values from a previous imaginary run
        realService.scanned.set(99);
        realService.enriched.set(88);
        realService.skipped.set(77);
        realService.errors.set(66);
        realService.conflicts.set(55);

        when(mockStub.listMemories(any())).thenReturn(emptyPage());

        service.runBackfill();

        assertEquals(0L, realService.scanned.get(), "scanned must be reset to 0 at run start");
        assertEquals(0L, realService.enriched.get(), "enriched must be reset to 0 at run start");
        assertEquals(0L, realService.skipped.get(), "skipped must be reset to 0 at run start");
        assertEquals(0L, realService.errors.get(), "errors must be reset to 0 at run start");
        assertEquals(0L, realService.conflicts.get(), "conflicts must be reset to 0 at run start");
    }

    @Test
    void runningFlagIsClearedAfterCompletion() {
        when(mockStub.listMemories(any())).thenReturn(emptyPage());

        service.runBackfill();

        assertFalse(realService.running.get(), "running flag must be false after backfill completes");
    }

    // -------------------------------------------------------------------------
    // Error and conflict handling
    // -------------------------------------------------------------------------

    @Test
    void runBackfillCountsAbortedStatusAsConflictNotError() {
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-conflict", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any()))
            .thenThrow(new StatusRuntimeException(Status.ABORTED));

        service.runBackfill();

        assertEquals(1L, realService.scanned.get());
        assertEquals(0L, realService.enriched.get());
        assertEquals(1L, realService.conflicts.get(), "ABORTED must increment conflicts, not errors");
        assertEquals(0L, realService.errors.get());
    }

    @Test
    void runBackfillCountsNonAbortedGrpcErrorAsError() {
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-err", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any()))
            .thenThrow(new StatusRuntimeException(Status.INTERNAL));

        service.runBackfill();

        assertEquals(1L, realService.scanned.get());
        assertEquals(0L, realService.enriched.get());
        assertEquals(1L, realService.errors.get(), "non-ABORTED error must increment errors");
        assertEquals(0L, realService.conflicts.get());
    }

    @Test
    void runBackfillContinuesAfterPerItemError() {
        // Two items; the first throws, the second must still be processed
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item1 = itemBuilder("key-err1", createdAt, structWithContent("one")).build();
        AdminMemoryItem item2 = itemBuilder("key-ok2", createdAt, structWithContent("two")).build();

        when(mockStub.listMemories(any())).thenReturn(twoItemPage(item1, item2));
        when(mockStub.putMemory(any()))
            .thenThrow(new StatusRuntimeException(Status.INTERNAL))
            .thenReturn(WRITE_OK);

        service.runBackfill();

        assertEquals(2L, realService.scanned.get());
        assertEquals(1L, realService.enriched.get(), "second item must succeed despite first failing");
        assertEquals(1L, realService.errors.get());
    }

    // -------------------------------------------------------------------------
    // toIso8601 — exercised through runBackfill() public surface
    // -------------------------------------------------------------------------

    @Test
    void runBackfillWritesUtcZSuffixForValidTimestamp() {
        // 1749562200 seconds = 2025-06-10T13:30:00Z (no nanos)
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-utc", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        assertEquals("2025-06-10T13:30:00Z",
            captor.getValue().getValue().getFieldsOrThrow("observed_at").getStringValue(),
            "observed_at must be ISO-8601 UTC with Z suffix");
    }

    @Test
    void runBackfillWritesEpochStringForZeroTimestamp() {
        // seconds=0, nanos=0 → 1970-01-01T00:00:00Z
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(0).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-epoch", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        assertEquals("1970-01-01T00:00:00Z",
            captor.getValue().getValue().getFieldsOrThrow("observed_at").getStringValue());
    }

    @Test
    void runBackfillPreservesNanosPrecisionInTimestamp() {
        // 100_000_000 nanos = 0.1 s → 2025-06-10T13:30:00.100Z
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(100_000_000).build();
        AdminMemoryItem item = itemBuilder("key-nanos", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        assertEquals("2025-06-10T13:30:00.100Z",
            captor.getValue().getValue().getFieldsOrThrow("observed_at").getStringValue(),
            "sub-second nanos must be preserved in the ISO-8601 string");
    }

    @Test
    void runBackfillHandlesNegativeEpochTimestamp() {
        // TS-N1: seconds=-1 → 1969-12-31T23:59:59Z (pre-epoch / corrupt data — must not throw)
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(-1).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-neg", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        assertEquals("1969-12-31T23:59:59Z",
            captor.getValue().getValue().getFieldsOrThrow("observed_at").getStringValue(),
            "negative epoch must produce a valid ISO-8601 string, not skip or throw");
    }

    @Test
    void runBackfillHandlesMaxNanosWithoutArithmeticException() {
        // TS-N2: nanos=999_999_999 must not throw ArithmeticException
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(0).setNanos(999_999_999).build();
        AdminMemoryItem item = itemBuilder("key-maxnanos", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        assertEquals("1970-01-01T00:00:00.999999999Z",
            captor.getValue().getValue().getFieldsOrThrow("observed_at").getStringValue());
    }

    // -------------------------------------------------------------------------
    // toIso8601 null-Timestamp contract (TS-N0) — merged from MemoryWriterTemporalTest
    // -------------------------------------------------------------------------

    @Test
    void runBackfillSkipsItemWhenCreatedAtIsNullTimestamp() {
        // TS-N0: a memory item reported by listMemories with hasCreatedAt()==false
        // exercises the toIso8601(null) path; the service must warn and increment skipped,
        // never throw and never call putMemory.
        AdminMemoryItem item = AdminMemoryItem.newBuilder()
            .setId(uuidBytes(UUID.randomUUID().toString()))
            .setKey("key-null-ts")
            .setValue(structWithContent("fact"))
            .setRevision(1L)
            .addNamespace("user")
            .addNamespace("user-123")
            .addNamespace("cognition.v1")
            .addNamespace("fact")
            // deliberately no createdAt → hasCreatedAt()==false → toIso8601(null) → Optional.empty()
            .build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));

        service.runBackfill();

        assertEquals(1L, service.getScanned(),
            "TS-N0: item must be scanned even when created_at is null");
        assertEquals(0L, service.getEnriched(),
            "TS-N0: null created_at must not result in enrichment");
        assertEquals(1L, service.getSkipped(),
            "TS-N0: null created_at must increment skipped, not errors");
        assertEquals(0L, service.getErrors(),
            "TS-N0: null Timestamp path must not increment errors");
        verify(mockStub, never()).putMemory(any());
    }

    // -------------------------------------------------------------------------
    // Backfill skip-predicate negative cases — BF-N1, BF-N2, BF-N3
    // -------------------------------------------------------------------------

    @Test
    void runBackfillEnrichesMemoryWhoseObservedAtIsEmptyString() {
        // BF-N1: field present but value "" — isBlank() true → must re-enrich
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        Struct emptyObservedAt = Struct.newBuilder()
            .putFields("content", Value.newBuilder().setStringValue("some fact").build())
            .putFields("observed_at", Value.newBuilder().setStringValue("").build())
            .build();
        AdminMemoryItem item = itemBuilder("key-bf-n1", createdAt, emptyObservedAt).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        assertEquals(1L, service.getEnriched(),
            "BF-N1: empty-string observed_at must NOT cause the backfill to skip");
        assertEquals(0L, service.getSkipped());
    }

    @Test
    void runBackfillEnrichesMemoryWhoseObservedAtIsWhitespace() {
        // BF-N2: field present but value "   " — isBlank() true → must re-enrich
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        Struct whitespaceObservedAt = Struct.newBuilder()
            .putFields("content", Value.newBuilder().setStringValue("some fact").build())
            .putFields("observed_at", Value.newBuilder().setStringValue("   ").build())
            .build();
        AdminMemoryItem item = itemBuilder("key-bf-n2", createdAt, whitespaceObservedAt).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        assertEquals(1L, service.getEnriched(),
            "BF-N2: whitespace-only observed_at must NOT cause the backfill to skip");
    }

    @Test
    void runBackfillEnrichesMemoryWithEmptyStruct() {
        // BF-N3: completely empty value struct — containsFields("observed_at") is false → enrich
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-bf-n3", createdAt,
            Struct.newBuilder().build()).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        assertEquals(1L, service.getEnriched(),
            "BF-N3: memory with empty struct must be enriched, not skipped");
    }

    // -------------------------------------------------------------------------
    // Struct negative cases — MW-N1, MW-N2
    // -------------------------------------------------------------------------

    @Test
    void runBackfillWrittenObservedAtIsNonBlank() {
        // MW-N1: a memory enriched by runBackfill must have a non-blank observed_at
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-mw-n1", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        String writtenObservedAt = captor.getValue().getValue()
            .getFieldsOrThrow("observed_at").getStringValue();
        assertFalse(writtenObservedAt.isBlank(),
            "MW-N1: enriched observed_at must not be blank");
    }

    @Test
    void runBackfillWritesExpiresAtAsNullValueNotEmptyString() {
        // MW-N2: expires_at is NULL_VALUE; getStringValue() returns "" by proto contract
        Timestamp createdAt = Timestamp.newBuilder().setSeconds(1749562200).setNanos(0).build();
        AdminMemoryItem item = itemBuilder("key-mw-n2", createdAt, structWithContent("fact")).build();

        when(mockStub.listMemories(any())).thenReturn(singlePage(item));
        when(mockStub.putMemory(any())).thenReturn(WRITE_OK);

        service.runBackfill();

        ArgumentCaptor<AdminPutMemoryRequest> captor =
            ArgumentCaptor.forClass(AdminPutMemoryRequest.class);
        verify(mockStub).putMemory(captor.capture());

        com.google.protobuf.Value expiresAt = captor.getValue().getValue()
            .getFieldsOrThrow("expires_at");
        assertEquals(com.google.protobuf.Value.KindCase.NULL_VALUE, expiresAt.getKindCase(),
            "MW-N2: expires_at must be stored as NULL_VALUE, not an empty string");
        assertEquals("", expiresAt.getStringValue(),
            "getStringValue() on NULL_VALUE returns proto default \"\" — callers must check KindCase");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AdminMemoryItem.Builder itemBuilder(String key, Timestamp createdAt, Struct value) {
        return AdminMemoryItem.newBuilder()
            .setId(uuidBytes(UUID.randomUUID().toString()))
            .setKey(key)
            .setCreatedAt(createdAt)
            .setValue(value)
            .setRevision(1L)
            .addNamespace("user")
            .addNamespace("user-123")
            .addNamespace("cognition.v1")
            .addNamespace("fact");
    }

    private static Struct structWithContent(String content) {
        return Struct.newBuilder()
            .putFields("content", Value.newBuilder().setStringValue(content).build())
            .build();
    }

    private static AdminListMemoriesResponse singlePage(AdminMemoryItem item) {
        return AdminListMemoriesResponse.newBuilder()
            .addItems(item)
            .build();
    }

    private static AdminListMemoriesResponse twoItemPage(AdminMemoryItem a, AdminMemoryItem b) {
        return AdminListMemoriesResponse.newBuilder()
            .addItems(a)
            .addItems(b)
            .build();
    }

    private static AdminListMemoriesResponse emptyPage() {
        return AdminListMemoriesResponse.newBuilder().build();
    }

    private static ByteString uuidBytes(String uuidString) {
        UUID uuid = UUID.fromString(uuidString);
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return ByteString.copyFrom(buffer.array());
    }
}
