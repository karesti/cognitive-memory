package io.github.rigazilla.memory.cognition.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Typed configuration for the memory-service connection.
 * <p>
 * Replaces scattered {@code @ConfigProperty(name = "memory-service.*")} fields
 * with a single injectable interface.  Prefer injecting {@code MemoryServiceConfig}
 * over individual {@code @ConfigProperty} fields in all new code.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Inject
 * MemoryServiceConfig memoryService;
 *
 * channel = GrpcChannelFactory.create(
 *     memoryService.grpc().host(),
 *     memoryService.grpc().port(),
 *     memoryService.apiKey(),
 *     memoryService.clientId());
 * }</pre>
 *
 * <p>Corresponding {@code application.properties} keys:
 * <ul>
 *   <li>{@code memory-service.grpc.host}</li>
 *   <li>{@code memory-service.grpc.port}</li>
 *   <li>{@code memory-service.api-key}</li>
 *   <li>{@code memory-service.client-id}</li>
 * </ul>
 */
@ConfigMapping(prefix = "memory-service")
public interface MemoryServiceConfig {

    /** gRPC transport settings. */
    Grpc grpc();

    /** API key used to authenticate the cognition processor with memory-service. */
    @WithName("api-key")
    String apiKey();

    /**
     * Client ID sent as the {@code x-client-id} gRPC header.
     * Must be listed in {@code MEMORY_SERVICE_ROLES_ADMIN_CLIENTS} for the admin role.
     */
    @WithName("client-id")
    @WithDefault("cognition_processor")
    String clientId();

    /** gRPC host/port sub-group. */
    interface Grpc {
        /** Hostname or IP of the memory-service gRPC endpoint. */
        @WithDefault("localhost")
        String host();

        /** Port of the memory-service gRPC endpoint. */
        @WithDefault("8082")
        int port();
    }
}
