package fr.anisekai.worker.dto;

import java.util.UUID;

public record TaskCompletionRequest(
        UUID instanceId,
        String result,
        String errorMessage
) {
}
