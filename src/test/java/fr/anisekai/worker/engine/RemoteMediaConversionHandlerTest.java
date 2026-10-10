package fr.anisekai.worker.engine;

import fr.anisekai.worker.client.ApiClient;
import fr.anisekai.worker.config.WorkerProperties;
import fr.anisekai.wireless.tasks.conversion.MediaConversionInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RemoteMediaConversionHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void storagePathHasAnExistingScratchParentButDoesNotCreateDestination() throws Exception {
        RemoteMediaConversionHandler handler = handler();

        Path destination = handler.getStoragePath(episode(), tempDir.resolve("source.mkv"));

        assertThat(destination).doesNotExist();
        assertThat(destination.getParent()).isDirectory();
        assertThat(destination.getParent().getParent()).isEqualTo(tempDir.toAbsolutePath());
    }

    @Test
    void cleanupRemovesSourceDestinationAndConversionDirectory() throws Exception {
        RemoteMediaConversionHandler handler = handler();
        Path source = Files.writeString(tempDir.resolve("source.mkv"), "source");
        Path destination = handler.getStoragePath(episode(), source);
        Files.writeString(destination, "converted");
        Path conversionDirectory = destination.getParent();

        handler.cleanupEpisode(episode(), source, destination);

        assertThat(source).doesNotExist();
        assertThat(destination).doesNotExist();
        assertThat(conversionDirectory).doesNotExist();
    }

    private RemoteMediaConversionHandler handler() {
        WorkerProperties properties = new WorkerProperties(
                "https://api.example",
                "api-key",
                "worker-a",
                List.of("media:convert"),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                0,
                Duration.ofMillis(1),
                tempDir,
                Duration.ofMinutes(10),
                Duration.ofSeconds(1)
        );
        return new RemoteMediaConversionHandler(mock(ApiClient.class), properties, UUID.randomUUID());
    }

    private static MediaConversionInput.Episode episode() {
        return new MediaConversionInput.Episode(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                new MediaConversionInput.Source(MediaConversionInput.Store.IMPORTS, "source.mkv"),
                "0".repeat(64)
        );
    }
}
