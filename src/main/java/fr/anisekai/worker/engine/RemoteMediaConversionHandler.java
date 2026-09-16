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
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.HexFormat;
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
    private final UUID episodeId;
    private final UUID isolationContextId;
    private final AtomicBoolean abandoned = new AtomicBoolean(false);

    public RemoteMediaConversionHandler(ApiClient apiClient, WorkerProperties properties, UUID episodeId, UUID isolationContextId) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.episodeId = episodeId;
        this.isolationContextId = isolationContextId;
    }

    @Override
    public Path fetchEpisode(MediaConversionInput.Episode episode) throws Exception {
        String sourceRef = episode.source().store().name() + "/" + episode.source().reference();
        Path sourcePath = apiClient.downloadSource(sourceRef);

        String expectedHash = episode.hash();
        String actualHash = sha256(sourcePath);
        if (!expectedHash.equalsIgnoreCase(actualHash)) {
            throw new IllegalStateException("SHA-256 mismatch for " + sourceRef + ": expected " + expectedHash + ", got " + actualHash);
        }
        return sourcePath;
    }

    @Override
    public Path getStoragePath(MediaConversionInput.Episode episode, Path source) throws Exception {
        Path tempDir = properties.scratchDir().toAbsolutePath();
        Files.createDirectories(tempDir);
        return Files.createTempFile(tempDir, "convert-", ".mkv");
    }

    @Override
    public void pushEpisode(MediaConversionInput.Episode episode, Path destination) throws Exception {
        apiClient.uploadEpisode(episode.id(), isolationContextId, destination);
    }

    @Override
    public void cleanupEpisode(MediaConversionInput.Episode episode, Path source, Path destination) throws Exception {
        try {
            if (source != null && Files.exists(source)) {
                Files.deleteIfExists(source);
            }
            if (destination != null && Files.exists(destination)) {
                Files.deleteIfExists(destination);
            }
        } catch (IOException e) {
            LOG.warn("Failed to cleanup temporary files for episode {}", episode.id(), e);
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

    // SHA-256 utility method (mirrors parent class logic)
    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path);
                 var digestInput = new DigestInputStream(input, digest)) {
                digestInput.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest()).toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}