package com.example.ollama.outsmart;

import java.util.Objects;

/** A submitted move or a failed response; both must reach the referee for the turn to resolve. */
public sealed interface OutsmartDecision permits OutsmartDecision.Valid, OutsmartDecision.Invalid {

    /** Holds a move that the rule engine will still check for legal targets and messages. */
    record Valid(OutsmartMove move) implements OutsmartDecision {
        /** Rejects a missing move before it can enter turn resolution. */
        public Valid {
            Objects.requireNonNull(move, "move");
        }
    }

    /** Records a model or parsing failure that forfeits the player's move. */
    record Invalid(String reason) implements OutsmartDecision {
        /** Keeps the failure reason available to the player history and future UI. */
        public Invalid {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
