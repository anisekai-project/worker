package fr.anisekai.worker.dto;

import fr.anisekai.scheduler.tasking.enums.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public record WorkerPingResponse(
        UUID workerId,
        TaskSummary task,
        boolean hasTask,
        UUID isolationContextId,
        WorkerDirective directive
) {
}