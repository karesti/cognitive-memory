# Preserve dynamic cognition configuration

## Problem

`CognitionConfig` mapped the complete `cognition` prefix. SmallRye therefore treated the dynamic resource and process override branches as unknown properties, including the documented `cognition.resources.default.llm.provider` setting.

## Implementation

- Kept `CognitionConfig` as the single `@ConfigMapping(prefix = "cognition")` interface.
- Added the documented global resource defaults at `cognition.resources.default`.
- Represented dynamic process IDs and named resource IDs with maps:
  - `cognition.process.{process-id}`
  - `cognition.process.{process-id}.resources.{resource-name}`
- Modeled the established `llm`, `api`, `database`, and `cache` resource properties as optional typed groups, allowing each configuration scope to override only the properties it needs.
- Added a Quarkus startup test covering the documented global LLM default, process-level override, and named-resource override.

## Validation

- `./mvnw -B -Dtest=CognitionResourceConfigurationStartupTest test`
- `./mvnw -B test checkstyle:check spotbugs:check`
