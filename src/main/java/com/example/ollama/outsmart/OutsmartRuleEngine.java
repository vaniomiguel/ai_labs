package com.example.ollama.outsmart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

/** Resolves a full turn using game rules only; it does not call models or store games. */
@Component
public class OutsmartRuleEngine {

    /** Applies every submitted decision to one snapshot, then returns a new snapshot with a completed turn. */
    public OutsmartGame playTurn(OutsmartGame game, Map<String, OutsmartDecision> submitted) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(submitted, "submitted");
        if (game.finished()) {
            throw new IllegalStateException("The Outsmart game is finished");
        }

        Set<String> names = new HashSet<>();
        for (OutsmartGame.PlayerState player : game.players()) {
            names.add(player.name());
        }
        if (!submitted.keySet().equals(names)) {
            throw new IllegalArgumentException("Submit one decision for every Outsmart player");
        }

        int turn = game.nextTurn();
        Map<String, Integer> coins = new LinkedHashMap<>();
        Map<String, TurnRecordBuilder> records = new LinkedHashMap<>();
        // Resolve all decisions before moving coins. A missing submission is an orchestration error,
        // while an explicit Invalid decision is a player's forfeited move.
        for (OutsmartGame.PlayerState player : game.players()) {
            OutsmartDecision decision = Objects.requireNonNull(submitted.get(player.name()), "decision");
            coins.put(player.name(), player.coins());
            records.put(player.name(), new TurnRecordBuilder(player.name(), turn,
                    validate(decision, player.name(), names), player.coins()));
        }

        // Every player spends one coin, including those with invalid moves. Valid gifts reach recipients.
        for (OutsmartGame.PlayerState player : game.players()) {
            String name = player.name();
            coins.compute(name, (ignored, value) -> value - 1);
            if (records.get(name).decision instanceof OutsmartDecision.Valid valid) {
                String recipient = valid.move().giveCoinTo();
                coins.compute(recipient, (ignored, value) -> value + 1);
                records.get(recipient).givers.add(name);
            }
        }
        // A valid take transfers one coin from its target to the player, even if the target goes below zero.
        for (OutsmartGame.PlayerState player : game.players()) {
            String name = player.name();
            if (records.get(name).decision instanceof OutsmartDecision.Valid valid) {
                String victim = valid.move().takeCoinFrom();
                coins.compute(victim, (ignored, value) -> value - 1);
                coins.compute(name, (ignored, value) -> value + 1);
                records.get(victim).takers.add(name);
            }
        }

        // Each pair can form at most one alliance per turn. Both gifts must be mutual and both
        // takes must target the same third player; the bonus moves two more coins from that target.
        Set<String> allied = new HashSet<>();
        for (OutsmartGame.PlayerState player : game.players()) {
            String first = player.name();
            if (allied.contains(first) || !(records.get(first).decision instanceof OutsmartDecision.Valid firstDecision)) {
                continue;
            }
            String second = firstDecision.move().giveCoinTo();
            if (allied.contains(second) || !(records.get(second).decision instanceof OutsmartDecision.Valid secondDecision)) {
                continue;
            }
            String victim = firstDecision.move().takeCoinFrom();
            if (secondDecision.move().giveCoinTo().equals(first)
                    && secondDecision.move().takeCoinFrom().equals(victim)) {
                allied.add(first);
                allied.add(second);
                coins.compute(first, (ignored, value) -> value + 1);
                coins.compute(second, (ignored, value) -> value + 1);
                coins.compute(victim, (ignored, value) -> value - 2);
                records.get(first).alliancesWith.add(second);
                records.get(second).alliancesWith.add(first);
                records.get(victim).alliancesAgainst.add(first);
                records.get(victim).alliancesAgainst.add(second);
            }
        }

        // Deliver messages only after moves are fixed, so they influence the next turn rather than this one.
        for (OutsmartGame.PlayerState player : game.players()) {
            String sender = player.name();
            if (records.get(sender).decision instanceof OutsmartDecision.Valid valid) {
                valid.move().privateMessages().forEach((recipient, message) ->
                        records.get(recipient).receivedMessages.put(sender, message));
            }
        }

