# Memory Service - Local Development Setup

This folder contains configuration files for running [memory-service](https://github.com/chirino/memory-service) locally with Ollama.

## Prerequisites

- Docker & Docker Compose
- **Ollama** - choose one option:
  - **Option A: Native Ollama** - [Install Ollama](https://ollama.ai) on your host machine
  - **Option B: Docker Ollama** - Run Ollama in a container (see setup below)

### Option A: Native Ollama Setup

Install Ollama on your system and pull required models:

```bash
# Install Ollama (if not already installed)
# Visit https://ollama.ai for installation instructions

# Pull required models
ollama pull qwen2.5:1.5b        # Chat model (lightweight)
ollama pull nomic-embed-text    # Embedding model

# Verify Ollama is running
ollama list
```

### Option B: Docker Ollama Setup

Pull and start Ollama in a container:

```bash
# Start Ollama container (named volume keeps pulled models across container re-creation)
docker run -d -p 11434:11434 -v ollama-data:/root/.ollama --name ollama ollama/ollama:latest

# Pull required models
docker exec ollama ollama pull qwen2.5:1.5b
docker exec ollama ollama pull nomic-embed-text

# Verify models are available
docker exec ollama ollama list
```

## Quick Setup

### 1. Clone memory-service

Clone the memory-service repository to any location on your machine:

```bash
cd ~/git  # or wherever you keep repositories
git clone https://github.com/chirino/memory-service.git
cd memory-service
```

### 2. Copy configuration files

Copy the example files from this folder to your memory-service clone:

```bash
# From this directory (cognitive-memory/memory-service)
cp compose.override.yaml.example ~/git/memory-service/compose.override.yaml
cp .env.example ~/git/memory-service/.env
```

Or manually copy them to your memory-service directory.

### 3. Start memory-service

```bash
cd ~/git/memory-service  # or wherever you cloned it
docker compose -f compose.yaml -f compose.override.yaml up -d
```

This will start:
- **memory-service** on `http://localhost:8082`
- **chat-quarkus** (sample app) on `http://localhost:8080`
- **PostgreSQL** database
- Other supporting services

### 4. Verify it's running

Check that memory-service is healthy:

```bash
curl http://localhost:8082/v1/health
```

You should see a health check response with status "ok".

### 5. Test with the sample app

Visit the chat-quarkus sample app:

```bash
open http://localhost:8080  # or visit in browser
```

Create a conversation and send messages. Memory-service will store the conversation data, and the cognition-processor can subscribe to process it.

## Configuration Details

### compose.override.yaml.example

Adds `host.docker.internal` mapping to allow containers to access services running on your host machine (like Ollama):

```yaml
services:
  memory-service:
    extra_hosts:
      - "host.docker.internal:host-gateway"

  chat-quarkus:
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

### .env.example

Configures memory-service to use your Ollama instance:

- **OPENAI_BASE_URL**: Points to Ollama on host machine
- **OPENAI_MODEL**: Chat model (qwen2.5:1.5b is fast and lightweight)
- **MEMORY_SERVICE_EMBEDDING_***: Embedding configuration for semantic search

## Using Different Models

You can change the models in `.env`:

```bash
# Faster, smaller (recommended for development)
OPENAI_MODEL=qwen2.5:1.5b

# Larger, more capable
OPENAI_MODEL=llama3.2
OPENAI_MODEL=llama3.1:8b

# Don't forget to pull the model first:
ollama pull <model-name>
```

## Stopping & Cleaning Up

Stop services:
```bash
cd ~/git/memory-service
docker compose down
```

Remove volumes (deletes all data):
```bash
docker compose down -v
```

## Troubleshooting

### Can't connect to Ollama from container

Make sure:
1. Ollama is running: `ollama list` should work
2. `compose.override.yaml` has the `extra_hosts` configuration
3. `.env` uses `host.docker.internal` not `localhost`

### Models not found

Pull required models:
```bash
# Native Ollama
ollama pull qwen2.5:1.5b
ollama pull nomic-embed-text

# Or if using Docker Ollama
docker exec ollama ollama pull qwen2.5:1.5b
docker exec ollama ollama pull nomic-embed-text
```

### Port conflicts

If ports 8080 or 8082 are in use, you can modify the port mappings in memory-service's `compose.yaml` or use a different override.

## Learn More

- [Memory Service Documentation](https://chirino.github.io/memory-service/)
- [Memory Service Repository](https://github.com/chirino/memory-service)
- [Getting Started Guide](https://chirino.github.io/memory-service/docs/quarkus/)
