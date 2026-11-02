package com.example.ollama.outsmart;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Describes the Outsmart players and local models selected by a game creation request. */
public record OutsmartCreateGameRequest(
        @NotNull @Size(min = 3, max = 8) List<@NotNull @Valid Participant> participants) {

    /** Holds one player's display name and model choice. */
    public record Participant(@NotBlank String name, @NotNull @Valid Model model) {
    }

    /** Identifies the provider and model ID without using the separate chat endpoint's defaults. */
    public record Model(
            // Add another provider here only when a matching OutsmartMoveGenerator is wired.
            @NotBlank @Pattern(regexp = "(?i)ollama", message = "provider must be ollama") String provider,
            @NotBlank String id) {
    }

    /** Converts validated API input into the participant type accepted by the game registry. */
    public List<OutsmartGame.Participant> toParticipants() {
        return participants.stream()
                .map(participant -> new OutsmartGame.Participant(participant.name(),
                        new OutsmartGame.Model(participant.model().provider().toLowerCase(java.util.Locale.ROOT),
                                participant.model().id())))
                .toList();
    }
}
