package fr.anisekai.worker.config;

import fr.anisekai.scheduler.commons.interfaces.ObjectSerializer;
import fr.anisekai.worker.client.ApiClient;
import fr.anisekai.worker.engine.WorkerLoop;
import fr.anisekai.worker.factory.RemoteMediaConversionClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkerConfig {

    @Bean
    public ApiClient apiClient(WorkerProperties properties) {
        return new ApiClient(properties);
    }

    @Bean
    public WorkerLoop workerLoop(ApiClient apiClient, WorkerProperties properties) {
        return new WorkerLoop(apiClient, properties);
    }

    @Bean
    public RemoteMediaConversionClientFactory remoteMediaConversionClientFactory(ApiClient apiClient) {
        return new RemoteMediaConversionClientFactory(
                apiClient.getInputSerializer(),
                apiClient.getOutputSerializer()
        );
    }
}