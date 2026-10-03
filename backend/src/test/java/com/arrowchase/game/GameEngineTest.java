package com.arrowchase.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of lock-in and settlement, with the path and the quotes fully scripted so every outcome is exact.
 */
class GameEngineTest {

    private static final int WINDOW = 3;
    private static final long START_BALANCE = 10_000;
    private static final GameConfig CONFIG = new GameConfig(4, 4, WINDOW, 10, 5_000, START_BALANCE, 3, 4);
    private static final Cell ORIGIN = new Cell(0, 0);

    private ScriptedPath path;
    private SettableQuotes quotes;
    private GameEngine engine;

    @BeforeEach
    void setUp() {
        path = new ScriptedPath();
        quotes = new SettableQuotes(4, 4, 250); // every block at 2.50x unless a test says otherwise
        engine = new GameEngine(CONFIG, path, quotes, new ArrowState(ORIGIN, Direction.RIGHT));
        engine.join("alice");
    }

    @Nested
    @DisplayName("lock-in")
    class LockIn {

        @Test
        void accepted_bet_locks_the_current_quote_and_debits_the_stake() {
            PlaceBetResult result = engine.placeBet("alice", "r1", new Cell(2, 3), 1_000);

            assertThat(result).isInstanceOf(PlaceBetResult.Accepted.class);
            Bet bet = ((PlaceBetResult.Accepted) result).bet();
            assertThat(bet.lockedMultiplierX100()).isEqualTo(250);
            assertThat(bet.status()).isEqualTo(BetStatus.OPEN);
            assertThat(bet.placedStep()).isZero();
            assertThat(bet.expiresAtStep()).isEqualTo(WINDOW);
            assertThat(((PlaceBetResult.Accepted) result).balanceCents()).isEqualTo(START_BALANCE - 1_000);
            assertThat(engine.balanceOf("alice")).isEqualTo(START_BALANCE - 1_000);
        }

        @Test
        void payout_uses_the_locked_multiplier_not_the_live_one() {
            engine.placeBet("alice", "r1", new Cell(0, 2), 1_000);
            quotes.setAll(900); // board moves after the lock

            path.then(new Cell(0, 1));
            engine.step();
            path.then(new Cell(0, 2));
            StepResult result = engine.step();

            assertThat(result.settled()).singleElement().satisfies(bet -> {
                assertThat(bet.status()).isEqualTo(BetStatus.WON);
                assertThat(bet.lockedMultiplierX100()).isEqualTo(250);
                assertThat(bet.payoutCents()).isEqualTo(2_500);
            });
            assertThat(engine.balanceOf("alice")).isEqualTo(START_BALANCE - 1_000 + 2_500);
        }

        @Test
        void quote_at_the_moment_of_placement_is_what_gets_locked() {
            path.then(new Cell(0, 1));
            quotes.setAll(400);
            engine.step(); // engine re-quotes after the move

            Bet bet = accepted(engine.placeBet("alice", "r1", new Cell(3, 3), 100));

            assertThat(bet.lockedMultiplierX100()).isEqualTo(400);
            assertThat(bet.placedStep()).isEqualTo(1);
            assertThat(bet.expiresAtStep()).isEqualTo(1 + WINDOW);
        }
    }

    @Nested
    @DisplayName("settlement")
    class Settlement {

        @Test
        void loses_when_the_window_passes_without_a_hit() {
            engine.placeBet("alice", "r1", new Cell(3, 3), 1_000);

            path.then(new Cell(0, 1));
            assertThat(engine.step().settled()).isEmpty();
            path.then(new Cell(0, 2));
            assertThat(engine.step().settled()).isEmpty();
            path.then(new Cell(1, 2));
            StepResult last = engine.step();

            assertThat(last.settled()).singleElement().satisfies(bet -> {
                assertThat(bet.status()).isEqualTo(BetStatus.LOST);
                assertThat(bet.payoutCents()).isZero();
                assertThat(bet.settledStep()).isEqualTo(WINDOW);
            });
            assertThat(engine.balanceOf("alice")).isEqualTo(START_BALANCE - 1_000);
            assertThat(engine.openBetsOf("alice")).isEmpty();
        }

