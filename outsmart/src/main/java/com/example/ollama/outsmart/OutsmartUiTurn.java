package com.example.ollama.outsmart;

import java.util.List;
import java.util.Map;

/** Spectator-only view of completed turns, including messages and optional private plans. */
public record OutsmartUiTurn(int number, List<PlayerTurn> players) {

    /** One player's move and messages as displayed after the turn has resolved. */
    public record PlayerTurn(String name, boolean valid, String strategy, String giveTo,
            String takeFrom, Map<String, String> sentMessages, Map<String, String> receivedMessages,
            List<String> alliancesWith, List<String> alliancesAgainst, String invalidReason) {
    }

    /** Builds the human spectator view without changing the private prompt boundary between models. */
    public static List<OutsmartUiTurn> from(OutsmartGame game) {
        return game.turns().stream().map(turn -> {
            List<PlayerTurn> players = game.players().stream().map(player -> {
                OutsmartGame.TurnRecord record = turn.records().get(player.name());
                if (record.decision() instanceof OutsmartDecision.Valid valid) {
                    OutsmartMove move = valid.move();
                    return new PlayerTurn(player.name(), true, move.secretStrategy(), move.giveCoinTo(),
                            move.takeCoinFrom(), move.privateMessages(), record.receivedMessages(),
                            record.alliancesWith(), record.alliancesAgainst(), null);
                }
                OutsmartDecision.Invalid invalid = (OutsmartDecision.Invalid) record.decision();
                return new PlayerTurn(player.name(), false, null, null, null, Map.of(),
                        record.receivedMessages(), record.alliancesWith(), record.alliancesAgainst(),
                        invalid.reason());
            }).toList();
            return new OutsmartUiTurn(turn.number(), players);
        }).toList();
    }
}
