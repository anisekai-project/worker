package fr.anisekai.worker.client;

@FunctionalInterface
public interface RunnableWithException {
    void run() throws TransientApiException, PermanentApiException;
}
