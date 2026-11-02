package com.example.ollama.outsmart;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public game view for the Outsmart API; private strategies and messages stay in memory only. */
public record OutsmartGameResponse(
        UUID id,
        boolean finished,
        int turnsCompleted,
        List<Player> players,
        List<Turn> turns) {

    /** Shows a player's model, current balance, and final status without private history. */
    public record Player(String name, String modelProvider, String modelId,
            int coins, boolean bankrupt, boolean winner) {
    }

    /** Shows public balances after a turn, without anyone's move or private messages. */
    public record Turn(int number, Map<String, Integer> coinsAfter) {
    }

    /** Projects the internal snapshot to safe API fields for creation, reads, and turn results. */
    public static OutsmartGameResponse from(OutsmartGame game) {
        List<Player> players = game.players().stream()
                .map(player -> new Player(player.name(), player.model().provider(), player.model().id(),
                        player.coins(), player.bankrupt(), player.winner()))
                .toList();
        List<Turn> turns = game.turns().stream().map(turn -> {
            Map<String, Integer> balances = new LinkedHashMap<>();
            // Preserve player order for a stable API response. TurnRecord contains private data,
            // so copy only the final coin count into this public projection.
            for (OutsmartGame.PlayerState player : game.players()) {
                balances.put(player.name(), turn.records().get(player.name()).coinsAfter());
            }
            return new Turn(turn.number(), Collections.unmodifiableMap(balances));
        }).toList();
        return new OutsmartGameResponse(game.id(), game.finished(), game.turns().size(), players, turns);
    }
}
