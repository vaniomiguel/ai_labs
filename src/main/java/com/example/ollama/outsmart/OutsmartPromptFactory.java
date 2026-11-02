package com.example.ollama.outsmart;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

/** Builds Outsmart instructions and a player-specific turn summary for a model call. */
@Component
public class OutsmartPromptFactory {

    /** Holds the two messages sent to a model for one player's turn. */
    public record Prompt(String system, String user) {
    }

    /** Describes the rules, legal targets, required JSON shape, and the player's visible history. */
    public Prompt forPlayer(OutsmartGame game, OutsmartGame.PlayerState player) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(player, "player");
        if (game.players().stream().noneMatch(candidate -> candidate.name().equals(player.name()))) {
            throw new IllegalArgumentException("Player is not in this Outsmart game");
        }
        List<String> opponents = game.players().stream().map(OutsmartGame.PlayerState::name)
                .filter(name -> !name.equals(player.name())).toList();
        String system = """
                You are %s in a negotiation game with %s.
                Everyone starts with %d coins. Each turn, send one private message to every opponent,
                give one coin to an opponent, and take one coin from a different opponent.
                Two players form an alliance if they give to each other and take from the same third player.
                Each ally then takes one additional coin from that target.
                The game ends after %d turns or when any player runs out of coins.
                Your goal is to finish with the most coins. You may negotiate or bluff.
                Moves are simultaneous: messages sent this turn are read after all moves are chosen.
                Respond with one JSON object only, without markdown or extra text:
                {"secret_strategy":"your private plan","give_coin_to":"opponent name",
                 "take_coin_from":"different opponent name",
                 "private_messages":{"opponent name":"message for that opponent"}}
                Use the exact keys shown. Give and take targets must be different names from: %s.
                Include a private_messages entry for each opponent. Your secret strategy is private.
                """.formatted(player.name(), String.join(", ", opponents), OutsmartGame.STARTING_COINS,
                OutsmartGame.MAX_TURNS, String.join(", ", opponents));

        StringBuilder user = new StringBuilder("Turn ").append(game.nextTurn())
                .append(". Your current balance is ").append(player.coins()).append(" coins.\n")
                .append("Current opponent balances:\n");
        for (OutsmartGame.PlayerState opponent : game.players()) {
            if (!opponent.name().equals(player.name())) {
                user.append("- ").append(opponent.name()).append(": ")
                        .append(opponent.coins()).append(" coins\n");
            }
        }
        if (game.turns().isEmpty()) {
            user.append("This is the first turn. There is no history yet.\n");
        } else {
            user.append("Your previous turns (other players' private plans and messages are hidden):\n");
            for (OutsmartGame.Turn turn : game.turns()) {
                appendOwnTurn(user, turn.records().get(player.name()));
            }
        }
        user.append("Choose your move and reply with JSON only.");
        return new Prompt(system, user.toString());
    }

    /** Adds only this player's own decision, outcomes, and received messages to the prompt. */
    private void appendOwnTurn(StringBuilder user, OutsmartGame.TurnRecord record) {
        user.append("Turn ").append(record.turn()).append(": ")
                .append(record.coinsBefore()).append(" -> ").append(record.coinsAfter()).append(" coins.\n");
        if (record.decision() instanceof OutsmartDecision.Valid valid) {
            OutsmartMove move = valid.move();
            user.append("Your private plan: ").append(move.secretStrategy()).append("\n")
                    .append("You gave to ").append(move.giveCoinTo()).append(" and took from ")
                    .append(move.takeCoinFrom()).append(".\n")
                    .append("Your sent messages: ").append(move.privateMessages()).append("\n");
        } else if (record.decision() instanceof OutsmartDecision.Invalid invalid) {
            user.append("Your move was invalid: ").append(invalid.reason()).append("\n");
        }
        user.append("Gifts received from: ").append(record.givers()).append("\n")
                .append("Coins taken by: ").append(record.takers()).append("\n")
                .append("Allied with: ").append(record.alliancesWith()).append("\n")
                .append("Alliances against you: ").append(record.alliancesAgainst()).append("\n")
                .append("Private messages received: ").append(record.receivedMessages()).append("\n");
    }
}
