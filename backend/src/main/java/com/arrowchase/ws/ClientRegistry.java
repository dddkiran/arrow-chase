package com.arrowchase.ws;

import com.arrowchase.app.GameEvents;
import com.arrowchase.game.Bet;
import com.arrowchase.game.BetStatus;
import com.arrowchase.game.GameEngine;
import com.arrowchase.game.StepResult;
import com.arrowchase.ws.Protocol.ArrowDto;
import com.arrowchase.ws.Protocol.BetDto;
import com.arrowchase.ws.Protocol.BetSettled;
import com.arrowchase.ws.Protocol.CellDto;
import com.arrowchase.ws.Protocol.ServerMessage;
import com.arrowchase.ws.Protocol.Step;
import com.arrowchase.ws.Protocol.WinDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** Connected sessions, and fan-out of game events to them. */
@Component
public class ClientRegistry implements GameEvents {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistry.class);
    /** A client that cannot drain this much within the time limit is disconnected; it resyncs on reconnect. */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    record Client(String playerId, String name, WebSocketSession session) {
    }

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public ClientRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void add(WebSocketSession session, String playerId, String name) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        clients.put(session.getId(), new Client(playerId, name, safe));
    }

    void remove(String sessionId) {
        clients.remove(sessionId);
    }

    int playersOnline() {
        return (int) clients.values().stream().map(Client::playerId).distinct().count();
    }

    String nameOf(String playerId) {
        return clients.values().stream()
                .filter(c -> c.playerId().equals(playerId))
                .map(Client::name)
                .findFirst()
                .orElse("someone");
    }

    void sendToSession(String sessionId, ServerMessage message) {
        Client client = clients.get(sessionId);
        if (client != null) {
            send(client, serialize(message));
        }
    }

    /** All tabs of a player see the same bets and balance. */
    void sendToPlayer(String playerId, ServerMessage message) {
        TextMessage text = serialize(message);
        clients.values().stream().filter(c -> c.playerId().equals(playerId)).forEach(c -> send(c, text));
    }

    void broadcast(ServerMessage message) {
        TextMessage text = serialize(message);
        clients.values().forEach(c -> send(c, text));
    }

    @Override
    public void onStep(StepResult result, GameEngine engine) {
        List<WinDto> wins = new ArrayList<>();
        for (Bet bet : result.settled()) {
            if (bet.status() == BetStatus.WON) {
                wins.add(new WinDto(nameOf(bet.playerId()), bet.cell().row(), bet.cell().col(),
                        bet.lockedMultiplierX100(), bet.payoutCents()));
            }
        }
        broadcast(stepMessage(engine, wins));
        for (Bet bet : result.settled()) {
            sendToPlayer(bet.playerId(), new BetSettled(BetDto.of(bet), engine.balanceOf(bet.playerId())));
        }
    }

    Step stepMessage(GameEngine engine, List<WinDto> wins) {
        var arrow = engine.arrow();
        return new Step(engine.currentStep(),
                new ArrowDto(arrow.cell().row(), arrow.cell().col(), arrow.direction().name()),
                engine.trail().stream().map(CellDto::of).toList(),
                engine.multipliersX100(),
                playersOnline(),
                wins,
                System.currentTimeMillis());
    }

    private TextMessage serialize(ServerMessage message) {
        try {
            return new TextMessage(mapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + message.getClass().getSimpleName(), e);
        }
    }

    private void send(Client client, TextMessage text) {
        WebSocketSession session = client.session();
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(text);
        } catch (IOException | RuntimeException e) {
            // Includes SessionLimitExceededException for slow consumers. Drop them; the client reconnects.
            log.debug("Dropping session {}: {}", session.getId(), e.getMessage());
            clients.remove(session.getId());
            try {
                session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}
