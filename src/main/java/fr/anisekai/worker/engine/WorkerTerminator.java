package fr.anisekai.worker.engine;

@FunctionalInterface
public interface WorkerTerminator {

    void terminate(Throwable cause);
}
