package fr.anisekai.worker.client;

import fr.anisekai.worker.config.WorkerProperties;
import fr.anisekai.worker.dto.WorkerDirective;
import fr.anisekai.worker.dto.WorkerPingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ApiClientTest {

    private static final String API_ROOT = "https://api.example/api/v3";
    private static final UUID INSTANCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TASK_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path tempDir;

    private MockRestServiceServer server;
    private ApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(API_ROOT);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ApiClient(properties(tempDir.resolve("scratch")), builder.build());
    }

    @Test
    void pingUsesExactJsonWithStableInstanceIdAndDecodesResponse() {
        String requestJson = """
                {"instanceId":"11111111-1111-1111-1111-111111111111","factoryNames":["media:convert"],"workerName":"worker-a","currentTaskId":null}""";
        String responseJson = """
                {"workerId":"33333333-3333-3333-3333-333333333333","task":null,"hasTask":false,"isolationContextId":null,"directive":"NONE"}""";

        server.expect(twice(), requestTo(API_ROOT + "/workers/ping"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(requestJson))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        WorkerPingResponse first = client.ping(INSTANCE_ID, List.of("media:convert"), "worker-a", null);
        WorkerPingResponse second = client.ping(INSTANCE_ID, List.of("media:convert"), "worker-a", null);

        assertThat(first.workerId()).isEqualTo(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        assertThat(first.directive()).isEqualTo(WorkerDirective.NONE);
        assertThat(second).isEqualTo(first);
        server.verify();
    }

    @Test
    void uploadUsesPutWithBinaryBodyAndRequiredHeaders() throws Exception {
        UUID episodeId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID isolationContextId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        byte[] media = {0, 1, 2, 3, -1};
        Path file = tempDir.resolve("output.mkv");
        Files.write(file, media);

        server.expect(requestTo(API_ROOT + "/library/episodes/" + episodeId))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Content-Type", "video/x-matroska"))
                .andExpect(header("Content-Length", Integer.toString(media.length)))
                .andExpect(header("X-Isolation-Context", isolationContextId.toString()))
                .andExpect(content().bytes(media))
                .andRespond(withNoContent());

        client.uploadEpisode(episodeId, isolationContextId, file);

        server.verify();
    }

    @Test
    void downloadWritesBinaryResponseIntoNewScratchDirectory() throws Exception {
        byte[] media = {0, -1, 10, 42};
        Path scratch = tempDir.resolve("scratch");
        server.expect(requestTo(API_ROOT + "/library/imports/folder/source.mkv"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andRespond(withSuccess(media, MediaType.APPLICATION_OCTET_STREAM));

        Path result = client.downloadSource("IMPORTS/folder/source.mkv");

        assertThat(scratch).isDirectory();
        assertThat(result).isRegularFile();
        assertThat(result.getParent()).isEqualTo(scratch);
        assertThat(result.getFileName().toString()).endsWith(".source");
        assertThat(Files.readAllBytes(result)).containsExactly(media);
        server.verify();
    }

    @Test
    void downloadTreatsNotFoundAsTaskError() {
        server.expect(requestTo(API_ROOT + "/library/downloads/missing.mkv"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("missing"));

        assertThatThrownBy(() -> client.downloadSource("DOWNLOADS/missing.mkv"))
                .isInstanceOf(TaskApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
        server.verify();
    }

    @Test
    void downloadTreatsUnprocessableEntityAsTaskError() {
        server.expect(requestTo(API_ROOT + "/library/downloads/pending.mkv"))
                .andRespond(withStatus(HttpStatusCode.valueOf(422)).body("pending"));

        assertThatThrownBy(() -> client.downloadSource("DOWNLOADS/pending.mkv"))
                .isInstanceOf(TaskApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatusCode.valueOf(422));
        server.verify();
    }

    @Test
    void completionNotFoundReturnsFalse() {
        server.expect(requestTo(API_ROOT + "/workers/" + TASK_ID + "/success"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string("""
                        {"instanceId":"11111111-1111-1111-1111-111111111111","result":"result-json","errorMessage":null}"""))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.reportSuccess(INSTANCE_ID, TASK_ID, "result-json")).isFalse();
        server.verify();
    }

    @Test
    void completionConflictIsFatal() {
        server.expect(requestTo(API_ROOT + "/workers/" + TASK_ID + "/failure"))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("different instance"));

        assertThatThrownBy(() -> client.reportFailure(INSTANCE_ID, TASK_ID, "failed"))
                .isInstanceOf(PermanentApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
        server.verify();
    }

    @Test
    void pingConflictIsRetryable() {
        server.expect(requestTo(API_ROOT + "/workers/ping"))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("active lease"));

        assertThatThrownBy(() -> client.ping(INSTANCE_ID, List.of("media:convert"), "worker-a", null))
                .isInstanceOf(TransientApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
        server.verify();
    }

    private static WorkerProperties properties(Path scratchDir) {
        return new WorkerProperties(
                "https://api.example",
                "api-key",
                "worker-a",
                List.of("media:convert"),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                0,
                Duration.ofMillis(1),
                scratchDir,
                Duration.ofMinutes(10),
                Duration.ofSeconds(1)
        );
    }
}
