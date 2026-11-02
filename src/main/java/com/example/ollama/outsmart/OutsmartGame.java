package com.example.ollama.outsmart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable snapshot of one Outsmart game, including the history needed for later player prompts. */
public record OutsmartGame(UUID id, List<PlayerState> players, List<Turn> turns, boolean finished) {

    /** The source game's opening balance; change this together with the rule text in future prompts. */
    public static final int STARTING_COINS = 12;
    /** The source game's turn limit; keep the future UI and prompts in sync if it changes. */
    public static final int MAX_TURNS = 10;

    /** Copies collections so callers cannot mutate a saved game snapshot. */
    public OutsmartGame {
        Objects.requireNonNull(id, "id");
        players = List.copyOf(players);
        turns = List.copyOf(turns);
    }

    /** Validates the lineup and creates a fresh game with each player at the opening balance. */
    public static OutsmartGame start(List<Participant> participants) {
        Objects.requireNonNull(participants, "participants");
        // If this range changes, update the future game-creation endpoint and model prompt together.
        if (participants.size() < 3 || participants.size() > 8) {
            throw new IllegalArgumentException("Outsmart requires between 3 and 8 players");
        }
        Set<String> names = new HashSet<>();
        List<PlayerState> players = new ArrayList<>();
        for (Participant participant : participants) {
            Objects.requireNonNull(participant, "participant");
            if (participant.name() == null || participant.name().isBlank()
                    || !participant.name().equals(participant.name().strip())) {
                throw new IllegalArgumentException("Player names must be nonblank and trimmed");
            }
            if (!names.add(participant.name().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Player names must be unique");
            }
            players.add(new PlayerState(participant.name(), participant.model(), STARTING_COINS, false, false));
        }
        return new OutsmartGame(UUID.randomUUID(), players, List.of(), false);
    }

    /** Returns the next turn number; the rule engine separately guards finished games. */
    public int nextTurn() {
        return turns.size() + 1;
    }

    /** Identifies a player's model independently of the provider client used to call it. */
    public record Model(String provider, String id) {
        /** Prevents an incomplete model selection from entering a game. */
        public Model {
            if (provider == null || provider.isBlank() || id == null || id.isBlank()) {
                throw new IllegalArgumentException("Model provider and id are required");
            }
        }
    }

    /** The name and model supplied when creating a game. */
    public record Participant(String name, Model model) {
        /** Requires a model for every participant, even before model calls are implemented. */
        public Participant {
            Objects.requireNonNull(model, "model");
        }
    }

    /** Public coin balance and end-of-game status for one player. */
    public record PlayerState(String name, Model model, int coins, boolean bankrupt, boolean winner) {
    }

    /** Results for every player from one completed turn, keyed by player name. */
    public record Turn(int number, Map<String, TurnRecord> records) {
        /** Freezes the result map before it is added to game history. */
        public Turn {
            records = Map.copyOf(records);
        }
    }

    /** A player's own move, outcomes, and received messages for one turn. */
    public record TurnRecord(
            String playerName,
            int turn,
            OutsmartDecision decision,
            int coinsBefore,
            int coinsAfter,
            List<String> givers,
            List<String> takers,
            List<String> alliancesWith,
            List<String> alliancesAgainst,
            Map<String, String> receivedMessages) {
        /** Freezes nested collections so private history cannot change after resolution. */
        public TurnRecord {
            givers = List.copyOf(givers);
            takers = List.copyOf(takers);
            alliancesWith = List.copyOf(alliancesWith);
            alliancesAgainst = List.copyOf(alliancesAgainst);
            receivedMessages = Map.copyOf(receivedMessages);
        }
    }
}
