package fr.anisekai.worker.engine;

import fr.anisekai.worker.client.ApiClient;
import fr.anisekai.worker.config.WorkerProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RemoteMediaConversionHandlerFactory {

    private final ApiClient apiClient;
    private final WorkerProperties properties;

    public RemoteMediaConversionHandlerFactory(ApiClient apiClient, WorkerProperties properties) {
        this.apiClient = apiClient;
        this.properties = properties;
    }

    public RemoteMediaConversionHandler create(UUID isolationContextId) {
        return new RemoteMediaConversionHandler(apiClient, properties, isolationContextId);
    }
}
