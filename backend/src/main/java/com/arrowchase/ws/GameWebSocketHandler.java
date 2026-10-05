package com.arrowchase.ws;

import com.arrowchase.app.GameLoop;
import com.arrowchase.game.Cell;
import com.arrowchase.game.GameEngine;
import com.arrowchase.game.PlaceBetResult;
import com.arrowchase.ws.Protocol.BetDto;
import com.arrowchase.ws.Protocol.BetLocked;
import com.arrowchase.ws.Protocol.BetRejected;
import com.arrowchase.ws.Protocol.ClientMessage;
import com.arrowchase.ws.Protocol.ErrorMessage;
import com.arrowchase.ws.Protocol.GameInfo;
import com.arrowchase.ws.Protocol.PlaceBet;
import com.arrowchase.ws.Protocol.ServerMessage;
import com.arrowchase.ws.Protocol.Welcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Thin gateway: validates input, then hands work to the game thread. It holds no game state itself.
 *
 * <p>Identity is a client-generated player id in the connect URL. That is enough for a demo wallet; a real
 * deployment would take the player id from an authenticated token instead (see docs/ARCHITECTURE.md).
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private static final Pattern PLAYER_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final int MAX_NAME_LENGTH = 20;
    private static final String PLAYER_ID_ATTR = "playerId";
    private static final String BET_LIMITER_ATTR = "betLimiter";
    /** A burst of 10 quick clicks, then 5 bets a second sustained: well above a human, far below a flood. */
    private static final int BET_BURST = 10;
    private static final double BETS_PER_SECOND = 5;
    static final String RATE_LIMITED = "RATE_LIMITED";

    private final GameLoop loop;
    private final ClientRegistry clients;
    private final ObjectMapper mapper;

    public GameWebSocketHandler(GameLoop loop, ClientRegistry clients, ObjectMapper mapper) {
        this.loop = loop;
        this.clients = clients;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        var params = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
        String playerId = params.getFirst("playerId");
        if (playerId == null || !PLAYER_ID.matcher(playerId).matches()) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid playerId"));
            return;
        }
        String name = sanitizeName(params.getFirst("name"));
        session.getAttributes().put(PLAYER_ID_ATTR, playerId);
        session.getAttributes().put(BET_LIMITER_ATTR, new TokenBucket(BET_BURST, BETS_PER_SECOND, System::nanoTime));

        // Register and greet on the game thread so the welcome snapshot is always the first message the
        // client sees, ahead of any step broadcast.
        loop.execute(engine -> {
            if (!session.isOpen()) {
                return;
            }
            long balance = engine.join(playerId);
            clients.add(session, playerId, name);
            clients.sendToSession(session.getId(), welcome(engine, playerId, name, balance));
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String playerId = playerIdOf(session);
        ClientMessage parsed;
        try {
            parsed = mapper.readValue(message.getPayload(), ClientMessage.class);
        } catch (IOException e) {
            clients.sendToSession(session.getId(), new ErrorMessage("Unreadable message"));
            return;
        }
        switch (parsed) {
            case PlaceBet bet -> placeBet(session, playerId, bet);
        }
    }

    private void placeBet(WebSocketSession session, String playerId, PlaceBet bet) {
        if (bet.requestId() == null || !REQUEST_ID.matcher(bet.requestId()).matches()) {
            clients.sendToSession(session.getId(), new ErrorMessage("placeBet needs a requestId"));
            return;
        }
        // Refused here, before the game thread: a flood never reaches the engine, so it can neither queue up
        // behind ticks nor fill the engine's idempotency cache. Nothing is cached, so the same requestId can be
        // sent again once the client slows down.
        if (!betLimiterOf(session).tryAcquire()) {
            clients.sendToSession(session.getId(), new BetRejected(bet.requestId(), RATE_LIMITED, null));
            return;
        }
        loop.execute(engine -> {
            PlaceBetResult result = engine.placeBet(playerId, bet.requestId(),
                    new Cell(bet.row(), bet.col()), bet.stakeCents());
            ServerMessage reply = switch (result) {
                case PlaceBetResult.Accepted a -> new BetLocked(BetDto.of(a.bet()), a.balanceCents());
                case PlaceBetResult.Rejected r ->
                        new BetRejected(r.requestId(), r.reason().name(), r.balanceCents());
            };
            clients.sendToPlayer(playerId, reply);
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    private Welcome welcome(GameEngine engine, String playerId, String name, long balance) {
        var config = engine.config();
        GameInfo info = new GameInfo(config.rows(), config.cols(), loop.stepMillis(), config.betWindowSteps(),
                config.minStakeCents(), config.maxStakeCents());
        List<BetDto> open = engine.openBetsOf(playerId).stream().map(BetDto::of).toList();
        List<BetDto> recent = engine.recentSettledOf(playerId).stream().map(BetDto::of).toList();
        return new Welcome(playerId, name, balance, info, clients.stepMessage(engine, List.of()), open, recent);
    }

    private static String playerIdOf(WebSocketSession session) {
        return (String) session.getAttributes().get(PLAYER_ID_ATTR);
    }

    private static TokenBucket betLimiterOf(WebSocketSession session) {
        return (TokenBucket) session.getAttributes().get(BET_LIMITER_ATTR);
    }

    static String sanitizeName(String raw) {
        if (raw == null) {
            return "Player";
        }
        String cleaned = raw.replaceAll("[^\\p{L}\\p{N} _-]", "").trim();
        if (cleaned.isEmpty()) {
            return "Player";
        }
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }
}
