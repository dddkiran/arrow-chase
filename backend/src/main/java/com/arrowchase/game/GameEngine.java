package com.arrowchase.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The single source of truth for the shared game: arrow position, live multipliers, wallets, bet lock-in and
 * settlement.
 *
 * <p><b>Not thread-safe by design.</b> The engine is driven by exactly one thread (see {@code GameLoop}), which
 * makes every bet and every step totally ordered without locks. It has no clock and no hidden randomness of its
 * own: time comes in as {@link #step()} calls and randomness through the injected {@link ArrowPath} and
 * {@link MultiplierSource}, so a test can replay any game exactly.
 */
public final class GameEngine {

    private static final int RECENT_SETTLED_PER_PLAYER = 20;
    private static final int IDEMPOTENCY_CACHE_SIZE = 50_000;

    private final GameConfig config;
    private final ArrowPath path;
    private final MultiplierSource multipliers;

    private final Map<String, Long> balances = new HashMap<>();
    private final Map<String, Bet> openBets = new LinkedHashMap<>();
    private final Map<String, Integer> openBetCount = new HashMap<>();
    private final Map<String, Deque<Bet>> recentSettled = new HashMap<>();
    private final Map<String, PlaceBetResult> processedRequests = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PlaceBetResult> eldest) {
            return size() > IDEMPOTENCY_CACHE_SIZE;
        }
    };
    private final Deque<Cell> trail = new ArrayDeque<>();

    private long step;
    private long betSequence;
    private ArrowState arrow;
    /** Replaced (never mutated) every step, so it is safe to hand out. */
    private int[][] quote;

    public GameEngine(GameConfig config, ArrowPath path, MultiplierSource multipliers, ArrowState start) {
        this.config = config;
        this.path = path;
        this.multipliers = multipliers;
        this.arrow = start;
        this.quote = multipliers.quote(start);
        this.trail.addFirst(start.cell());
    }

    /** Registers a player (idempotent) and returns their balance. */
    public long join(String playerId) {
        return balances.computeIfAbsent(playerId, id -> config.startingBalanceCents());
    }

    /**
     * Locks the block's current multiplier for this bet and debits the stake, or rejects with a reason.
     * Replaying the same {@code requestId} for the same player returns the original decision and changes nothing;
     * the bet and balance in the answer are as they are now, so a late retry never shows the client stale state.
     */
    public PlaceBetResult placeBet(String playerId, String requestId, Cell cell, long stakeCents) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(requestId, "requestId");
        String idempotencyKey = playerId + '\u0000' + requestId;
        PlaceBetResult previous = processedRequests.get(idempotencyKey);
        if (previous != null) {
            return replay(playerId, previous);
        }
        PlaceBetResult result = decide(playerId, requestId, cell, stakeCents);
        processedRequests.put(idempotencyKey, result);
        return result;
    }

    private PlaceBetResult replay(String playerId, PlaceBetResult previous) {
        long balance = balanceOf(playerId);
        return switch (previous) {
            case PlaceBetResult.Accepted a -> new PlaceBetResult.Accepted(currentStateOf(a.bet()), balance);
            case PlaceBetResult.Rejected r -> new PlaceBetResult.Rejected(r.requestId(), r.reason(), balance);
        };
    }

    /** The bet as it is now: open, recently settled, or (if long gone from history) as it was placed. */
    private Bet currentStateOf(Bet bet) {
        Bet open = openBets.get(bet.id());
        if (open != null) {
            return open;
        }
        return recentSettledOf(bet.playerId()).stream()
                .filter(b -> b.id().equals(bet.id()))
                .findFirst()
                .orElse(bet);
    }

    private PlaceBetResult decide(String playerId, String requestId, Cell cell, long stakeCents) {
        Long balance = balances.get(playerId);
        if (balance == null) {
            return new PlaceBetResult.Rejected(requestId, RejectReason.UNKNOWN_PLAYER, 0);
        }
        if (cell == null || cell.row() < 0 || cell.row() >= config.rows()
                || cell.col() < 0 || cell.col() >= config.cols()) {
            return new PlaceBetResult.Rejected(requestId, RejectReason.INVALID_CELL, balance);
        }
        if (stakeCents < config.minStakeCents() || stakeCents > config.maxStakeCents()) {
            return new PlaceBetResult.Rejected(requestId, RejectReason.INVALID_STAKE, balance);
        }
        int locked = quote[cell.row()][cell.col()];
        if (locked <= 0) {
            return new PlaceBetResult.Rejected(requestId, RejectReason.CELL_CLOSED, balance);
        }
        if (openBetCount.getOrDefault(playerId, 0) >= config.maxOpenBetsPerPlayer()) {
            return new PlaceBetResult.Rejected(requestId, RejectReason.TOO_MANY_OPEN_BETS, balance);
        }
        if (balance < stakeCents) {
            return new PlaceBetResult.Rejected(requestId, RejectReason.INSUFFICIENT_FUNDS, balance);
        }

        long newBalance = balance - stakeCents;
        balances.put(playerId, newBalance);
        Bet bet = new Bet("b" + (++betSequence), playerId, requestId, cell, stakeCents, locked,
                step, step + config.betWindowSteps(), BetStatus.OPEN, 0, null);
        openBets.put(bet.id(), bet);
        openBetCount.merge(playerId, 1, Integer::sum);
        return new PlaceBetResult.Accepted(bet, newBalance);
    }

    /** Advances the arrow one block, settles every bet that won or expired, then re-quotes the board. */
    public StepResult step() {
        step++;
        arrow = path.next(arrow);
        trail.addFirst(arrow.cell());
        while (trail.size() > config.trailLength()) {
            trail.removeLast();
        }

        List<Bet> settled = new ArrayList<>();
        Iterator<Bet> it = openBets.values().iterator();
        while (it.hasNext()) {
            Bet bet = it.next();
            BetStatus outcome = null;
            if (bet.cell().equals(arrow.cell())) {
                outcome = BetStatus.WON;
            } else if (step >= bet.expiresAtStep()) {
                outcome = BetStatus.LOST;
            }
            if (outcome != null) {
                Bet done = bet.settle(outcome, step);
                if (done.payoutCents() > 0) {
                    balances.merge(done.playerId(), done.payoutCents(), Long::sum);
                }
                it.remove();
                openBetCount.merge(done.playerId(), -1, Integer::sum);
                Deque<Bet> recent = recentSettled.computeIfAbsent(done.playerId(), id -> new ArrayDeque<>());
                recent.addFirst(done);
                while (recent.size() > RECENT_SETTLED_PER_PLAYER) {
                    recent.removeLast();
                }
                settled.add(done);
            }
        }

        quote = multipliers.quote(arrow);
        return new StepResult(step, arrow, quote, List.copyOf(settled));
    }

    public GameConfig config() {
        return config;
    }

    public long currentStep() {
        return step;
    }

    public ArrowState arrow() {
        return arrow;
    }

    public int[][] multipliersX100() {
        return quote;
    }

    /** Most recent first. */
    public List<Cell> trail() {
        return List.copyOf(trail);
    }

    public long balanceOf(String playerId) {
        return balances.getOrDefault(playerId, 0L);
    }

    public List<Bet> openBetsOf(String playerId) {
        return openBets.values().stream().filter(b -> b.playerId().equals(playerId)).toList();
    }

    /** Most recent first. */
    public List<Bet> recentSettledOf(String playerId) {
        Deque<Bet> recent = recentSettled.get(playerId);
        return recent == null ? List.of() : List.copyOf(recent);
    }
}
