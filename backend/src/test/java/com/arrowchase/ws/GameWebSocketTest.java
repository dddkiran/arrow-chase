package com.arrowchase.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Drives the real server over a real WebSocket: join, see the board, place a bet, get it locked, see it settled.
 * A fast tick keeps the whole thing to a couple of seconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"game.step-millis=40", "game.seed=42"})
class GameWebSocketTest {

    private static final long TIMEOUT_MS = 5_000;

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper();
    private TestClient client;

    @AfterEach
    void close() throws Exception {
        if (client != null) {
            client.session.close();
        }
    }

    @Test
    void a_player_can_join_bet_see_the_lock_and_see_the_bet_settle() throws Exception {
        client = connect("it-player-0001", "Tester");

        JsonNode welcome = client.await(m -> type(m, "welcome"));
        long startBalance = welcome.get("balanceCents").asLong();
        assertThat(welcome.at("/game/rows").asInt()).isEqualTo(8);
        assertThat(welcome.at("/state/multipliersX100")).hasSize(8);

        JsonNode locked = placeBetOnAnOpenBlock("req-1", 500);
        JsonNode bet = locked.get("bet");
        assertThat(bet.get("status").asText()).isEqualTo("OPEN");
        assertThat(bet.get("lockedMultiplierX100").asInt()).isGreaterThanOrEqualTo(105);
        assertThat(locked.get("balanceCents").asLong()).isEqualTo(startBalance - 500);

        String betId = bet.get("id").asText();
        JsonNode settled = client.await(m -> type(m, "betSettled") && m.at("/bet/id").asText().equals(betId));
        String status = settled.at("/bet/status").asText();
        assertThat(status).isIn("WON", "LOST");
        long expectedPayout = "WON".equals(status) ? 500L * bet.get("lockedMultiplierX100").asInt() / 100 : 0;
        assertThat(settled.at("/bet/payoutCents").asLong()).isEqualTo(expectedPayout);
        assertThat(settled.at("/bet/lockedMultiplierX100").asInt())
                .isEqualTo(bet.get("lockedMultiplierX100").asInt());
    }

    @Test
    void a_retried_request_is_not_charged_twice() throws Exception {
        client = connect("it-player-0002", "Retry");
        long startBalance = client.await(m -> type(m, "welcome")).get("balanceCents").asLong();

        JsonNode first = placeBetOnAnOpenBlock("dup-1", 300);
        JsonNode bet = first.get("bet");
        client.send(placeBet("dup-1", bet.get("row").asInt(), bet.get("col").asInt(), 300));
        JsonNode second = client.await(m -> type(m, "betLocked"));

        assertThat(second.at("/bet/id").asText()).isEqualTo(bet.get("id").asText());
        assertThat(second.get("balanceCents").asLong()).isEqualTo(startBalance - 300);
    }

    @Test
    void reconnecting_restores_balance_and_open_bets() throws Exception {
        client = connect("it-player-0003", "Comeback");
        client.await(m -> type(m, "welcome"));
        JsonNode locked = placeBetOnAnOpenBlock("rc-1", 1_000);
        client.session.close();

        client = connect("it-player-0003", "Comeback");
        JsonNode welcome = client.await(m -> type(m, "welcome"));
        long balanceNow = welcome.get("balanceCents").asLong();
        boolean stillOpen = welcome.get("openBets").findValuesAsText("id").contains(locked.at("/bet/id").asText());
        boolean alreadySettled = welcome.get("recentBets").findValuesAsText("id")
                .contains(locked.at("/bet/id").asText());
        assertThat(stillOpen || alreadySettled).isTrue();
        assertThat(balanceNow).isLessThanOrEqualTo(locked.get("balanceCents").asLong()
                + 1_000L * locked.at("/bet/lockedMultiplierX100").asInt() / 100);
    }

    @Test
    void the_server_rejects_a_connection_without_a_valid_player_id() throws Exception {
        client = connect("bad", "x");
        assertThat(client.closed.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void the_server_refuses_browsers_from_origins_it_does_not_trust() {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("https://evil.example");
        URI uri = URI.create("ws://localhost:" + port + "/ws?playerId=it-player-0004&name=x");

        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new TextWebSocketHandler(), headers, uri)
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void the_server_accepts_browsers_from_allowed_origins() throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:5173");
        URI uri = URI.create("ws://localhost:" + port + "/ws?playerId=it-player-0005&name=x");

        WebSocketSession session = new StandardWebSocketClient()
                .execute(new TextWebSocketHandler(), headers, uri)
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(session.isOpen()).isTrue();
        session.close();
    }

    /** The board moves every 40 ms here, so a block can close between reading and betting. Retry a few times. */
    private JsonNode placeBetOnAnOpenBlock(String requestIdPrefix, long stake) throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            JsonNode step = client.await(m -> type(m, "step"));
            JsonNode grid = step.get("multipliersX100");
            int[] cell = firstOpen(grid);
            String requestId = attempt == 0 ? requestIdPrefix : requestIdPrefix + "-" + attempt;
            client.send(placeBet(requestId, cell[0], cell[1], stake));
            JsonNode reply = client.await(m -> (type(m, "betLocked") || type(m, "betRejected"))
                    && requestId.equals(m.has("bet") ? m.at("/bet/requestId").asText() : m.path("requestId").asText()));
            if (type(reply, "betLocked")) {
                return reply;
            }
            assertThat(reply.get("reason").asText()).isEqualTo("CELL_CLOSED");
        }
        throw new AssertionError("could not get a bet accepted");
    }

    private static int[] firstOpen(JsonNode grid) {
        for (int r = 0; r < grid.size(); r++) {
            for (int c = 0; c < grid.get(r).size(); c++) {
                int m = grid.get(r).get(c).asInt();
                if (m >= 150 && m <= 600) {
                    return new int[] {r, c};
                }
            }
        }
        return new int[] {0, 0};
    }

    private String placeBet(String requestId, int row, int col, long stake) {
        return """
                {"type":"placeBet","requestId":"%s","row":%d,"col":%d,"stakeCents":%d}"""
                .formatted(requestId, row, col, stake);
    }

    private static boolean type(JsonNode message, String type) {
        return type.equals(message.path("type").asText());
    }

    private TestClient connect(String playerId, String name) throws Exception {
        TestClient c = new TestClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws?playerId=" + playerId + "&name=" + name);
        c.session = new StandardWebSocketClient().execute(c, new WebSocketHttpHeaders(), uri)
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return c;
    }

    private final class TestClient extends TextWebSocketHandler {
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> closed = new LinkedBlockingQueue<>();
        WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
            inbox.add(mapper.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            closed.add(status.getCode());
        }

        void send(String json) throws Exception {
            session.sendMessage(new TextMessage(json));
        }

        /** Skips messages until one matches. */
        JsonNode await(Predicate<JsonNode> match) throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                JsonNode next = inbox.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (next != null && match.test(next)) {
                    return next;
                }
            }
            throw new AssertionError("timed out waiting for message");
        }
    }
}
