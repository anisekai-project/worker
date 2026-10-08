package fr.anisekai.worker.client;

import org.springframework.http.HttpStatusCode;

public class TaskApiException extends RuntimeException {

    private final HttpStatusCode status;

    public TaskApiException(String message, HttpStatusCode status) {
        super(message);
        this.status = status;
    }

    public HttpStatusCode getStatus() {
        return status;
    }
}
