package com.arrowchase.ws;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * One client's outbound messages, written to its socket by its own virtual thread, in the order they were queued.
 *
 * <p>Callers (above all the game thread) only enqueue, so a socket that stops draining blocks its own sender and
 * nobody else. A client whose write has been stuck longer than the time limit, or whose backlog exceeds the buffer
 * limit, is disconnected; it resyncs from the welcome snapshot when it reconnects.
 */
final class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    private final WebSocketSession session;
    private final long sendTimeLimitNanos;
    private final long bufferLimitBytes;
    private final LinkedBlockingQueue<TextMessage> queue = new LinkedBlockingQueue<>();
    private final AtomicLong bufferedBytes = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** When the in-flight write started, or 0 while the sender is idle. */
    private volatile long writeStartedNanos;
    private final Thread sender;

    Outbox(WebSocketSession session, long sendTimeLimitMillis, long bufferLimitBytes) {
        this.session = session;
        this.sendTimeLimitNanos = sendTimeLimitMillis * 1_000_000;
        this.bufferLimitBytes = bufferLimitBytes;
        this.sender = Thread.ofVirtual().name("ws-send-" + session.getId()).start(this::drain);
    }

    /** Queues a message without blocking. Returns false, and closes the session, if the client is too far behind. */
    boolean offer(TextMessage message) {
        if (closed.get()) {
            return false;
        }
        long started = writeStartedNanos;
        boolean stuck = started != 0 && System.nanoTime() - started > sendTimeLimitNanos;
        boolean overflowing = bufferedBytes.get() + message.getPayloadLength() > bufferLimitBytes;
        if (stuck || overflowing) {
            log.debug("Dropping session {}: {}", session.getId(), stuck ? "write stuck" : "backlog too large");
            close(CloseStatus.SESSION_NOT_RELIABLE);
            return false;
        }
        bufferedBytes.addAndGet(message.getPayloadLength());
        queue.add(message);
        return true;
    }

    /** Stops the sender and closes the session. Idempotent; never blocks the caller. */
    void close(CloseStatus status) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        queue.clear();
        sender.interrupt();
        // Closing writes a close frame, which can block on a stuck socket just like any other write.
        Thread.ofVirtual().name("ws-close-" + session.getId()).start(() -> {
            try {
                session.close(status);
            } catch (IOException | RuntimeException ignored) {
                // already gone
            }
        });
    }

    private void drain() {
        try {
            while (!closed.get()) {
                TextMessage message = queue.take();
                writeStartedNanos = System.nanoTime();
                session.sendMessage(message);
                writeStartedNanos = 0;
                bufferedBytes.addAndGet(-message.getPayloadLength());
            }
        } catch (InterruptedException e) {
            // closed
        } catch (IOException | RuntimeException e) {
            if (!closed.get()) {
                log.debug("Dropping session {}: {}", session.getId(), e.getMessage());
                close(CloseStatus.SESSION_NOT_RELIABLE);
            }
        }
    }
}
