package fr.anisekai.worker.config;

import fr.anisekai.worker.client.ApiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class WorkerConfig {

    @Bean
    public ApiClient apiClient(WorkerProperties properties, RestClient.Builder builder) {
        return new ApiClient(properties, builder);
    }
}
