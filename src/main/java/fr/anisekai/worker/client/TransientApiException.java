package fr.anisekai.worker.client;

import org.springframework.http.HttpStatusCode;

public class TransientApiException extends RuntimeException {

    private final HttpStatusCode status;

    public TransientApiException(String message, HttpStatusCode status) {
        super(message);
        this.status = status;
    }

    public TransientApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = null;
    }

    public TransientApiException(String message) {
        super(message);
        this.status = null;
    }

    public HttpStatusCode getStatus() {
        return status;
    }
}
