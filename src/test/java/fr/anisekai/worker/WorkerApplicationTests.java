package fr.anisekai.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "anisekai.worker.api-url=http://localhost:8080",
        "anisekai.worker.api-key=test-key",
        "anisekai.worker.worker-name=test-worker",
        "anisekai.worker.factories=media:convert",
        "anisekai.worker.poll-interval=10s",
        "anisekai.worker.heartbeat-interval=60s",
        "anisekai.worker.connect-timeout=10s",
        "anisekai.worker.read-timeout=300s",
        "anisekai.worker.max-retries=3",
        "anisekai.worker.retry-base-delay=1s",
        "anisekai.worker.scratch-dir=/tmp/anisekai-worker-test",
        "anisekai.worker.conversion-timeout=3h",
        "anisekai.worker.log-level=INFO"
})
class WorkerApplicationTests {

	@Test
	void contextLoads() {
	}
}