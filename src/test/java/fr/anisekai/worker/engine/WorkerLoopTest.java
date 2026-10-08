package fr.anisekai.worker.engine;

import fr.anisekai.worker.client.ApiClient;
import fr.anisekai.worker.client.PermanentApiException;
import fr.anisekai.worker.client.TransientApiException;
import fr.anisekai.worker.config.WorkerProperties;
import fr.anisekai.worker.dto.TaskSummary;
import fr.anisekai.worker.dto.WorkerDirective;
import fr.anisekai.worker.dto.WorkerPingResponse;
import fr.anisekai.wireless.tasks.conversion.MediaConversionInput;
import fr.anisekai.wireless.tasks.conversion.MediaConversionOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerLoopTest {

    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ISOLATION_CONTEXT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String RESULT_JSON = "{\"tracks\":[]}";

    @Mock
    ApiClient apiClient;

    @Mock
    ObjectMapper objectMapper;

    @Mock
    RemoteMediaConversionHandlerFactory handlerFactory;

    @Mock
    RemoteMediaConversionHandler handler;

    @Mock
    WorkerTerminator terminator;

    @Mock
    MediaConversionInput input;

    @Mock
    MediaConversionOutput output;

    private WorkerLoop loop;

    @BeforeEach
    void setUp() {
        loop = newLoop(Duration.ofMinutes(10));
        loop.start();
    }

    private WorkerLoop newLoop(Duration conversionTimeout) {
        WorkerProperties properties = new WorkerProperties(
                "https://api.example",
                "api-key",
                "worker-a",
                List.of("media:convert"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(60),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                0,
                Duration.ofMillis(1),
                Path.of("scratch"),
                conversionTimeout,
                Duration.ofMillis(100)
        );
        return new WorkerLoop(apiClient, properties, objectMapper, handlerFactory, terminator);
    }

    @AfterEach
    void tearDown() {
        loop.stop();
    }

    @Test
    void assignmentExecutesExactlyOnceAndReportsSuccess() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        prepareSuccessfulConversion();
        doAnswer(invocation -> {
            started.countDown();
            release.await();
            return output;
        }).when(handler).handle(input);
        when(apiClient.ping(any(), anyList(), anyString(), any()))
                .thenReturn(assignedResponse(), assignedResponse(), idleResponse());
        when(apiClient.reportSuccess(any(), eq(TASK_ID), eq(RESULT_JSON))).thenReturn(true);

        loop.tick();
        await().atMost(Duration.ofSeconds(1)).until(() -> started.getCount() == 0);
        loop.tick();
        release.countDown();

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                verify(apiClient).reportSuccess(loop.instanceId(), TASK_ID, RESULT_JSON));
        verify(handler, times(1)).handle(input);
    }

    @Test
    void conversionFailureReportsFailure() throws Exception {
        prepareConversionInput();
        when(apiClient.ping(any(), anyList(), anyString(), any()))
                .thenReturn(assignedResponse(), idleResponse());
        when(handler.handle(input)).thenThrow(new IllegalStateException("codec exploded"));
        when(apiClient.reportFailure(any(), eq(TASK_ID), anyString())).thenReturn(true);

        loop.tick();

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                verify(apiClient).reportFailure(
                        loop.instanceId(), TASK_ID, "IllegalStateException: codec exploded"));
    }

    @Test
    void pendingTransientCompletionRetriesWithoutRerunningConversion() throws Exception {
        prepareSuccessfulConversion();
        when(apiClient.ping(any(), anyList(), anyString(), any()))
                .thenReturn(assignedResponse(), idleResponse());
        when(handler.handle(input)).thenReturn(output);
        when(apiClient.reportSuccess(any(), eq(TASK_ID), eq(RESULT_JSON)))
                .thenThrow(new TransientApiException("offline"))
                .thenReturn(true);

        loop.tick();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                verify(apiClient).reportSuccess(loop.instanceId(), TASK_ID, RESULT_JSON));
        loop.tick();

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                verify(apiClient, times(2)).reportSuccess(loop.instanceId(), TASK_ID, RESULT_JSON));
        verify(handler, times(1)).handle(input);
    }

    @Test
    void giveUpDirectiveCancelsActiveConversionWithoutReporting() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        prepareConversionInput();
        doAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return output;
        }).when(handler).handle(input);
        when(apiClient.ping(any(), anyList(), anyString(), any()))
                .thenReturn(assignedResponse(), giveUpResponse());

        loop.tick();
        await().atMost(Duration.ofSeconds(1)).until(() -> started.getCount() == 0);
        loop.tick();

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> verify(handler).abandon());
        verify(apiClient, never()).reportSuccess(any(), any(), anyString());
        verify(apiClient, never()).reportFailure(any(), any(), anyString());
        release.countDown();
    }

    @Test
    void fatalPingInvokesTerminator() {
        PermanentApiException failure = new PermanentApiException("conflict", HttpStatus.CONFLICT);
        when(apiClient.ping(any(), anyList(), anyString(), isNull())).thenThrow(failure);

        loop.tick();

        verify(terminator).terminate(failure);
        assertThat(loop.isRunning()).isTrue();
    }

    @Test
    void conversionTimeoutReportsFailure() throws Exception {
        loop.stop();
        loop = newLoop(Duration.ofMillis(20));
        loop.start();
        CountDownLatch started = new CountDownLatch(1);
        prepareConversionInput();
        doAnswer(invocation -> {
            started.countDown();
            new CountDownLatch(1).await();
            return output;
        }).when(handler).handle(input);
        when(apiClient.ping(any(), anyList(), anyString(), any()))
                .thenReturn(assignedResponse(), idleResponse());
        when(apiClient.reportFailure(any(), eq(TASK_ID), anyString())).thenReturn(true);

        loop.tick();

        await().atMost(Duration.ofSeconds(1)).until(() -> started.getCount() == 0);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                verify(apiClient).reportFailure(
                        loop.instanceId(), TASK_ID, "Conversion exceeded timeout PT0.02S"));
    }

    @Test
    void heartbeatContinuesWhileConversionIsBlocked() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        prepareSuccessfulConversion();
        doAnswer(invocation -> {
            started.countDown();
            release.await();
            return output;
        }).when(handler).handle(input);
        when(apiClient.ping(any(), anyList(), anyString(), any()))
                .thenReturn(assignedResponse(), idleResponse());
        when(apiClient.reportSuccess(any(), eq(TASK_ID), eq(RESULT_JSON))).thenReturn(true);

        loop.tick();
        await().atMost(Duration.ofSeconds(1)).until(() -> started.getCount() == 0);
        loop.tick();

        verify(apiClient).ping(loop.instanceId(), List.of("media:convert"), "worker-a", TASK_ID);
        release.countDown();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                verify(apiClient).reportSuccess(loop.instanceId(), TASK_ID, RESULT_JSON));
    }

    private void prepareConversionInput() throws Exception {
        when(objectMapper.readValue("{}", MediaConversionInput.class)).thenReturn(input);
        when(handlerFactory.create(ISOLATION_CONTEXT_ID)).thenReturn(handler);
    }

    private void prepareSuccessfulConversion() throws Exception {
        prepareConversionInput();
        when(objectMapper.writeValueAsString(output)).thenReturn(RESULT_JSON);
    }

    private static WorkerPingResponse assignedResponse() {
        TaskSummary task = new TaskSummary(TASK_ID, "media:convert", "conversion", null,
                (byte) 0, null, null, null, "{}");
        return new WorkerPingResponse(UUID.randomUUID(), task, true, ISOLATION_CONTEXT_ID, WorkerDirective.NONE);
    }

    private static WorkerPingResponse idleResponse() {
        return new WorkerPingResponse(UUID.randomUUID(), null, false, null, WorkerDirective.NONE);
    }

    private static WorkerPingResponse giveUpResponse() {
        return new WorkerPingResponse(UUID.randomUUID(), null, false, null, WorkerDirective.GIVE_UP_TASK);
    }
}
