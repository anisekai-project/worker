package fr.anisekai.worker.engine;

import fr.anisekai.worker.client.ApiClient;
import fr.anisekai.worker.config.WorkerProperties;
import fr.anisekai.wireless.tasks.conversion.MediaConversionHandler;
import fr.anisekai.wireless.tasks.conversion.MediaConversionInput;
import fr.anisekai.wireless.tasks.conversion.MediaConversionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Remote media conversion handler that fetches source files via HTTP, converts them locally,
 * and uploads the result back to the API server.
 */
public class RemoteMediaConversionHandler extends MediaConversionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteMediaConversionHandler.class);

    private final ApiClient apiClient;
    private final WorkerProperties properties;
    private final UUID isolationContextId;
    private final AtomicBoolean abandoned = new AtomicBoolean(false);

    public RemoteMediaConversionHandler(ApiClient apiClient, WorkerProperties properties, UUID isolationContextId) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.isolationContextId = isolationContextId;
    }

    @Override
    public Path fetchEpisode(MediaConversionInput.Episode episode) throws Exception {
        requireActive();
        String sourceRef = episode.source().store().name() + "/" + episode.source().reference();
        Path sourcePath = apiClient.downloadSource(sourceRef);
        if (abandoned.get()) {
            delete(sourcePath, episode.id());
            throw new CancellationException("Task was abandoned after downloading its source");
        }
        return sourcePath;
    }

    @Override
    public Path getStoragePath(MediaConversionInput.Episode episode, Path source) throws Exception {
        Path tempDir = properties.scratchDir().toAbsolutePath();
        Files.createDirectories(tempDir);
        return Files.createTempDirectory(tempDir, "convert-").resolve("output.mkv");
    }

    @Override
    public void pushEpisode(MediaConversionInput.Episode episode, Path destination) throws Exception {
        requireActive();
        apiClient.uploadEpisode(episode.id(), isolationContextId, destination);
        requireActive();
    }

    @Override
    public void cleanupEpisode(MediaConversionInput.Episode episode, Path source, Path destination) throws Exception {
        delete(source, episode.id());
        delete(destination, episode.id());
        if (destination != null) {
            delete(destination.getParent(), episode.id());
        }
    }

    @Override
    protected BooleanSupplier cancelSignal() {
        return abandoned::get;
    }

    public void abandon() {
        abandoned.set(true);
    }

    public boolean isAbandoned() {
        return abandoned.get();
    }

    private void requireActive() {
        if (abandoned.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Task was abandoned");
        }
    }

    private static void delete(Path path, UUID episodeId) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Failed to delete temporary path {} for episode {}", path, episodeId, e);
        }
    }
}
