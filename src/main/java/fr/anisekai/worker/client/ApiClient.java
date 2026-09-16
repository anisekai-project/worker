package fr.anisekai.worker.client;

import fr.anisekai.worker.config.WorkerProperties;
import fr.anisekai.worker.dto.TaskCompletionRequest;
import fr.anisekai.worker.dto.TaskSummary;
import fr.anisekai.worker.dto.WorkerPingRequest;
import fr.anisekai.worker.dto.WorkerPingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.*;
import org.springframework.http.client.ClientHttpResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.anisekai.scheduler.commons.interfaces.ObjectSerializer;
import fr.anisekai.wireless.tasks.conversion.MediaConversionInput;
import fr.anisekai.wireless.tasks.conversion.MediaConversionOutput;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class ApiClient {

    private static final Logger LOG = LoggerFactory.getLogger(ApiClient.class);

    private final RestClient restClient;
    private final WorkerProperties properties;
    private final String baseUrl;
    private final String apiKey;

    private final ObjectSerializer<MediaConversionInput> inputSerializer;
    private final ObjectSerializer<MediaConversionOutput> outputSerializer;

    public ApiClient(WorkerProperties properties) {
        this.properties = properties;
        this.baseUrl = properties.apiUrl().replaceAll("/+$", "") + "/api/v3";
        this.apiKey = properties.apiKey();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) properties.readTimeout().toMillis());

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(this::handleError)
                .build();

        ObjectMapper mapper = new ObjectMapper();
        this.inputSerializer = new ObjectSerializer<>() {
            @Override
            public String serialize(MediaConversionInput object) {
                try {
                    return mapper.writeValueAsString(object);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize input", e);
                }
            }

            @Override
            public MediaConversionInput deserialize(String data) {
                try {
                    return mapper.readValue(data, MediaConversionInput.class);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to deserialize input", e);
                }
            }
        };

        this.outputSerializer = new ObjectSerializer<>() {
            @Override
            public String serialize(MediaConversionOutput object) {
                try {
                    return mapper.writeValueAsString(object);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize output", e);
                }
            }

            @Override
            public MediaConversionOutput deserialize(String data) {
                try {
                    return mapper.readValue(data, MediaConversionOutput.class);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to deserialize output", e);
                }
            }
        };
    }

    public ObjectSerializer<MediaConversionInput> getInputSerializer() {
        return inputSerializer;
    }

    public ObjectSerializer<MediaConversionOutput> getOutputSerializer() {
        return outputSerializer;
    }

    private boolean handleError(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String body = StreamUtils.copyToString(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);

        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            throw new PermanentApiException("Authentication failed: " + status + " - " + body, status);
        }
        if (status == HttpStatus.CONFLICT) {
            throw new PermanentApiException("Duplicate worker (409): " + body, status);
        }
        if (status == HttpStatus.NOT_FOUND) {
            throw new TransientApiException("Resource not found (404): " + body, status);
        }
        if (status.is4xxClientError()) {
            throw new PermanentApiException("Client error " + status + ": " + body, status);
        }
        if (status.is5xxServerError()) {
            throw new TransientApiException("Server error " + status + ": " + body, status);
        }
        return false; // Let other handlers process if not matched
    }

    public WorkerPingResponse ping(List<String> factoryNames, String workerName, UUID currentTaskId) {
        WorkerPingRequest request = new WorkerPingRequest(factoryNames, workerName, currentTaskId);
        String url = baseUrl + "/workers/ping";

        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(WorkerPingResponse.class);
    }

    public void reportSuccess(UUID taskId, String result) {
        String url = baseUrl + "/workers/" + taskId + "/success";
        TaskCompletionRequest request = new TaskCompletionRequest(result, null);

        restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    public void reportFailure(UUID taskId, String errorMessage) {
        String url = baseUrl + "/workers/" + taskId + "/failure";
        TaskCompletionRequest request = new TaskCompletionRequest(null, errorMessage);

        restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    public Path downloadSource(String sourceRef) throws IOException {
        String[] parts = sourceRef.split("/", 2);
        String store = parts[0].toUpperCase();
        String ref = parts.length > 1 ? parts[1] : "";

        String url;
        if ("IMPORTS".equals(store)) {
            url = baseUrl + "/library/imports/" + ref;
        } else if ("DOWNLOADS".equals(store)) {
            url = baseUrl + "/library/downloads/" + ref;
        } else {
            throw new IllegalArgumentException("Unknown store: " + store);
        }

        Path tempFile = createTempFile();
        try (InputStream in = restClient.get().uri(url).retrieve().toEntity(InputStream.class).getBody()) {
            if (in == null) {
                throw new IllegalStateException("Empty response body for " + url);
            }
            try {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new TransientApiException("Failed to save downloaded file: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return tempFile;
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status == HttpStatus.UNPROCESSABLE_ENTITY) {
                throw new TransientApiException("Source not ready (422): " + url, status);
            }
            if (status == HttpStatus.NOT_FOUND) {
                throw new PermanentApiException("Source not found (404): " + url, status);
            }
            throw new TransientApiException("Download failed: " + e.getMessage(), status);
        }
    }

    public void uploadEpisode(UUID episodeId, UUID isolationContextId, Path file) {
        String url = baseUrl + "/library/episodes/" + episodeId;
        long contentLength;
        try {
            contentLength = Files.size(file);
        } catch (IOException e) {
            throw new TransientApiException("Failed to determine file size: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        restClient.post()
                .uri(url)
                .header(HttpHeaders.CONTENT_TYPE, "video/x-matroska")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                .header("X-Isolation-Context", isolationContextId.toString())
                .body(file.toFile())
                .retrieve()
                .toBodilessEntity();
    }

    private Path createTempFile() throws IOException {
        return Files.createTempFile(properties.scratchDir(), "anisekai-", ".mkv");
    }

    public void executeWithBackoff(RunnableWithException action, String operationName) {
        int maxRetries = properties.maxRetries();
        Duration baseDelay = properties.retryBaseDelay();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                action.run();
                return;
            } catch (TransientApiException e) {
                if (attempt == maxRetries) {
                    LOG.error("Operation {} failed after {} retries: {}", operationName, maxRetries, e.getMessage());
                    throw e;
                }
                long delayMs = baseDelay.toMillis() * (1L << attempt) + ThreadLocalRandom.current().nextLong(0, baseDelay.toMillis());
                LOG.warn("Operation {} failed (attempt {}/{}): {}. Retrying in {}ms",
                        operationName, attempt + 1, maxRetries, e.getMessage(), delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during backoff", ie);
                }
            } catch (PermanentApiException e) {
                LOG.error("Permanent failure for {}: {}", operationName, e.getMessage());
                throw e;
            }
        }
    }

}