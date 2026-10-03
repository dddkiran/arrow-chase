package com.arrowchase.app;

import com.arrowchase.game.GameEngine;
import com.arrowchase.game.StepResult;

/** Outbound side of the game loop. Called on the game-loop thread, so implementations may read the engine. */
public interface GameEvents {
    void onStep(StepResult result, GameEngine engine);
}
