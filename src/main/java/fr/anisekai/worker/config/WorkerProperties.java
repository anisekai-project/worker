package fr.anisekai.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "anisekai.worker")
@Validated
public record WorkerProperties(

        @NotBlank
        String apiUrl,

        @NotBlank
        String apiKey,

        @NotBlank
        String workerName,

        @NotNull
        List<String> factories,

        @NotNull
        Duration pollInterval,

        @NotNull
        Duration heartbeatInterval,

        @NotNull
        Duration connectTimeout,

        @NotNull
        Duration readTimeout,

        @NotNull
        Duration controlTimeout,

        int maxRetries,

        @NotNull
        Duration retryBaseDelay,

        @NotNull
        java.nio.file.Path scratchDir,

        @NotNull
        Duration conversionTimeout,

        @NotNull
        Duration shutdownTimeout

) {

    public WorkerProperties {
        if (factories == null || factories.isEmpty()) {
            throw new IllegalArgumentException("At least one worker factory is required");
        }
        factories = List.copyOf(factories);
        if (!factories.equals(List.of("media:convert"))) {
            throw new IllegalArgumentException("Only the media:convert factory is supported");
        }
        if (maxRetries < 0 || maxRetries > 20) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 20");
        }

        requirePositive(pollInterval, "pollInterval");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        requirePositive(controlTimeout, "controlTimeout");
        requirePositive(retryBaseDelay, "retryBaseDelay");
        requirePositive(conversionTimeout, "conversionTimeout");
        requirePositive(shutdownTimeout, "shutdownTimeout");
        if (pollInterval.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("pollInterval must not exceed 60 seconds");
        }
        if (heartbeatInterval.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("heartbeatInterval must not exceed 60 seconds");
        }
        if (controlTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("controlTimeout must not exceed 30 seconds");
        }
        if (conversionTimeout.compareTo(Duration.ofHours(3)) > 0) {
            throw new IllegalArgumentException("conversionTimeout must not exceed 3 hours");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(name + " must be at least 1 millisecond");
        }
    }
}
