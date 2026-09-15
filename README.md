# Anisekai Worker

Remote conversion worker for the [Anisekai API](https://github.com/anisekai-project/api).
It polls the API for tasks (e.g. `media:convert`), fetches conversion sources, runs the
conversion locally (via `ffmpeg4j`), and uploads the result back to the API.

> Worker implementation is still in progress. This repository currently only holds the
> project scaffold: build conventions, CI, and licensing.

### How it works (API protocol)

- Heartbeat / task assignment: `POST /workers/ping` with `factoryNames` and `workerName`.
  The response carries the claimed task, its `arguments` (build source URLs from
  `arguments.source`), and the `isolationContextId`.
- Result reporting: `POST /workers/{taskId}/success` or `POST /workers/{taskId}/failure`.
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

Configuration is done through `src/main/resources/application.properties`
(environment overrides supported by Spring Boot).

### Contributing

- Open an issue first and await approval before working on it.
- Conventional commits (`feat:`, `fix:`, `chore:`, …).

### License

Apache-2.0, see [LICENSE](LICENSE).
