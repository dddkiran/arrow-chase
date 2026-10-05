package com.arrowchase.ws;

import com.arrowchase.game.Bet;
import com.arrowchase.game.Cell;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Bet on a block. Answered with betLocked or betRejected.")
    public record PlaceBet(
            @Schema(description = "Client-chosen id, 1-64 of A-Z a-z 0-9 -. Resending it returns the first answer.",
                    requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
            int row, int col, long stakeCents) implements ClientMessage {
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

    @Schema(description = "First message on every connect: a full snapshot to render from.")
    public record Welcome(String playerId, String name, long balanceCents, GameInfo game, Step state,
                          List<BetDto> openBets, List<BetDto> recentBets) implements ServerMessage {
    }

    @Schema(description = "Broadcast to everyone after every arrow move.")
    public record Step(long step, ArrowDto arrow, List<CellDto> trail,
                       @Schema(description = "[row][col] live multiplier x100; 0 means the block is closed.")
                       int[][] multipliersX100,
                       int playersOnline, List<WinDto> wins, long serverTimeMillis) implements ServerMessage {
    }

    @Schema(description = "placeBet accepted: the stake is taken and the multiplier locked.")
    public record BetLocked(BetDto bet, long balanceCents) implements ServerMessage {
    }

    @Schema(description = "placeBet refused; nothing was charged.")
    public record BetRejected(String requestId,
                              @Schema(allowableValues = {"UNKNOWN_PLAYER", "INVALID_CELL", "CELL_CLOSED",
                                      "INVALID_STAKE", "INSUFFICIENT_FUNDS", "TOO_MANY_OPEN_BETS",
                                      "RATE_LIMITED"})
                              String reason,
                              @Schema(nullable = true, description = "Null for RATE_LIMITED, which the gateway "
                                      + "decides without consulting the wallet.")
                              Long balanceCents) implements ServerMessage {
    }

    @Schema(description = "A bet won or lost.")
    public record BetSettled(BetDto bet, long balanceCents) implements ServerMessage {
    }

    @Schema(description = "The last client frame could not be processed.")
    public record ErrorMessage(String message) implements ServerMessage {
    }

    public record ArrowDto(int row, int col,
                           @Schema(allowableValues = {"UP", "RIGHT", "DOWN", "LEFT"}) String direction) {
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
                         long placedStep, long expiresAtStep,
                         @Schema(allowableValues = {"OPEN", "WON", "LOST"}) String status,
                         long payoutCents,
                         @Schema(description = "Null while the bet is open.") Long settledStep) {
        static BetDto of(Bet bet) {
            return new BetDto(bet.id(), bet.requestId(), bet.cell().row(), bet.cell().col(), bet.stakeCents(),
                    bet.lockedMultiplierX100(), bet.placedStep(), bet.expiresAtStep(), bet.status().name(),
                    bet.payoutCents(), bet.settledStep());
        }
    }
}