        @Test
        void hit_on_the_last_step_of_the_window_wins() {
            engine.placeBet("alice", "r1", new Cell(1, 2), 1_000);

            path.then(new Cell(0, 1));
            engine.step();
            path.then(new Cell(0, 2));
            engine.step();
            path.then(new Cell(1, 2));
            StepResult last = engine.step();

            assertThat(last.settled()).singleElement()
                    .extracting(Bet::status).isEqualTo(BetStatus.WON);
        }

        @Test
        void hit_after_the_window_does_not_count() {
            engine.placeBet("alice", "r1", new Cell(2, 2), 1_000);

            path.then(new Cell(0, 1));
            engine.step();
            path.then(new Cell(1, 1));
            engine.step();
            path.then(new Cell(1, 2));
            assertThat(engine.step().settled()).extracting(Bet::status).containsExactly(BetStatus.LOST);
            path.then(new Cell(2, 2));
            assertThat(engine.step().settled()).isEmpty();
        }

        @Test
        void the_block_the_arrow_is_on_when_betting_does_not_count_as_a_hit() {
            Bet bet = accepted(engine.placeBet("alice", "r1", ORIGIN, 1_000));

            path.then(new Cell(0, 1));
            assertThat(engine.step().settled()).isEmpty();
            assertThat(engine.openBetsOf("alice")).containsExactly(bet);
        }

        @Test
        void a_bet_settles_exactly_once() {
            engine.placeBet("alice", "r1", new Cell(0, 1), 1_000);

            path.then(new Cell(0, 1));
            assertThat(engine.step().settled()).hasSize(1);
            path.then(new Cell(0, 2));
            path.then(new Cell(0, 1));
            assertThat(engine.step().settled()).isEmpty();
            assertThat(engine.step().settled()).isEmpty();
            assertThat(engine.balanceOf("alice")).isEqualTo(START_BALANCE - 1_000 + 2_500);
        }

        @Test
        void players_and_bets_on_the_same_block_settle_independently() {
            engine.join("bob");
            engine.placeBet("alice", "r1", new Cell(1, 1), 1_000);
            quotes.setAll(300);
            path.then(new Cell(1, 0));
            engine.step(); // board re-quotes at 3.00x
            engine.placeBet("bob", "r1", new Cell(1, 1), 2_000);

            path.then(new Cell(1, 1));
            StepResult result = engine.step();

            assertThat(result.settled()).hasSize(2);
            assertThat(engine.balanceOf("alice")).isEqualTo(START_BALANCE - 1_000 + 2_500);
            assertThat(engine.balanceOf("bob")).isEqualTo(START_BALANCE - 2_000 + 6_000);
            assertThat(engine.recentSettledOf("bob")).singleElement()
                    .extracting(Bet::lockedMultiplierX100).isEqualTo(300);
        }

        @Test
        void payout_rounds_down_to_the_cent() {
            assertThat(Bet.payoutFor(15, 333)).isEqualTo(49); // 49.95 -> 49
        }
    }

    @Nested
    @DisplayName("rejections")
    class Rejections {

        @Test
        void closed_block_is_rejected_and_nothing_is_charged() {
            quotes.set(new Cell(3, 3), 0);
            path.then(new Cell(0, 1));
            engine.step(); // quotes only change on a step
            assertRejected(engine.placeBet("alice", "r1", new Cell(3, 3), 100), RejectReason.CELL_CLOSED);
            assertThat(engine.balanceOf("alice")).isEqualTo(START_BALANCE);
        }

        @Test
        void block_outside_the_grid_is_rejected() {
            assertRejected(engine.placeBet("alice", "r1", new Cell(4, 0), 100), RejectReason.INVALID_CELL);
            assertRejected(engine.placeBet("alice", "r2", new Cell(0, -1), 100), RejectReason.INVALID_CELL);
        }

        @Test
        void stake_outside_limits_is_rejected() {
            assertRejected(engine.placeBet("alice", "r1", ORIGIN, 9), RejectReason.INVALID_STAKE);
            assertRejected(engine.placeBet("alice", "r2", ORIGIN, 5_001), RejectReason.INVALID_STAKE);
            assertRejected(engine.placeBet("alice", "r3", ORIGIN, -100), RejectReason.INVALID_STAKE);
        }

