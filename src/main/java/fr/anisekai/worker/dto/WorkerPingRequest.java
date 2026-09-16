package fr.anisekai.worker.dto;

import java.util.List;
import java.util.UUID;

public record WorkerPingRequest(
        List<String> factoryNames,
        String workerName,
        UUID currentTaskId
) {
}