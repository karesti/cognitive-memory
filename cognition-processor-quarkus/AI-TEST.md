# Testing Instructions

## Test Frameworks

This module uses a single framework stack, run through Maven Surefire on the JUnit Platform.

| Test Type                            | Framework                                  | Annotations / Base                          | Test Naming |
|--------------------------------------|--------------------------------------------|---------------------------------------------|-------------|
| Pure-logic classes (no CDI)          | JUnit 5 (Jupiter) + AssertJ                | none                                        | `*Test`     |
| Config binding without a container   | JUnit 5 + `SmallRyeConfigBuilder`          | none                                        | `*Test`     |
| CDI beans, services, processes       | `quarkus-junit5` + Mockito                 | `@QuarkusTest`, `@Inject`, `@InjectMock`    | `*Test`     |
| Config / profile variations          | `quarkus-junit5`                           | `@QuarkusTest` + `@TestProfile`             | `*Test`     |

Available test dependencies: `quarkus-junit5`, `quarkus-junit5-mockito`, `rest-assured`, `mockito-core`, `assertj-core`.
There is no Awaitility, TestNG or Testcontainers dependency. Do not add one without discussion.

There is currently no integration-test (`*IT`) suite. `maven-failsafe-plugin` is configured only for the native profile.
The memory-service and LLM providers are never contacted from tests.

## Mandatory: Concurrency, Asynchrony and Parsing Test Coverage

Every feature or fix MUST be evaluated for the following coverage. Not every change needs all levels, but the evaluation is mandatory.

### Asynchronous and concurrent behaviour
This service is event-driven. Events arrive on a gRPC stream, are grouped in `DirtyWindow`s, promoted to jobs and processed per conversation. If the change touches windows, queues, schedulers, checkpoints or process lifecycle, you MUST test:
- ordering and per-conversation singleton processing (see `ConversationJobQueueTest`, `ScopeJobTest`)
- promotion triggers and edge cases (see `DirtyWindowTest`, `SerializedWindowTest`)
- failure paths: a failed job or stub call must be logged and must not kill the worker (see `JobProcessorTest`)
- resume and replay behaviour via checkpoints (see `CheckpointServiceTest`)

### LLM and structured-response tests
Any new LLM-backed service, prompt, or response record MUST be tested for how it handles model output:
- valid, empty, partial, and malformed responses (see `DurableExtractionResponseTest`, `MetadataExtractionResponseTest`)
- retry and failure behaviour (see `LlmRetryHelper` and its callers)
- provenance and citation handling for derived memories (see `MemoryWriterTest`, `EvidencePackTest`)

The LLM itself is ALWAYS mocked. Tests must be deterministic and must not need network access or API keys.

### gRPC boundary tests
Code that talks to memory-service through gRPC stubs MUST be tested with mocked stubs:
- request construction (namespace, UUID/`ByteString` conversion, metadata)
- response mapping
- `StatusRuntimeException` handling (see `MemoryWriterTest`, `TranscriptLoaderTest`)

### Governance
Derived memories must preserve provenance, scope and access control. A change that writes memories MUST assert the namespace and provenance fields on the written request.

## Writing Tests

### Choose the right style

| Scenario                                                              | Pattern                                                       |
|-----------------------------------------------------------------------|---------------------------------------------------------------|
| Service under test is a CDI bean                                      | `@QuarkusTest` + `@Inject`                                    |
| CDI collaborator to control (e.g. `ConsolidationService`)             | `@InjectMock` (Quarkus swaps in a Mockito mock)               |
| gRPC stub / `ManagedChannel` (not a CDI bean)                         | `mock()` in `@BeforeEach`, assign to the package-private field |
| Config values (`MemoryServiceConfig`, `CognitionConfig`)              | **Do not mock.** Values come from `src/test/resources/application.properties` |
| Pure-logic classes (`DirtyWindow`, `EvidencePack`, records)           | Plain JUnit 5, no `@QuarkusTest`                              |
| Config mapping behaviour with custom values, no container             | `SmallRyeConfigBuilder` + `PropertiesConfigSource`            |
| Config mapping behaviour with custom values, full container          | `@QuarkusTest` + `@TestProfile` with `getConfigOverrides()`   |

Prefer the cheapest style that validates the behaviour. `@QuarkusTest` starts a real container, so use it only when CDI wiring matters.

### Example: Pure Logic Test

```java
class DirtyWindowTest {

    @Test
    void windowAcceptsEvents() {
        DirtyWindow window = new DirtyWindow("conv-123", Instant.now());
        window.acceptEvent("entry-1");
        assertThat(window.size()).isEqualTo(1);
    }
}
```

### Example: CDI Service Test with a Mocked gRPC Stub