        @Test
        void stake_above_balance_is_rejected() {
            GameEngine poor = new GameEngine(new GameConfig(4, 4, WINDOW, 10, 5_000, 500, 3, 4),
                    path, quotes, new ArrowState(ORIGIN, Direction.RIGHT));
            poor.join("alice");
            assertRejected(poor.placeBet("alice", "r1", ORIGIN, 501), RejectReason.INSUFFICIENT_FUNDS);
            assertThat(poor.placeBet("alice", "r2", ORIGIN, 500)).isInstanceOf(PlaceBetResult.Accepted.class);
        }

        @Test
        void unknown_player_is_rejected() {
            assertRejected(engine.placeBet("mallory", "r1", ORIGIN, 100), RejectReason.UNKNOWN_PLAYER);
        }

        @Test
        void open_bet_limit_is_enforced_and_frees_up_after_settlement() {
            for (int i = 0; i < 3; i++) {
                accepted(engine.placeBet("alice", "r" + i, new Cell(3, 3), 100));
            }
            assertRejected(engine.placeBet("alice", "r3", new Cell(3, 3), 100), RejectReason.TOO_MANY_OPEN_BETS);

            for (int i = 0; i < WINDOW; i++) {
                path.then(new Cell(0, 1 + i % 2));
                engine.step();
            }
            assertThat(engine.placeBet("alice", "r4", new Cell(3, 3), 100))
                    .isInstanceOf(PlaceBetResult.Accepted.class);
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        void retrying_a_request_returns_the_same_bet_and_charges_once() {
            PlaceBetResult first = engine.placeBet("alice", "r1", new Cell(1, 1), 1_000);
            quotes.setAll(700);
            PlaceBetResult retry = engine.placeBet("alice", "r1", new Cell(1, 1), 1_000);

            assertThat(retry).isSameAs(first);
            assertThat(engine.balanceOf("alice")).isEqualTo(START_BALANCE - 1_000);
            assertThat(engine.openBetsOf("alice")).hasSize(1);
        }

        @Test
        void request_ids_are_scoped_per_player() {
            engine.join("bob");
            accepted(engine.placeBet("alice", "same", new Cell(1, 1), 1_000));
            accepted(engine.placeBet("bob", "same", new Cell(1, 1), 1_000));
            assertThat(engine.balanceOf("bob")).isEqualTo(START_BALANCE - 1_000);
        }
    }

    @Test
    void joining_twice_keeps_the_balance() {
        engine.placeBet("alice", "r1", ORIGIN, 1_000);
        assertThat(engine.join("alice")).isEqualTo(START_BALANCE - 1_000);
    }

    @Test
    void trail_keeps_the_most_recent_cells_first() {
        for (int c = 1; c <= 3; c++) {
            path.then(new Cell(0, c));
            engine.step();
        }
        path.then(new Cell(1, 3));
        engine.step();
        assertThat(engine.trail()).containsExactly(new Cell(1, 3), new Cell(0, 3), new Cell(0, 2), new Cell(0, 1));
    }

    // ---- helpers ----

    private static Bet accepted(PlaceBetResult result) {
        assertThat(result).isInstanceOf(PlaceBetResult.Accepted.class);
        return ((PlaceBetResult.Accepted) result).bet();
    }

    private static void assertRejected(PlaceBetResult result, RejectReason reason) {
        assertThat(result).isInstanceOfSatisfying(PlaceBetResult.Rejected.class,
                r -> assertThat(r.reason()).isEqualTo(reason));
    }

    /** Plays back cells queued with {@link #then}. Heading is irrelevant to the engine. */
    static final class ScriptedPath implements ArrowPath {
        private final Deque<Cell> script = new ArrayDeque<>();

        ScriptedPath then(Cell cell) {
            script.addLast(cell);
            return this;
        }

        @Override
        public ArrowState next(ArrowState current) {
            if (script.isEmpty()) {
                throw new IllegalStateException("path script exhausted");
            }
            return new ArrowState(script.removeFirst(), Direction.RIGHT);
        }
    }

    /** A board whose quotes a test can change at will. */
    static final class SettableQuotes implements MultiplierSource {
        private final int[][] grid;

        SettableQuotes(int rows, int cols, int value) {
            grid = new int[rows][cols];
            setAll(value);
        }

        void setAll(int value) {
            for (int[] row : grid) {
                Arrays.fill(row, value);
            }
        }

        void set(Cell cell, int value) {
            grid[cell.row()][cell.col()] = value;
        }

        @Override
        public int[][] quote(ArrowState from) {
            return Arrays.stream(grid).map(int[]::clone).toArray(int[][]::new);
        }
    }
}
