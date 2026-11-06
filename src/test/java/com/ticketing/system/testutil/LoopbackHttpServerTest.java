package com.ticketing.system.testutil;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.testutil.LoopbackHttpServer.RecordedRequest;

/**
 * The request log and the reply queue of {@link LoopbackHttpServer}, which the WSEP client tests rely on to
 * see what was sent and to script several answers. Driven with the JDK's own HTTP client, so a fault in the
 * WSEP client cannot hide a fault here.
 */
class LoopbackHttpServerTest {

    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    // Short, since a silent reply makes the client wait this long; loopback answers far faster.
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(1);
    // Bounds every wait here, well past the client timeout and far short of the server's silence limit.
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    private LoopbackHttpServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        server = LoopbackHttpServer.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    @Test
    void GivenAFormPost_WhenTheServerReceivesIt_ThenItIsRecordedWithMethodPathContentTypeAndBody() throws Exception {
        send(post("wsep", "a=1&b=x%26y"));

        assertEquals(List.of(new RecordedRequest("POST", "/wsep", FORM_CONTENT_TYPE, "a=1&b=x%26y")),
                server.requests(), "the request must be recorded as sent, its body still form-encoded");
    }

    @Test
    void GivenThreeRequests_WhenTheyArriveOneAfterAnother_ThenTheyAreRecordedInArrivalOrder() throws Exception {
        send(post("", "first"));
        send(post("", "second"));
        send(post("", "third"));

        assertEquals(List.of("first", "second", "third"),
                server.requests().stream().map(RecordedRequest::body).toList(),
                "the log must keep every request, in the order it arrived");
    }

    @Test
    void GivenTwoQueuedReplies_WhenThreeRequestsArrive_ThenTheQueuedRepliesComeFirstInOrderAndThenTheDefault()
            throws Exception {
        server.respondWith(200, "default");
        server.enqueue(503, "queued first");
        server.enqueue(201, "queued second");

        List<String> answers = List.of(
                answer(send(post("", "1"))), answer(send(post("", "2"))), answer(send(post("", "3"))));

        assertEquals(List.of("503 queued first", "201 queued second", "200 default"), answers,
                "each queued reply must answer one request, in the order queued, before the default answers");
    }

    @Test
    void GivenAQueuedSilence_WhenARequestArrives_ThenItIsRecordedAndTheClientTimesOutWithoutAnAnswer() {
        server.enqueueSilence();

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> sendAsync(post("", "held")).get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS));

        assertEquals(HttpTimeoutException.class, failure.getCause().getClass(),
                "a silent reply must leave the client waiting until its own timeout, but it got: " + failure.getCause());
        assertEquals(List.of("held"), server.requests().stream().map(RecordedRequest::body).toList(),
                "a held request must still be recorded");
    }

    @Test
    void GivenARequestHeldBySilence_WhenTheServerIsClosed_ThenCloseReturnsWithoutWaitingForTheSilenceLimit() {
        server.enqueueSilence();
        CompletableFuture<HttpResponse<String>> held = sendAsync(post("", "held"));
        await().atMost(WAIT_LIMIT).until(() -> server.requests().size() == 1);

        assertTimeoutPreemptively(WAIT_LIMIT, server::close,
                "close must release a held request, or every test that queues a silence hangs on its teardown");
        assertThrows(ExecutionException.class,
                () -> held.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS),
                "a request released by close was never answered, so it must fail");
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .timeout(CLIENT_TIMEOUT)
                .header("Content-Type", FORM_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest request) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String answer(HttpResponse<String> response) {
        return response.statusCode() + " " + response.body();
    }
}
