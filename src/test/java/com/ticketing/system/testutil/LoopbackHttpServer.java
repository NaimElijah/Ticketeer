package com.ticketing.system.testutil;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

/**
 * An in-process HTTP server on the loopback interface and an ephemeral port, answering every request
 * with one canned status and body. It lets a test drive the real {@code WsepHttpClient} through real
 * HTTP without any network access: nothing leaves the JVM's host, and the port is picked by the OS so
 * parallel forks never collide.
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
    // IPv4 loopback, fixed so the base URL needs no IPv6 brackets whatever the JVM prefers.
    private static final byte[] IPV4_LOOPBACK = {127, 0, 0, 1};

    private final HttpServer server;
    private volatile int status = 200;
    private volatile byte[] body = new byte[0];

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
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] payload = loopback.body;
                int code = loopback.status;
                boolean empty = payload.length == 0 || code == NO_CONTENT;
                exchange.sendResponseHeaders(code, empty ? NO_BODY_LENGTH : payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    if (!empty) {
                        out.write(payload);
                    }
                }
            });
            server.start();
            return loopback;
        } catch (IOException e) {
            throw new UncheckedIOException("could not bind a loopback HTTP server", e);
        }
    }

    /** Makes every later request answer with {@code status} and the UTF-8 {@code body}. */
    public void respondWith(int status, String body) {
        respondWith(status, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Makes every later request answer with {@code status} and the raw {@code body}. */
    public void respondWith(int status, byte[] body) {
        this.body = body.clone();
        this.status = status;
    }

    /** The URL the server answers on, with a trailing slash, e.g. {@code http://127.0.0.1:54321/}. */
    public String baseUrl() {
        InetSocketAddress address = server.getAddress();
        return "http://" + address.getAddress().getHostAddress() + ":" + address.getPort() + "/";
    }

    @Override
    public void close() {
        server.stop(STOP_DELAY_SECONDS);
    }
}
