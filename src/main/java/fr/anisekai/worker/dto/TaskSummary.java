package fr.anisekai.worker.dto;

import fr.anisekai.scheduler.tasking.enums.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public record TaskSummary(
        UUID id,
        String factoryName,
        String name,
        TaskStatus status,
        byte priority,
        String activeKey,
        Instant startedAt,
        Instant completedAt,
        String arguments
) {
}