# Anisekai Worker

Remote conversion worker for the [Anisekai API](https://github.com/anisekai-project/api).
It polls the API for tasks (e.g. `media:convert`), fetches conversion sources, runs the
conversion locally (via `ffmpeg4j`), and uploads the result back to the API.

### How it works (API protocol)

- Heartbeat / task assignment: `POST /api/v3/workers/ping` with `factoryNames`, `workerName`, and `currentTaskId`.
  The response carries the claimed task, its `arguments` (build source URLs from
  `arguments.source`), the `isolationContextId`, and a `directive` (`NONE` or `GIVE_UP_TASK`).
- Result reporting: `POST /api/v3/workers/{taskId}/success` or `POST /api/v3/workers/{taskId}/failure`.
- File plane: conversion sources are served by the API (`IMPORTS`, finished `DOWNLOADS`);
  converted episodes are staged with `PUT` while sending `X-Isolation-Context`.
- One worker per token: provision one API key (with the `worker` scope) per worker instance.

See the API repository for the authoritative protocol definition.

### Requirements

- JDK 25+
- `ffmpeg` / `ffprobe` binaries available on `PATH` (for actual conversions)
- A running Anisekai API instance and a worker-scoped API key

### Usage

```bash
./gradlew test
./gradlew bootRun
```

Configuration is done through `src/main/resources/application.yml`
(environment overrides supported by Spring Boot, see `.env.example`).

### Provisioning a worker key

As an admin, create an API key with the `worker` scope:

```bash
curl -X POST http://api:8080/api/v3/users/api-key \
  -H "Authorization: Bearer <admin-token>" \
  -H "Content-Type: application/json" \
  -d '{"scopes":["worker"]}'
```

The response contains the API key and the worker ID. Use this key as `ANISEKAI_API_KEY`
for the worker. **One key per worker instance** — the protocol enforces a single worker per token.

### Configuration

All configuration is done via environment variables (see `.env.example`) or `application.yml`.
Key settings:

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| API base URL | `ANISEKAI_API_URL` | `http://localhost:8080` | Base URL of the Anisekai API |
| Worker API key | `ANISEKAI_API_KEY` | *(required)* | Worker-scoped API key |
| Worker name | `WORKER_NAME` | hostname | Display name for this worker |
| Factories | `WORKER_FACTORIES` | `media:convert` | Comma-separated list of factory names |
| Poll interval | `WORKER_POLL_INTERVAL` | `10s` | How often to ping when idle |
| Heartbeat interval | `WORKER_HEARTBEAT_INTERVAL` | `60s` | How often to ping while busy |
| Connect timeout | `WORKER_CONNECT_TIMEOUT` | `10s` | HTTP connect timeout |
| Read timeout | `WORKER_READ_TIMEOUT` | `300s` | HTTP read timeout |
| Max retries | `WORKER_MAX_RETRIES` | `3` | Max retries for transient failures |
| Retry base delay | `WORKER_RETRY_BASE_DELAY` | `1s` | Base delay for exponential backoff |
| Scratch dir | `WORKER_SCRATCH_DIR` | `$TMPDIR/anisekai-worker` | Directory for temporary files |
| Conversion timeout | `WORKER_CONVERSION_TIMEOUT` | `3h` | Max time for a single conversion |
| Log level | `WORKER_LOG_LEVEL` | `INFO` | Log level for the application |

### Docker

```bash
docker build -t anisekai/worker .
docker run --rm \
  -e ANISEKAI_API_URL=http://host.docker.internal:8080 \
  -e ANISEKAI_API_KEY=<worker-key> \
  anisekai/worker
```

### Contributing

- Open an issue first and await approval before working on it.
- Conventional commits (`feat:`, `fix:`, `chore:`, …).

### License

Apache-2.0, see [LICENSE](LICENSE).