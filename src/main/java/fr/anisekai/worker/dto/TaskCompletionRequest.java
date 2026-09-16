package fr.anisekai.worker.dto;

public record TaskCompletionRequest(
        String result,
        String errorMessage
) {
}