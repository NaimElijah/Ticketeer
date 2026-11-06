package com.ticketing.system.shared.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.config.WsepProperties;
import com.ticketing.system.testutil.LoopbackHttpServer;
import com.ticketing.system.testutil.LoopbackHttpServer.RecordedRequest;

/**
 * What {@link WsepHttpClient} puts on the wire, how often, and how it fails when no reply comes back, seen
 * from an in-process loopback server that records every request (#189). The gateways read the failure's cause
 * to tell a refund that never left from one whose outcome is unknown, so the cause is part of the contract.
 */
class WsepHttpClientWireTest {

    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final long REQUEST_TIMEOUT_SECONDS = 5;
    // The shortest timeout the properties can express (whole seconds), for the server that never answers.
    private static final long SHORT_REQUEST_TIMEOUT_SECONDS = 1;
    private static final int SINGLE_ATTEMPT = 1;
    // More than one, so a post that retried would show more than one request.
    private static final int HANDSHAKE_ATTEMPTS = 3;
    private static final long NO_BACKOFF_MS = 0;
    // Bounds every call that waits on the network: past the short timeout, and past the two seconds or so
    // Windows takes to report a refused loopback connection.
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(15);

    private LoopbackHttpServer server;

    @BeforeEach
    void startServer() {
        server = LoopbackHttpServer.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void GivenFieldsWithReservedCharacters_WhenPost_ThenTheBodyIsFormEncodedAndActionTypeComesFirst() {
        server.respondWith(200, "54321");
        WsepHttpClient.Form form = WsepHttpClient.action("pay")
                .add("holder", "Ann & Bo=Co+Op 100% \u00e9")
                .add("amount", 100.0);

        client(REQUEST_TIMEOUT_SECONDS, SINGLE_ATTEMPT).post(form);

        assertEquals(List.of(new RecordedRequest("POST", "/", FORM_CONTENT_TYPE,
                        "action_type=pay&holder=Ann+%26+Bo%3DCo%2BOp+100%25+%C3%A9&amount=100.0")),
                server.requests(),
                "each value must be form-encoded, so a reserved character cannot split or add a field, and the"
                        + " fields must go in the order added, action_type first");
    }

    @Test
    void GivenA503_WhenPost_ThenExactlyOneRequestIsSent() {
        server.respondWith(503, "Service Unavailable");
        WsepHttpClient client = client(REQUEST_TIMEOUT_SECONDS, HANDSHAKE_ATTEMPTS);

        assertThrows(WsepCommunicationException.class,
                () -> client.post(WsepHttpClient.action("refund").add("transaction_id", 54321)));

        assertEquals(1, server.requests().size(),
                "post serves pay, issue_ticket and refund, which are not idempotent, so it must never send twice,"
                        + " even with handshake retries configured");
    }

    @Test
    void GivenA503ThenA200_WhenPostWithRetry_ThenReturnsTheSecondReply() {
        server.enqueue(503, "Service Unavailable");
        server.enqueue(200, "OK");

        String reply = client(REQUEST_TIMEOUT_SECONDS, HANDSHAKE_ATTEMPTS)
                .postWithRetry(WsepHttpClient.action("handshake"));

        assertEquals("OK", reply, "a waking host's 503 must be retried, and the later answer returned");
        assertEquals(2, server.requests().size(), "one retry after the 503, and none after the answer");
    }

    @Test
    void GivenAStoppedServer_WhenPost_ThenThrowsWithTheCause() {
        WsepHttpClient client = client(REQUEST_TIMEOUT_SECONDS, SINGLE_ATTEMPT);
        server.close();

        WsepCommunicationException failure = assertTimeoutPreemptively(WAIT_LIMIT, () -> assertThrows(
                WsepCommunicationException.class, () -> client.post(WsepHttpClient.action("refund"))));

        assertInstanceOf(ConnectException.class, failure.getCause(),
                "a refused connection must travel as the cause, since it tells a refund that never left apart"
                        + " from one whose outcome is unknown; got " + failure.getCause());
    }

    @Test
    void GivenASilentServer_WhenPost_ThenTimesOut() {
        server.enqueueSilence();
        WsepHttpClient client = client(SHORT_REQUEST_TIMEOUT_SECONDS, SINGLE_ATTEMPT);

        WsepCommunicationException failure = assertTimeoutPreemptively(WAIT_LIMIT, () -> assertThrows(
                WsepCommunicationException.class, () -> client.post(WsepHttpClient.action("refund"))));

        assertInstanceOf(HttpTimeoutException.class, failure.getCause(),
                "a server that never answers must time out after the configured request timeout; got "
                        + failure.getCause());
        assertFalse(failure.getCause() instanceof HttpConnectTimeoutException,
                "the request was sent, so this is a read timeout, whose refund outcome is unknown, not a connect"
                        + " timeout");
    }

    @Test
    void GivenAnInterruptedThread_WhenPost_ThenTheFlagIsRestored() {
        // A silent server, so no answer can arrive before the interrupt is noticed.
        server.enqueueSilence();
        WsepHttpClient client = client(SHORT_REQUEST_TIMEOUT_SECONDS, SINGLE_ATTEMPT);

        assertTimeoutPreemptively(WAIT_LIMIT, () -> {
            Thread.currentThread().interrupt();

            WsepCommunicationException failure = assertThrows(WsepCommunicationException.class,
                    () -> client.post(WsepHttpClient.action("handshake")));

            assertTrue(Thread.interrupted(),
                    "post must restore the interrupt it consumed, so the caller's thread can still stop");
            assertInstanceOf(InterruptedException.class, failure.getCause(),
                    "the interrupt must be the cause; got " + failure.getCause());
        });
    }

    private WsepHttpClient client(long requestTimeoutSeconds, int handshakeAttempts) {
        return new WsepHttpClient(new WsepProperties(
                server.baseUrl(), requestTimeoutSeconds, handshakeAttempts, NO_BACKOFF_MS));
    }
}
