package fr.anisekai.worker.engine;

import fr.anisekai.scheduler.commons.interfaces.ObjectSerializer;
import fr.anisekai.scheduler.tasking.data.TaskMeta;
import fr.anisekai.scheduler.tasking.interfaces.factories.ClientFactory;
import fr.anisekai.scheduler.tasking.interfaces.factories.FactoryRegistry;
import fr.anisekai.worker.client.ApiClient;
import fr.anisekai.worker.client.PermanentApiException;
import fr.anisekai.worker.client.TransientApiException;
import fr.anisekai.worker.config.WorkerProperties;
import fr.anisekai.worker.dto.TaskSummary;
import fr.anisekai.worker.dto.WorkerDirective;
import fr.anisekai.worker.dto.WorkerPingRequest;
import fr.anisekai.worker.dto.WorkerPingResponse;
import fr.anisekai.worker.factory.RemoteMediaConversionClientFactory;
import fr.anisekai.wireless.tasks.conversion.MediaConversionInput;
import fr.anisekai.wireless.tasks.conversion.MediaConversionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

@Component
public class WorkerLoop implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerLoop.class);

    private final ApiClient apiClient;
    private final WorkerProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "worker-loop");
        t.setDaemon(true);
        return t;
    });

    private final ObjectSerializer<MediaConversionInput> inputSerializer;
    private final ObjectSerializer<MediaConversionOutput> outputSerializer;

    private volatile UUID currentTaskId = null;
    private volatile RemoteMediaConversionHandler currentHandler = null;
    private volatile boolean running = false;
    private volatile boolean shuttingDown = false;

    private ScheduledFuture<?> scheduledFuture;

    public WorkerLoop(ApiClient apiClient,
                      WorkerProperties properties) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.inputSerializer = apiClient.getInputSerializer();
        this.outputSerializer = apiClient.getOutputSerializer();
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        shuttingDown = false;

        // Initial short delay to allow full startup
        Duration initialDelay = Duration.ofSeconds(5);
        Duration interval = properties.pollInterval();

        LOG.info("Starting worker loop with poll interval {}", interval);
        scheduledFuture = scheduler.scheduleAtFixedRate(this::tick, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (!running) return;
        LOG.info("Stopping worker loop...");
        shuttingDown = true;

        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        scheduler.shutdown();

        // Wait for in-flight task to complete or timeout
        try {
            if (!scheduler.awaitTermination(properties.conversionTimeout().toMinutes() + 5, TimeUnit.MINUTES)) {
                LOG.warn("Worker loop did not terminate gracefully within timeout");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // Start late, stop early
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    public void tick() {
        if (shuttingDown) return;

        try {
            // Determine heartbeat cadence
            Duration heartbeatInterval = currentTaskId != null
                    ? properties.heartbeatInterval()
                    : properties.pollInterval();

            WorkerPingRequest request = new WorkerPingRequest(
                    properties.factories(),
                    properties.workerName(),
                    currentTaskId
            );

            WorkerPingResponse response = apiClient.ping(
                    properties.factories(),
                    properties.workerName(),
                    currentTaskId
            );

            if (response == null) {
                LOG.warn("Received null ping response");
                return;
            }

            // Handle directive
            if (response.directive() == WorkerDirective.GIVE_UP_TASK) {
                LOG.warn("Server sent GIVE_UP_TASK directive, abandoning current task {}", currentTaskId);
                abandonCurrentTask();
                return;
            }

            // Handle task assignment
            if (response.hasTask() && response.task() != null) {
                TaskSummary task = response.task();
                if (currentTaskId == null) {
                    // New task assigned
                    currentTaskId = task.id();
                    executeTask(task, response.isolationContextId());
                } else if (!task.id().equals(currentTaskId)) {
                    // Server assigned a different task - this should not happen with single-slot
                    LOG.warn("Received different task {} while busy with {}", task.id(), currentTaskId);
                    // Ignore - we're single-slot
                }
                // else: same task, just a heartbeat ack
            } else {
                // No task available
                if (currentTaskId == null) {
                    // Truly idle - nothing to do
                }
                // else: we're working on currentTaskId, just heartbeat ack
            }

        } catch (Exception e) {
            if (e instanceof PermanentApiException) {
                LOG.error("Permanent API error, stopping worker: {}", e.getMessage());
                // Re-throw to trigger container restart
                throw e;
            } else if (e instanceof TransientApiException) {
                LOG.warn("Transient API error during tick: {}", e.getMessage());
            } else {
                LOG.error("Unexpected error during tick", e);
            }
        }
    }

    private void executeTask(TaskSummary task, UUID isolationContextId) {
        try {
            // Create handler with isolation context
            RemoteMediaConversionHandler handler = new RemoteMediaConversionHandler(
                    // Need to wire properly
                    null, null, task.id(), isolationContextId);
            currentHandler = handler;
            currentTaskId = task.id();

            // TODO: Actually execute the task using the factory
            // This is a placeholder - the actual execution should use the factory

        } catch (Exception e) {
            LOG.error("Failed to execute task {}", task.id(), e);
            currentTaskId = null;
        }
    }

    private void abandonCurrentTask() {
        if (currentHandler != null) {
            currentHandler.abandon();
            // Wait for the thread to finish
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            currentHandler = null;
            currentTaskId = null;
        }
    }
}