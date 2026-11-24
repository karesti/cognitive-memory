# GitHub Actions Setup

This document describes the CI/CD workflows and required configuration.

## Workflows

### Reusable Workflow (`.github/workflows/docker-build-test.yml`)

Shared logic called by both CI and CD. Not triggered directly.

- **Inputs**:
  - `push` (boolean) — whether to push the image to Quay.io after a successful health check
  - `quay_repo` (string) — Quay.io repository path (default: `rigazilla/cognitive-memory`)
- **Jobs**:
  - Build application with Maven
  - Build Docker image
  - Test container health check (`/q/health/live`)
  - Optionally push image to Quay.io

### Build and Test (`.github/workflows/quarkus-build-and-test.yml`)

Reusable workflow (`workflow_call`) called by CI. It builds and runs `./mvnw test` on `ubuntu-latest` and `macos-latest` (JDK 21). On Linux only it also:
- generates the JaCoCo report and posts a PR coverage summary (`Madrapps/jacoco-report`, minimums: 35% overall, 50% on changed files)
- runs `checkstyle:checkstyle` and `spotbugs:spotbugs`

Note: CI runs the `test` phase, not `verify`, so the 15% line / 10% branch `check-coverage` rule in `pom.xml` is only enforced when running `./mvnw verify` locally. See [`cognition-processor-quarkus/AGENTS.md`](../cognition-processor-quarkus/AGENTS.md).

### CI Workflow (`.github/workflows/ci.yml`)
- **Trigger**: Pull requests targeting `main`
- **Jobs**:
  - Call `quarkus-build-and-test.yml` (build + unit tests)
  - Call the reusable Docker workflow with `push: false` (build + health check only, no registry push)

### OpenAPI Sync Check (`.github/workflows/verify-openapi.yml`)
- **Trigger**: Pull requests targeting `main` (path-filtered)
- **Purpose**: Fails if the checked-in `site/public/openapi.json|yaml` differs from what the Quarkus build generates. Regenerate with `./mvnw compile quarkus:build -DskipTests` in `cognition-processor-quarkus/` (see [`site/README.md`](../site/README.md)).

### Site Deployment (`.github/workflows/deploy-site.yml`)
- **Trigger**: Push to `main` (path-filtered)
- **Purpose**: Builds the Astro site in `site/` and deploys it to GitHub Pages.

### CD Workflow (`.github/workflows/cd.yml`)
- **Trigger**: Push to `main` branch
- **Jobs**:
  - Call reusable workflow with `push: true` (build + health check + push to Quay.io)

## Required Configuration

### Environment Variables

The CD workflow uses the following environment variable (configured in the workflow file):

#### QUAY_REPO
- **Location**: `.github/workflows/cd.yml` (env section)
- **Value**: `rigazilla/cognitive-memory`
- **Purpose**: Specifies the Quay.io repository path for the Docker image

### Required Secrets

To enable the CD workflow, you need to configure the following secrets in your GitHub repository:

#### Setting up Quay.io Secrets

1. Go to your GitHub repository
2. Navigate to **Settings** → **Secrets and variables** → **Actions**
3. Click **New repository secret**
4. Add the following secrets:

**QUAY_USERNAME**
- **Name**: `QUAY_USERNAME`
- **Value**: Your Quay.io username (used for authentication only)

**QUAY_TOKEN**
- **Name**: `QUAY_TOKEN`
- **Value**: Your Quay.io robot account token or personal access token

### Creating a Quay.io Robot Account (Recommended)

1. Log in to [Quay.io](https://quay.io)
2. Go to your organization or user settings
3. Navigate to **Robot Accounts**
4. Click **Create Robot Account**
5. Give it a name (e.g., `github_actions`)
6. Grant it **Write** permissions to the repository
7. Copy the generated token and use it as `QUAY_TOKEN`

## Docker Image Tags

The CD workflow creates the following tags:
- `main` - Latest build from main branch
- `main-<git-sha>` - Specific commit SHA
- `cd-latest` - Alias for the latest main branch build

## Image Location

After successful CD run, the image will be available at:
```
quay.io/rigazilla/cognitive-memory:latest
```

The repository path is configured via the `QUAY_REPO` environment variable in the workflow file.

## Health Check

The CD workflow verifies the container is healthy by:
1. Starting the container
2. Waiting up to 60 seconds
3. Checking the `/q/health/ready` endpoint
4. Only pushing to registry if health check passes

## Local Testing

To test the Docker image locally:

```bash
cd cognition-processor-quarkus
./mvnw clean package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t cognition-processor-quarkus:test .
docker run -p 8090:8090 \
  -e MEMORY_SERVICE_GRPC_HOST=localhost \
  -e MEMORY_SERVICE_GRPC_PORT=8082 \
  cognition-processor-quarkus:test
```

Check health:
```bash
curl http://localhost:8090/q/health/ready
```
