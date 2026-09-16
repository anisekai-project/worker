package fr.anisekai.worker.client;

import org.springframework.http.HttpStatusCode;

public class PermanentApiException extends RuntimeException {
    private final HttpStatusCode status;

    public PermanentApiException(String message, HttpStatusCode status) {
        super(message);
        this.status = status;
    }

    public HttpStatusCode getStatus() {
        return status;
    }
}
