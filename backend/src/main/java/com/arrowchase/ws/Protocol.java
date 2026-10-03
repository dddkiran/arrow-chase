package com.arrowchase.ws;

import com.arrowchase.game.Bet;
import com.arrowchase.game.Cell;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * Wire format. JSON objects tagged with {@code type}. Money is integer cents and multipliers are integer x100
 * everywhere, so the browser never does money arithmetic in floating point.
 */
public final class Protocol {

    private Protocol() {
    }

    // ---- client -> server ----

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({@JsonSubTypes.Type(value = PlaceBet.class, name = "placeBet")})
    public sealed interface ClientMessage permits PlaceBet {
    }

    public record PlaceBet(String requestId, int row, int col, long stakeCents) implements ClientMessage {
    }

    // ---- server -> client ----

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Welcome.class, name = "welcome"),
            @JsonSubTypes.Type(value = Step.class, name = "step"),
            @JsonSubTypes.Type(value = BetLocked.class, name = "betLocked"),
            @JsonSubTypes.Type(value = BetRejected.class, name = "betRejected"),
            @JsonSubTypes.Type(value = BetSettled.class, name = "betSettled"),
            @JsonSubTypes.Type(value = ErrorMessage.class, name = "error")
    })
    public sealed interface ServerMessage permits Welcome, Step, BetLocked, BetRejected, BetSettled, ErrorMessage {
    }

    public record GameInfo(int rows, int cols, long stepMillis, int betWindowSteps,
                           long minStakeCents, long maxStakeCents) {
    }

    public record Welcome(String playerId, String name, long balanceCents, GameInfo game, Step state,
                          List<BetDto> openBets, List<BetDto> recentBets) implements ServerMessage {
    }

    public record Step(long step, ArrowDto arrow, List<CellDto> trail, int[][] multipliersX100,
                       int playersOnline, List<WinDto> wins, long serverTimeMillis) implements ServerMessage {
    }

    public record BetLocked(BetDto bet, long balanceCents) implements ServerMessage {
    }

    public record BetRejected(String requestId, String reason, long balanceCents) implements ServerMessage {
    }

    public record BetSettled(BetDto bet, long balanceCents) implements ServerMessage {
    }

    public record ErrorMessage(String message) implements ServerMessage {
    }

    public record ArrowDto(int row, int col, String direction) {
    }

    public record CellDto(int row, int col) {
        static CellDto of(Cell cell) {
            return new CellDto(cell.row(), cell.col());
        }
    }

    /** A win shown to everyone watching, for the shared "who just won" feed. */
    public record WinDto(String name, int row, int col, int multiplierX100, long payoutCents) {
    }

    public record BetDto(String id, String requestId, int row, int col, long stakeCents, int lockedMultiplierX100,
                         long placedStep, long expiresAtStep, String status, long payoutCents, Long settledStep) {
        static BetDto of(Bet bet) {
            return new BetDto(bet.id(), bet.requestId(), bet.cell().row(), bet.cell().col(), bet.stakeCents(),
                    bet.lockedMultiplierX100(), bet.placedStep(), bet.expiresAtStep(), bet.status().name(),
                    bet.payoutCents(), bet.settledStep());
        }
    }
}
