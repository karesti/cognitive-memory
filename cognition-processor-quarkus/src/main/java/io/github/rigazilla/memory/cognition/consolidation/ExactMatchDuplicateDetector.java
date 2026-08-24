package io.github.rigazilla.memory.cognition.consolidation;

import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.chirino.memory.grpc.v1.AdminSearchMemoriesRequest;
import io.github.chirino.memory.grpc.v1.AdminSearchMemoriesResponse;
import io.github.chirino.memory.grpc.v1.MemoryItem;
import io.github.rigazilla.memory.cognition.config.MemoryServiceConfig;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import io.grpc.ManagedChannel;
import io.github.rigazilla.memory.cognition.grpc.GrpcChannelFactory;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Exact content-string duplicate detector.
 * <p>
 * Uses {@code AdminSearchMemories} scoped to the candidate's namespace and type,
 * then post-filters results to only those whose stored {@code content} field
 * exactly matches the incoming candidate content.
 * <p>
 * Pattern copied from
 * {@link io.github.rigazilla.memory.cognition.profile.ProfileContextService#queryUserMemories}.
 */
@ApplicationScoped
public class ExactMatchDuplicateDetector implements DuplicateDetector {

    private static final Logger LOG = Logger.getLogger(ExactMatchDuplicateDetector.class);
    private static final String COGNITION_VERSION = "cognition.v1";
    private static final int SEARCH_LIMIT = 10;

    @Inject
    MemoryServiceConfig memoryService;

    ManagedChannel channel;
    AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub memoriesStub;

    @PostConstruct
    void init() {
        LOG.infof("Initializing ExactMatchDuplicateDetector: %s:%d",
                memoryService.grpc().host(), memoryService.grpc().port());
        channel = GrpcChannelFactory.create(
                memoryService.grpc().host(), memoryService.grpc().port(),
                memoryService.apiKey());
        memoriesStub = AdminMemoriesServiceGrpc.newBlockingStub(channel);
        LOG.info("ExactMatchDuplicateDetector initialized successfully");
    }

    @PreDestroy
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            LOG.info("Shutting down ExactMatchDuplicateDetector gRPC channel");
            channel.shutdown();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Searches the namespace {@code ["user", userId, "cognition.v1", type]} using
     * the candidate content as the query, then post-filters to exact matches only.
     */
    @Override
    public List<MemoryItem> findDuplicates(String userId, MemoryCandidate candidate) {
        try {
            // Search namespace: ["user", userId, "cognition.v1", type]
            AdminSearchMemoriesRequest request = AdminSearchMemoriesRequest.newBuilder()
                    .addNamespacePrefix("user")
                    .addNamespacePrefix(userId)
                    .addNamespacePrefix(COGNITION_VERSION)
                    .addNamespacePrefix(candidate.type())
                    .setQuery(candidate.content())
                    .setAsUserId(userId)
                    .setLimit(SEARCH_LIMIT)
                    .build();

            AdminSearchMemoriesResponse response = memoriesStub.searchMemories(request);

            // Post-filter to exact content matches only (search API may return fuzzy results)
            List<MemoryItem> duplicates = response.getItemsList().stream()
                    .map(adminItem -> {
                        // Convert AdminMemoryItem -> MemoryItem (same pattern as ProfileContextService)
                        String createdAt = Instant.ofEpochSecond(
                                adminItem.getCreatedAt().getSeconds(),
                                adminItem.getCreatedAt().getNanos()
                        ).toString();

                        MemoryItem.Builder builder = MemoryItem.newBuilder()
                                .setId(adminItem.getId())
                                .addAllNamespace(adminItem.getNamespaceList())
                                .setKey(adminItem.getKey())
                                .setValue(adminItem.getValue())
                                .setCreatedAt(createdAt)
                                .setArchived(adminItem.getArchived())
                                .setRevision(adminItem.getRevision());

                        if (adminItem.hasScore()) {
                            builder.setScore(adminItem.getScore());
                        }
                        return builder.build();
                    })
                    .filter(item -> {
                        // Exact content match: compare stored content field against candidate
                        String storedContent = item.getValue()
                                .getFieldsOrDefault("content",
                                        com.google.protobuf.Value.newBuilder().setStringValue("").build())
                                .getStringValue();
                        return candidate.content().equals(storedContent);
                    })
                    .collect(Collectors.toList());

            if (!duplicates.isEmpty()) {
                LOG.infof("Found %d duplicate(s) for candidate [%s]: '%s'",
                        duplicates.size(), candidate.type(),
                        candidate.content().length() > 50
                                ? candidate.content().substring(0, 47) + "..."
                                : candidate.content());
            }

            return duplicates;

        } catch (StatusRuntimeException e) {
            // Dedup is best-effort — a search failure must not block memory writes.
            // Transient failures (UNAVAILABLE, DEADLINE_EXCEEDED) are expected during restarts; WARN.
            // Permanent failures (PERMISSION_DENIED, UNAUTHENTICATED, UNIMPLEMENTED) indicate
            // a configuration problem that will recur on every call; escalate to ERROR.
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.PERMISSION_DENIED
                    || code == Status.Code.UNAUTHENTICATED
                    || code == Status.Code.UNIMPLEMENTED) {
                LOG.errorf(e, "Duplicate search permanently failed (code=%s) for userId=%s type=%s; "
                        + "dedup will be skipped until the configuration issue is resolved",
                        code, userId, candidate.type());
            } else {
                LOG.warnf(e, "Duplicate search failed (code=%s) for userId=%s type=%s; "
                        + "treating as no duplicates",
                        code, userId, candidate.type());
            }
            return List.of();
        } catch (Exception e) {
            LOG.warnf(e, "Duplicate search failed (unexpected error) for userId=%s type=%s; "
                    + "treating as no duplicates",
                    userId, candidate.type());
            return List.of();
        }
    }

}
