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

        int maxRetries,

        @NotNull
        Duration retryBaseDelay,

        @NotNull
        java.nio.file.Path scratchDir,

        @NotNull
        Duration conversionTimeout,

        @NotNull
        ch.qos.logback.classic.Level logLevel

) {
}