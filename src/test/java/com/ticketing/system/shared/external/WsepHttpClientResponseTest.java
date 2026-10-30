package com.ticketing.system.shared.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.shared.config.WsepProperties;
import com.ticketing.system.testutil.LoopbackHttpServer;

/**
 * How {@link WsepHttpClient#post} treats the HTTP response, driven through real HTTP against an
 * in-process loopback server (#185). WSEP's verdict lives in a 2xx body; any other status means the
 * endpoint, a proxy or a load balancer answered instead, and its body is not a WSEP reply. A body far
 * larger than any WSEP reply is refused rather than buffered.
 */
class WsepHttpClientResponseTest {

    private static final long REQUEST_TIMEOUT_SECONDS = 5;
    private static final int SINGLE_ATTEMPT = 1;
    private static final long NO_BACKOFF_MS = 0;

    // The longest legitimate reply: a ticket code filling tickets.barcode_value (varchar 255, V1 migration).
    private static final int LONGEST_LEGITIMATE_REPLY = 255;
    // Far past any WSEP reply; the client's named cap must sit below it.
    private static final int OVERSIZED_REPLY_BYTES = 1024 * 1024;

    private LoopbackHttpServer server;
    private WsepHttpClient client;

    @BeforeEach
    void startServer() {
        server = LoopbackHttpServer.start();
        client = new WsepHttpClient(new WsepProperties(
                server.baseUrl(), REQUEST_TIMEOUT_SECONDS, SINGLE_ATTEMPT, NO_BACKOFF_MS));
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {200, 201, 299})
    void Given2xxStatus_WhenPost_ThenReturnsTheTrimmedBody(int status) {
        server.respondWith(status, " 54321 \n");

        assertEquals("54321", client.post(WsepHttpClient.action("pay")),
                "a 2xx reply is WSEP's answer and its trimmed body must be returned");
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {300, 302, 400, 404, 429, 500, 502, 503})
    void GivenNon2xxStatus_WhenPost_ThenThrowsWsepCommunicationException(int status) {
        server.respondWith(status, "Service Unavailable");

        assertThrows(WsepCommunicationException.class, () -> client.post(WsepHttpClient.action("pay")),
                "HTTP " + status + " is not a WSEP reply, so its body must never be handed to a caller");
    }

    @Test
    void GivenBodyAsLongAsTheLongestTicketCode_WhenPost_ThenReturnsIt() {
        String longestCode = "T".repeat(LONGEST_LEGITIMATE_REPLY);
        server.respondWith(200, longestCode);

        assertEquals(longestCode, client.post(WsepHttpClient.action("issue_ticket")),
                "the body cap must leave room for the longest ticket code the schema stores");
    }

    @Test
    void GivenBodyOverTheCap_WhenPost_ThenThrowsWsepCommunicationException() {
        byte[] oversized = new byte[OVERSIZED_REPLY_BYTES];
        Arrays.fill(oversized, (byte) 'x');
        server.respondWith(200, oversized);

        assertThrows(WsepCommunicationException.class, () -> client.post(WsepHttpClient.action("issue_ticket")),
                "a " + OVERSIZED_REPLY_BYTES + "-byte body is no WSEP reply and must be refused, not buffered");
    }
}
