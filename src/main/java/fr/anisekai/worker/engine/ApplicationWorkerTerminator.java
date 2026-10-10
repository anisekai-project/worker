package fr.anisekai.worker.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class ApplicationWorkerTerminator implements WorkerTerminator {

    private final ConfigurableApplicationContext context;

    public ApplicationWorkerTerminator(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void terminate(Throwable cause) {
        AvailabilityChangeEvent.publish(context, cause, LivenessState.BROKEN);

        Thread shutdownThread = new Thread(() -> {
            int exitCode = SpringApplication.exit(context, () -> 1);
            System.exit(exitCode);
        }, "worker-fatal-shutdown");
        shutdownThread.setDaemon(false);
        shutdownThread.start();
    }
}
