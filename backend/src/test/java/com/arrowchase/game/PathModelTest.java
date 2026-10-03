package com.arrowchase.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class PathModelTest {

    private final PathModel model = new PathModel(8, 8, 0.6, 0.2);

    @Test
    void every_state_has_a_valid_probability_distribution() {
        for (int s = 0; s < model.stateCount(); s++) {
            List<PathModel.Transition> options = model.transitions(model.stateAt(s));
            assertThat(options).isNotEmpty().hasSizeLessThanOrEqualTo(3);
            assertThat(options.stream().mapToDouble(PathModel.Transition::probability).sum())
                    .isCloseTo(1.0, within(1e-12));
        }
    }

    @Test
    void transitions_stay_on_the_grid_move_one_block_and_never_reverse() {
        for (int s = 0; s < model.stateCount(); s++) {
            ArrowState from = model.stateAt(s);
            for (PathModel.Transition t : model.transitions(from)) {
                Cell to = t.next().cell();
                assertThat(model.contains(to)).isTrue();
                assertThat(manhattan(from.cell(), to)).isEqualTo(1);
                assertThat(to).isNotEqualTo(from.cell().step(opposite(from.direction())));
            }
        }
    }

    @Test
    void in_open_space_going_straight_is_the_most_likely_move() {
        ArrowState middle = new ArrowState(new Cell(4, 4), Direction.UP);
        assertThat(model.transitions(middle))
                .extracting(t -> t.next().cell(), PathModel.Transition::probability)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(new Cell(3, 4), 0.6),
                        org.assertj.core.groups.Tuple.tuple(new Cell(4, 3), 0.2),
                        org.assertj.core.groups.Tuple.tuple(new Cell(4, 5), 0.2));
    }

    @Test
    void heading_into_a_corner_forces_the_only_open_turn() {
        ArrowState corner = new ArrowState(new Cell(0, 0), Direction.UP);
        assertThat(model.transitions(corner)).singleElement().satisfies(t -> {
            assertThat(t.next()).isEqualTo(new ArrowState(new Cell(0, 1), Direction.RIGHT));
            assertThat(t.probability()).isEqualTo(1.0);
        });
    }

    @Test
    void random_walk_is_deterministic_for_a_seed_and_never_leaves_the_grid() {
        List<Cell> a = walk(42, 10_000);
        List<Cell> b = walk(42, 10_000);
        assertThat(a).isEqualTo(b);
        assertThat(a).allMatch(model::contains);
        assertThat(walk(43, 10_000)).isNotEqualTo(a);
    }

    @Test
    void random_walk_eventually_visits_every_block() {
        assertThat(walk(7, 20_000).stream().distinct().count()).isEqualTo(64);
    }

    @Test
    void rejects_degenerate_grids() {
        assertThatThrownBy(() -> new PathModel(1, 5, 0.6, 0.2)).isInstanceOf(IllegalArgumentException.class);
    }

    private List<Cell> walk(long seed, int steps) {
        RandomWalkPath path = new RandomWalkPath(model, new SplittableRandom(seed));
        ArrowState state = new ArrowState(new Cell(4, 4), Direction.RIGHT);
        List<Cell> cells = new ArrayList<>(steps);
        for (int i = 0; i < steps; i++) {
            state = path.next(state);
            cells.add(state.cell());
        }
        return cells;
    }

    private static int manhattan(Cell a, Cell b) {
        return Math.abs(a.row() - b.row()) + Math.abs(a.col() - b.col());
    }

    private static Direction opposite(Direction d) {
        return d.turnLeft().turnLeft();
    }
}