        // Bankruptcy is checked after all trades and alliances. Negative balances are shown as zero.
        boolean bankrupt = coins.values().stream().anyMatch(value -> value <= 0);
        boolean finished = bankrupt || turn == OutsmartGame.MAX_TURNS;
        int highest = coins.values().stream().mapToInt(value -> Math.max(0, value)).max().orElseThrow();
        List<OutsmartGame.PlayerState> players = new ArrayList<>();
        Map<String, OutsmartGame.TurnRecord> completedRecords = new LinkedHashMap<>();
        for (OutsmartGame.PlayerState previous : game.players()) {
            String name = previous.name();
            int balance = Math.max(0, coins.get(name));
            players.add(new OutsmartGame.PlayerState(name, previous.model(), balance,
                    balance == 0, finished && balance == highest));
            completedRecords.put(name, records.get(name).build(balance));
        }
        List<OutsmartGame.Turn> turns = new ArrayList<>(game.turns());
        turns.add(new OutsmartGame.Turn(turn, completedRecords));
        return new OutsmartGame(game.id(), players, turns, finished);
    }

    /** Converts illegal model output into a forfeited move and keeps only messages for actual opponents. */
    private OutsmartDecision validate(OutsmartDecision decision, String player, Set<String> names) {
        if (decision instanceof OutsmartDecision.Invalid) {
            return decision;
        }
        OutsmartMove move = ((OutsmartDecision.Valid) decision).move();
        if (move.secretStrategy() == null || move.giveCoinTo() == null || move.takeCoinFrom() == null
                || move.privateMessages() == null) {
            return new OutsmartDecision.Invalid("Missing move fields");
        }
        if (!names.contains(move.giveCoinTo()) || move.giveCoinTo().equals(player)) {
            return new OutsmartDecision.Invalid("Invalid coin recipient");
        }
        if (!names.contains(move.takeCoinFrom()) || move.takeCoinFrom().equals(player)
                || move.takeCoinFrom().equals(move.giveCoinTo())) {
            return new OutsmartDecision.Invalid("Invalid coin target");
        }
        Map<String, String> messages = new LinkedHashMap<>();
        for (String other : names) {
            if (!other.equals(player)) {
                String message = move.privateMessages().get(other);
                if (message == null) {
                    return new OutsmartDecision.Invalid("Missing private message for " + other);
                }
                messages.put(other, message);
            }
        }
        return new OutsmartDecision.Valid(new OutsmartMove(move.secretStrategy(),
                move.giveCoinTo(), move.takeCoinFrom(), Map.copyOf(messages)));
    }

    /** Collects one player's turn outcome while the rule phases are being resolved. */
    private static final class TurnRecordBuilder {
        private final String name;
        private final int turn;
        private final OutsmartDecision decision;
        private final int coinsBefore;
        private final List<String> givers = new ArrayList<>();
        private final List<String> takers = new ArrayList<>();
        private final List<String> alliancesWith = new ArrayList<>();
        private final List<String> alliancesAgainst = new ArrayList<>();
        private final Map<String, String> receivedMessages = new LinkedHashMap<>();

        /** Starts a record from the player's validated decision and pre-turn balance. */
        private TurnRecordBuilder(String name, int turn, OutsmartDecision decision, int coinsBefore) {
            this.name = name;
            this.turn = turn;
            this.decision = decision;
            this.coinsBefore = coinsBefore;
        }

        /** Freezes the accumulated outcome with the final, bankruptcy-clamped balance. */
        private OutsmartGame.TurnRecord build(int coinsAfter) {
            return new OutsmartGame.TurnRecord(name, turn, decision, coinsBefore, coinsAfter,
                    givers, takers, alliancesWith, alliancesAgainst, receivedMessages);
        }
    }
}
