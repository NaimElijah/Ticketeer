package com.ticketing.system.shared.external;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.config.WsepProperties;

/**
 * Thin HTTP client for the WSEP external-systems endpoint. Every operation is a
 * single {@code application/x-www-form-urlencoded} POST to one URL, dispatched
 * by the {@code action_type} field; the server replies with a tiny plain-text
 * body where {@code "-1"} is the universal failure sentinel (callers interpret
 * the body). Only a 2xx reply of at most {@code MAX_RESPONSE_BODY_BYTES} bytes
 * counts as a WSEP reply; anything else is a {@link WsepCommunicationException}.
 * The base URL is https-only, enforced where it binds ({@link WsepProperties}).
 *
 * <p>Shared by {@link WsepPaymentGateway} and {@link WsepTicketIssuer}. Always a
 * bean (it is lightweight) even when the in-process stubs are the active
 * provider — the adapters that use it are property-gated, not this helper.
 */
@Component
@Profile("!test")
public class WsepHttpClient {

    // WSEP replies are an integer or a ticket code (at most 255 characters, the tickets.barcode_value
    // column), so anything past this is not a WSEP reply and is refused rather than buffered.
    static final int MAX_RESPONSE_BODY_BYTES = 4096;
    private static final int FIRST_SUCCESS_STATUS = 200;
    private static final int FIRST_REDIRECT_STATUS = 300;

    private final String baseUrl;
    private final long requestTimeoutSeconds;
    private final int handshakeAttempts;
    private final long handshakeBackoffMs;
    private final HttpClient http;

    public WsepHttpClient(WsepProperties properties) {
        this.baseUrl = properties.baseUrl();
        this.requestTimeoutSeconds = properties.requestTimeoutSeconds();
        this.handshakeAttempts = properties.handshakeAttempts();
        this.handshakeBackoffMs = properties.handshakeBackoffMs();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Starts an ordered form body with {@code action_type} first. */
    public static Form action(String actionType) {
        return new Form().add("action_type", actionType);
    }

    /** A tiny ordered form builder (insertion order preserved for readable request logs). */
    public static final class Form {
        private final Map<String, String> fields = new LinkedHashMap<>();

        public Form add(String key, Object value) {
            fields.put(key, value == null ? "" : String.valueOf(value));
            return this;
        }

        /** Read-only copy of the fields — lets adapters/tests inspect the request without re-encoding. */
        public Map<String, String> fields() {
            return Map.copyOf(fields);
        }

        private String encoded() {
            return fields.entrySet().stream()
                    .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                    .collect(Collectors.joining("&"));
        }

        private static String enc(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }
    }

    /**
     * POSTs the form and returns the trimmed plain-text body of a 2xx reply, so a
     * caller can tell "no WSEP reply" apart from a {@code "-1"} reply.
     *
     * @throws WsepCommunicationException on a transport-level failure, a non-2xx status (whose body is
     *                                    not a WSEP reply and is never read), or a body longer than
     *                                    {@code MAX_RESPONSE_BODY_BYTES} bytes
     */
    public String post(Form form) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.encoded(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (!isSuccess(response.statusCode())) {
                    throw new WsepCommunicationException("WSEP answered HTTP " + response.statusCode());
                }
                return readCapped(body).trim();
            }
        } catch (IOException e) {
            throw new WsepCommunicationException("WSEP request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WsepCommunicationException("WSEP request interrupted", e);
        }
    }

    private static boolean isSuccess(int status) {
        return status >= FIRST_SUCCESS_STATUS && status < FIRST_REDIRECT_STATUS;
    }

    // Reads one byte past the cap, so an oversized body is detected without buffering the rest of it.
    private static String readCapped(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BODY_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BODY_BYTES) {
            throw new WsepCommunicationException(
                    "WSEP response body exceeds " + MAX_RESPONSE_BODY_BYTES + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Like {@link #post}, but retries on any {@link WsepCommunicationException} (a transport failure,
     * a non-2xx status such as a waking host's 503, an oversized body) with a short backoff. Use ONLY for
     * <b>idempotent</b> actions — the reachability {@code handshake}. NEVER for {@code pay} /
     * {@code issue_ticket} / {@code refund}: those are non-idempotent and a blind retry could
     * double-charge or double-issue. The retry lets a cold-starting WSEP endpoint (a free-tier host
     * that sleeps when idle) wake on an early attempt and answer on a later one, instead of failing
     * the market open at boot (#455 / #365).
     */
    public String postWithRetry(Form form) {
        return withRetry(() -> post(form));
    }

    // Bounded retry around a transport call: retries only WsepCommunicationException (no usable WSEP
    // reply), rethrowing the last one after exhausting attempts. Package-private for unit testing.
    String withRetry(Supplier<String> action) {
        int attempts = Math.max(1, handshakeAttempts);
        WsepCommunicationException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return action.get();
            } catch (WsepCommunicationException e) {
                last = e;
                if (attempt < attempts) {
                    sleepBackoff(attempt);
                }
            }
        }
        throw last;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(handshakeBackoffMs * attempt); // linear backoff: 1x, 2x, … gives the host time to wake
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