```java
@QuarkusTest
class MyServiceTest {

    @Inject
    MyService service;                 // real CDI bean, config wired automatically

    @InjectMock
    SomeCdiCollaborator collaborator;  // CDI bean replaced by a Mockito mock

    @BeforeEach
    void setUp() {
        // Replace only the gRPC stub/channel (infrastructure, not a CDI bean)
        service.memoriesStub = mock(AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub.class);
        service.channel      = mock(ManagedChannel.class);
    }

    @Test
    void doesSomething() {
        // Given / When / Then
    }
}
```

`@ApplicationScoped` beans are wrapped in client proxies. Assigning a field on the proxy is a no-op. Unwrap first, as `MemoryWriterTest` does:

```java
MyService real = (MyService) ((io.quarkus.arc.ClientProxy) service).arc_contextualInstance();
real.memoriesStub = mockStub;
```

### Example: Config Binding Without a Container

```java
SmallRyeConfig cfg = new SmallRyeConfigBuilder()
        .withMapping(SalienceScorerConfig.class)
        .withSources(new PropertiesConfigSource(props, "test-overrides", 300))
        .build();
SalienceScorerConfig config = cfg.getConfigMapping(SalienceScorerConfig.class);
```

`@WithDefault` values on the interface supply defaults. The source carries only per-test overrides.

### Example: Test Profile

```java
@QuarkusTest
@TestProfile(MyFeatureTest.MyProfile.class)
class MyFeatureTest {

    @Inject
    CognitionConfig config;

    public static class MyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cognition.scheduler.debounce-delay", "PT0S");
        }
    }
}
```

Each distinct profile restarts the Quarkus application, which is slow. Use profiles only when the behaviour truly depends on startup configuration.

### Test Configuration

`src/test/resources/application.properties` must contain every property required by the `@ConfigMapping` interfaces.
When you add a property to `MemoryServiceConfig`, `CognitionConfig` or `SalienceScorerConfig`, add a test value here so the container starts cleanly.

**Never** mock `MemoryServiceConfig` or `CognitionConfig` in a `@QuarkusTest`. CDI already injects the real implementations.

### Conventions

- Class naming: `<ClassUnderTest>Test`, in the same package as the class under test.
- Method naming: describe the behaviour, e.g. `spanishTerms_bindViaProfile`, `testWriteMemory_Success_ReturnsResult`. Follow the style already used in the file you edit.
- Structure with Given / When / Then comments for non-trivial tests.
- Group related cases with `@Nested` classes (see `SalienceScorerConfigBindingTest`).
- Prefer AssertJ (`assertThat`) for new assertions. Existing tests also use JUnit `Assertions`; do not rewrite them.
- Use `ArgumentCaptor` to assert on the request sent to a stub, not only that a call happened.
- Use fixed `Instant`/`Clock` values or fixture constants, such as `TEST_OBSERVED_AT`. Never depend on wall-clock time.
- Avoid `Thread.sleep()`. If you must wait for async work, poll with a short bounded loop or inject a controllable scheduler/clock.
- Javadoc on test classes and methods is optional. A short class comment stating what is covered is welcome.
- Tests must not touch the network, `.env`, or real credentials.

### Cleanup and Isolation

- Tests must not depend on execution order or on state left by another test.
- Beans are shared across `@QuarkusTest` classes that use the same profile. Reset any stub or mock you assign in `@BeforeEach`.
- Static or registry state (`DirtyWindowRegistry`, `JobQueueRegistry`) must be cleared or use unique conversation IDs per test.

## Coverage

- Guideline: 80% line and 70% branch coverage. This is not a hard target. Focus on meaningful logic and edge cases, not boilerplate exception handlers.
- Enforced today: JaCoCo `check` at the `verify` phase requires at least 15% line and 10% branch. The CI coverage report requires 35% overall and 50% on changed files.
- Generated gRPC code (`io/github/chirino/memory/grpc/**`) is excluded.

```bash
./mvnw -B test jacoco:report
open target/site/jacoco/index.html
```

## Running Tests

Run from `cognition-processor-quarkus/`.

```bash
# Run all tests
./mvnw -B test

# Run a single test class
./mvnw -B test -Dtest=MemoryWriterTest

# Run a single test method
./mvnw -B test -Dtest=MemoryWriterTest#testWriteMemory_Success_ReturnsResult

# Run all tests in a package
./mvnw -B test -Dtest='io.github.rigazilla.memory.cognition.event.*Test'

# Tests plus coverage report
./mvnw -B test jacoco:report

# Full verification: tests, coverage check, checkstyle, spotbugs
./mvnw -B verify
```

## Before Submitting

1. All tests pass: `./mvnw -B test`
2. Style and static analysis pass: `./mvnw -B checkstyle:check spotbugs:check`
3. New config properties have a value in `src/test/resources/application.properties`.
4. The concurrency, LLM-response, gRPC and governance checks above were evaluated for the change.

CI (`.github/workflows/quarkus-build-and-test.yml`) runs the same steps on Ubuntu and macOS with JDK 21.
