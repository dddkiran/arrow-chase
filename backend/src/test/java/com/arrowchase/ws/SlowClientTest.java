package com.arrowchase.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A client whose connection stops draining (a phone in a tunnel, or a client that never reads) must not stall the
 * game for everyone else. Ticks are fast and the board large so the stuck socket's buffers fill in seconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "game.step-millis=5",
        "game.rows=24",
        "game.cols=24",
        "game.seed=1"})
class SlowClientTest {

    private static final Duration OBSERVE = Duration.ofSeconds(10);
    private static final long MAX_ACCEPTABLE_GAP_MS = 2_000;

    @LocalServerPort
    int port;

    @Test
    void stuckClientDoesNotStallTheGameForOthers() throws Exception {
        List<Long> arrivals = new CopyOnWriteArrayList<>();
        WebSocket healthy = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "http://localhost:" + port)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?playerId=healthy-client-1"),
                        new StepRecorder(arrivals))
                .join();

        try (Socket stuck = openAndNeverRead()) {
            Thread.sleep(OBSERVE);
            long maxGapMs = maxGapMs(arrivals, System.nanoTime());
            assertThat(arrivals).as("healthy client received steps").isNotEmpty();
            assertThat(maxGapMs)
                    .as("longest pause between the %d steps the healthy client saw, in ms", arrivals.size())
                    .isLessThan(MAX_ACCEPTABLE_GAP_MS);
        } finally {
            healthy.abort();
        }
    }

    /** Completes the WebSocket handshake by hand with a tiny receive window, then never reads again. */
    private Socket openAndNeverRead() throws Exception {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(1024);
        socket.connect(new InetSocketAddress("localhost", port));
        String key = Base64.getEncoder().encodeToString("stuck-client-key".getBytes(StandardCharsets.US_ASCII));
        String request = "GET /ws?playerId=stuck-client-1 HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Origin: http://localhost:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        // Read just the 101 response headers, one byte at a time so nothing past them is consumed.
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new IllegalStateException("handshake closed: " + head);
            }
            head.append((char) b);
        }
        assertThat(head.toString()).startsWith("HTTP/1.1 101");
        return socket;
    }

    private static long maxGapMs(List<Long> arrivalsNanos, long nowNanos) {
        long max = 0;
        long prev = arrivalsNanos.getFirst();
        for (long t : arrivalsNanos) {
            max = Math.max(max, t - prev);
            prev = t;
        }
        max = Math.max(max, nowNanos - prev);
        return max / 1_000_000;
    }

    /** Records the arrival time of every step message. Large messages can arrive in several parts. */
    private static final class StepRecorder implements WebSocket.Listener {
        private final List<Long> arrivals;
        private boolean atMessageStart = true;

        StepRecorder(List<Long> arrivals) {
            this.arrivals = arrivals;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (atMessageStart && data.toString().startsWith("{\"type\":\"step\"")) {
                arrivals.add(System.nanoTime());
            }
            atMessageStart = last;
            ws.request(1);
            return null;
        }
    }
}
