package com.ticketing.system.testutil;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * An in-process HTTP server on the loopback interface and an ephemeral port. It lets a test drive the real
 * {@code WsepHttpClient} through real HTTP without any network access: nothing leaves the JVM's host, and the
 * port is picked by the OS so parallel forks never collide.
 *
 * <p>It records every request it receives ({@link #requests()}), so a test can check what was sent and how
 * often. It answers each request with the next queued reply ({@link #enqueue}, {@link #enqueueSilence()}), and
 * once the queue is empty with the default reply ({@link #respondWith}), an empty 200 until one is set.
 * A recorded body prints in full in a failed assertion, and so reaches the surefire report and the CI
 * artifact uploaded from a public repository: send only test card numbers through it.
 *
 * <p>Plain HTTP only: the client builds its own {@code HttpClient}, so there is no seam to make it
 * trust a self-signed certificate. Construct the client from a {@code WsepProperties} record directly
 * (records are not validated on construction) rather than through Spring binding.
 */
public final class LoopbackHttpServer implements AutoCloseable {

    // A status the JDK server treats as "no body": it must be sent with a length of -1.
    private static final int NO_CONTENT = 204;
    private static final int NO_BODY_LENGTH = -1;
    private static final int EPHEMERAL_PORT = 0;
    private static final int DEFAULT_BACKLOG = 0;
    private static final int STOP_DELAY_SECONDS = 0;
    // How long a silent reply holds its request if the server is never closed; close() releases it at once.
    private static final long SILENCE_LIMIT_SECONDS = 60;
    // IPv4 loopback, fixed so the base URL needs no IPv6 brackets whatever the JVM prefers.
    private static final byte[] IPV4_LOOPBACK = {127, 0, 0, 1};

    /**
     * One request as the server received it.
     *
     * @param method      the HTTP method, e.g. {@code POST}
     * @param path        the request path, e.g. {@code /}
     * @param contentType the {@code Content-Type} header, or {@code null} if none was sent
     * @param body        the request body, decoded as UTF-8 but otherwise exactly as sent (still form-encoded)
     */
    public record RecordedRequest(String method, String path, String contentType, String body) {
    }

    private record Reply(int status, byte[] body, boolean silent) {

        static final Reply SILENCE = new Reply(NO_CONTENT, new byte[0], true);

        static Reply of(int status, byte[] body) {
            return new Reply(status, body.clone(), false);
        }
    }

    private final HttpServer server;
    private final Queue<Reply> queued = new ConcurrentLinkedQueue<>();
    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile Reply defaultReply = Reply.of(200, new byte[0]);

    private LoopbackHttpServer(HttpServer server) {
        this.server = server;
    }

    /**
     * Starts a server bound to the loopback address on a free port.
     *
     * @return the started server; close it to release the port
     * @throws UncheckedIOException if the socket cannot be bound
     */
    public static LoopbackHttpServer start() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByAddress(IPV4_LOOPBACK), EPHEMERAL_PORT), DEFAULT_BACKLOG);
            LoopbackHttpServer loopback = new LoopbackHttpServer(server);
            server.createContext("/", loopback::handle);
            server.start();
            return loopback;
        } catch (IOException e) {
            throw new UncheckedIOException("could not bind a loopback HTTP server", e);
        }
    }

    /** Makes every later request that no queued reply answers get {@code status} and the UTF-8 {@code body}. */
    public void respondWith(int status, String body) {
        respondWith(status, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Makes every later request that no queued reply answers get {@code status} and the raw {@code body}. */
    public void respondWith(int status, byte[] body) {
        this.defaultReply = Reply.of(status, body);
    }

    /** Queues a reply: requests take the queued replies in the order they were queued, before the default. */
    public void enqueue(int status, String body) {
        queued.add(Reply.of(status, body.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Queues a reply that never comes: the request that takes it is recorded and then held, unanswered, until
     * the server is closed. Requests are handled one at a time, so while it is held no other request is
     * answered either.
     */
    public void enqueueSilence() {
        queued.add(Reply.SILENCE);
    }

    /** The requests received so far, in the order they arrived. */
    public List<RecordedRequest> requests() {
        return List.copyOf(received);
    }

    /** The URL the server answers on, with a trailing slash, e.g. {@code http://127.0.0.1:54321/}. */
    public String baseUrl() {
        InetSocketAddress address = server.getAddress();
        return "http://" + address.getAddress().getHostAddress() + ":" + address.getPort() + "/";
    }

    /** Releases any request held by a silent reply, then stops the server and frees its port. */
    @Override
    public void close() {
        closed.countDown();
        server.stop(STOP_DELAY_SECONDS);
    }

    // The request is recorded before it is answered, so a client that has its answer finds it in the log.
    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
        Reply reply = Objects.requireNonNullElse(queued.poll(), defaultReply);
        if (reply.silent()) {
            holdUntilClosed();
            exchange.close();
            return;
        }
        boolean empty = reply.body().length == 0 || reply.status() == NO_CONTENT;
        exchange.sendResponseHeaders(reply.status(), empty ? NO_BODY_LENGTH : reply.body().length);
        try (OutputStream out = exchange.getResponseBody()) {
            if (!empty) {
                out.write(reply.body());
            }
        }
    }

    // Bounded, so a test that forgets to close the server holds its request, and blocks every later one, for a
    // minute rather than for the whole run.
    private void holdUntilClosed() {
        try {
            closed.await(SILENCE_LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
