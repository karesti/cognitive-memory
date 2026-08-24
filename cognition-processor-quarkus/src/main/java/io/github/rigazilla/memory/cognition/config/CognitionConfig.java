package io.github.rigazilla.memory.cognition.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Typed configuration for the cognition processor.
 * <p>
 * Replaces scattered {@code @ConfigProperty(name = "cognition.*")} fields with a
 * single injectable interface. Prefer injecting {@code CognitionConfig} over
 * individual {@code @ConfigProperty} fields in all new code.
 *
 * <p>Dynamic process IDs and named resource IDs are represented as maps so configured
 * resource defaults and overrides are validated and available to consumers.
 */
@ConfigMapping(prefix = "cognition")
public interface CognitionConfig {

    /** Runtime identity settings. */
    Runtime runtime();

    /** Worker identity settings. */
    Worker worker();

    /** Checkpoint settings. */
    Checkpoint checkpoint();

    /** Debounce scheduler settings. */
    Scheduler scheduler();

    /** LLM call settings. */
    Llm llm();

    /** Secrets / credential resolution settings. */
    Secrets secrets();

    /** Global defaults for external resources. */
    Resources resources();

    /** Per-process resource overrides keyed by process ID. */
    Map<String, Process> process();

    /** Runtime identity sub-group. */
    interface Runtime {
        /** Stable identifier for this cognition runtime implementation. */
        @WithDefault("cognition-processor-v1")
        String id();

        /** Semantic version of the runtime implementation. */
        @WithDefault("1.0.0-SNAPSHOT")
        String version();
    }

    /** Worker identity sub-group. */
    interface Worker {
        /** Worker identifier used to scope checkpoints. */
        @WithDefault("cognition_processor")
        String id();
    }

    /** Checkpoint settings sub-group. */
    interface Checkpoint {
        /**
         * When {@code true} the checkpoint is reset to {@code "start"} on application
         * startup, causing all events to be replayed from the beginning.
         */
        @WithName("reset-on-startup")
        @WithDefault("false")
        boolean resetOnStartup();
    }

    /** Debounce scheduler settings sub-group. */
    interface Scheduler {
        /** Quiet-period before a dirty window is promoted to a job. */
        @WithName("debounce-delay")
        @WithDefault("PT1M")
        Duration debounceDelay();

        /** Maximum age before a dirty window is promoted regardless of quiet-period. */
        @WithName("max-batch-age")
        @WithDefault("PT5M")
        Duration maxBatchAge();

        /** Maximum number of entries in one processing batch. */
        @WithName("max-batch-entries")
        @WithDefault("24")
        int maxBatchEntries();

        /** Maximum number of dirty windows retained between checkpoints. */
        @WithName("max-checkpoint-windows")
        @WithDefault("1000")
        int maxCheckpointWindows();

        /** Maximum number of conversations processed concurrently. */
        @WithName("max-concurrent-jobs")
        @WithDefault("8")
        int maxConcurrentJobs();
    }

    /** LLM call settings sub-group. */
    interface Llm {
        /** LLM retry settings. */
        Retry retry();

        /** LLM backfill settings. */
        Backfill backfill();

        /** Retry / back-off settings for transient LLM failures. */
        interface Retry {
            @WithName("max-attempts")
            @WithDefault("3")
            int maxAttempts();

            @WithName("initial-delay-ms")
            @WithDefault("1000")
            long initialDelayMs();

            @WithName("max-delay-ms")
            @WithDefault("30000")
            long maxDelayMs();
        }

        /** Backfill pass settings. */
        interface Backfill {
            @WithName("inter-call-delay-ms")
            @WithDefault("0")
            long interCallDelayMs();
        }
    }

    /** Credential resolution settings sub-group. */
    interface Secrets {
        /** Credential provider used to resolve secret references. */
        @WithDefault("env")
        String provider();
    }

    /** Global resource defaults. */
    interface Resources {
        /** Defaults that apply when a process does not define an override. */
        @WithName("default")
        Resource defaultResource();
    }

    /** Resource overrides for one cognitive process. */
    interface Process extends Resource {
        /** Named resource overrides keyed by resource name. */
        Map<String, Resource> resources();
    }

    /** Resource configuration supported at global, process, and named-resource scopes. */
    interface Resource {
        /** LLM resource properties. */
        Optional<ResourceLlm> llm();

        /** HTTP API resource properties. */
        Optional<ResourceApi> api();

        /** Database resource properties. */
        Optional<ResourceDatabase> database();

        /** Cache resource properties. */
        Optional<ResourceCache> cache();
    }

    /** LLM properties supported by the resource configuration contract. */
    interface ResourceLlm {
        Optional<String> provider();

        Optional<String> model();

        Optional<Double> temperature();

        @WithName("max-tokens")
        Optional<Integer> maxTokens();

        Optional<Duration> timeout();

        @WithName("api-key-ref")
        Optional<String> apiKeyRef();
    }

    /** HTTP API properties supported by the resource configuration contract. */
    interface ResourceApi {
        Optional<String> endpoint();

        Optional<Duration> timeout();

        @WithName("retry-attempts")
        Optional<Integer> retryAttempts();

        @WithName("retry-delay")
        Optional<Duration> retryDelay();

        @WithName("api-key-ref")
        Optional<String> apiKeyRef();

        @WithName("bearer-token-ref")
        Optional<String> bearerTokenRef();
    }

    /** Database properties supported by the resource configuration contract. */
    interface ResourceDatabase {
        @WithName("connection-string")
        Optional<String> connectionString();

        Optional<String> username();

        @WithName("password-ref")
        Optional<String> passwordRef();

        @WithName("pool-size")
        Optional<Integer> poolSize();

        Optional<Duration> timeout();
    }

    /** Cache properties supported by the resource configuration contract. */
    interface ResourceCache {
        Optional<String> provider();

        Optional<String> host();

        Optional<Integer> port();

        @WithName("password-ref")
        Optional<String> passwordRef();
    }
}
