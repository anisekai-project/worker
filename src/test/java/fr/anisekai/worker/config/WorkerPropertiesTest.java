package fr.anisekai.worker.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WorkerPropertiesTest {

    @Test
    void acceptsSupportedConfigurationAndCopiesFactories() {
        List<String> factories = new ArrayList<>(List.of("media:convert"));

        WorkerProperties properties = properties(factories, 3, Duration.ofSeconds(1));
        factories.clear();

        assertThat(properties.factories()).containsExactly("media:convert");
    }

    @Test
    void rejectsMissingOrUnsupportedFactories() {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(List.of(), 3, Duration.ofSeconds(1)))
                .withMessage("At least one worker factory is required");
        assertThatIllegalArgumentException().isThrownBy(
                        () -> properties(List.of("media:convert", "other"), 3, Duration.ofSeconds(1)))
                .withMessage("Only the media:convert factory is supported");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 21})
    void rejectsRetryCountOutsideSupportedRange(int maxRetries) {
        assertThatIllegalArgumentException().isThrownBy(
                        () -> properties(List.of("media:convert"), maxRetries, Duration.ofSeconds(1)))
                .withMessage("maxRetries must be between 0 and 20");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void rejectsNonPositiveDurations(long seconds) {
                assertThatIllegalArgumentException().isThrownBy(
                        () -> properties(List.of("media:convert"), 3, Duration.ofSeconds(seconds)))
                .withMessage("pollInterval must be at least 1 millisecond");
    }

    @Test
    void rejectsSubMillisecondDurations() {
        assertThatIllegalArgumentException().isThrownBy(
                        () -> properties(List.of("media:convert"), 3, Duration.ofNanos(1)))
                .withMessage("pollInterval must be at least 1 millisecond");
    }

    @Test
    void rejectsIntervalsOutsideLeaseSafetyWindow() {
        assertThatIllegalArgumentException().isThrownBy(
                        () -> properties(List.of("media:convert"), 3, Duration.ofSeconds(61)))
                .withMessage("pollInterval must not exceed 60 seconds");
    }

    private static WorkerProperties properties(List<String> factories, int maxRetries, Duration pollInterval) {
        return new WorkerProperties(
                "https://api.example",
                "api-key",
                "worker-a",
                factories,
                pollInterval,
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                maxRetries,
                Duration.ofSeconds(1),
                Path.of("scratch"),
                Duration.ofMinutes(10),
                Duration.ofSeconds(1)
        );
    }
}
