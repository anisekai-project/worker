package fr.anisekai.worker.client;

import fr.anisekai.worker.config.WorkerProperties;
import fr.anisekai.worker.dto.TaskCompletionRequest;
import fr.anisekai.worker.dto.WorkerPingRequest;
import fr.anisekai.worker.dto.WorkerPingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;

public class ApiClient {

    private static final Logger LOG = LoggerFactory.getLogger(ApiClient.class);

    private final RestClient controlClient;
    private final RestClient restClient;
    private final WorkerProperties properties;

    public ApiClient(WorkerProperties properties, RestClient.Builder builder) {
        this.properties = properties;

        RestClient.Builder configuredBuilder = builder
                .baseUrl(properties.apiUrl().replaceAll("/+$", "") + "/api/v3")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());

        this.controlClient = configuredBuilder.clone()
                .requestFactory(requestFactory(properties.controlTimeout()))
                .build();
        this.restClient = configuredBuilder.clone()
                .requestFactory(requestFactory(properties.readTimeout()))
                .build();
    }

    ApiClient(WorkerProperties properties, RestClient restClient) {
        this.properties = properties;
        this.controlClient = restClient;
        this.restClient = restClient;
    }

    public WorkerPingResponse ping(UUID instanceId, List<String> factoryNames, String workerName,
                                   UUID currentTaskId) {
        WorkerPingRequest request = new WorkerPingRequest(instanceId, factoryNames, workerName, currentTaskId);

        try {
            return controlClient.post()
                    .uri("/workers/ping")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        requirePingSuccess(response);
                        return response.bodyTo(WorkerPingResponse.class);
                    });
        } catch (ResourceAccessException e) {
            throw new TransientApiException("Ping failed: " + e.getMessage(), e);
        }
    }

    public boolean reportSuccess(UUID instanceId, UUID taskId, String result) {
        return reportCompletion(taskId, new TaskCompletionRequest(instanceId, result, null), "success");
    }

    public boolean reportFailure(UUID instanceId, UUID taskId, String errorMessage) {
        return reportCompletion(taskId, new TaskCompletionRequest(instanceId, null, errorMessage), "failure");
    }

    private boolean reportCompletion(UUID taskId, TaskCompletionRequest request, String outcome) {
        return executeWithBackoff(() -> {
                    try {
                        return controlClient.post()
                                .uri("/workers/{taskId}/{outcome}", taskId, outcome)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(request)
                                .exchange((httpRequest, response) -> {
                                    HttpStatusCode status = response.getStatusCode();
                                    if (status == HttpStatus.NOT_FOUND) {
                                        return false;
                                    }
                                    requireWorkerRequestSuccess(response, "Report " + outcome);
                                    return true;
                                });
                    } catch (ResourceAccessException e) {
                        throw new TransientApiException("Report " + outcome + " failed: " + e.getMessage(), e);
                    }
                },
                "report task " + outcome);
    }

    public Path downloadSource(String sourceRef) {
        String[] parts = sourceRef.split("/", 2);
        String store = parts[0].toUpperCase();
        String reference = parts.length > 1 ? parts[1] : "";
        String[] segments = reference.split("/", -1);

        if (!("IMPORTS".equals(store) || "DOWNLOADS".equals(store))) {
            throw new IllegalArgumentException("Unknown store: " + store);
        }

        return executeWithBackoff(() -> download(store, segments), "download source");
    }

    private Path download(String store, String[] referenceSegments) {
        Path tempFile = createTempFile();
        try {
            return restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.pathSegment("library", store.toLowerCase());
                        for (String segment : referenceSegments) {
                            uriBuilder.pathSegment(segment);
                        }
                        return uriBuilder.build();
                    })
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .exchange((httpRequest, response) -> {
                        requireTaskRequestSuccess(response, "Download source");
                        Files.copy(response.getBody(), tempFile, StandardCopyOption.REPLACE_EXISTING);
                        return tempFile;
                    });
        } catch (ResourceAccessException e) {
            deletePartialFile(tempFile);
            throw new TransientApiException("Download failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            deletePartialFile(tempFile);
            throw e;
        }
    }

    public void uploadEpisode(UUID episodeId, UUID isolationContextId, Path file) {
        executeWithBackoff(() -> {
            try {
                restClient.put()
                        .uri("/library/episodes/{episodeId}", episodeId)
                        .contentType(MediaType.parseMediaType("video/x-matroska"))
                        .contentLength(Files.size(file))
                        .header("X-Isolation-Context", isolationContextId.toString())
                        .body(new FileSystemResource(file))
                        .exchange((httpRequest, response) -> {
                            requireTaskRequestSuccess(response, "Upload episode");
                            return null;
                        });
                return null;
            } catch (ResourceAccessException e) {
                throw new TransientApiException("Upload failed: " + e.getMessage(), e);
            } catch (IOException e) {
                throw new TaskApiException("Cannot read converted episode: " + e.getMessage(),
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }, "upload episode");
    }

    private Path createTempFile() {
        try {
            Files.createDirectories(properties.scratchDir());
            return Files.createTempFile(properties.scratchDir(), "anisekai-", ".source");
        } catch (IOException e) {
            throw new TaskApiException("Cannot create scratch file: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static void deletePartialFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            LOG.warn("Failed to remove partial download {}", path, cleanupFailure);
        }
    }

    private static void requirePingSuccess(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is2xxSuccessful()) {
            return;
        }

        String body = readErrorBody(response);
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            throw new PermanentApiException("Worker authentication failed: " + status + " - " + body, status);
        }
        if (status == HttpStatus.CONFLICT) {
            throw new TransientApiException("Another worker instance still owns this API key: " + body, status);
        }
        if (status.is5xxServerError()) {
            throw new TransientApiException("Ping server error " + status + ": " + body, status);
        }
        throw new PermanentApiException("Ping rejected with " + status + ": " + body, status);
    }

    private static void requireWorkerRequestSuccess(ClientHttpResponse response, String operation) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is2xxSuccessful()) {
            return;
        }

        String body = readErrorBody(response);
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN || status == HttpStatus.CONFLICT) {
            throw new PermanentApiException(operation + " rejected with " + status + ": " + body, status);
        }
        if (status.is5xxServerError()) {
            throw new TransientApiException(operation + " failed with " + status + ": " + body, status);
        }
        throw new PermanentApiException(operation + " rejected with " + status + ": " + body, status);
    }

    private static void requireTaskRequestSuccess(ClientHttpResponse response, String operation) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is2xxSuccessful()) {
            return;
        }

        String body = readErrorBody(response);
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            throw new PermanentApiException(operation + " authentication failed: " + status + " - " + body, status);
        }
        if (status.is5xxServerError()) {
            throw new TransientApiException(operation + " server error " + status + ": " + body, status);
        }
        throw new TaskApiException(operation + " rejected with " + status + ": " + body, status);
    }

    private static String readErrorBody(ClientHttpResponse response) throws IOException {
        return StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
    }

    private SimpleClientHttpRequestFactory requestFactory(Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }

    private <T> T executeWithBackoff(Callable<T> action, String operationName) {
        int maxRetries = properties.maxRetries();
        Duration baseDelay = properties.retryBaseDelay();

        for (int attempt = 0; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException(operationName + " was cancelled");
            }

            try {
                return action.call();
            } catch (TransientApiException e) {
                if (attempt == maxRetries) {
                    LOG.error("Operation {} failed after {} retries: {}", operationName, maxRetries, e.getMessage());
                    throw e;
                }

                long multiplier = 1L << Math.min(attempt, 20);
                long baseMillis = baseDelay.toMillis();
                long exponentialDelay = Math.multiplyExact(baseMillis, multiplier);
                long delayMillis = Math.addExact(exponentialDelay,
                        ThreadLocalRandom.current().nextLong(baseMillis));
                LOG.warn("Operation {} failed (attempt {}/{}): {}. Retrying in {}ms",
                        operationName, attempt + 1, maxRetries, e.getMessage(), delayMillis);
                sleep(delayMillis);
            } catch (PermanentApiException | TaskApiException | CancellationException e) {
                throw e;
            } catch (Exception e) {
                throw new TransientApiException(operationName + " failed: " + e.getMessage(), e);
            }
        }
    }

    private static void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted during API retry backoff");
        }
    }
}
