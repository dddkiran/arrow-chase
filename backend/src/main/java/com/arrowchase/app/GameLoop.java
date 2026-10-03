package com.arrowchase.app;

import com.arrowchase.game.GameEngine;
import com.arrowchase.game.StepResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Owns the one thread that touches the {@link GameEngine}. Ticks advance the arrow on a fixed schedule; player
 * commands are queued onto the same thread. That serialises everything, so a bet is always either before or after a
 * step, never racing it, and outbound messages leave in the order the engine produced them.
 */
public class GameLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GameLoop.class);

    private final GameEngine engine;
    private final GameEvents events;
    private final long stepMillis;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "game-loop");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;

    public GameLoop(GameEngine engine, GameEvents events, long stepMillis) {
        this.engine = engine;
        this.events = events;
        this.stepMillis = stepMillis;
    }

    @Override
    public void start() {
        executor.scheduleAtFixedRate(this::tick, stepMillis, stepMillis, TimeUnit.MILLISECONDS);
        running = true;
        log.info("Game loop started: one step every {} ms", stepMillis);
    }

    private void tick() {
        // An exception escaping here would silently cancel the schedule and freeze the game for everyone.
        try {
            StepResult result = engine.step();
            events.onStep(result, engine);
        } catch (RuntimeException e) {
            log.error("Game tick failed", e);
        }
    }

    /** Runs a command on the game thread. Fire-and-forget; failures are logged. */
    public void execute(Consumer<GameEngine> command) {
        executor.execute(() -> {
            try {
                command.accept(engine);
            } catch (RuntimeException e) {
                log.error("Game command failed", e);
            }
        });
    }

    /** Runs a query or command on the game thread and returns its result. */
    public <T> CompletableFuture<T> call(Function<GameEngine, T> command) {
        return CompletableFuture.supplyAsync(() -> command.apply(engine), executor);
    }

    public long stepMillis() {
        return stepMillis;
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
