package com.example.ollama.outsmart;

/** Generates one player's decision; provider-specific clients implement this boundary. */
public interface OutsmartMoveGenerator {

    /** Requests a move using only the game information this player may see. */
    OutsmartDecision decide(OutsmartGame game, OutsmartGame.PlayerState player);
}
