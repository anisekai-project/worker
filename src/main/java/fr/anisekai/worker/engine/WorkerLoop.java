package fr.anisekai.worker.engine;

import fr.anisekai.worker.client.ApiClient;
import fr.anisekai.worker.client.PermanentApiException;
import fr.anisekai.worker.client.TransientApiException;
import fr.anisekai.worker.config.WorkerProperties;
import fr.anisekai.worker.dto.TaskSummary;
import fr.anisekai.worker.dto.WorkerDirective;
import fr.anisekai.worker.dto.WorkerPingResponse;
import fr.anisekai.wireless.tasks.conversion.MediaConversionInput;
import fr.anisekai.wireless.tasks.conversion.MediaConversionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class WorkerLoop implements SmartLifecycle, HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerLoop.class);
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(5);

    private final ApiClient apiClient;
    private final WorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final RemoteMediaConversionHandlerFactory handlerFactory;
    private final WorkerTerminator terminator;
    private final UUID instanceId = UUID.randomUUID();
    private final AtomicReference<ActiveTask> activeTask = new AtomicReference<>();
    private final AtomicBoolean fatal = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();

    private volatile boolean running;
    private volatile boolean shuttingDown;
    private volatile Instant lastHeartbeat;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService deadlineScheduler;
    private ExecutorService taskExecutor;
    private ScheduledFuture<?> scheduledTick;

    public WorkerLoop(ApiClient apiClient, WorkerProperties properties, ObjectMapper objectMapper,
                      RemoteMediaConversionHandlerFactory handlerFactory, WorkerTerminator terminator) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.handlerFactory = handlerFactory;
        this.terminator = terminator;
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }

            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> daemonThread(runnable, "worker-heartbeat"));
            deadlineScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> daemonThread(runnable, "worker-deadline"));
            taskExecutor = Executors.newSingleThreadExecutor(runnable -> daemonThread(runnable, "worker-task"));
            shuttingDown = false;
            fatal.set(false);
            running = true;
            scheduleLocked(INITIAL_DELAY);
        }
        LOG.info("Started worker instance {}", instanceId);
    }

    @Override
    public void stop() {
        ExecutorService executorToStop;
        ScheduledExecutorService schedulerToStop;
        ScheduledExecutorService deadlineSchedulerToStop;

        synchronized (lifecycleLock) {
            if (!running && scheduler == null && deadlineScheduler == null && taskExecutor == null) {
                return;
            }

            running = false;
            shuttingDown = true;
            if (scheduledTick != null) {
                scheduledTick.cancel(false);
                scheduledTick = null;
            }
            schedulerToStop = scheduler;
            deadlineSchedulerToStop = deadlineScheduler;
            executorToStop = taskExecutor;
            scheduler = null;
            deadlineScheduler = null;
            taskExecutor = null;
        }

        ActiveTask task = activeTask.get();
        if (task != null) {
            abandon(task);
        }
        if (schedulerToStop != null) {
            schedulerToStop.shutdownNow();
        }
        if (deadlineSchedulerToStop != null) {
            deadlineSchedulerToStop.shutdownNow();
        }
        if (executorToStop != null) {
            executorToStop.shutdownNow();
            awaitTermination(executorToStop);
        }
        activeTask.set(null);
        LOG.info("Stopped worker instance {}", instanceId);
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    public void tick() {
        if (!running || shuttingDown || fatal.get()) {
            return;
        }

        try {
            ActiveTask task = activeTask.get();
            if (task != null && task.state.get() == ExecutionState.ABANDONED && !task.executionFinished) {
                return;
            }

            UUID currentTaskId = task != null ? task.id : null;
            WorkerPingResponse response = apiClient.ping(
                    instanceId,
                    properties.factories(),
                    properties.workerName(),
                    currentTaskId
            );
            if (response == null) {
                throw new TransientApiException("API returned an empty ping response");
            }
            lastHeartbeat = Instant.now();

            if (response.directive() == WorkerDirective.GIVE_UP_TASK) {
                if (task != null) {
                    LOG.warn("Server released task {}; abandoning local execution", task.id);
                    abandon(task);
                }
                return;
            }

            if (response.hasTask() && response.task() != null) {
                if (task == null) {
                    startTask(response.task(), response.isolationContextId());
                } else if (!task.id.equals(response.task().id())) {
                    LOG.error("Server assigned task {} while task {} is still active",
                            response.task().id(), task.id);
                }
            }
            task = activeTask.get();
            if (task != null && task.pendingCompletion != null) {
                retryCompletionAsync(task);
            }
        } catch (PermanentApiException e) {
            failWorker(e);
        } catch (TransientApiException e) {
            LOG.warn("Transient API error during heartbeat: {}", e.getMessage());
        } catch (Exception e) {
            LOG.error("Unexpected heartbeat failure", e);
        }
    }

    private void startTask(TaskSummary summary, UUID isolationContextId) {
        ActiveTask task = new ActiveTask(summary.id());
        if (!activeTask.compareAndSet(null, task)) {
            return;
        }

        ExecutorService executor = taskExecutor;
        ScheduledExecutorService timeoutScheduler = deadlineScheduler;
        if (executor == null || timeoutScheduler == null) {
            activeTask.compareAndSet(task, null);
            return;
        }

        try {
            task.timeout = timeoutScheduler.schedule(() -> timeout(task),
                    properties.conversionTimeout().toMillis(), TimeUnit.MILLISECONDS);
            executor.submit(() -> runTask(task, summary, isolationContextId));
        } catch (RejectedExecutionException e) {
            cancelTimeout(task);
            activeTask.compareAndSet(task, null);
            if (!shuttingDown) {
                failWorker(e);
            }
        }
    }

    private void runTask(ActiveTask task, TaskSummary summary, UUID isolationContextId) {
        task.workerThread = Thread.currentThread();
        try {
            if (task.state.get() != ExecutionState.RUNNING) {
                return;
            }
            if (!"media:convert".equals(summary.factoryName())) {
                throw new IllegalArgumentException("Unsupported task factory: " + summary.factoryName());
            }
            if (isolationContextId == null) {
                throw new IllegalArgumentException("Conversion task has no isolation context");
            }

            MediaConversionInput input = objectMapper.readValue(summary.arguments(), MediaConversionInput.class);
            RemoteMediaConversionHandler handler = handlerFactory.create(isolationContextId);
            task.handler = handler;
            if (task.state.get() != ExecutionState.RUNNING) {
                handler.abandon();
                throw new CancellationException("Task was cancelled before conversion started");
            }

            LOG.info("Executing task {}", task.id);
            MediaConversionOutput output = handler.handle(input);
            String serializedOutput = objectMapper.writeValueAsString(output);
            if (!task.state.compareAndSet(ExecutionState.RUNNING, ExecutionState.COMPLETED)) {
                throw new CancellationException("Task deadline or abandonment won completion race");
            }
            task.pendingCompletion = PendingCompletion.success(serializedOutput);
        } catch (PermanentApiException e) {
            failWorker(e);
            return;
        } catch (Throwable failure) {
            if (failure instanceof Error error) {
                failWorker(error);
                return;
            }
            ExecutionState state = task.state.get();
            if (state == ExecutionState.ABANDONED) {
                return;
            }
            if (state == ExecutionState.TIMED_OUT) {
                Thread.interrupted();
                task.pendingCompletion = PendingCompletion.failure(
                        "Conversion exceeded timeout " + properties.conversionTimeout());
            } else if (task.state.compareAndSet(ExecutionState.RUNNING, ExecutionState.COMPLETED)) {
                task.pendingCompletion = PendingCompletion.failure(failureMessage(failure));
            }
        } finally {
            task.executionFinished = true;
            task.workerThread = null;
            cancelTimeout(task);
            if (task.state.get() == ExecutionState.ABANDONED) {
                clearTask(task);
            } else if (task.pendingCompletion != null) {
                flushCompletion(task);
            }
        }
    }

    private void flushCompletion(ActiveTask task) {
        PendingCompletion completion = task.pendingCompletion;
        if (completion == null || !task.reporting.compareAndSet(false, true)) {
            return;
        }

        try {
            boolean accepted = completion.success
                    ? apiClient.reportSuccess(instanceId, task.id, completion.payload)
                    : apiClient.reportFailure(instanceId, task.id, completion.payload);
            if (!accepted) {
                LOG.info("Task {} was no longer assigned while reporting completion", task.id);
            }
            clearTask(task);
        } catch (TransientApiException e) {
            LOG.warn("Completion for task {} remains pending: {}", task.id, e.getMessage());
        } catch (PermanentApiException e) {
            failWorker(e);
        } finally {
            task.reporting.set(false);
        }
    }

    private void retryCompletionAsync(ActiveTask task) {
        ExecutorService executor = taskExecutor;
        if (executor == null || task.reporting.get() || !task.completionRetryQueued.compareAndSet(false, true)) {
            return;
        }

        try {
            executor.execute(() -> {
                try {
                    flushCompletion(task);
                } finally {
                    task.completionRetryQueued.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            task.completionRetryQueued.set(false);
            if (!shuttingDown) {
                failWorker(e);
            }
        }
    }

    private void timeout(ActiveTask task) {
        if (activeTask.get() != task || !task.state.compareAndSet(ExecutionState.RUNNING, ExecutionState.TIMED_OUT)) {
            return;
        }

        RemoteMediaConversionHandler handler = task.handler;
        if (handler != null) {
            handler.abandon();
        }
        Thread workerThread = task.workerThread;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    private void abandon(ActiveTask task) {
        task.state.set(ExecutionState.ABANDONED);
        task.pendingCompletion = null;
        cancelTimeout(task);

        RemoteMediaConversionHandler handler = task.handler;
        if (handler != null) {
            handler.abandon();
        }
        Thread workerThread = task.workerThread;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        if (task.executionFinished) {
            clearTask(task);
        }
    }

    private void clearTask(ActiveTask task) {
        cancelTimeout(task);
        if (activeTask.compareAndSet(task, null)) {
            requestTick();
        }
    }

    private static void cancelTimeout(ActiveTask task) {
        ScheduledFuture<?> timeout = task.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    private void failWorker(Throwable cause) {
        if (!fatal.compareAndSet(false, true)) {
            return;
        }

        shuttingDown = true;
        ActiveTask task = activeTask.get();
        if (task != null) {
            abandon(task);
        }
        synchronized (lifecycleLock) {
            if (scheduledTick != null) {
                scheduledTick.cancel(false);
                scheduledTick = null;
            }
        }
        LOG.error("Fatal worker failure", cause);
        terminator.terminate(cause);
    }

    private void scheduledTick() {
        synchronized (lifecycleLock) {
            scheduledTick = null;
        }
        tick();
        synchronized (lifecycleLock) {
            if (running && !shuttingDown && !fatal.get() && scheduledTick == null) {
                scheduleLocked(activeTask.get() == null
                        ? properties.pollInterval()
                        : properties.heartbeatInterval());
            }
        }
    }

    private void requestTick() {
        synchronized (lifecycleLock) {
            if (!running || shuttingDown || fatal.get() || scheduler == null) {
                return;
            }
            if (scheduledTick != null) {
                scheduledTick.cancel(false);
            }
            scheduleLocked(Duration.ZERO);
        }
    }

    private void scheduleLocked(Duration delay) {
        scheduledTick = scheduler.schedule(this::scheduledTick, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void awaitTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("Task executor did not stop within {}", properties.shutdownTimeout());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Health health() {
        if (fatal.get()) {
            return Health.down().withDetail("instanceId", instanceId).build();
        }
        if (!running) {
            return Health.outOfService().withDetail("instanceId", instanceId).build();
        }
        ActiveTask task = activeTask.get();
        return Health.up()
                .withDetail("instanceId", instanceId)
                .withDetail("taskId", task != null ? task.id : "idle")
                .withDetail("lastHeartbeat", lastHeartbeat != null ? lastHeartbeat : "not yet connected")
                .build();
    }

    UUID instanceId() {
        return instanceId;
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        String result = failure.getClass().getSimpleName() + (message != null ? ": " + message : "");
        return result.length() <= 2000 ? result : result.substring(0, 2000);
    }

    private static final class ActiveTask {

        private final UUID id;
        private final AtomicReference<ExecutionState> state = new AtomicReference<>(ExecutionState.RUNNING);
        private final AtomicBoolean reporting = new AtomicBoolean(false);
        private final AtomicBoolean completionRetryQueued = new AtomicBoolean(false);
        private volatile RemoteMediaConversionHandler handler;
        private volatile Thread workerThread;
        private volatile ScheduledFuture<?> timeout;
        private volatile PendingCompletion pendingCompletion;
        private volatile boolean executionFinished;

        private ActiveTask(UUID id) {
            this.id = id;
        }
    }

    private enum ExecutionState {
        RUNNING,
        COMPLETED,
        TIMED_OUT,
        ABANDONED
    }

    private record PendingCompletion(boolean success, String payload) {

        private static PendingCompletion success(String result) {
            return new PendingCompletion(true, result);
        }

        private static PendingCompletion failure(String error) {
            return new PendingCompletion(false, error);
        }
    }
}
